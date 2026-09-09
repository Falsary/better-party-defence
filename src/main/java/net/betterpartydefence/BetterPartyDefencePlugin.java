/*
 * Copyright (c) 2026, Maxwell
 * All rights reserved.
 *
 * Better Party Defence
 *
 * Hub Party Panel integration note:
 * Hub Party Panel creates/joins the same underlying RuneLite PartyService session used by
 * RuneLite's party message bus. This plugin does not use or enable RuneLite's built-in Party
 * sidebar and does not create a second party. It only observes the existing PartyService session.
 *
 * Remote party special-attack events use RuneLite's standard SpecialCounterUpdate party message.
 * This plugin also detects its own supported defence specs locally, so its core tracking does not
 * depend on the separate Special Attack Counter plugin. See ATTRIBUTION.md for attribution.
 */
package net.betterpartydefence;

import com.google.inject.Provides;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.specialcounter.SpecialCounterPlugin;
import net.runelite.client.plugins.specialcounter.SpecialCounterUpdate;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBox;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.Text;

/**
 * Displays a boss's live Defence using special-attack events shared by members of the
 * current Hub Party Panel party.
 *
 * <p>Hub Party Panel is the user-facing party UI. Under the hood it uses RuneLite's
 * {@link PartyService}; therefore this plugin can observe that exact party without depending
 * on Hub Party Panel's implementation classes or enabling RuneLite's built-in Party sidebar.
 *
 * <p>Remote drains arrive as RuneLite's standard {@link SpecialCounterUpdate} messages.
 * Better Party Defence also contains a minimal local detector for supported defence-draining specs.
 * If RuneLite's {@link SpecialCounterPlugin} is active it remains the party-message producer; if it
 * is disabled, Better Party Defence publishes the same standard message for its own supported drains.
 * No second party, custom websocket, or OSParty protocol is created.
 */
@Slf4j
@Singleton
@PluginDescriptor(
	name = "Better Party Defence",
	description = "Tracks boss Defence from special attacks shared through your Hub Party Panel party.",
	tags = {"defence", "defense", "drain", "party", "hub", "boss", "special"}
)
public class BetterPartyDefencePlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private PartyService partyService;

	@Inject
	private WSClient wsClient;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private SkillIconManager skillIconManager;

	@Inject
	private DefenceTracker defenceTracker;

	@Inject
	private LocalDefenceSpecDetector localSpecDetector;

	@Inject
	private BetterPartyDefenceConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private InfoBoxManager infoBoxManager;

	private NpcDefenceOverlay defenceOverlay;
	private ScreenDefenceOverlay screenDefenceOverlay;
	private PreviousTargetScreenOverlay previousTargetScreenOverlay;
	private PreviousTargetDisplayState previousTargetDisplayState;
	private SkillIconSource skillIconSource;
	private TrackerFontManager trackerFontManager;
	private DefenceInfoBox defenceBox;
	private DefenceInfoBox magicDefenceBox;
	private static final int DUPLICATE_WINDOW_TICKS = 1;
	private static final int SYNC_HISTORY_DUPLICATE_WINDOW_TICKS = 2;
	private static final int NON_INSTANCE_HP_TOLERANCE = 20;
	private static final int NON_INSTANCE_POSITION_TOLERANCE = 16;
	private static final long SYNC_MAX_AGE_MILLIS = 5_000L;
	private static final long SYNC_REBROADCAST_INTERVAL_MILLIS = 2_000L;
	private static final long SYNC_PARTY_WIDE_ABSENCE_TIMEOUT_MILLIS = 20_000L;
	private static final long ENCOUNTER_PRESENCE_INTERVAL_MILLIS = 2_000L;
	private static final long ENCOUNTER_PRESENCE_MAX_AGE_MILLIS = 6_000L;
	/** Blocks stale pre-reset absolute snapshots long enough for their normal max age to expire. */
	private static final long ENCOUNTER_RESET_BARRIER_MILLIS = SYNC_MAX_AGE_MILLIS;
	/** Duplicate death/wipe notices from several clients must not clear a freshly restarted encounter. */
	private static final long ENCOUNTER_RESET_DEDUP_MILLIS = 10_000L;
	private static final int TOA_PARTY_MEMBER_DEAD = 30;
	private static final int[] TOA_PARTY_HEALTH_VARBITS =
	{
		VarbitID.TOA_CLIENT_P0, VarbitID.TOA_CLIENT_P1, VarbitID.TOA_CLIENT_P2, VarbitID.TOA_CLIENT_P3,
		VarbitID.TOA_CLIENT_P4, VarbitID.TOA_CLIENT_P5, VarbitID.TOA_CLIENT_P6, VarbitID.TOA_CLIENT_P7
	};
	private final Map<SpecEventKey, Integer> recentSpecEvents = new HashMap<>();
	private final Map<SyncedSpecKey, Integer> recentSyncedSpecs = new HashMap<>();
	private final List<InfoBox> hiddenSpecialCounterInfoBoxes = new ArrayList<>();
	private Integer hiddenSpecialCounterNpcIndex;
	private boolean wasInParty;
	private Integer lastSyncSignature;
	private long lastSyncBroadcastMillis;
	private final Map<BossDefence, PendingWorldSync> pendingWorldSyncs = new HashMap<>();
	private final Map<BossDefence, Long> lastPartyEncounterPresenceMillis = new HashMap<>();
	/** Targets whose current remembered encounter has actually received remote party state. */
	private final Set<BossDefence> previouslySyncedBosses = new HashSet<>();
	/** Local absence timer used only for formerly synced open-world state after leaving the party. */
	private final Map<BossDefence, Long> lastSoloSyncedEncounterPresenceMillis = new HashMap<>();
	private final Map<BossDefence, SyncScope> retainedRaidScopes = new HashMap<>();
	private final Map<Long, SenderEncounterPresence> senderEncounterPresence = new HashMap<>();
	/** Recent authoritative encounter resets, keyed by boss + concrete world/raid scope. */
	private final Map<EncounterResetKey, Long> recentEncounterResets = new HashMap<>();
	/** Party members proven to be running BPD during this PartyService session. */
	private final Set<Long> knownBpdPartyMembers = new HashSet<>();
	/** Debounces the all-party-dead ToA state so one wipe clears once, not every tick. */
	private boolean toaPartyWasFullyDead;
	private ActiveSyncScope activeSyncScope;
	private SyncScope lastPresenceScope;
	private long lastPresenceBroadcastMillis;

	@Provides
	@Singleton
	BetterPartyDefenceConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BetterPartyDefenceConfig.class);
	}

	@Override
	protected void startUp()
	{
		migrateLegacyHealthBarDisplayMode();
		syncOverlappingDefenceDisplays();

		// Register the core SpecialCounterUpdate message ourselves so remote party drains can
		// still be decoded even when this client's Special Attack Counter UI is disabled.
		// WSClient de-duplicates registrations by message class.
		wsClient.registerMessage(SpecialCounterUpdate.class);
		// The experimental richer BPD message is harmless when the option is off; registering it
		// simply allows clients with the option enabled to decode one another's snapshots.
		wsClient.registerMessage(BpdDefenceSync.class);
		wsClient.registerMessage(BpdEncounterPresence.class);
		wsClient.registerMessage(BpdEncounterReset.class);

		skillIconSource = new SkillIconSource(client, skillIconManager, config);
		trackerFontManager = new TrackerFontManager(config);
		previousTargetDisplayState = new PreviousTargetDisplayState(client, defenceTracker, config);
		defenceOverlay = new NpcDefenceOverlay(client, defenceTracker, config, skillIconSource,
			trackerFontManager, previousTargetDisplayState);
		screenDefenceOverlay = new ScreenDefenceOverlay(defenceTracker, config, skillIconSource, trackerFontManager);
		previousTargetScreenOverlay = new PreviousTargetScreenOverlay(config, previousTargetDisplayState,
			skillIconSource, trackerFontManager);
		overlayManager.add(defenceOverlay);
		overlayManager.add(screenDefenceOverlay);
		overlayManager.add(previousTargetScreenOverlay);

		wasInParty = partyService.isInParty();
		log.info("Better Party Defence started; Hub Party session active={}", wasInParty);
		if (!isSpecialCounterActive())
		{
			log.info("Special Attack Counter is disabled; Better Party Defence will detect and broadcast "
				+ "this client's supported defence specs itself.");
		}
	}

	@Override
	protected void shutDown()
	{
		restoreOverlappingDefenceDisplays();

		if (defenceOverlay != null)
		{
			overlayManager.remove(defenceOverlay);
			defenceOverlay = null;
		}
		if (screenDefenceOverlay != null)
		{
			overlayManager.remove(screenDefenceOverlay);
			screenDefenceOverlay = null;
		}
		if (previousTargetScreenOverlay != null)
		{
			overlayManager.remove(previousTargetScreenOverlay);
			previousTargetScreenOverlay = null;
		}
		if (previousTargetDisplayState != null)
		{
			previousTargetDisplayState.clear();
			previousTargetDisplayState = null;
		}
		skillIconSource = null;
		trackerFontManager = null;
		removeInfoBox();
		recentSpecEvents.clear();
		recentSyncedSpecs.clear();
		pendingWorldSyncs.clear();
		lastPartyEncounterPresenceMillis.clear();
		previouslySyncedBosses.clear();
		lastSoloSyncedEncounterPresenceMillis.clear();
		retainedRaidScopes.clear();
		senderEncounterPresence.clear();
		recentEncounterResets.clear();
		knownBpdPartyMembers.clear();
		toaPartyWasFullyDead = false;
		activeSyncScope = null;
		lastPresenceScope = null;
		lastPresenceBroadcastMillis = 0L;
		lastSyncSignature = null;
		lastSyncBroadcastMillis = 0L;
		wsClient.unregisterMessage(BpdEncounterReset.class);
		wsClient.unregisterMessage(BpdEncounterPresence.class);
		wsClient.unregisterMessage(BpdDefenceSync.class);
		// Do not unregister a message class that the active core Special Attack Counter still needs.
		if (!isSpecialCounterActive())
		{
			wsClient.unregisterMessage(SpecialCounterUpdate.class);
		}
		localSpecDetector.reset();
		defenceTracker.reset("plugin shutdown");
		wasInParty = false;
		log.info("Better Party Defence shut down");
	}

	@Subscribe(priority = -100f)
	public void onGameTick(GameTick event)
	{
		boolean inParty = partyService.isInParty();
		if (inParty != wasInParty)
		{
			log.debug("Hub Party session state changed: {}", inParty ? "joined" : "left");
			wasInParty = inParty;
		}

		int tick = client.getTickCount();
		recentSpecEvents.entrySet().removeIf(entry -> tick - entry.getValue() > DUPLICATE_WINDOW_TICKS);
		recentSyncedSpecs.entrySet().removeIf(
			entry -> tick - entry.getValue() > SYNC_HISTORY_DUPLICATE_WINDOW_TICKS);
		long now = System.currentTimeMillis();
		senderEncounterPresence.entrySet().removeIf(
			entry -> now - entry.getValue().getReceivedAtMillis() > ENCOUNTER_PRESENCE_MAX_AGE_MILLIS);
		recentEncounterResets.entrySet().removeIf(
			entry -> now - entry.getValue() > ENCOUNTER_RESET_DEDUP_MILLIS);

		// Local defence tracking is intentionally independent of party membership. Hub Party is
		// the transport for remote specs, not a prerequisite for drawing our own tracked target.
		localSpecDetector.onGameTick();
		defenceTracker.onGameTick();
		reconcileAuthoritativeNpcDeaths();
		reconcileToaFullWipe();
		reconcilePendingWorldSyncs();
		reconcileActiveSyncScope();
		maybeBroadcastEncounterPresence();
		maybeBroadcastDefenceSync();
		reconcileRaidEncounterLifecycle();
		reconcilePartyWideEncounterAbsence();
		reconcileFormerSyncedSoloAbsence();
		if (previousTargetDisplayState != null)
		{
			previousTargetDisplayState.update();
		}
		updateDefenceInfoBox();

		// RuneLite's EventBus requires GameTick subscribers to be named exactly onGameTick.
		// Run the visual suppression at the end of our low-priority tick so Special Attack
		// Counter can continue its normal detection/broadcast work first.
		if (config.hideOverlappingDefenceDisplays())
		{
			hideExistingSpecialCounterInfoBoxes();
		}
	}

	@Subscribe
	public void onFakeXpDrop(FakeXpDrop event)
	{
		localSpecDetector.onFakeXpDrop(event);
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		localSpecDetector.onStatChanged(event);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		localSpecDetector.onVarbitChanged(event);
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		localSpecDetector.onHitsplatApplied(event);
	}

	@Subscribe
	public void onPartyChanged(PartyChanged event)
	{
		recentSpecEvents.clear();
		recentSyncedSpecs.clear();
		wasInParty = event.getPartyId() != null;
		lastSyncSignature = null;
		lastSyncBroadcastMillis = 0L;
		pendingWorldSyncs.clear();
		lastPartyEncounterPresenceMillis.clear();
		lastSoloSyncedEncounterPresenceMillis.clear();
		// Party membership is transport only. Do not clear remembered raid scopes or tracker state
		// here: leaving a Hub Party while still inside the same raid must preserve the encounter.
		senderEncounterPresence.clear();
		recentEncounterResets.clear();
		knownBpdPartyMembers.clear();
		toaPartyWasFullyDead = false;
		lastPresenceScope = null;
		lastPresenceBroadcastMillis = 0L;
		activeSyncScope = null;
		log.debug("Hub Party session changed: partyId={}", event.getPartyId());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST)
		{
			localSpecDetector.reset();
			defenceTracker.reset("game state " + state);
			removeInfoBox();
			hiddenSpecialCounterInfoBoxes.clear();
			hiddenSpecialCounterNpcIndex = null;
			recentSpecEvents.clear();
			recentSyncedSpecs.clear();
			activeSyncScope = null;
			pendingWorldSyncs.clear();
			lastPartyEncounterPresenceMillis.clear();
			previouslySyncedBosses.clear();
			lastSoloSyncedEncounterPresenceMillis.clear();
			retainedRaidScopes.clear();
			senderEncounterPresence.clear();
			recentEncounterResets.clear();
			knownBpdPartyMembers.clear();
			toaPartyWasFullyDead = false;
			lastPresenceScope = null;
			lastPresenceBroadcastMillis = 0L;
			lastSyncSignature = null;
			lastSyncBroadcastMillis = 0L;
		}
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		if (!(event.getPlugin() instanceof SpecialCounterPlugin))
		{
			return;
		}

		if (event.isLoaded())
		{
			log.debug("Special Attack Counter enabled: it will own the standard party spec broadcast");
			if (config.hideOverlappingDefenceDisplays())
			{
				hideExistingSpecialCounterInfoBoxes();
			}
		}
		else
		{
			// Its shutdown unregisters SpecialCounterUpdate. Re-register so BPD can continue
			// decoding messages sent by other party members. Any boxes captured from the old
			// Special Counter session are no longer valid after that plugin shuts down.
			hiddenSpecialCounterInfoBoxes.clear();
			hiddenSpecialCounterNpcIndex = null;
			wsClient.registerMessage(SpecialCounterUpdate.class);
			log.debug("Special Attack Counter disabled: Better Party Defence will broadcast supported local drains itself");
		}
	}

	/**
	 * Receives the party message emitted by RuneLite's Special Attack Counter. The active
	 * PartyService session can have been created/joined entirely through Hub Party Panel;
	 * the built-in RuneLite Party sidebar is not required.
	 */
	@Subscribe
	public void onSpecialCounterUpdate(SpecialCounterUpdate event)
	{
		if (!partyService.isInParty())
		{
			return;
		}

		PartyMember localMember = partyService.getLocalMember();
		if (localMember != null && localMember.getMemberId() == event.getMemberId())
		{
			// Our local detector applies our spec immediately. Ignore the server echo (whether the
			// message was sent by BPD or by RuneLite's Special Attack Counter) to prevent doubles.
			return;
		}

		PartyMember sender = partyService.getMemberById(event.getMemberId());
		if (sender == null)
		{
			// PartyService can deliver a message already in flight after its sender has left.
			// Never let that late packet mutate the current encounter.
			return;
		}
		SpecialWeapon weapon = event.getWeapon();
		if (!DefenceTracker.isSupportedWeapon(weapon))
		{
			return;
		}

		String senderName = sender.getDisplayName();
		// Party websocket messages are posted from the websocket thread. Keep all game-client
		// state reads and tracker mutation on RuneLite's client thread, matching core plugin practice.
		clientThread.invoke(() ->
		{
			if (!partyService.isInParty() || partyService.getMemberById(event.getMemberId()) == null)
			{
				return;
			}

			if (!standardPartySpecScopeMatches(event.getMemberId(), event.getWorld()))
			{
				return;
			}

			SpecEventKey key = new SpecEventKey(
				event.getMemberId(), event.getWorld(), event.getNpcIndex(), event.getPlayerId(), weapon, event.getHit());
			int tick = client.getTickCount();
			SyncedSpecKey syncedKey = new SyncedSpecKey(normalizePlayerName(senderName), weapon, event.getHit());
			Integer syncedAt = recentSyncedSpecs.get(syncedKey);
			if (syncedAt != null && tick - syncedAt <= SYNC_HISTORY_DUPLICATE_WINDOW_TICKS)
			{
				log.debug("Ignoring standard party spec already covered by BPD sync {}", syncedKey);
				return;
			}
			Integer lastSeen = recentSpecEvents.get(key);
			if (lastSeen != null && tick - lastSeen <= DUPLICATE_WINDOW_TICKS)
			{
				log.debug("Ignoring duplicate party spec event {}", key);
				return;
			}
			recentSpecEvents.put(key, tick);

			log.debug("Party spec: member={} ({}) weapon={} hit={} npc={} world={}",
				event.getMemberId(), Objects.toString(senderName, "unknown"), weapon,
				event.getHit(), event.getNpcIndex(), event.getWorld());

			markRemotePartySpecTarget(event.getNpcIndex());
			defenceTracker.queue(weapon, event.getNpcIndex(), event.getHit(), event.getWorld(),
				Objects.toString(senderName, "Party member"));
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!BetterPartyDefenceConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		if ("hideOverlappingDefenceDisplays".equals(event.getKey()))
		{
			syncOverlappingDefenceDisplays();
			return;
		}

		if ("defenceInfoBoxUseThemeSkillIcon".equals(event.getKey()))
		{
			if (defenceBox != null || magicDefenceBox != null)
			{
				removeInfoBox();
				updateDefenceInfoBox();
			}
			return;
		}

		if (("previousTargetDisplay".equals(event.getKey()) || "defenceHpBar".equals(event.getKey()))
			&& previousTargetDisplayState != null)
		{
			previousTargetDisplayState.clear();
			return;
		}

		if ("syncWithOtherPartyDefenceUsers".equals(event.getKey()))
		{
			lastSyncSignature = null;
			lastSyncBroadcastMillis = 0L;
			recentSyncedSpecs.clear();
			if (!config.syncWithOtherPartyDefenceUsers())
			{
				// Disabling experimental sync stops BPD transport only. Do not erase a value the
				// player is already using, and especially do not discard raid state. Formerly
				// synced open-world state falls back to the same local 20s absence cleanup used
				// after leaving PartyService; raid state remains governed by its raid scope.
				activeSyncScope = null;
				pendingWorldSyncs.clear();
				lastPartyEncounterPresenceMillis.clear();
				senderEncounterPresence.clear();
				lastPresenceScope = null;
				lastPresenceBroadcastMillis = 0L;
			}
			return;
		}

		if ("defenceFont".equals(event.getKey()) && config.defenceFont() == TrackerFont.ADD_CUSTOM)
		{
			chooseCustomFont(false);
			return;
		}

		if ("previousTargetFont".equals(event.getKey()) && config.previousTargetFont() == TrackerFont.ADD_CUSTOM)
		{
			chooseCustomFont(true);
		}
	}


	private void chooseCustomFont(boolean previousTarget)
	{
		SwingUtilities.invokeLater(() ->
		{
			JFileChooser chooser = new JFileChooser();
			chooser.setDialogTitle(previousTarget
				? "Choose a font for Better Party Defence sticky display"
				: "Choose a font for Better Party Defence");
			chooser.setFileFilter(new FileNameExtensionFilter("Font files (*.ttf, *.otf)", "ttf", "otf"));

			String pathKey = previousTarget ? "previousTargetCustomFontPath" : "customFontPath";
			String fontKey = previousTarget ? "previousTargetFont" : "defenceFont";
			String current = previousTarget ? config.previousTargetCustomFontPath() : config.customFontPath();
			if (current != null && !current.trim().isEmpty())
			{
				File file = new File(current);
				if (file.getParentFile() != null)
				{
					chooser.setCurrentDirectory(file.getParentFile());
				}
			}

			if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
			{
				configManager.setConfiguration(BetterPartyDefenceConfig.GROUP, pathKey,
					chooser.getSelectedFile().getAbsolutePath());
				configManager.setConfiguration(BetterPartyDefenceConfig.GROUP, fontKey, TrackerFont.CUSTOM);
			}
			else
			{
				TrackerFont fallback = current == null || current.trim().isEmpty()
					? TrackerFont.RUNESCAPE : TrackerFont.CUSTOM;
				configManager.setConfiguration(BetterPartyDefenceConfig.GROUP, fontKey, fallback);
			}
		});
	}


	@Subscribe
	public void onBpdEncounterPresence(BpdEncounterPresence event)
	{
		if (!partyService.isInParty()
			|| event == null || event.getProtocolVersion() != BpdEncounterPresence.PROTOCOL_VERSION)
		{
			return;
		}

		PartyMember localMember = partyService.getLocalMember();
		if (localMember != null && localMember.getMemberId() == event.getMemberId())
		{
			return;
		}
		if (partyService.getMemberById(event.getMemberId()) == null)
		{
			return;
		}

		clientThread.invoke(() ->
		{
			knownBpdPartyMembers.add(event.getMemberId());
			if (config.syncWithOtherPartyDefenceUsers())
			{
				rememberSenderEncounterPresence(event.getMemberId(), event.getWorld(),
					event.getScopeType(), event.getScopeId(), event.getSentAtMillis());
			}
		});
	}

	/** Receive an authoritative encounter reset from another current BPD party member. */
	@Subscribe
	public void onBpdEncounterReset(BpdEncounterReset event)
	{
		if (!partyService.isInParty()
			|| event == null || event.getProtocolVersion() != BpdEncounterReset.PROTOCOL_VERSION)
		{
			return;
		}

		PartyMember localMember = partyService.getLocalMember();
		if (localMember != null && localMember.getMemberId() == event.getMemberId())
		{
			return;
		}
		if (partyService.getMemberById(event.getMemberId()) == null)
		{
			return;
		}

		clientThread.invoke(() ->
		{
			knownBpdPartyMembers.add(event.getMemberId());
			if (!config.syncWithOtherPartyDefenceUsers() || !partyService.isInParty()
				|| partyService.getMemberById(event.getMemberId()) == null
				|| event.getWorld() != client.getWorld())
			{
				return;
			}

			long age = Math.abs(System.currentTimeMillis() - event.getSentAtMillis());
			if (event.getSentAtMillis() <= 0 || age > SYNC_MAX_AGE_MILLIS)
			{
				return;
			}

			BossDefence boss = event.toBossType();
			if (boss == null || !incomingEncounterResetScopeMatches(event, boss))
			{
				return;
			}

			rememberSenderEncounterPresence(event.getMemberId(), event.getWorld(),
				event.getScopeType(), event.getScopeId(), event.getSentAtMillis());
			applyEncounterReset(boss, new SyncScope(event.getScopeType(), event.getScopeId()),
				"party encounter reset from member " + event.getMemberId(), false);
		});
	}

	private void maybeBroadcastEncounterPresence()
	{
		if (!config.syncWithOtherPartyDefenceUsers() || !partyService.isInParty()
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			lastPresenceScope = null;
			lastPresenceBroadcastMillis = 0L;
			return;
		}

		SyncScope scope = currentEncounterScope();
		if (scope == null)
		{
			return;
		}

		long now = System.currentTimeMillis();
		boolean changed = !scope.equals(lastPresenceScope);
		if (!changed && now - lastPresenceBroadcastMillis < ENCOUNTER_PRESENCE_INTERVAL_MILLIS)
		{
			return;
		}

		partyService.send(new BpdEncounterPresence(client.getWorld(), scope.getType(), scope.getId()));
		lastPresenceScope = scope;
		lastPresenceBroadcastMillis = now;
	}

	private void rememberSenderEncounterPresence(long memberId, int world, int scopeType, int scopeId, long sentAtMillis)
	{
		// A valid BPD protocol message proves this member is a BPD peer even if it was sent from
		// another world. Remember that fact for the life of this PartyService session so a stale
		// heartbeat can fail closed rather than silently downgrading the sender to legacy mode.
		knownBpdPartyMembers.add(memberId);
		if (world != client.getWorld() || sentAtMillis <= 0)
		{
			return;
		}
		long age = Math.abs(System.currentTimeMillis() - sentAtMillis);
		if (age > ENCOUNTER_PRESENCE_MAX_AGE_MILLIS)
		{
			return;
		}
		senderEncounterPresence.put(memberId, new SenderEncounterPresence(
			world, scopeType, scopeId, System.currentTimeMillis()));
	}

	/**
	 * For another BPD client, normal SpecialCounterUpdate events are accepted only when both
	 * clients advertise the same concrete encounter scope. Once a member is known to be running
	 * BPD, a missing/stale heartbeat fails closed while experimental sync is enabled instead of
	 * silently falling back to legacy matching. Truly legacy/non-BPD members keep RuneLite's
	 * standard fallback. When the user disables experimental sync, all standard party specs use
	 * the legacy path as before.
	 */
	private boolean standardPartySpecScopeMatches(long memberId, int eventWorld)
	{
		if (!config.syncWithOtherPartyDefenceUsers())
		{
			return true;
		}

		SenderEncounterPresence senderScope = senderEncounterPresence.get(memberId);
		if (senderScope == null)
		{
			if (knownBpdPartyMembers.contains(memberId))
			{
				log.debug("Ignoring BPD party spec from member={} until a fresh encounter heartbeat arrives",
					memberId);
				return false;
			}
			return true;
		}

		long age = System.currentTimeMillis() - senderScope.getReceivedAtMillis();
		if (age > ENCOUNTER_PRESENCE_MAX_AGE_MILLIS)
		{
			senderEncounterPresence.remove(memberId);
			log.debug("Ignoring BPD party spec from member={} because encounter heartbeat is stale ({}ms)",
				memberId, age);
			return false;
		}
		if (eventWorld != client.getWorld() || senderScope.getWorld() != client.getWorld())
		{
			log.debug("Ignoring BPD party spec from member={} due to world mismatch sender={} event={} local={}",
				memberId, senderScope.getWorld(), eventWorld, client.getWorld());
			return false;
		}

		SyncScope localScope = currentEncounterScope();
		boolean match = localScope != null
			&& localScope.getType() == senderScope.getScopeType()
			&& localScope.getId() == senderScope.getScopeId();
		if (!match)
		{
			log.debug("Ignoring BPD party spec from member={} due to encounter mismatch sender={}:{} local={}",
				memberId, senderScope.getScopeType(), senderScope.getScopeId(), localScope);
		}
		return match;
	}

	/** Receive the optional absolute BPD tracker snapshot from another member of this PartyService party. */
	@Subscribe
	public void onBpdDefenceSync(BpdDefenceSync event)
	{
		if (!partyService.isInParty()
			|| event == null || event.getProtocolVersion() != BpdDefenceSync.PROTOCOL_VERSION)
		{
			return;
		}

		PartyMember localMember = partyService.getLocalMember();
		if (localMember != null && localMember.getMemberId() == event.getMemberId())
		{
			return;
		}
		if (partyService.getMemberById(event.getMemberId()) == null)
		{
			return;
		}

		clientThread.invoke(() ->
		{
			knownBpdPartyMembers.add(event.getMemberId());
			if (!config.syncWithOtherPartyDefenceUsers())
			{
				return;
			}
			rememberSenderEncounterPresence(event.getMemberId(), event.getWorld(),
				event.getScopeType(), event.getScopeId(), event.getSentAtMillis());
			acceptBpdDefenceSync(event);
		});
	}

	private void acceptBpdDefenceSync(BpdDefenceSync event)
	{
		if (!config.syncWithOtherPartyDefenceUsers() || !partyService.isInParty()
			|| event.getWorld() != client.getWorld())
		{
			return;
		}
		long age = Math.abs(System.currentTimeMillis() - event.getSentAtMillis());
		if (event.getSentAtMillis() <= 0 || age > SYNC_MAX_AGE_MILLIS)
		{
			log.debug("Ignoring stale BPD sync age={}ms", age);
			return;
		}

		DefenceTracker.SyncState sync = event.toSyncState();
		if (sync == null || sync.getBossType() == null || !sync.isDrained())
		{
			return;
		}

		BossDefence boss = sync.getBossType();
		NPC localBoss = findLiveNpcForBoss(boss);
		if (isBlockedByRecentEncounterReset(boss, event.getScopeType(), event.getScopeId()))
		{
			log.debug("Ignoring BPD sync for {} during the post-reset stale-snapshot barrier", boss);
			return;
		}

		// A world-scoped boss cannot be position/HP-verified until it is rendered locally. Cache
		// the fresh absolute snapshot silently, then bind it when that boss actually enters this
		// client's scene. This preserves late-render catch-up without letting an out-of-vicinity
		// party spec replace the user's current detached display/InfoBox selection. Keep the event
		// cached too so the first local render can run the stricter world position/HP reconciliation.
		if (event.getScopeType() == BpdDefenceSync.SCOPE_WORLD && localBoss == null)
		{
			markPartyEncounterPresent(boss);
			cachePendingWorldSync(event, sync);

			if (incomingSyncAddsState(sync))
			{
				// Multi-target memory saves the previously active target before this synced one
				// becomes active, so a party spec on another boss/Olm hand never erases it.
				applyAcceptedSync(event, sync, null);
			}
			return;
		}

		if (!incomingScopeMatches(event, boss, localBoss))
		{
			return;
		}

		markPartyEncounterPresent(boss);

		// Different supported targets are independent. The tracker remembers the current one
		// before activating this target, so simultaneous encounters such as both Olm hands retain
		// their own defence/history instead of overwriting each other.
		if (!incomingSyncIsUseful(sync, localBoss))
		{
			return;
		}

		applyAcceptedSync(event, sync, localBoss);
	}

	/**
	 * A concrete dead NPC is authoritative for non-raid encounters. The tracker already removed
	 * its local state; this method atomically invalidates the remaining sync/history metadata and
	 * tells BPD peers so an older, longer spec history cannot be restored onto the respawn.
	 */
	private void reconcileAuthoritativeNpcDeaths()
	{
		for (BossDefence boss : defenceTracker.consumeEndedBosses())
		{
			if (boss == null || isRaidEncounterBoss(boss))
			{
				continue;
			}
			resetEncounterAndBroadcast(boss, currentEncounterScope(), "local NPC death");
		}
	}

	/**
	 * ToA is intentionally special here. An individual death/runback keeps raid Defence state, but
	 * when every occupied ToA party orb reports dead the room has wiped and the boss encounter is
	 * restarting inside the same raid instance. Clear every remembered ToA target once on that
	 * transition so Defence, spec history and the previous-target marker all restart together.
	 */
	private void reconcileToaFullWipe()
	{
		List<BossDefence> remembered = rememberedToaBosses();
		boolean fullyDead = isToaPartyFullyDead();

		if (!fullyDead)
		{
			toaPartyWasFullyDead = false;
			return;
		}
		if (toaPartyWasFullyDead || remembered.isEmpty())
		{
			toaPartyWasFullyDead = true;
			return;
		}

		toaPartyWasFullyDead = true;
		for (BossDefence boss : remembered)
		{
			SyncScope scope = currentRaidScopeForBoss(boss);
			resetEncounterAndBroadcast(boss, scope, "ToA full-party wipe");
		}
	}

	private List<BossDefence> rememberedToaBosses()
	{
		List<BossDefence> bosses = new ArrayList<>();
		for (BossDefence boss : BossDefence.values())
		{
			if (isToaBoss(boss) && defenceTracker.hasRememberedState(boss))
			{
				bosses.add(boss);
			}
		}
		return bosses;
	}

	private boolean isToaPartyFullyDead()
	{
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || !worldView.isInstance())
		{
			return false;
		}

		boolean anyOccupiedSlot = false;
		for (int varbit : TOA_PARTY_HEALTH_VARBITS)
		{
			int health = client.getVarbitValue(varbit);
			if (health == 0)
			{
				continue; // Hidden/unused party slot.
			}
			anyOccupiedSlot = true;
			if (health != TOA_PARTY_MEMBER_DEAD)
			{
				return false;
			}
		}
		return anyOccupiedSlot;
	}

	private void resetEncounterAndBroadcast(BossDefence boss, SyncScope scope, String reason)
	{
		if (!applyEncounterReset(boss, scope, reason, true))
		{
			return;
		}

		if (scope != null && config.syncWithOtherPartyDefenceUsers() && partyService.isInParty())
		{
			partyService.send(new BpdEncounterReset(client.getWorld(), boss, scope.getType(), scope.getId()));
			log.debug("Broadcast BPD encounter reset boss={} scope={}:{} reason={}",
				boss, scope.getType(), scope.getId(), reason);
		}
	}

	/** Clear Defence + history + all reconciliation metadata as one encounter state. */
	private boolean applyEncounterReset(BossDefence boss, SyncScope scope, String reason, boolean authoritativeLocal)
	{
		if (boss == null)
		{
			return false;
		}

		long now = System.currentTimeMillis();
		EncounterResetKey key = scope == null ? null
			: new EncounterResetKey(client.getWorld(), boss, scope.getType(), scope.getId());
		if (key != null)
		{
			Long lastReset = recentEncounterResets.get(key);
			if (!authoritativeLocal && lastReset != null && now - lastReset <= ENCOUNTER_RESET_DEDUP_MILLIS)
			{
				return false;
			}
			recentEncounterResets.put(key, now);
		}

		defenceTracker.clearBossState(boss, reason);
		pendingWorldSyncs.remove(boss);
		lastPartyEncounterPresenceMillis.remove(boss);
		lastSoloSyncedEncounterPresenceMillis.remove(boss);
		previouslySyncedBosses.remove(boss);
		retainedRaidScopes.remove(boss);
		if (activeSyncScope != null && activeSyncScope.getBoss() == boss)
		{
			activeSyncScope = null;
		}
		lastSyncSignature = null;
		lastSyncBroadcastMillis = 0L;
		log.debug("Reset encounter state for {}: {}", boss, reason);
		return true;
	}

	private boolean isBlockedByRecentEncounterReset(BossDefence boss, int scopeType, int scopeId)
	{
		EncounterResetKey key = new EncounterResetKey(client.getWorld(), boss, scopeType, scopeId);
		Long resetAt = recentEncounterResets.get(key);
		return resetAt != null && System.currentTimeMillis() - resetAt <= ENCOUNTER_RESET_BARRIER_MILLIS;
	}

	private boolean incomingEncounterResetScopeMatches(BpdEncounterReset event, BossDefence boss)
	{
		int expectedRaidScope = raidScopeType(boss);
		if (expectedRaidScope != -1)
		{
			return event.getScopeType() == expectedRaidScope
				&& event.getScopeId() > 0
				&& raidController(expectedRaidScope) == event.getScopeId();
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return false;
		}
		if (event.getScopeType() == BpdDefenceSync.SCOPE_INSTANCE)
		{
			return worldView.isInstance() && instanceFingerprint(worldView) == event.getScopeId();
		}
		return event.getScopeType() == BpdDefenceSync.SCOPE_WORLD && !worldView.isInstance();
	}

	/** Broadcast drained visible targets periodically; the refresh also acts as party encounter presence. */
	private void maybeBroadcastDefenceSync()
	{
		if (!config.syncWithOtherPartyDefenceUsers() || !partyService.isInParty())
		{
			lastSyncSignature = null;
			lastSyncBroadcastMillis = 0L;
			return;
		}

		List<DefenceTracker.SyncTarget> targets = defenceTracker.syncTargets();
		if (targets.isEmpty())
		{
			lastSyncSignature = null;
			lastSyncBroadcastMillis = 0L;
			return;
		}

		int signature = targets.hashCode();
		long now = System.currentTimeMillis();
		if (lastSyncSignature != null && lastSyncSignature == signature
			&& now - lastSyncBroadcastMillis < SYNC_REBROADCAST_INTERVAL_MILLIS)
		{
			return;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || worldView.npcs() == null)
		{
			return;
		}

		boolean sentAny = false;
		for (DefenceTracker.SyncTarget target : targets)
		{
			DefenceTracker.SyncState sync = target.getState();
			NPC npc = target.getNpcIndex() < 0 ? null : worldView.npcs().byIndex(target.getNpcIndex());
			if (sync == null || !sync.isDrained() || npc == null || npc.isDead() || npc.getHealthRatio() == 0)
			{
				continue;
			}

			SyncScope scope = localSyncScope(sync.getBossType(), npc);
			if (scope == null)
			{
				continue;
			}
			rememberRaidScope(sync.getBossType(), scope);

			WorldPoint point = npc.getWorldLocation();
			int x = point == null ? 0 : point.getX();
			int y = point == null ? 0 : point.getY();
			int plane = point == null ? 0 : point.getPlane();
			int hp = healthPercent(npc);

			partyService.send(new BpdDefenceSync(
				client.getWorld(), sync, scope.getType(), scope.getId(), x, y, plane, hp));
			markPartyEncounterPresent(sync.getBossType());
			sentAny = true;
			log.debug("Broadcast BPD sync boss={} scope={}:{} def={}/{}",
				sync.getBossType(), scope.getType(), scope.getId(), sync.getCurrent(), sync.getBase());
		}

		if (sentAny)
		{
			lastSyncSignature = signature;
			lastSyncBroadcastMillis = now;
		}
	}

	private void markPartyEncounterPresent(BossDefence boss)
	{
		if (boss != null)
		{
			lastPartyEncounterPresenceMillis.put(boss, System.currentTimeMillis());
		}
	}

	/**
	 * Open-world drained targets are retained while at least one participating BPD client still has
	 * the encounter rendered. If nobody in the party can see an open-world target for twenty
	 * continuous seconds, expire only that target's remembered state. Raid targets deliberately do
	 * not use this timeout: deaths, runbacks, room transitions and temporary loss of render must keep
	 * their state until the actual raid scope ends or changes.
	 */
	private void reconcilePartyWideEncounterAbsence()
	{
		if (!config.syncWithOtherPartyDefenceUsers() || !partyService.isInParty())
		{
			lastPartyEncounterPresenceMillis.clear();
			return;
		}

		long now = System.currentTimeMillis();
		for (BossDefence boss : new ArrayList<>(defenceTracker.drainedBosses()))
		{
			if (isRaidEncounterBoss(boss))
			{
				lastPartyEncounterPresenceMillis.remove(boss);
				continue;
			}

			if (defenceTracker.hasBoundNpc(boss))
			{
				markPartyEncounterPresent(boss);
				continue;
			}

			Long lastPresent = lastPartyEncounterPresenceMillis.get(boss);
			if (lastPresent == null)
			{
				lastPartyEncounterPresenceMillis.put(boss, now);
				continue;
			}

			if (now - lastPresent >= SYNC_PARTY_WIDE_ABSENCE_TIMEOUT_MILLIS)
			{
				log.debug("Clearing {} after {}ms of party-wide encounter absence",
					boss, now - lastPresent);
				defenceTracker.clearBossState(boss, "20s party-wide encounter absence");
				pendingWorldSyncs.remove(boss);
				lastPartyEncounterPresenceMillis.remove(boss);
				if (activeSyncScope != null && activeSyncScope.getBoss() == boss)
				{
					activeSyncScope = null;
				}
				lastSyncSignature = null;
			}
		}

		lastPartyEncounterPresenceMillis.keySet().removeIf(
			boss -> !defenceTracker.hasRememberedState(boss));
	}


	/**
	 * When BPD sync transport is unavailable (left PartyService or experimental sync is disabled),
	 * only encounter state that actually received remote party data falls back to a local-only
	 * absence grace. Open-world targets remain while their NPC is rendered and clear after twenty
	 * continuous seconds out of this client's scene. Purely local states never enter this path.
	 * Raid targets are excluded entirely: their lifetime is governed only by the concrete raid scope
	 * in {@link #reconcileRaidEncounterLifecycle()}.
	 */
	private void reconcileFormerSyncedSoloAbsence()
	{
		// Drop provenance as soon as that remembered encounter is actually gone. This prevents a
		// later purely-local encounter with the same boss type inheriting an old sync marker.
		previouslySyncedBosses.removeIf(boss -> !defenceTracker.hasRememberedState(boss));
		lastSoloSyncedEncounterPresenceMillis.keySet().removeIf(
			boss -> !previouslySyncedBosses.contains(boss));

		if (config.syncWithOtherPartyDefenceUsers() && partyService.isInParty())
		{
			lastSoloSyncedEncounterPresenceMillis.clear();
			return;
		}

		long now = System.currentTimeMillis();
		for (BossDefence boss : new ArrayList<>(previouslySyncedBosses))
		{
			if (isRaidEncounterBoss(boss))
			{
				// Leaving the Hub Party only stops future synchronization. A raid value stays until
				// the actual raid controller/instance disappears or changes.
				lastSoloSyncedEncounterPresenceMillis.remove(boss);
				continue;
			}

			if (defenceTracker.hasBoundNpc(boss))
			{
				// Full grace starts only after the formerly synced target leaves the local scene.
				lastSoloSyncedEncounterPresenceMillis.remove(boss);
				continue;
			}

			Long absentSince = lastSoloSyncedEncounterPresenceMillis.get(boss);
			if (absentSince == null)
			{
				lastSoloSyncedEncounterPresenceMillis.put(boss, now);
				continue;
			}

			if (now - absentSince >= SYNC_PARTY_WIDE_ABSENCE_TIMEOUT_MILLIS)
			{
				log.debug("Clearing formerly synced {} after {}ms of solo encounter absence",
					boss, now - absentSince);
				defenceTracker.clearBossState(boss, "20s solo absence after leaving party");
				pendingWorldSyncs.remove(boss);
				lastPartyEncounterPresenceMillis.remove(boss);
				lastSoloSyncedEncounterPresenceMillis.remove(boss);
				previouslySyncedBosses.remove(boss);
				if (activeSyncScope != null && activeSyncScope.getBoss() == boss)
				{
					activeSyncScope = null;
				}
				lastSyncSignature = null;
			}
		}
	}


	/**
	 * Raid state is encounter-scoped rather than render-scoped. Once a drained raid target is tied
	 * to a concrete raid scope, keep it through deaths/runbacks and only clear it when that scope
	 * actually disappears or changes. This is deliberately independent of the 20-second world
	 * absence timeout.
	 */
	private void reconcileRaidEncounterLifecycle()
	{
		for (BossDefence boss : new ArrayList<>(defenceTracker.drainedBosses()))
		{
			if (!isRaidEncounterBoss(boss))
			{
				retainedRaidScopes.remove(boss);
				continue;
			}

			SyncScope currentScope = currentRaidScopeForBoss(boss);
			SyncScope retainedScope = retainedRaidScopes.get(boss);
			if (retainedScope == null)
			{
				if (currentScope != null)
				{
					retainedRaidScopes.put(boss, currentScope);
				}
				continue;
			}

			if (!retainedScope.equals(currentScope))
			{
				log.debug("Clearing {} because raid scope ended/changed from {}:{} to {}",
					boss, retainedScope.getType(), retainedScope.getId(), currentScope);
				defenceTracker.clearBossState(boss, "raid encounter scope ended or changed");
				pendingWorldSyncs.remove(boss);
				lastPartyEncounterPresenceMillis.remove(boss);
				retainedRaidScopes.remove(boss);
				if (activeSyncScope != null && activeSyncScope.getBoss() == boss)
				{
					activeSyncScope = null;
				}
				lastSyncSignature = null;
			}
		}

		retainedRaidScopes.keySet().removeIf(boss -> !defenceTracker.hasRememberedState(boss));
	}

	private void rememberRaidScope(BossDefence boss, SyncScope scope)
	{
		if (isRaidEncounterBoss(boss) && scope != null)
		{
			retainedRaidScopes.putIfAbsent(boss, scope);
		}
	}

	private SyncScope currentRaidScopeForBoss(BossDefence boss)
	{
		int raidScope = raidScopeType(boss);
		if (raidScope != -1)
		{
			int controller = raidController(raidScope);
			return controller > 0 ? new SyncScope(raidScope, controller) : null;
		}

		if (!isToaBoss(boss))
		{
			return null;
		}

		WorldView worldView = client.getTopLevelWorldView();
		return worldView != null && worldView.isInstance()
			? new SyncScope(BpdDefenceSync.SCOPE_INSTANCE, instanceFingerprint(worldView))
			: null;
	}

	private static boolean isRaidEncounterBoss(BossDefence boss)
	{
		return raidScopeType(boss) != -1 || isToaBoss(boss);
	}

	private static boolean isToaBoss(BossDefence boss)
	{
		if (boss == null)
		{
			return false;
		}
		switch (boss)
		{
			case AKKHA:
			case AKKHAS_SHADOW:
			case BA_BA:
			case CORE:
			case ELIDINIS_WARDEN:
			case KEPHRI:
			case OBELISK:
			case TUMEKENS_WARDEN:
			case ZEBAK:
				return true;
			default:
				return false;
		}
	}

	private void cachePendingWorldSync(BpdDefenceSync event, DefenceTracker.SyncState sync)
	{
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || worldView.isInstance())
		{
			return;
		}

		PendingWorldSync existing = pendingWorldSyncs.get(sync.getBossType());
		if (existing == null || event.getSentAtMillis() >= existing.getEvent().getSentAtMillis())
		{
			pendingWorldSyncs.put(sync.getBossType(), new PendingWorldSync(event, sync));
			log.debug("Cached BPD world sync from member={} boss={} def={}/{}",
				event.getMemberId(), sync.getBossType(), sync.getCurrent(), sync.getBase());
		}
	}

	private void reconcilePendingWorldSyncs()
	{
		if (pendingWorldSyncs.isEmpty())
		{
			return;
		}
		if (!config.syncWithOtherPartyDefenceUsers() || !partyService.isInParty())
		{
			pendingWorldSyncs.clear();
			return;
		}

		long now = System.currentTimeMillis();
		Iterator<Map.Entry<BossDefence, PendingWorldSync>> iterator = pendingWorldSyncs.entrySet().iterator();
		while (iterator.hasNext())
		{
			Map.Entry<BossDefence, PendingWorldSync> entry = iterator.next();
			PendingWorldSync pending = entry.getValue();
			BpdDefenceSync event = pending.getEvent();
			DefenceTracker.SyncState sync = pending.getSync();

			if (event.getWorld() != client.getWorld() || event.getSentAtMillis() <= 0
				|| now - event.getSentAtMillis() > SYNC_MAX_AGE_MILLIS)
			{
				iterator.remove();
				continue;
			}

			NPC localBoss = findLiveNpcForBoss(entry.getKey());
			if (localBoss == null || !incomingScopeMatches(event, entry.getKey(), localBoss))
			{
				continue;
			}

			if (!incomingSyncIsUseful(sync, localBoss))
			{
				iterator.remove();
				continue;
			}

			applyAcceptedSync(event, sync, localBoss);
			iterator.remove();
		}
	}

	/**
	 * A snapshot can be useful even when it carries the exact same defence/history: if this
	 * client previously accepted it while the boss was out of scene, the newly rendered NPC
	 * still needs to be bound to that already-correct absolute state. Treat that bind-only
	 * transition as reconciliation instead of rejecting the snapshot as a duplicate.
	 */
	private boolean incomingSyncIsUseful(DefenceTracker.SyncState incoming, NPC localBoss)
	{
		if (incomingSyncAddsState(incoming))
		{
			return true;
		}

		return localBoss != null
			&& !defenceTracker.hasBoundNpc(incoming.getBossType());
	}

	private boolean incomingSyncAddsState(DefenceTracker.SyncState incoming)
	{
		DefenceTracker.SyncState local = defenceTracker.syncStateForBoss(incoming.getBossType());
		if (local == null)
		{
			return true;
		}

		int localHistory = local.getHistory() == null ? 0 : local.getHistory().size();
		int incomingHistory = incoming.getHistory() == null ? 0 : incoming.getHistory().size();
		if (incomingHistory != localHistory)
		{
			return incomingHistory > localHistory;
		}
		return !local.isDrained() && incoming.isDrained();
	}

	private void applyAcceptedSync(BpdDefenceSync event, DefenceTracker.SyncState sync, NPC localBoss)
	{
		if (localBoss == null)
		{
			if (defenceTracker.trackedBossType() == sync.getBossType() && !defenceTracker.hasBoundNpc())
			{
				// Updating the same already-selected logical target cannot steal selection, and avoids
				// the active target's periodic save from overwriting a newer passive snapshot.
				defenceTracker.applySyncState(sync, null);
			}
			else
			{
				// Cache a different remote target silently until this client can verify/bind its NPC.
				// An out-of-vicinity party spec must not steal detached/InfoBox selection.
				defenceTracker.cacheSyncState(sync);
			}
		}
		else
		{
			defenceTracker.applySyncState(sync, localBoss);
		}
		previouslySyncedBosses.add(sync.getBossType());
		rememberRaidScope(sync.getBossType(), new SyncScope(event.getScopeType(), event.getScopeId()));
		activeSyncScope = localBoss == null ? null
			: new ActiveSyncScope(event.getScopeType(), event.getScopeId(), sync.getBossType());
		lastSyncSignature = defenceTracker.syncTargets().hashCode();
		// Treat a received snapshot as recent activity so this client does not immediately echo it
		// back on the same tick. It may still refresh the state later if it remains the live tracker.
		lastSyncBroadcastMillis = System.currentTimeMillis();
		rememberSyncedSpecs(sync);
		log.debug("Accepted BPD sync from member={} boss={} scope={}:{} actor={} def={}/{}",
			event.getMemberId(), sync.getBossType(), event.getScopeType(), event.getScopeId(),
			localBoss == null ? -1 : localBoss.getIndex(), sync.getCurrent(), sync.getBase());
	}

	private int syncSignature(DefenceTracker.SyncState sync)
	{
		return Objects.hash(
			sync.getBossType(), sync.getCurrent(), sync.getMin(), sync.getBase(),
			sync.getAttackLevel(), sync.getStrengthLevel(), sync.getMagicLevel(),
			sync.getMagicBaseLevel(), sync.getMagicDef(), sync.getMagicBaseDef(),
			sync.isAccursedApplied(), sync.isDrained(), sync.getHistory());
	}

	private void rememberSyncedSpecs(DefenceTracker.SyncState sync)
	{
		List<DefenceTracker.SpecHistoryEntry> history = sync.getHistory();
		if (history == null || history.isEmpty())
		{
			return;
		}

		// The snapshot is absolute and may contain more than one new same-tick spec. Mark every
		// represented row briefly so the standard SpecialCounterUpdate echo cannot apply it again.
		int tick = client.getTickCount();
		for (DefenceTracker.SpecHistoryEntry entry : history)
		{
			if (entry != null && entry.getWeapon() != null)
			{
				recentSyncedSpecs.put(new SyncedSpecKey(
					normalizePlayerName(entry.getPlayerName()), entry.getWeapon(), entry.getHit()), tick);
			}
		}
	}

	private static String normalizePlayerName(String name)
	{
		if (name == null)
		{
			return "";
		}
		return Text.removeTags(name).trim().toLowerCase();
	}

	private SyncScope currentEncounterScope()
	{
		int coxController = raidController(BpdDefenceSync.SCOPE_COX);
		if (coxController > 0)
		{
			return new SyncScope(BpdDefenceSync.SCOPE_COX, coxController);
		}

		int tobController = raidController(BpdDefenceSync.SCOPE_TOB);
		if (tobController > 0)
		{
			return new SyncScope(BpdDefenceSync.SCOPE_TOB, tobController);
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return null;
		}
		if (worldView.isInstance())
		{
			return new SyncScope(BpdDefenceSync.SCOPE_INSTANCE, instanceFingerprint(worldView));
		}
		return new SyncScope(BpdDefenceSync.SCOPE_WORLD, 0);
	}

	private SyncScope localSyncScope(BossDefence boss, NPC npc)
	{
		int raidScope = raidScopeType(boss);
		if (raidScope != -1)
		{
			int controller = raidController(raidScope);
			return controller > 0 ? new SyncScope(raidScope, controller) : null;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return null;
		}
		if (worldView.isInstance())
		{
			// For instances without a public shared controller id (including ToA), use a
			// conservative scene/template fingerprint. A mismatch rejects the sync rather than
			// risking cross-instance state. This is intentionally strict while the feature is experimental.
			return new SyncScope(BpdDefenceSync.SCOPE_INSTANCE, instanceFingerprint(worldView));
		}
		return npc == null ? null : new SyncScope(BpdDefenceSync.SCOPE_WORLD, 0);
	}

	private boolean incomingScopeMatches(BpdDefenceSync event, BossDefence boss, NPC localBoss)
	{
		int expectedRaidScope = raidScopeType(boss);
		if (expectedRaidScope != -1)
		{
			return event.getScopeType() == expectedRaidScope
				&& event.getScopeId() > 0
				&& raidController(expectedRaidScope) == event.getScopeId();
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return false;
		}
		if (event.getScopeType() == BpdDefenceSync.SCOPE_INSTANCE)
		{
			return worldView.isInstance() && instanceFingerprint(worldView) == event.getScopeId();
		}
		if (event.getScopeType() != BpdDefenceSync.SCOPE_WORLD || worldView.isInstance() || localBoss == null)
		{
			return false;
		}

		// Open-world NPC health is not guaranteed to be known merely because the actor is
		// rendered. Giant Mole is a common example: a late-arriving party member may see the
		// NPC before RuneLite has a usable health ratio, and the ratio becomes available only
		// after combat starts. Do not make HP a prerequisite for binding an already-authenticated
		// party snapshot. First require the same world-scene location; when both clients do know
		// HP, keep the HP tolerance as an additional anti-mismatch check.
		WorldPoint point = localBoss.getWorldLocation();
		if (point == null || point.getPlane() != event.getBossPlane()
			|| Math.abs(point.getX() - event.getBossX()) > NON_INSTANCE_POSITION_TOLERANCE
			|| Math.abs(point.getY() - event.getBossY()) > NON_INSTANCE_POSITION_TOLERANCE)
		{
			return false;
		}

		int localHp = healthPercent(localBoss);
		int remoteHp = event.getHealthPercent();
		return localHp < 0 || remoteHp < 0
			|| Math.abs(localHp - remoteHp) <= NON_INSTANCE_HP_TOLERANCE;
	}

	private void reconcileActiveSyncScope()
	{
		if (activeSyncScope == null)
		{
			return;
		}

		DefenceTracker.DefenceState state = defenceTracker.state();
		BossDefence tracked = defenceTracker.trackedBossType();
		if (state == null)
		{
			activeSyncScope = null;
			return;
		}
		if (tracked != activeSyncScope.getBoss())
		{
			// A normal local event moved the tracker to another boss; that local state wins.
			activeSyncScope = null;
			return;
		}
		if (!partyService.isInParty())
		{
			// Party membership is only the sync transport. Preserve the tracker state and let
			// raid-scope lifecycle / formerly-synced world cleanup decide when it actually ends.
			activeSyncScope = null;
			return;
		}

		if (!syncScopeStillValid(activeSyncScope))
		{
			defenceTracker.reset("BPD synced encounter ended locally");
			activeSyncScope = null;
			lastSyncSignature = null;
			removeInfoBox();
		}
	}

	private boolean syncScopeStillValid(ActiveSyncScope scope)
	{
		if (client.getGameState() != GameState.LOGGED_IN || !partyService.isInParty())
		{
			return false;
		}
		switch (scope.getType())
		{
			case BpdDefenceSync.SCOPE_COX:
			case BpdDefenceSync.SCOPE_TOB:
				return scope.getId() > 0 && raidController(scope.getType()) == scope.getId();
			case BpdDefenceSync.SCOPE_INSTANCE:
				WorldView worldView = client.getTopLevelWorldView();
				return worldView != null && worldView.isInstance()
					&& instanceFingerprint(worldView) == scope.getId();
			case BpdDefenceSync.SCOPE_WORLD:
			default:
				// Open-world encounters are cleared by the normal local boss death/world-hop rules.
				// Do not treat walking out of render distance as an encounter end.
				return true;
		}
	}

	private int raidController(int scopeType)
	{
		switch (scopeType)
		{
			case BpdDefenceSync.SCOPE_COX:
				return client.getVarpValue(VarPlayerID.RAIDS_PARTY_GROUPHOLDER);
			case BpdDefenceSync.SCOPE_TOB:
				return client.getVarpValue(VarPlayerID.TOB_MYCONTROLLER);
			default:
				return -1;
		}
	}

	private static int raidScopeType(BossDefence boss)
	{
		if (boss == null)
		{
			return -1;
		}
		if (boss.has(BossDefence.Flag.COX_SCALED))
		{
			return BpdDefenceSync.SCOPE_COX;
		}
		switch (boss)
		{
			case THE_MAIDEN_OF_SUGADINTI:
			case PESTILENT_BLOAT:
			case NYLOCAS_VASILIAS:
			case SOTETSEG:
			case XARPUS:
			case VERZIK_VITUR:
				return BpdDefenceSync.SCOPE_TOB;
			default:
				return -1;
		}
	}

	private static int instanceFingerprint(WorldView worldView)
	{
		int hash = 17;
		hash = 31 * hash + worldView.getBaseX();
		hash = 31 * hash + worldView.getBaseY();
		int[][][] chunks = worldView.getInstanceTemplateChunks();
		if (chunks != null)
		{
			for (int[][] plane : chunks)
			{
				if (plane == null) continue;
				for (int[] row : plane)
				{
					if (row == null) continue;
					for (int chunk : row)
					{
						hash = 31 * hash + chunk;
					}
				}
			}
		}
		return hash;
	}


	private void markRemotePartySpecTarget(int npcIndex)
	{
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || worldView.npcs() == null)
		{
			return;
		}

		NPC npc = worldView.npcs().byIndex(npcIndex);
		if (npc == null || npc.getName() == null)
		{
			return;
		}

		BossDefence boss = BossDefence.matchingNpcName(npc.getName());
		if (boss != null)
		{
			previouslySyncedBosses.add(boss);
		}
	}

	private NPC findBoundNpc()
	{
		DefenceTracker.DefenceState state = defenceTracker.state();
		if (state == null || state.getNpcIndex() < 0)
		{
			return null;
		}
		WorldView worldView = client.getTopLevelWorldView();
		return worldView == null || worldView.npcs() == null ? null : worldView.npcs().byIndex(state.getNpcIndex());
	}

	private NPC findLiveNpcForBoss(BossDefence boss)
	{
		if (boss == null)
		{
			return null;
		}
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || worldView.npcs() == null)
		{
			return null;
		}
		Iterator<? extends NPC> iterator = worldView.npcs().iterator();
		while (iterator != null && iterator.hasNext())
		{
			NPC npc = iterator.next();
			if (npc != null && !npc.isDead() && npc.getHealthRatio() != 0 && npc.getName() != null
				&& BossDefence.matchingNpcName(npc.getName()) == boss)
			{
				return npc;
			}
		}
		return null;
	}

	private static int healthPercent(NPC npc)
	{
		if (npc == null || npc.getHealthRatio() < 0 || npc.getHealthScale() <= 0)
		{
			return -1;
		}
		return Math.max(0, Math.min(100,
			(int) Math.round(npc.getHealthRatio() * 100.0 / npc.getHealthScale())));
	}

	private void syncOverlappingDefenceDisplays()
	{
		if (config.hideOverlappingDefenceDisplays())
		{
			hideExistingSpecialCounterInfoBoxes();
		}
		else
		{
			restoreOverlappingDefenceDisplays();
		}
	}

	/**
	 * Hide only the visual infoboxes owned by RuneLite's Special Attack Counter. The core plugin
	 * remains enabled, so its spec detection, party SpecialCounterUpdate messages and thresholds
	 * continue to work. BPD never disables the other plugin or rewrites its settings.
	 */
	private void hideExistingSpecialCounterInfoBoxes()
	{
		DefenceTracker.DefenceState state = defenceTracker.state();
		Integer currentNpcIndex = state == null ? null : state.getNpcIndex();

		if (!Objects.equals(hiddenSpecialCounterNpcIndex, currentNpcIndex)
			&& !hiddenSpecialCounterInfoBoxes.isEmpty())
		{
			// The tracked encounter changed while the boxes were hidden. Do not keep stale
			// Special Counter boxes around for a later restore.
			hiddenSpecialCounterInfoBoxes.clear();
		}
		hiddenSpecialCounterNpcIndex = currentNpcIndex;

		for (InfoBox infoBox : new ArrayList<>(infoBoxManager.getInfoBoxes()))
		{
			// InfoBox#getPlugin() is package-private in RuneLite, so Plugin Hub plugins
			// cannot use it to determine ownership. Special Attack Counter's infobox
			// implementations live in its own package, which is safe to inspect via
			// the public runtime class.
			if (isSpecialCounterInfoBox(infoBox)
				&& !hiddenSpecialCounterInfoBoxes.contains(infoBox))
			{
				hiddenSpecialCounterInfoBoxes.add(infoBox);
				infoBoxManager.removeInfoBox(infoBox);
			}
		}
	}

	private static boolean isSpecialCounterInfoBox(InfoBox infoBox)
	{
		return infoBox != null
			&& infoBox.getClass().getName().startsWith("net.runelite.client.plugins.specialcounter.");
	}

	private void restoreOverlappingDefenceDisplays()
	{
		DefenceTracker.DefenceState state = defenceTracker.state();
		Integer currentNpcIndex = state == null ? null : state.getNpcIndex();
		boolean sameLiveEncounter = state != null
			&& Objects.equals(hiddenSpecialCounterNpcIndex, currentNpcIndex)
			&& isSpecialCounterActive();

		if (sameLiveEncounter)
		{
			for (InfoBox infoBox : hiddenSpecialCounterInfoBoxes)
			{
				infoBoxManager.addInfoBox(infoBox);
			}
		}

		hiddenSpecialCounterInfoBoxes.clear();
		hiddenSpecialCounterNpcIndex = null;
	}

	/**
	 * The display-polish build briefly exposed health-bar pinning as a third display mode.
	 * Migrate that saved value to the attached-position setting so existing users keep the
	 * same placement while Display location now contains only Attached and Detached.
	 */
	private void migrateLegacyHealthBarDisplayMode()
	{
		String savedMode = configManager.getConfiguration(BetterPartyDefenceConfig.GROUP, "defenceDisplayMode");
		if ("NPC_HEALTH_BAR".equals(savedMode))
		{
			configManager.setConfiguration(BetterPartyDefenceConfig.GROUP, "defenceDisplayMode", DefenceDisplayMode.NPC);
			configManager.setConfiguration(BetterPartyDefenceConfig.GROUP, "defenceHpBarPosition",
				DefenceOverlayPosition.RIGHT_OF_HP_BAR);
		}
	}

	private boolean isSpecialCounterActive()
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (plugin instanceof SpecialCounterPlugin && pluginManager.isPluginActive(plugin))
			{
				return true;
			}
		}
		return false;
	}

	private void updateDefenceInfoBox()
	{
		DefenceTracker.DefenceState state = defenceTracker.state();
		boolean live = state != null && trackedNpcIsLive(state);
		boolean showDefence = config.defenceInfoBox() && live
			&& (defenceTracker.hasDefenceSpecHistory() || config.defenceAlwaysShow());
		boolean showMagic = config.magicDefence() && config.magicDefenceInfoBox() && live
			&& (defenceTracker.hasMagicDefenceSpecHistory() || config.defenceAlwaysShow());

		if (showDefence && defenceBox == null)
		{
			defenceBox = new DefenceInfoBox(
				config.defenceInfoBoxUseThemeSkillIcon() && skillIconSource != null
					? skillIconSource.defence(true)
					: skillIconManager.getSkillImage(Skill.DEFENCE),
				this, defenceTracker, config);
			infoBoxManager.addInfoBox(defenceBox);
		}
		else if (!showDefence && defenceBox != null)
		{
			infoBoxManager.removeInfoBox(defenceBox);
			defenceBox = null;
		}

		if (showMagic && magicDefenceBox == null)
		{
			magicDefenceBox = new DefenceInfoBox(
				config.defenceInfoBoxUseThemeSkillIcon() && skillIconSource != null
					? skillIconSource.magic(true)
					: skillIconManager.getSkillImage(Skill.MAGIC),
				this, defenceTracker, config, DefenceInfoBox.Stat.MAGIC_DEFENCE);
			infoBoxManager.addInfoBox(magicDefenceBox);
		}
		else if (!showMagic && magicDefenceBox != null)
		{
			infoBoxManager.removeInfoBox(magicDefenceBox);
			magicDefenceBox = null;
		}
	}

	/**
	 * Info boxes live outside scene rendering, so a stale tracker snapshot can otherwise
	 * remain visible after the boss actor has died or the raid room has unloaded. Keep
	 * the box tied to the same concrete NPC actor the attached display requires.
	 */
	private boolean trackedNpcIsLive(DefenceTracker.DefenceState state)
	{
		if (state.getNpcIndex() < 0)
		{
			// Remote party sync may seed/cache the drained state before this client has
			// the boss rendered. Keep that state for late binding, but do not surface an
			// infobox until the matching NPC actually exists in this client's scene.
			return false;
		}
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || worldView.npcs() == null)
		{
			return false;
		}

		NPC npc = worldView.npcs().byIndex(state.getNpcIndex());
		return npc != null && !npc.isDead() && npc.getHealthRatio() != 0;
	}

	private void removeInfoBox()
	{
		if (defenceBox != null)
		{
			infoBoxManager.removeInfoBox(defenceBox);
			defenceBox = null;
		}
		if (magicDefenceBox != null)
		{
			infoBoxManager.removeInfoBox(magicDefenceBox);
			magicDefenceBox = null;
		}
	}

	@Value
	private static class EncounterResetKey
	{
		int world;
		BossDefence boss;
		int scopeType;
		int scopeId;
	}

	@Value
	private static class PendingWorldSync
	{
		BpdDefenceSync event;
		DefenceTracker.SyncState sync;
	}

	@Value
	private static class SyncScope
	{
		int type;
		int id;
	}

	@Value
	private static class ActiveSyncScope
	{
		int type;
		int id;
		BossDefence boss;
	}

	@Value
	private static class SenderEncounterPresence
	{
		int world;
		int scopeType;
		int scopeId;
		long receivedAtMillis;
	}

	@Value
	private static class SyncedSpecKey
	{
		String playerName;
		SpecialWeapon weapon;
		int hit;
	}

	@Value
	private static class SpecEventKey
	{
		long memberId;
		int world;
		int npcIndex;
		int playerId;
		SpecialWeapon weapon;
		int hit;
	}
}
