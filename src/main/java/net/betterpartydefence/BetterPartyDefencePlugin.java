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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Iterator;
import java.io.File;
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
	private SkillIconSource skillIconSource;
	private TrackerFontManager trackerFontManager;
	private DefenceInfoBox defenceBox;
	private static final int DUPLICATE_WINDOW_TICKS = 1;
	private static final int SYNC_HISTORY_DUPLICATE_WINDOW_TICKS = 2;
	private static final int NON_INSTANCE_HP_TOLERANCE = 5;
	private static final int NON_INSTANCE_POSITION_TOLERANCE = 8;
	private static final long SYNC_MAX_AGE_MILLIS = 5_000L;
	private final Map<SpecEventKey, Integer> recentSpecEvents = new HashMap<>();
	private final Map<SyncedSpecKey, Integer> recentSyncedSpecs = new HashMap<>();
	private final List<InfoBox> hiddenSpecialCounterInfoBoxes = new ArrayList<>();
	private Integer hiddenSpecialCounterNpcIndex;
	private boolean wasInParty;
	private Integer lastSyncSignature;
	private ActiveSyncScope activeSyncScope;

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

		skillIconSource = new SkillIconSource(client, skillIconManager, config);
		trackerFontManager = new TrackerFontManager(config);
		defenceOverlay = new NpcDefenceOverlay(client, defenceTracker, config, skillIconSource, trackerFontManager);
		screenDefenceOverlay = new ScreenDefenceOverlay(defenceTracker, config, skillIconSource, trackerFontManager);
		overlayManager.add(defenceOverlay);
		overlayManager.add(screenDefenceOverlay);

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
		skillIconSource = null;
		trackerFontManager = null;
		removeInfoBox();
		recentSpecEvents.clear();
		recentSyncedSpecs.clear();
		activeSyncScope = null;
		lastSyncSignature = null;
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

		// Local defence tracking is intentionally independent of party membership. Hub Party is
		// the transport for remote specs, not a prerequisite for drawing our own tracked target.
		localSpecDetector.onGameTick();
		defenceTracker.onGameTick();
		reconcileActiveSyncScope();
		maybeBroadcastDefenceSync();
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
		if (!wasInParty && activeSyncScope != null && !defenceTracker.hasBoundNpc())
		{
			defenceTracker.reset("left party while using remote BPD sync");
		}
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
			lastSyncSignature = null;
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
		SpecialWeapon weapon = event.getWeapon();
		if (!DefenceTracker.isSupportedWeapon(weapon))
		{
			return;
		}

		String senderName = sender == null ? null : sender.getDisplayName();
		// Party websocket messages are posted from the websocket thread. Keep all game-client
		// state reads and tracker mutation on RuneLite's client thread, matching core plugin practice.
		clientThread.invoke(() ->
		{
			if (!partyService.isInParty())
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

		if ("syncWithOtherPartyDefenceUsers".equals(event.getKey()))
		{
			lastSyncSignature = null;
			recentSyncedSpecs.clear();
			if (!config.syncWithOtherPartyDefenceUsers())
			{
				if (activeSyncScope != null && !defenceTracker.hasBoundNpc())
				{
					defenceTracker.reset("experimental BPD sync disabled");
				}
				activeSyncScope = null;
			}
			return;
		}

		if (!"defenceFont".equals(event.getKey()) || config.defenceFont() != TrackerFont.ADD_CUSTOM)
		{
			return;
		}

		SwingUtilities.invokeLater(() ->
		{
			JFileChooser chooser = new JFileChooser();
			chooser.setDialogTitle("Choose a font for Better Party Defence");
			chooser.setFileFilter(new FileNameExtensionFilter("Font files (*.ttf, *.otf)", "ttf", "otf"));
			String current = config.customFontPath();
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
				configManager.setConfiguration(BetterPartyDefenceConfig.GROUP, "customFontPath",
					chooser.getSelectedFile().getAbsolutePath());
				configManager.setConfiguration(BetterPartyDefenceConfig.GROUP, "defenceFont", TrackerFont.CUSTOM);
			}
			else
			{
				TrackerFont fallback = config.customFontPath() == null || config.customFontPath().trim().isEmpty()
					? TrackerFont.RUNESCAPE : TrackerFont.CUSTOM;
				configManager.setConfiguration(BetterPartyDefenceConfig.GROUP, "defenceFont", fallback);
			}
		});
	}


	/** Receive the optional absolute BPD tracker snapshot from another member of this PartyService party. */
	@Subscribe
	public void onBpdDefenceSync(BpdDefenceSync event)
	{
		if (!config.syncWithOtherPartyDefenceUsers() || !partyService.isInParty()
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

		clientThread.invoke(() -> acceptBpdDefenceSync(event));
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

		// Never let the experimental path override a normal live local encounter. It is a fallback
		// for clients which cannot currently resolve the boss actor themselves.
		if (defenceTracker.hasBoundNpc())
		{
			return;
		}

		BossDefence boss = sync.getBossType();
		NPC localBoss = findLiveNpcForBoss(boss);
		if (!incomingScopeMatches(event, boss, localBoss))
		{
			return;
		}

		defenceTracker.applySyncState(sync, localBoss);
		activeSyncScope = new ActiveSyncScope(event.getScopeType(), event.getScopeId(), boss);
		lastSyncSignature = syncSignature(sync);
		rememberSyncedSpecs(sync);
		log.debug("Accepted BPD sync from member={} boss={} scope={}:{} actor={}",
			event.getMemberId(), boss, event.getScopeType(), event.getScopeId(),
			localBoss == null ? -1 : localBoss.getIndex());
	}

	/** Broadcast only after the normal/local tracker has changed and while this client can see the boss. */
	private void maybeBroadcastDefenceSync()
	{
		if (!config.syncWithOtherPartyDefenceUsers() || !partyService.isInParty())
		{
			lastSyncSignature = null;
			return;
		}

		DefenceTracker.SyncState sync = defenceTracker.syncState();
		if (sync == null || !sync.isDrained())
		{
			lastSyncSignature = null;
			return;
		}

		NPC npc = findBoundNpc();
		if (npc == null || npc.isDead() || npc.getHealthRatio() == 0)
		{
			return;
		}

		int signature = syncSignature(sync);
		if (lastSyncSignature != null && lastSyncSignature == signature)
		{
			return;
		}

		SyncScope scope = localSyncScope(sync.getBossType(), npc);
		if (scope == null)
		{
			return;
		}

		WorldPoint point = npc.getWorldLocation();
		int x = point == null ? 0 : point.getX();
		int y = point == null ? 0 : point.getY();
		int plane = point == null ? 0 : point.getPlane();
		int hp = healthPercent(npc);
		if (scope.getType() == BpdDefenceSync.SCOPE_WORLD && hp < 0)
		{
			return;
		}

		partyService.send(new BpdDefenceSync(
			client.getWorld(), sync, scope.getType(), scope.getId(), x, y, plane, hp));
		lastSyncSignature = signature;
		log.debug("Broadcast BPD sync boss={} scope={}:{} def={}/{}",
			sync.getBossType(), scope.getType(), scope.getId(), sync.getCurrent(), sync.getBase());
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

		int localHp = healthPercent(localBoss);
		if (localHp < 0 || event.getHealthPercent() < 0
			|| Math.abs(localHp - event.getHealthPercent()) > NON_INSTANCE_HP_TOLERANCE)
		{
			return false;
		}
		WorldPoint point = localBoss.getWorldLocation();
		if (point == null || point.getPlane() != event.getBossPlane())
		{
			return false;
		}
		return Math.abs(point.getX() - event.getBossX()) <= NON_INSTANCE_POSITION_TOLERANCE
			&& Math.abs(point.getY() - event.getBossY()) <= NON_INSTANCE_POSITION_TOLERANCE;
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
		boolean show = config.defenceInfoBox() && state != null && trackedNpcIsLive(state);
		if (show && defenceBox == null)
		{
			defenceBox = new DefenceInfoBox(
				skillIconManager.getSkillImage(Skill.DEFENCE), this, defenceTracker, config);
			infoBoxManager.addInfoBox(defenceBox);
		}
		else if (!show)
		{
			removeInfoBox();
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
			return activeSyncScope != null && syncScopeStillValid(activeSyncScope);
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
