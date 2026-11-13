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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.ScriptEvent;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.Varbits;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.specialcounter.SpecialCounterPlugin;
import net.runelite.client.plugins.specialcounter.SpecialCounterUpdate;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import net.runelite.client.ui.overlay.OverlayManager;
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
	private ItemManager itemManager;

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
	private SkillIconSource skillIconSource;
	private TrackerFontManager trackerFontManager;
	private DefenceInfoBox defenceBox;
	private DefenceInfoBox magicDefenceBox;
	private BossEffectDisplay bossEffectDisplay;
	/** BPD-owned weapon icons. Only an infobox projection of the current target's accepted history. */
	private final Map<SpecialWeapon, SpecWeaponInfoBox> specWeaponBoxes = new LinkedHashMap<>();
	private static final int DUPLICATE_WINDOW_TICKS = 1;
	private static final int SYNC_HISTORY_DUPLICATE_WINDOW_TICKS = 2;
	private static final int NON_INSTANCE_HP_TOLERANCE = 20;
	private static final int NON_INSTANCE_POSITION_TOLERANCE = 16;
	private static final long SYNC_MAX_AGE_MILLIS = 5_000L;
	/** Maximum time to withhold a new party-eligible boss display while requesting better peer state. */
	private static final int INITIAL_SYNC_DISPLAY_GRACE_TICKS = 4;
	/** Expired presence falls back to the standard same-world and target-association rules. */
	private static final long ENCOUNTER_PRESENCE_MAX_AGE_MILLIS = 6_000L;
	private static final int TOA_PARTY_MEMBER_DEAD = 30;
	private static final int[] TOA_PARTY_HEALTH_VARBITS =
	{
		VarbitID.TOA_CLIENT_P0, VarbitID.TOA_CLIENT_P1, VarbitID.TOA_CLIENT_P2, VarbitID.TOA_CLIENT_P3,
		VarbitID.TOA_CLIENT_P4, VarbitID.TOA_CLIENT_P5, VarbitID.TOA_CLIENT_P6, VarbitID.TOA_CLIENT_P7
	};
	private final Map<SpecEventKey, Integer> recentSpecEvents = new HashMap<>();
	private final SnapshotEchoGuard<SyncedSpecKey> recentSyncedSpecs =
		new SnapshotEchoGuard<>(SYNC_HISTORY_DUPLICATE_WINDOW_TICKS);
	private final java.util.ArrayDeque<BpdDefenceSync> pendingSnapshots = new java.util.ArrayDeque<>();
	private volatile long partyEpoch;
	/** Last party id reported by PartyChanged; used while PartyService briefly reports zero. */
	private Long currentPartyId;
	private boolean wasInParty;
	/** Local tick observed on the actual raid-begun notification, never instance entry. */
	private boolean sharedRaidClockActive;
	private int sharedRaidStartTick;
	/** Raid elapsed-tick clock fed by the game's own ToA/TFA timer scripts and CoX timer varbit. */
	private final java.util.Deque<BpdKerisTimer> pendingKerisTimers = new java.util.ArrayDeque<>();
	private final RaidTimerClock raidTimerClock = new RaidTimerClock();
	/** CoX's timer is locally interpolated; stop trusting it if that display stops advancing. */
	private static final int COX_TIMER_STALE_TICKS = 4;
	private int lastCoxTimerValue = Integer.MIN_VALUE;
	private int lastCoxTimerAdvanceTick = -1;
	private boolean coxTimerStale;
	private final java.util.ArrayDeque<BpdSpecEvent> pendingPartySpecs = new java.util.ArrayDeque<>();
	private final Set<BossDefence> bossesNeedingCatchUp = new HashSet<>();
	private final Set<BossDefence> visibleTrackedBosses = new HashSet<>();
	/** Logical state version per boss. Prevents stale snapshots from winning when many peers broadcast. */
	private final Map<BossDefence, VersionedSyncState> syncStateVersions = new HashMap<>();
	private final Map<BossDefence, PendingWorldSync> pendingWorldSyncs = new HashMap<>();
	/** Raid scopes retained for the lifetime of their concrete raid encounter. */
	private final Map<BossDefence, SyncScope> retainedRaidScopes = new HashMap<>();
	private final Map<Long, SenderEncounterPresence> senderEncounterPresence = new HashMap<>();
	/** Latest authoritative reset for each boss and scope in the current world session. */
	private final Map<EncounterResetKey, Long> encounterResetTimes = new HashMap<>();
	/** Party members proven to be running BPD during this PartyService session. */
	private final Set<Long> knownBpdPartyMembers = new HashSet<>();
	/** Debounces the all-party-dead ToA state so one wipe clears once, not every tick. */
	private boolean toaPartyWasFullyDead;
	/** Once The Blade/P3 is seen, never accept stale Phase 1 Defence during this raid. */
	private boolean ocellusPhaseOneEnded;
	private ActiveSyncScope activeSyncScope;
	private SyncScope lastPresenceScope;
	/** Revalidate visible bosses once RuneLite confirms the local member joined the party. */
	private boolean revalidateBossesAfterPartyJoin;
	private final Set<BossDefence> pendingJoinHistoryOffers = new HashSet<>();
	/** Presentation-only gate: never changes tracker math/state, only suppresses an unverified first frame. */
	private final InitialSyncDisplayGate initialSyncDisplayGate = new InitialSyncDisplayGate();

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
		bossEffectDisplay = new BossEffectDisplay(client, clientThread, defenceTracker);

		// Register the core SpecialCounterUpdate message ourselves so remote party drains can
		// still be decoded even when this client's Special Attack Counter UI is disabled.
		// WSClient de-duplicates registrations by message class.
		wsClient.registerMessage(SpecialCounterUpdate.class);
		// Register the BPD messages used for live spec sync and encounter recovery.
		wsClient.registerMessage(BpdDefenceSync.class);
		wsClient.registerMessage(BpdSpecEvent.class);
		wsClient.registerMessage(BpdEncounterPresence.class);
		wsClient.registerMessage(BpdBossPresence.class);
		wsClient.registerMessage(BpdEncounterReset.class);
		wsClient.registerMessage(BpdKerisTimer.class);

		skillIconSource = new SkillIconSource(client, skillIconManager, config);
		trackerFontManager = new TrackerFontManager(config);
		defenceOverlay = new NpcDefenceOverlay(client, defenceTracker, config, skillIconSource,
			trackerFontManager, initialSyncDisplayGate);
		overlayManager.add(defenceOverlay);

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
		if (bossEffectDisplay != null)
		{
			bossEffectDisplay.reset(infoBoxManager);
			bossEffectDisplay = null;
		}
		partyEpoch++;
		sharedRaidClockActive = false;
		raidTimerClock.clear();
		resetCoxTimerTracking();
		defenceTracker.setSharedTickAnchor(null, 0);
		if (defenceOverlay != null)
		{
			overlayManager.remove(defenceOverlay);
			defenceOverlay = null;
		}
		skillIconSource = null;
		trackerFontManager = null;
		removeInfoBox();
		recentSpecEvents.clear();
		if (defenceTracker != null) { defenceTracker.clearStandardSpecCorrelation(); }
		pendingSnapshots.clear();
		pendingPartySpecs.clear();
		bossesNeedingCatchUp.clear();
		pendingJoinHistoryOffers.clear();
		visibleTrackedBosses.clear();
		pendingKerisTimers.clear();
		recentSyncedSpecs.clear();
		pendingWorldSyncs.clear();
		retainedRaidScopes.clear();
		senderEncounterPresence.clear();
		initialSyncDisplayGate.clear();
		revalidateBossesAfterPartyJoin = false;
		encounterResetTimes.clear();
		knownBpdPartyMembers.clear();
		toaPartyWasFullyDead = false;
		ocellusPhaseOneEnded = false;
		activeSyncScope = null;
		lastPresenceScope = null;
		syncStateVersions.clear();
		wsClient.unregisterMessage(BpdEncounterReset.class);
		wsClient.unregisterMessage(BpdKerisTimer.class);
		wsClient.unregisterMessage(BpdEncounterPresence.class);
		wsClient.unregisterMessage(BpdDefenceSync.class);
		wsClient.unregisterMessage(BpdSpecEvent.class);
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
		updateRaidTimerClock(tick);
		applySharedRaidTickAnchor(tick);
		recentSpecEvents.entrySet().removeIf(entry -> tick - entry.getValue() > DUPLICATE_WINDOW_TICKS);
		recentSyncedSpecs.prune(tick);
		long now = System.currentTimeMillis();
		senderEncounterPresence.entrySet().removeIf(
			entry -> now - entry.getValue().getReceivedAtMillis() > ENCOUNTER_PRESENCE_MAX_AGE_MILLIS);

		// Local defence tracking is intentionally independent of party membership. Hub Party is
		// the transport for remote specs, not a prerequisite for drawing our own tracked target.
		localSpecDetector.onGameTick();
		defenceTracker.onGameTick();
		reconcileTfaRaidExit();
		reconcileTfaPhaseChange();
		initialSyncDisplayGate.expire(tick);
		releaseInitialSyncGatesForDrainedStates();
		reconcileAuthoritativeNpcDeaths();
		reconcileToaFullWipe();
		reconcilePartySpecEvents();
		reconcileQueuedSnapshots();
		reconcilePendingWorldSyncs();
		reconcileActiveSyncScope();
		maybeBroadcastEncounterPresence();
		broadcastLocalSpecEvents();
		reconcileVisibleBosses();
		reconcileRaidEncounterLifecycle();
		if (bossEffectDisplay != null)
		{
			bossEffectDisplay.settleKeris(this);
			reconcileKerisTimers();
		}
		updateDefenceInfoBox();

	}

	/** CoX's actual start notification is shared; walking into its lobby is not. */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event == null || event.getType() != ChatMessageType.FRIENDSCHATNOTIFICATION
			|| client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) != 1) { return; }
		String message = Text.removeTags(event.getMessage());
		if (message == null) { return; }
		if (message.startsWith("The raid has begun!") && !sharedRaidClockActive)
		{
			sharedRaidStartTick = client.getTickCount();
			sharedRaidClockActive = true;
			updateSharedRaidTickAnchor(sharedRaidStartTick);
		}
		else if (message.startsWith("Congratulations - your raid is complete!"))
		{
			sharedRaidClockActive = false;
			if (raidTimerClock.isActiveFor(RaidTimerClock.SOURCE_COX))
			{
				raidTimerClock.clear();
			}
			defenceTracker.setSharedTickAnchor(null, 0);
		}
	}

	/**
	 * ToA and TFA publish their raid timer through a root clientscript:
	 * {@code (elapsedTicks, stopped)}. Capture the raw elapsed ticks only; the animated display
	 * callback converts them with clientclock and must never be treated as a fresh sample.
	 */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		// Scripts fire constantly; only the two verified raid-timer roots are interesting.
		if (event == null || RaidTimerClock.sourceForScript(event.getScriptId()) == -1)
		{
			return;
		}
		ScriptEvent scriptEvent = event.getScriptEvent();
		if (scriptEvent == null)
		{
			return;
		}
		RaidTimerClock.TimerInput input =
			RaidTimerClock.parseTimerScript(event.getScriptId(), scriptEvent.getArguments());
		if (input == null)
		{
			return;
		}

		int tick = client.getTickCount();
		if (input.elapsedTicks < 0)
		{
			if (raidTimerClock.isActiveFor(input.source))
			{
				raidTimerClock.clear();
			}
			applySharedRaidTickAnchor(tick);
			return;
		}

		WorldView worldView = client.getTopLevelWorldView();
		// A raid timer only exists inside the raid instance. This also keeps a lobby/other
		// instance from latching a clock domain that party members inside the raid cannot share.
		if (worldView == null || !worldView.isInstance())
		{
			return;
		}
		String scope = raidTimerScope(input.source, worldView);
		if (scope == null)
		{
			return;
		}
		raidTimerClock.sample(input.source, scope, input.elapsedTicks, input.stopped, tick);
		applySharedRaidTickAnchor(tick);
	}

	/**
	 * Keep the timer clock honest about the raid it belongs to. CoX's generator room
	 * ({@code RAIDS_CLIENT_PROGRESS == 0}) is still the lobby, so a zero there is not a start.
	 */
	private void updateRaidTimerClock(int tick)
	{
		int coxInRaid = client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON);
		int coxProgress = client.getVarbitValue(VarbitID.RAIDS_CLIENT_PROGRESS);
		if (coxInRaid == 1 && coxProgress >= 1 && coxProgress < 5)
		{
			int elapsed = client.getVarbitValue(VarbitID.RAIDS_TIMER);
			if (elapsed != lastCoxTimerValue)
			{
				lastCoxTimerValue = elapsed;
				lastCoxTimerAdvanceTick = tick;
				coxTimerStale = false;
			}
			WorldView worldView = client.getTopLevelWorldView();
			String scope = coxTimerScope(worldView);
			// The varbit is driven by an interface animation callback. If that display stops,
			// the value freezes; do not keep extrapolating a frozen clock onto live specs.
			if (raidTimerClock.isActiveFor(RaidTimerClock.SOURCE_COX)
				&& lastCoxTimerAdvanceTick >= 0
				&& tick - lastCoxTimerAdvanceTick > COX_TIMER_STALE_TICKS)
			{
				raidTimerClock.clear();
				coxTimerStale = true;
			}
			// Accept the genuine zero at the start only when this client also saw the raid
			// begin. A late joiner waits for the first non-zero elapsed value instead of
			// anchoring a stale zero to its own login counter.
			else if (!coxTimerStale && scope != null && elapsed >= 0
				&& (elapsed > 0 || sharedRaidClockActive))
			{
				raidTimerClock.sample(RaidTimerClock.SOURCE_COX, scope, elapsed, false, tick);
			}
		}
		else if (coxInRaid == 1 && coxProgress >= 5)
		{
			if (raidTimerClock.isActiveFor(RaidTimerClock.SOURCE_COX))
			{
				raidTimerClock.markStopped();
			}
		}
		else
		{
			if (raidTimerClock.isActiveFor(RaidTimerClock.SOURCE_COX))
			{
				raidTimerClock.clear();
			}
			resetCoxTimerTracking();
		}

		WorldView worldView = client.getTopLevelWorldView();
		boolean instance = worldView != null && worldView.isInstance();
		if (raidTimerClock.isActiveFor(RaidTimerClock.SOURCE_TOA) && !instance)
		{
			raidTimerClock.clear();
		}
		if (raidTimerClock.isActiveFor(RaidTimerClock.SOURCE_TFA) && !instance)
		{
			raidTimerClock.clear();
		}
		// ToB intentionally has no branch here: its HUD publishes room progress, not a
		// verified elapsed clock, so ToB keeps the conservative local ordering.
	}

	private void resetCoxTimerTracking()
	{
		lastCoxTimerValue = Integer.MIN_VALUE;
		lastCoxTimerAdvanceTick = -1;
		coxTimerStale = false;
	}

	/** A shared scope key every client in this raid can derive independently. */
	private String raidTimerScope(int source, WorldView worldView)
	{
		if (source == RaidTimerClock.SOURCE_TFA)
		{
			// TFA moves between five instanced rooms, so its scene fingerprint is not a raid id.
			// The party controller is the only stable shared identifier; without it, leave the
			// clock unavailable rather than latch a per-client first-seen scene.
			int controller = client.getVarpValue(VarPlayerID.TFA_MYCONTROLLER);
			return controller > 0 ? "c" + controller : null;
		}
		if (source == RaidTimerClock.SOURCE_TOA)
		{
			// Prefer the party controller: the Tombs scene can change between rooms, and a
			// per-room fingerprint is not a stable shared raid identity for late joiners.
			int controller = client.getVarpValue(VarPlayerID.TOA_MYCONTROLLER);
			if (controller > 0)
			{
				return "c" + controller;
			}
			return worldView != null && worldView.isInstance()
				? "i" + instanceFingerprint(worldView) : null;
		}
		return null;
	}

	private String coxTimerScope(WorldView worldView)
	{
		if (worldView != null && worldView.isInstance())
		{
			return "i" + instanceFingerprint(worldView);
		}
		int controller = raidController(BpdDefenceSync.SCOPE_COX);
		return controller > 0 ? "c" + controller : null;
	}

	/**
	 * Prefer the game's elapsed-tick clock. The CoX raid-begun domain stays as a distinct
	 * fallback for clients where the timer value is unavailable; the two are never mixed.
	 */
	private void applySharedRaidTickAnchor(int tick)
	{
		if (raidTimerClock.isActive())
		{
			if (raidTimerClock.isStopped())
			{
				defenceTracker.setSharedTickAnchor(null, 0);
			}
			else
			{
				defenceTracker.setSharedTickAnchor(raidTimerClock.domain(),
					raidTimerClock.anchorLocalTick(), raidTimerClock.anchorRaidTick());
			}
			return;
		}
		updateSharedRaidTickAnchor(tick);
	}

	/** Refresh scope/exit state without manufacturing a start from presence or party status. */
	private void updateSharedRaidTickAnchor(int tick)
	{
		if (client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) != 1)
		{
			sharedRaidClockActive = false;
		}
		if (!sharedRaidClockActive)
		{
			defenceTracker.setSharedTickAnchor(null, 0);
			return;
		}
		SyncScope scope = currentEncounterScope();
		if (scope == null)
		{
			defenceTracker.setSharedTickAnchor(null, 0);
			return;
		}
		WorldView worldView = client.getTopLevelWorldView();
		// A distinct domain prevents older entry-based peers from claiming the same origin.
		String anchor = worldView != null && worldView.isInstance()
			? "raid-begun:v2:cox:" + BpdDefenceSync.SCOPE_INSTANCE + ":" + instanceFingerprint(worldView)
			: "raid-begun:v2:cox:" + scope.getType() + ":" + scope.getId();
		defenceTracker.setSharedTickAnchor(anchor, sharedRaidStartTick);
	}


	/**
	 * A newly rendered supported boss is an immediate continuity signal. If this client does not
	 * already have drained state for it, ask BPD peers in the same encounter for their remembered
	 * absolute state. No interaction/click is required.
	 */
	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		if (event != null)
		{
			checkTfaNewAttempt(event.getNpc());
			visibleTrackedBosses.remove(BossDefence.matchingNpc(event.getNpc()));
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
		if (bossEffectDisplay != null && event.getVarpId() == VarPlayerID.SA_ENERGY)
		{
			bossEffectDisplay.onSpecialEnergy(event.getValue());
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		localSpecDetector.onHitsplatApplied(event);
		if (bossEffectDisplay != null)
		{
			bossEffectDisplay.onHitsplatApplied(event);
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (bossEffectDisplay != null)
		{
			bossEffectDisplay.onLocalAnimation(event.getActor());
		}
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		if (bossEffectDisplay != null)
		{
			bossEffectDisplay.onProjectileMoved(event.getProjectile());
		}
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		if (bossEffectDisplay != null)
		{
			bossEffectDisplay.onGraphicChanged(event.getActor());
		}
	}

	@Subscribe
	public void onPartyChanged(PartyChanged event)
	{
		partyEpoch++;
		Long changedPartyId = event.getPartyId();
		boolean partySessionChanged = !Objects.equals(currentPartyId, changedPartyId);
		currentPartyId = changedPartyId;
		if (partySessionChanged)
		{
			// Logical version numbers and open-world "already synced" memory belong to one
			// party transport session. A new party must not inherit either one.
			syncStateVersions.clear();
		}
		recentSpecEvents.clear();
		if (defenceTracker != null) { defenceTracker.clearStandardSpecCorrelation(); }
		pendingSnapshots.clear();
		pendingPartySpecs.clear();
		bossesNeedingCatchUp.clear();
		pendingJoinHistoryOffers.clear();
		visibleTrackedBosses.clear();
		pendingKerisTimers.clear();
		recentSyncedSpecs.clear();
		wasInParty = changedPartyId != null;

		pendingWorldSyncs.clear();
		// Party membership is transport only. Do not clear remembered raid scopes or tracker state
		// here: leaving a Hub Party while still inside the same raid must preserve the encounter.
		senderEncounterPresence.clear();
		knownBpdPartyMembers.clear();
		initialSyncDisplayGate.clear();
		revalidateBossesAfterPartyJoin = false;
		toaPartyWasFullyDead = false;
		lastPresenceScope = null;
		activeSyncScope = null;
		log.debug("Hub Party session changed: partyId={}", changedPartyId);
	}

	@Subscribe
	public void onUserJoin(UserJoin event)
	{
		PartyMember localMember = partyService.getLocalMember();
		if (localMember == null || event.getMemberId() != localMember.getMemberId()
			|| event.getPartyId() != partyService.getPartyId())
		{
			return;
		}

		long receivedInPartyEpoch = partyEpoch;
		clientThread.invoke(() ->
		{
			if (receivedInPartyEpoch == partyEpoch)
			{
				revalidateBossesAfterPartyJoin = true;
			}
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST)
		{
			partyEpoch++;
			sharedRaidClockActive = false;
			raidTimerClock.clear();
			resetCoxTimerTracking();
			defenceTracker.setSharedTickAnchor(null, 0);
			localSpecDetector.reset();
			defenceTracker.reset("game state " + state);
			if (bossEffectDisplay != null)
			{
				bossEffectDisplay.reset(infoBoxManager);
			}
			removeInfoBox();
			recentSpecEvents.clear();
			pendingSnapshots.clear();
			pendingPartySpecs.clear();
			bossesNeedingCatchUp.clear();
			pendingJoinHistoryOffers.clear();
			visibleTrackedBosses.clear();
			pendingKerisTimers.clear();
			recentSyncedSpecs.clear();
			activeSyncScope = null;
			pendingWorldSyncs.clear();
			retainedRaidScopes.clear();
			senderEncounterPresence.clear();
			initialSyncDisplayGate.clear();
			revalidateBossesAfterPartyJoin = false;
			encounterResetTimes.clear();
			knownBpdPartyMembers.clear();
			toaPartyWasFullyDead = false;
			ocellusPhaseOneEnded = false;
			lastPresenceScope = null;
			syncStateVersions.clear();
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
		}
		else
		{
			// Its shutdown unregisters SpecialCounterUpdate. Re-register so BPD can continue
			// decoding messages sent by other party members.
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
		final long receivedInPartyEpoch = partyEpoch;
		clientThread.invoke(() ->
		{
			if (receivedInPartyEpoch != partyEpoch || !partyService.isInParty()
				|| partyService.getMemberById(event.getMemberId()) == null)
			{
				return;
			}

			if (!standardPartySpecScopeMatches(
				event.getMemberId(), event.getWorld(), event.getNpcIndex()))
			{
				return;
			}

			SpecEventKey key = new SpecEventKey(
				event.getMemberId(), event.getWorld(), event.getNpcIndex(), event.getPlayerId(), weapon, event.getHit());
			int tick = client.getTickCount();
			Integer lastSeen = recentSpecEvents.get(key);
			if (lastSeen != null && tick - lastSeen <= DUPLICATE_WINDOW_TICKS)
			{
				log.debug("Ignoring duplicate party spec event {}", key);
				return;
			}
			SyncedSpecKey syncedKey = new SyncedSpecKey(event.getWorld(), event.getNpcIndex(),
				normalizePlayerName(senderName), weapon, DefenceTracker.specCorrelationHit(weapon, event.getHit()));
			if (recentSyncedSpecs.consume(syncedKey, tick))
			{
				recentSpecEvents.put(key, tick);
				log.debug("Ignoring standard party spec already covered by BPD sync {}", syncedKey);
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

		if ("defenceInfoBoxUseThemeSkillIcon".equals(event.getKey()))
		{
			if (defenceBox != null || magicDefenceBox != null)
			{
				removeInfoBox();
				updateDefenceInfoBox();
			}
			return;
		}

		if ("defenceFont".equals(event.getKey()) && config.defenceFont() == TrackerFont.ADD_CUSTOM)
		{
			chooseCustomFont();
		}
	}


	private void chooseCustomFont()
	{
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
				TrackerFont fallback = current == null || current.trim().isEmpty()
					? TrackerFont.RUNESCAPE : TrackerFont.CUSTOM;
				configManager.setConfiguration(BetterPartyDefenceConfig.GROUP, "defenceFont", fallback);
			}
		});
	}


	@Subscribe
	public void onBpdEncounterPresence(BpdEncounterPresence event)
	{
		if (!partyService.isInParty()
			|| event == null || event.getProtocolVersion() != BpdEncounterPresence.PROTOCOL_VERSION
			|| !partyMessageBelongsToCurrentParty(event.getPartyId()))
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

		final long receivedInPartyEpoch = partyEpoch;
		clientThread.invoke(() ->
		{
			if (receivedInPartyEpoch != partyEpoch)
			{
				return;
			}
			rememberSenderEncounterPresence(event.getMemberId(), event.getWorld(),
				event.getScopeType(), event.getScopeId(), event.getSentAtMillis());
		});
	}


	/**
	 * Another BPD client has actually rendered a supported boss and is asking whether the party
	 * already knows a still-valid drained state for that encounter. A peer that recently held the
	 * same open-world encounter, or is still in the same raid scope, can answer immediately even
	 * when it no longer renders the boss itself.
	 */
	@Subscribe
	public void onBpdBossPresence(BpdBossPresence event)
	{
		if (!partyService.isInParty()
			|| event == null || event.getProtocolVersion() != BpdBossPresence.PROTOCOL_VERSION
			|| !partyMessageBelongsToCurrentParty(event.getPartyId()))
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

		final long receivedInPartyEpoch = partyEpoch;
		clientThread.invoke(() ->
		{
			if (receivedInPartyEpoch != partyEpoch)
			{
				return;
			}
			knownBpdPartyMembers.add(event.getMemberId());
			if (event.getWorld() != client.getWorld())
			{
				return;
			}

			long age = Math.abs(System.currentTimeMillis() - event.getSentAtMillis());
			if (event.getSentAtMillis() <= 0 || age > SYNC_MAX_AGE_MILLIS)
			{
				return;
			}

			BossDefence boss = event.toBossType();
			if (boss == null)
			{
				return;
			}
			DefenceTracker.SyncState sync;
			if (event.getScopeType() == BpdDefenceSync.SCOPE_WORLD && !isRaidEncounterBoss(boss))
			{
				if (event.getScopeId() != 0)
				{
					return;
				}
				WorldView view = client.getTopLevelWorldView();
				if (view != null && !view.isInstance())
				{
					sync = defenceTracker.syncStateForBoss(boss);
				}
				else
				{
					if (!isPublicFallbackResponder(event.getMemberId()))
					{
						return;
					}
					WorldPoint requestedTile = new WorldPoint(event.getBossX(), event.getBossY(), event.getBossPlane());
					sync = defenceTracker.publicSyncStateForBoss(boss, event.getWorld(), requestedTile,
						NON_INSTANCE_POSITION_TOLERANCE);
				}
			}
			else
			{
				if (!bossPresenceScopeMatches(event, boss))
				{
					return;
				}
				sync = defenceTracker.syncStateForBoss(boss);
			}

			rememberSenderEncounterPresence(event.getMemberId(), event.getWorld(),
				event.getScopeType(), event.getScopeId(), event.getSentAtMillis());

			BpdDefenceSync offered = event.getOfferedHistory();
			DefenceTracker.SyncState offeredState = null;
			if (offered != null && offered.getWorld() == event.getWorld()
				&& boss.name().equals(offered.getBossType())
				&& offered.getScopeType() == event.getScopeType() && offered.getScopeId() == event.getScopeId()
				&& Objects.equals(offered.getBossLocation(),
					new WorldPoint(event.getBossX(), event.getBossY(), event.getBossPlane())))
			{
				offeredState = offered.toSyncState();
				if (offeredState != null && offeredState.isDrained())
				{
					offered.setMemberId(event.getMemberId());
					onBpdDefenceSync(offered);
				}
			}

			if (sync == null || !sync.isDrained() || historyCovers(offeredState, sync))
			{
				return;
			}

			// The requesting peer is currently rendering the boss, so its actor coordinates are
			// the freshest proof of where this encounter is. We already authenticated world/scope
			// above; echo those coordinates so the requester can bind immediately without clicking.
			long stateVersion = observeLocalStateVersion(sync);
			partyService.send(new BpdDefenceSync(
				client.getWorld(), sync, event.getScopeType(), event.getScopeId(),
				event.getBossX(), event.getBossY(), event.getBossPlane(), event.getHealthPercent(),
				stateVersion));
			log.debug("Answered BPD boss-presence request member={} boss={} scope={}:{} def={}/{}",
				event.getMemberId(), boss, event.getScopeType(), event.getScopeId(),
				sync.getCurrent(), sync.getBase());
		});
	}

	private static boolean historyCovers(DefenceTracker.SyncState offered, DefenceTracker.SyncState local)
	{
		if (offered == null || !offered.isDrained())
		{
			return false;
		}
		List<DefenceTracker.SpecHistoryEntry> union = EncounterTimeline.union(offered.getHistory(), local.getHistory());
		if (union != null)
		{
			return union.size() == offered.getHistory().size();
		}
		SyncHistoryRelation.Result relation = SyncHistoryRelation.compare(local, offered);
		return relation == SyncHistoryRelation.Result.SAME || relation == SyncHistoryRelation.Result.EXTENSION;
	}

	/** Allow at most one additional off-scope responder per request. */
	private boolean isPublicFallbackResponder(long requester)
	{
		PartyMember local = partyService.getLocalMember();
		if (local == null)
		{
			return false;
		}
		for (PartyMember member : partyService.getMembers())
		{
			if (member.getMemberId() != requester && member.getMemberId() < local.getMemberId())
			{
				return false;
			}
		}
		return true;
	}

	void broadcastKeris(NPC npc, int hitTick, long hitAtMillis)
	{
		if (!partyService.isInParty())
		{
			return;
		}

		BossDefence boss = BossDefence.matchingNpc(npc);
		SyncScope scope = boss == null ? null : localSyncScope(boss, npc);
		WorldPoint point = npc.getWorldLocation();
		if (scope == null || point == null || !peerSyncAllowedAtCurrentEncounter(boss))
		{
			return;
		}

		long raidTick = raidTimerClock.isActiveFor(RaidTimerClock.SOURCE_TOA)
			? raidTimerClock.tickAt(hitTick) : RaidTimerClock.NO_TICK;
		String clockScope = raidTick >= 0 ? raidTimerScope(RaidTimerClock.SOURCE_TOA,
			client.getTopLevelWorldView()) : null;
		rememberRaidScope(boss, scope);
		partyService.send(new BpdKerisTimer(partyService.getPartyId(), client.getWorld(), boss,
			scope.getType(), scope.getId(), npc.getId(), point, hitAtMillis, clockScope, raidTick));
	}

	@Subscribe
	public void onBpdKerisTimer(BpdKerisTimer event)
	{
		if (event == null || !partyService.isInParty()
			|| event.getProtocolVersion() != BpdKerisTimer.PROTOCOL_VERSION
			|| !partyMessageBelongsToCurrentParty(event.getPartyId()))
		{
			return;
		}
		PartyMember localMember = partyService.getLocalMember();
		if (localMember != null && localMember.getMemberId() == event.getMemberId())
		{
			return;
		}

		long receivedInPartyEpoch = partyEpoch;
		clientThread.invoke(() ->
		{
			if (receivedInPartyEpoch != partyEpoch || !acceptsKerisTimer(event))
			{
				return;
			}
			if (pendingKerisTimers.size() >= 32)
			{
				pendingKerisTimers.removeFirst();
			}
			pendingKerisTimers.addLast(event);
		});
	}

	private boolean acceptsKerisTimer(BpdKerisTimer event)
	{
		return partyService.isInParty()
			&& partyMessageBelongsToCurrentParty(event.getPartyId())
			&& partyService.getMemberById(event.getMemberId()) != null
			&& event.getWorld() == client.getWorld() && event.getBoss() != null;
	}

	private void reconcileKerisTimers()
	{
		if (pendingKerisTimers.isEmpty())
		{
			return;
		}

		int tick = client.getTickCount();
		long now = System.currentTimeMillis();
		WorldView worldView = client.getTopLevelWorldView();
		long raidTick = raidTimerClock.isActiveFor(RaidTimerClock.SOURCE_TOA)
			? raidTimerClock.tickAt(tick) : RaidTimerClock.NO_TICK;
		String clockScope = raidTick >= 0 ? raidTimerScope(RaidTimerClock.SOURCE_TOA, worldView) : null;
		for (Iterator<BpdKerisTimer> it = pendingKerisTimers.iterator(); it.hasNext();)
		{
			BpdKerisTimer timer = it.next();
			BossDefence boss = timer.getBoss();
			if (!acceptsKerisTimer(timer))
			{
				it.remove();
				continue;
			}
			NPC npc = NpcTargetResolver.uniqueLiveNpcNear(worldView, boss,
				timer.getBossX(), timer.getBossY(), timer.getBossPlane(), NON_INSTANCE_POSITION_TOLERANCE);
			SyncScope scope = localSyncScope(boss, npc);
			int remaining = timer.remainingTicks(now, clockScope, raidTick);
			Long resetAt = encounterResetTimes.get(new EncounterResetKey(client.getWorld(), boss,
				timer.getScopeType(), timer.getScopeId()));
			if (remaining <= 0 || scope == null || scope.getType() != timer.getScopeType()
				|| scope.getId() != timer.getScopeId() || !peerSyncAllowedAtCurrentEncounter(boss)
				|| resetAt != null && timer.getHitAtMillis() <= resetAt)
			{
				it.remove();
				continue;
			}
			if (npc != null)
			{
				it.remove();
				if (npc.getId() == timer.getNpcId())
				{
					rememberRaidScope(boss, scope);
					bossEffectDisplay.applyKerisTimer(npc, tick + remaining, timer.getHitAtMillis());
				}
			}
		}
	}

	/** Receive an authoritative encounter reset from another current BPD party member. */
	@Subscribe
	public void onBpdEncounterReset(BpdEncounterReset event)
	{
		if (!partyService.isInParty()
			|| event == null || event.getProtocolVersion() != BpdEncounterReset.PROTOCOL_VERSION
			|| !partyMessageBelongsToCurrentParty(event.getPartyId()))
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

		final long receivedInPartyEpoch = partyEpoch;
		clientThread.invoke(() ->
		{
			if (receivedInPartyEpoch != partyEpoch)
			{
				return;
			}
			knownBpdPartyMembers.add(event.getMemberId());
			if (!partyService.isInParty()
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
				"party encounter reset from member " + event.getMemberId(), false, event.getSentAtMillis());
		});
	}

	private void maybeBroadcastEncounterPresence()
	{
		if (!partyService.isInParty()
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			lastPresenceScope = null;
			return;
		}

		SyncScope scope = currentEncounterScope();
		if (scope == null || scope.equals(lastPresenceScope))
		{
			return;
		}

		// Stay silent on initial entry to an idle open world. A transition from a raid or
		// instance still needs one notice to replace the previous scope on peers.
		if (lastPresenceScope == null && scope.getType() == BpdDefenceSync.SCOPE_WORLD && !supportedBossVisible())
		{
			return;
		}

		partyService.send(new BpdEncounterPresence(client.getWorld(), scope.getType(), scope.getId())
			.withPartyId(partyService.getPartyId()));
		lastPresenceScope = scope;
	}

	/** True while at least one supported, live boss is rendered on this client. */
	private boolean supportedBossVisible()
	{
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || worldView.npcs() == null)
		{
			return false;
		}

		Iterator<? extends NPC> iterator = worldView.npcs().iterator();
		while (iterator != null && iterator.hasNext())
		{
			NPC npc = iterator.next();
			if (BossDefence.isLiveNpc(npc) && npc.getName() != null && BossDefence.matchingNpc(npc) != null)
			{
				return true;
			}
		}
		return false;
	}

	private boolean partyMessageBelongsToCurrentParty(long eventPartyId)
	{
		if (eventPartyId == 0L)
		{
			// Legacy sender: no party identity available, keep existing scope/epoch checks.
			return true;
		}
		if (!partyService.isInParty())
		{
			return false;
		}
		long livePartyId = partyService.getPartyId();
		if (livePartyId != 0L)
		{
			return eventPartyId == livePartyId;
		}
		// PartyService can briefly report zero while a session transition is still settling.
		// Fall back only to the last party id observed by the PartyChanged event, never to an
		// arbitrary packet id. If no session identity is known, preserve the legacy-compatible path.
		return currentPartyId == null || currentPartyId == 0L || eventPartyId == currentPartyId;
	}

	private void rememberSenderEncounterPresence(long memberId, int world, int scopeType, int scopeId, long sentAtMillis)
	{
		// A valid BPD protocol message proves this member is a BPD peer even if it was sent from
		// another world. Remember that fact for the life of this PartyService session; it helps
		// diagnostics and distinguishes BPD peers from SAC-only members without gating evidence.
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
		SenderEncounterPresence existing = senderEncounterPresence.get(memberId);
		if (existing != null && sentAtMillis < existing.getSentAtMillis())
		{
			log.debug("Ignoring out-of-order encounter heartbeat from member={} sent={} current={}",
				memberId, sentAtMillis, existing.getSentAtMillis());
			return;
		}
		senderEncounterPresence.put(memberId, new SenderEncounterPresence(
			world, scopeType, scopeId, System.currentTimeMillis(), sentAtMillis));
	}

	/**
	 * For another BPD client, normal SpecialCounterUpdate events are accepted only when both
	 * clients advertise the same concrete encounter scope. Missing or stale heartbeat metadata is
	 * not treated as proof that the attack is invalid: the observation falls back to the same
	 * world and target-association rules used for legacy/SAC-only members.
	 */
	private boolean standardPartySpecScopeMatches(long memberId, int eventWorld, int npcIndex)
	{
		SenderEncounterPresence senderScope = senderEncounterPresence.get(memberId);
		if (senderScope == null)
		{
			return standardPartySpecWithoutFreshHeartbeat(
				memberId, eventWorld, npcIndex, "missing");
		}

		long age = System.currentTimeMillis() - senderScope.getReceivedAtMillis();
		if (age > ENCOUNTER_PRESENCE_MAX_AGE_MILLIS)
		{
			senderEncounterPresence.remove(memberId);
			return standardPartySpecWithoutFreshHeartbeat(
				memberId, eventWorld, npcIndex, "stale (" + age + "ms)");
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
		if (!match && senderScope.getScopeType() == BpdDefenceSync.SCOPE_INSTANCE)
		{
			WorldView worldView = client.getTopLevelWorldView();
			match = worldView != null && worldView.isInstance()
				&& instanceFingerprint(worldView) == senderScope.getScopeId();
		}
		if (!match && standardPartySpecTargetEvidenceMatches(npcIndex))
		{
			// Encounter fingerprints are transport evidence, not encounter identity. The observed
			// TFA failure had differing scope identifiers; why they differed is not established.
			// One uniquely resolved local target is stronger evidence than that fingerprint, so
			// retain the combat observation rather than rejecting it. Sender rendering is local
			// presentation state and is never required to receive combat evidence.
			log.debug("Accepting BPD party spec from member={} despite scope mismatch sender={}:{} local={}: "
				+ "one unique local target is present",
				memberId, senderScope.getScopeType(), senderScope.getScopeId(), localScope);
			return true;
		}
		if (!match)
		{
			log.debug("Ignoring BPD party spec from member={} due to encounter mismatch sender={}:{} local={}",
				memberId, senderScope.getScopeType(), senderScope.getScopeId(), localScope);
		}
		return match;
	}

	/**
	 * Missing synchronization metadata cannot invalidate combat evidence by itself. Keep the
	 * event's own world identity, then let DefenceTracker apply the normal target rules: a unique
	 * live target can be associated immediately, while an absent or ambiguous target stays in the
	 * existing bounded deferred-event path. This intentionally does not guess between same-type NPCs.
	 */
	private boolean standardPartySpecWithoutFreshHeartbeat(
		long memberId, int eventWorld, int npcIndex, String heartbeatState)
	{
		if (eventWorld != client.getWorld())
		{
			log.debug("Ignoring party spec from member={} due to wrong world event={} local={}",
				memberId, eventWorld, client.getWorld());
			return false;
		}

		if (knownBpdPartyMembers.contains(memberId))
		{
			if (standardPartySpecTargetEvidenceMatches(npcIndex))
			{
				log.debug("Accepting BPD party spec from member={} with {} heartbeat and one unique local target",
					memberId, heartbeatState);
			}
			else
			{
				log.debug("Retaining BPD party spec from member={} with {} heartbeat for deferred target association",
					memberId, heartbeatState);
			}
		}
		return true;
	}

	/**
	 * A scope mismatch may come from local instance-identification differences rather than two
	 * different encounters. Accept it only when the event points at one unique supported local
	 * target. Sender visibility is intentionally not part of this check: rendered players are a
	 * presentation concern, not evidence about which encounter received a special attack. If the
	 * target is absent or ambiguous, the normal scope rejection remains in force.
	 */
	private boolean standardPartySpecTargetEvidenceMatches(int npcIndex)
	{
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || worldView.npcs() == null)
		{
			return false;
		}

		NPC indexedTarget = worldView.npcs().byIndex(npcIndex);
		if (indexedTarget == null || !BossDefence.isLiveNpc(indexedTarget))
		{
			return false;
		}
		BossDefence boss = BossDefence.matchingNpc(indexedTarget);
		if (boss == null || NpcTargetResolver.uniqueLiveNpc(worldView, boss) != indexedTarget)
		{
			return false;
		}
		return true;
	}

	/** Receive the optional absolute BPD tracker snapshot from another member of this PartyService party. */
	@Subscribe
	public void onBpdDefenceSync(BpdDefenceSync event)
	{
		if (!partyService.isInParty()
			|| event == null || !event.isSupportedProtocol()
			|| !partyMessageBelongsToCurrentParty(event.getPartyId()))
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

		final long receivedInPartyEpoch = partyEpoch;
		clientThread.invoke(() ->
		{
			if (receivedInPartyEpoch != partyEpoch || !partyService.isInParty()
				|| partyService.getMemberById(event.getMemberId()) == null) { return; }
			knownBpdPartyMembers.add(event.getMemberId());
			rememberSenderEncounterPresence(event.getMemberId(), event.getWorld(),
				event.getScopeType(), event.getScopeId(), event.getSentAtMillis());
			if (pendingSnapshots.size() >= 128) { pendingSnapshots.removeFirst(); }
			pendingSnapshots.addLast(event);
		});
	}

	/** Apply snapshots only after accepted local/standard observations have settled. */
	private void reconcileQueuedSnapshots()
	{
		// Visit each queued packet once; blocked targets do not starve unrelated bosses.
		int count = pendingSnapshots.size();
		for (int i = 0; i < count; i++)
		{
			BpdDefenceSync snapshot = pendingSnapshots.pollFirst();
			if (partyService.getMemberById(snapshot.getMemberId()) == null) { continue; }
			DefenceTracker.SyncState state = snapshot.toSyncState();
			if (state == null) { continue; }
			if (defenceTracker.hasUnprocessedSpecsForBoss(state.getBossType()))
			{
				pendingSnapshots.addLast(snapshot);
				continue;
			}
			acceptBpdDefenceSync(snapshot);
		}
	}

	private void acceptBpdDefenceSync(BpdDefenceSync event)
	{
		if (!partyService.isInParty()
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
		if (boss == BossDefence.TFA_OCELLUS_P1 && ocellusPhaseOneEnded)
		{
			return; // A late party snapshot must not bring back P1 in P2/P3.
		}
		NPC localBoss = findLiveNpcForBoss(boss, event);
		if (localBoss != null && !peerSyncAllowedAtCurrentEncounter(boss))
		{
			log.debug("Ignoring BPD sync for {} in a solo/single-combat encounter", boss);
			return;
		}
		if (snapshotPredatesReset(event, sync))
		{
			log.debug("Ignoring BPD sync for {} with history from before the encounter reset", boss);
			return;
		}

		// A world-scoped boss cannot be position/HP-verified until it is rendered locally. Cache
		// the fresh absolute snapshot silently, then bind it when that boss actually enters this
		// client's scene. This preserves late-render catch-up without letting an out-of-vicinity
		// party spec replace the user's current detached display/InfoBox selection. Keep the event
		// cached too so the first local render can run the stricter world position/HP reconciliation.
		if (event.getScopeType() == BpdDefenceSync.SCOPE_WORLD && localBoss == null)
		{
			// Do not pre-load world-scoped state while this client is somewhere else. When the
			// actual boss enters this client's scene, BpdBossPresence requests the still-valid
			// encounter state immediately. This prevents a same-world solo/single-combat boss
			// from inheriting state that belongs to somebody else's encounter.
			return;
		}

		if (!incomingScopeMatches(event, boss, localBoss))
		{
			return;
		}

		// Different supported targets are independent. The tracker remembers the current one
		// before activating this target, so simultaneous encounters such as both Olm hands retain
		// their own defence/history instead of overwriting each other.
		if (!incomingVersionIsAcceptable(event, sync, localBoss))
		{
			return;
		}
		if (!incomingSyncIsUseful(sync, localBoss, event.getStateVersion()))
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
		for (BossDefence boss : defenceTracker.consumeEndedPublicBosses())
		{
			resetEncounterAndBroadcast(boss, new SyncScope(BpdDefenceSync.SCOPE_WORLD, 0), "local NPC death");
		}
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
	 * transition so Defence and spec history restart together.
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
		BpdEncounterReset event = scope == null ? null
			: new BpdEncounterReset(client.getWorld(), boss, scope.getType(), scope.getId());
		long resetAt = event == null ? System.currentTimeMillis() : event.getSentAtMillis();
		if (!applyEncounterReset(boss, scope, reason, true, resetAt))
		{
			return;
		}

		if (event != null && partyService.isInParty())
		{
			partyService.send(event.withPartyId(partyService.getPartyId()));
			log.debug("Broadcast BPD encounter reset boss={} scope={}:{} reason={}",
				boss, scope.getType(), scope.getId(), reason);
		}
	}

	/** Clear Defence + history + all reconciliation metadata as one encounter state. */
	private boolean applyEncounterReset(BossDefence boss, SyncScope scope, String reason, boolean authoritativeLocal)
	{
		return applyEncounterReset(boss, scope, reason, authoritativeLocal, System.currentTimeMillis());
	}

	private boolean applyEncounterReset(BossDefence boss, SyncScope scope, String reason,
		boolean authoritativeLocal, long resetAt)
	{
		if (boss == null)
		{
			return false;
		}

		EncounterResetKey key = scope == null ? null
			: new EncounterResetKey(client.getWorld(), boss, scope.getType(), scope.getId());
		if (key != null)
		{
			Long lastReset = encounterResetTimes.get(key);
			if (!authoritativeLocal && lastReset != null && resetAt <= lastReset)
			{
				return false;
			}
			encounterResetTimes.merge(key, resetAt, Math::max);
			if (!authoritativeLocal)
			{
				NPC liveBoss = findLiveNpcForBoss(boss);
				DefenceTracker.SyncState current = liveBoss == null ? null : defenceTracker.syncStateForActor(liveBoss);
				if (current != null && scopeMatches(scope.getType(), scope.getId(), boss))
				{
					List<DefenceTracker.SpecHistoryEntry> surviving = current.getHistory().stream()
						.filter(spec -> spec.getObservedAtMillis() > resetAt)
						.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
					if (surviving.size() != current.getHistory().size() && EncounterTimeline.complete(surviving))
					{
						defenceTracker.replaySpecsAfterReset(liveBoss, surviving);
						syncStateVersions.remove(boss);
						return true;
					}
					if (!surviving.isEmpty() && surviving.size() == current.getHistory().size())
					{
						return false; // The next encounter already has confirmed combat evidence.
					}
				}
			}
		}

		WorldView view = client.getTopLevelWorldView();
		if (scope != null && scope.getType() == BpdDefenceSync.SCOPE_WORLD
			&& !isRaidEncounterBoss(boss) && (view == null || view.isInstance()))
		{
			defenceTracker.clearPublicState(boss, client.getWorld(), reason);
			pendingSnapshots.removeIf(snapshot -> boss.name().equals(snapshot.getBossType())
				&& snapshot.getScopeType() == BpdDefenceSync.SCOPE_WORLD
				&& !snapshotHasOnlyNewSpecs(snapshot, resetAt));
			pendingPartySpecs.removeIf(spec -> spec.getBoss() == boss
				&& spec.getScopeType() == BpdDefenceSync.SCOPE_WORLD && !specWasObservedAfter(spec, resetAt));
			pendingWorldSyncs.remove(boss);
			return true;
		}
		if (scope != null && scope.getType() == BpdDefenceSync.SCOPE_INSTANCE && !isRaidEncounterBoss(boss))
		{
			defenceTracker.clearInstancedBossState(boss, reason);
		}
		else
		{
			defenceTracker.clearBossState(boss, reason);
		}
		clearBossScopedReconciliationMetadata(boss, scope, resetAt);
		log.debug("Reset encounter state for {}: {}", boss, reason);
		return true;
	}

	/**
	 * Remove party/reconciliation bookkeeping owned by one ended encounter.
	 *
	 * <p>The tracker remains the authority for boss lifetime; callers invoke this only when that
	 * owner has already decided that the named boss encounter ended or reset. State that protects
	 * unrelated bosses or remote-party members is deliberately retained.
	 */
	private void clearBossScopedReconciliationMetadata(BossDefence boss)
	{
		clearBossScopedReconciliationMetadata(boss, null, 0);
	}

	private void clearBossScopedReconciliationMetadata(BossDefence boss, SyncScope scope, long resetAt)
	{
		if (boss == null)
		{
			return;
		}
		if (bossEffectDisplay != null)
		{
			bossEffectDisplay.clearBoss(boss);
		}
		pendingSnapshots.removeIf(snapshot -> boss.name().equals(snapshot.getBossType())
			&& (scope == null || scope.getType() == snapshot.getScopeType()
				&& scope.getId() == snapshot.getScopeId() && !snapshotHasOnlyNewSpecs(snapshot, resetAt)));
		pendingPartySpecs.removeIf(spec -> spec.getBoss() == boss
			&& (scope == null || scope.getType() == spec.getScopeType()
				&& scope.getId() == spec.getScopeId() && !specWasObservedAfter(spec, resetAt)));
		bossesNeedingCatchUp.remove(boss);
		pendingJoinHistoryOffers.remove(boss);
		visibleTrackedBosses.remove(boss);
		pendingKerisTimers.removeIf(timer -> timer.getBoss() == boss);
		pendingWorldSyncs.remove(boss);
		syncStateVersions.remove(boss);
		retainedRaidScopes.remove(boss);
		initialSyncDisplayGate.clearForBoss(boss);
		if (activeSyncScope != null && activeSyncScope.getBoss() == boss)
		{
			activeSyncScope = null;
		}
	}

	private static boolean specWasObservedAfter(BpdSpecEvent spec, long resetAt)
	{
		Long observedAt = spec.getObservedAt();
		return observedAt != null && observedAt > resetAt && observedAt <= spec.getSentAt();
	}

	private static boolean snapshotHasOnlyNewSpecs(BpdDefenceSync snapshot, long resetAt)
	{
		DefenceTracker.SyncState state = snapshot.toSyncState();
		return state != null && !state.getHistory().isEmpty()
			&& state.getHistory().stream().allMatch(spec -> spec.getObservedAtMillis() > resetAt);
	}

	private boolean observationPredatesReset(BossDefence boss, int scopeType, int scopeId, long observedAt)
	{
		Long resetAt = encounterResetTimes.get(new EncounterResetKey(client.getWorld(), boss, scopeType, scopeId));
		// An untimestamped observation cannot establish that it belongs to the next fight.
		return resetAt != null && observedAt <= resetAt;
	}

	private boolean snapshotPredatesReset(BpdDefenceSync event, DefenceTracker.SyncState state)
	{
		// A snapshot's absolute Defence can already include an old hit even if its packet is fresh.
		return state.getHistory().stream().anyMatch(spec -> observationPredatesReset(
			state.getBossType(), event.getScopeType(), event.getScopeId(), spec.getObservedAtMillis()));
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
		if (event.getScopeType() == BpdDefenceSync.SCOPE_WORLD)
		{
			return event.getScopeId() == 0 && (worldView != null && !worldView.isInstance()
				|| !isRaidEncounterBoss(boss) && defenceTracker.hasPublicState(boss, event.getWorld()));
		}
		if (worldView == null)
		{
			return false;
		}
		if (event.getScopeType() == BpdDefenceSync.SCOPE_INSTANCE)
		{
			return worldView.isInstance() && instanceFingerprint(worldView) == event.getScopeId();
		}
		return false;
	}


	/** Coalesce visibility, party join and history gap events before requesting catch-up. */
	private void reconcileVisibleBosses()
	{
		if (!partyService.isInParty() || partyService.getLocalMember() == null
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || worldView.npcs() == null)
		{
			return;
		}

		if (revalidateBossesAfterPartyJoin)
		{
			pendingJoinHistoryOffers.addAll(defenceTracker.drainedBosses());
		}
		Set<BossDefence> seen = new HashSet<>();
		Iterator<? extends NPC> iterator = worldView.npcs().iterator();
		while (iterator != null && iterator.hasNext())
		{
			NPC npc = iterator.next();
			if (!BossDefence.isLiveNpc(npc) || npc.getName() == null)
			{
				continue;
			}

			BossDefence boss = BossDefence.matchingNpc(npc);
			if (!peerSyncAllowedAtCurrentEncounter(boss) || !seen.add(boss))
			{
				continue;
			}

			boolean offerOnJoin = pendingJoinHistoryOffers.contains(boss);
			boolean revalidate = revalidateBossesAfterPartyJoin || offerOnJoin || bossesNeedingCatchUp.contains(boss);
			if (!revalidate && visibleTrackedBosses.contains(boss))
			{
				continue;
			}

			DefenceTracker.SyncState local = defenceTracker.syncStateForBoss(boss);
			if (!revalidate && local != null && local.isDrained())
			{
				continue;
			}

			SyncScope scope = localSyncScope(boss, npc);
			WorldPoint point = npc.getWorldLocation();
			if (scope == null || point == null)
			{
				// Scene data may settle on a later tick. Keep the original trigger pending.
				seen.remove(boss);
				if (revalidate)
				{
					bossesNeedingCatchUp.add(boss);
				}
				continue;
			}

			if (!revalidate)
			{
				initialSyncDisplayGate.arm(npc.getIndex(), boss, client.getTickCount(), INITIAL_SYNC_DISPLAY_GRACE_TICKS);
			}
			BpdBossPresence request = new BpdBossPresence(
				client.getWorld(), boss, scope.getType(), scope.getId(),
				point.getX(), point.getY(), point.getPlane(), healthPercent(npc))
				.withPartyId(partyService.getPartyId());
			DefenceTracker.SyncState offer = revalidate ? defenceTracker.syncStateForActor(npc) : null;
			if (offer != null && offer.isDrained())
			{
				request.offerHistory(new BpdDefenceSync(client.getWorld(), offer, scope.getType(), scope.getId(),
					point.getX(), point.getY(), point.getPlane(), request.getHealthPercent(), observeLocalStateVersion(offer)));
			}
			partyService.send(request);
			pendingJoinHistoryOffers.remove(boss);
			bossesNeedingCatchUp.remove(boss);
			log.debug("Broadcast BPD boss presence boss={} scope={}:{} actor={}",
				boss, scope.getType(), scope.getId(), npc.getIndex());
		}
		for (BossDefence boss : visibleTrackedBosses)
		{
			if (!seen.contains(boss) && defenceTracker.hasRememberedState(boss))
			{
				bossesNeedingCatchUp.add(boss);
			}
		}
		visibleTrackedBosses.clear();
		visibleTrackedBosses.addAll(seen);
		revalidateBossesAfterPartyJoin = false;
	}

	/**
	 * Party state is only meaningful where more than one player can actually share the same
	 * encounter. Raid encounters are explicitly shareable. Outside raids, require the local
	 * multicombat flag so single-combat open-world bosses and solo/private instances never
	 * exchange BPD state.
	 */
	private boolean peerSyncAllowedAtCurrentEncounter(BossDefence boss)
	{
		if (boss == null)
		{
			return false;
		}
		if (isRaidEncounterBoss(boss))
		{
			return true;
		}

		return client.getVarbitValue(Varbits.MULTICOMBAT_AREA) != 0;
	}

	private boolean bossPresenceScopeMatches(BpdBossPresence event, BossDefence boss)
	{
		if (event.getWorld() != client.getWorld())
		{
			return false;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return false;
		}

		if (event.getScopeType() == BpdDefenceSync.SCOPE_WORLD)
		{
			// In the normal world, boss type + world + the existing short encounter grace is the
			// continuity identity. This deliberately allows the original observer to answer after
			// dying/teleporting away, as long as the remembered encounter has not expired.
			return !worldView.isInstance();
		}

		SyncScope localScope = localSyncScope(boss, null);
		return localScope != null
			&& localScope.getType() == event.getScopeType()
			&& localScope.getId() == event.getScopeId();
	}

	/** Each local observation is sent once. Full snapshots are sent in response to boss presence. */
	private void broadcastLocalSpecEvents()
	{
		if (!partyService.isInParty()) { return; }
		for (DefenceTracker.AppliedSpec applied : defenceTracker.localSpecs())
		{
			NPC npc = applied.getNpc();
			BossDefence boss = applied.getBoss();
			if (!BossDefence.isLiveNpc(npc) || !peerSyncAllowedAtCurrentEncounter(boss)
				|| !defenceTracker.hasPartySpec(npc, applied.getEntry().getEventId()))
			{
				continue;
			}
			SyncScope scope = localSyncScope(boss, npc);
			WorldPoint point = npc.getWorldLocation();
			Integer tile = point == null ? null : BpdDefenceSync.packBossLocation(point.getX(), point.getY(), point.getPlane());
			if (scope == null || tile == null) { continue; }
			rememberRaidScope(boss, scope);
			partyService.send(new BpdSpecEvent(client.getWorld(), scope.getType(), scope.getId(), boss,
				npc.getIndex(), tile, scope.getType() == BpdDefenceSync.SCOPE_WORLD ? healthPercent(npc) : -1,
				applied.getEntry(), applied.getPreviousId()));
		}
	}

	@Subscribe
	public void onBpdSpecEvent(BpdSpecEvent event)
	{
		if (event == null || !partyService.isInParty() || partyService.getMemberById(event.getMemberId()) == null)
		{
			return;
		}
		PartyMember local = partyService.getLocalMember();
		if (local != null && local.getMemberId() == event.getMemberId()) { return; }

		long epoch = partyEpoch;
		clientThread.invoke(() ->
		{
			if (epoch != partyEpoch || !partyService.isInParty()
				|| partyService.getMemberById(event.getMemberId()) == null) { return; }
			if (pendingPartySpecs.size() >= 128) { pendingPartySpecs.removeFirst(); }
			pendingPartySpecs.addLast(event);
		});
	}

	private void reconcilePartySpecEvents()
	{
		for (BpdSpecEvent event = pendingPartySpecs.pollFirst(); event != null; event = pendingPartySpecs.pollFirst())
		{
			PartyMember sender = partyService.getMemberById(event.getMemberId());
			BossDefence boss = event.getBoss();
			long now = System.currentTimeMillis();
			if (sender == null || boss == null || event.getWorld() != client.getWorld()
				|| event.getSentAt() < now - SYNC_MAX_AGE_MILLIS || event.getSentAt() > now + SYNC_MAX_AGE_MILLIS
				|| boss == BossDefence.TFA_OCELLUS_P1 && ocellusPhaseOneEnded)
			{
				continue;
			}
			String player = Objects.toString(sender.getDisplayName(), "Unknown");
			DefenceTracker.SpecHistoryEntry entry = event.historyEntry(player);
			WorldPoint tile = event.location();
			if (entry == null || tile == null || !scopeMatches(event.getScopeType(), event.getScopeId(), boss)
				|| observationPredatesReset(boss, event.getScopeType(), event.getScopeId(), entry.getObservedAtMillis()))
			{
				continue;
			}
			WorldView view = client.getTopLevelWorldView();
			if (view == null || view.npcs() == null) { continue; }
			NPC npc = view.npcs().byIndex(event.getNpcIndex());
			if (!BossDefence.isLiveNpc(npc) || BossDefence.matchingNpc(npc) != boss
				|| npc.getWorldLocation() == null || npc.getWorldLocation().distanceTo(tile) > NON_INSTANCE_POSITION_TOLERANCE)
			{
				npc = NpcTargetResolver.uniqueLiveNpcNear(view, boss, tile.getX(), tile.getY(), tile.getPlane(),
					NON_INSTANCE_POSITION_TOLERANCE);
			}
			if (npc == null)
			{
				bossesNeedingCatchUp.add(boss);
				continue;
			}
			if (!peerSyncAllowedAtCurrentEncounter(boss) || defenceTracker.hasPartySpec(npc, entry.getEventId()))
			{
				continue;
			}
			if (event.getScopeType() == BpdDefenceSync.SCOPE_WORLD && !healthMatches(npc, event.getHealthPercent()))
			{
				// The boss may have taken several hits while the packet was in flight. Revalidate
				// through the normal request path rather than bind the spec to the wrong fight.
				bossesNeedingCatchUp.add(boss);
				continue;
			}
			boolean hasPrevious = event.getPreviousId() == null || defenceTracker.hasPartySpec(npc, event.getPreviousId());
			DefenceTracker.PartySpecResult result = defenceTracker.applyPartySpec(npc, entry);
			if (result == DefenceTracker.PartySpecResult.IGNORED)
			{
				bossesNeedingCatchUp.add(boss);
				continue;
			}
			rememberSenderEncounterPresence(event.getMemberId(), event.getWorld(), event.getScopeType(),
				event.getScopeId(), event.getSentAt());
			rememberRaidScope(boss, new SyncScope(event.getScopeType(), event.getScopeId()));
			if (result == DefenceTracker.PartySpecResult.ADDED && entry.getWeapon() != null)
			{
				recentSyncedSpecs.add(new SyncedSpecKey(client.getWorld(), npc.getIndex(),
					normalizePlayerName(player), entry.getWeapon(),
					DefenceTracker.specCorrelationHit(entry.getWeapon(), entry.getHit())), client.getTickCount());
			}
			initialSyncDisplayGate.release(npc.getIndex(), boss);
			if (!hasPrevious) { bossesNeedingCatchUp.add(boss); }
		}
	}

	/**
	 * A TFA raid has five rooms. While running back after a death (or moving
	 * between rooms), a previously drained NPC can be absent from the scene.
	 * Never use NPC visibility or a room template fingerprint as proof of an
	 * encounter reset. Exiting the instanced raid is authoritative cleanup.
	 */
	private void reconcileTfaRaidExit()
	{
		WorldView world = client.getTopLevelWorldView();
		if (world == null || world.isInstance())
		{
			return;
		}
		clearAllTfaState("left The Fractured Archive instance");
	}

	/**
	 * Phase 2 must never reuse Phase 1's stored values. The Blade actor by
	 * itself was not a sufficient transition signal: the Ocellus actor changes
	 * during the portal phase, and its Phase 1 actor can reach zero health.
	 * Phase 2 is The Blade's own 250 -> 0 Defence pool, never P1's state.
	 */
	private void reconcileTfaPhaseChange()
	{
		WorldView world = client.getTopLevelWorldView();
		if (world == null || !world.isInstance())
		{
			return;
		}

		boolean phaseThree = findLiveNpcForBoss(BossDefence.TFA_OCELLUS_P3) != null;
		boolean phaseTwo = false;
		boolean phaseOneDefeated = false;
		if (world.npcs() != null)
		{
			Iterator<? extends NPC> iterator = world.npcs().iterator();
			while (iterator != null && iterator.hasNext())
			{
				NPC npc = iterator.next();
				if (isOcellusPhaseTwoActor(npc))
				{
					phaseTwo = true;
				}
				if (isOcellusPhaseOneDefeated(npc))
				{
					phaseOneDefeated = true;
				}
			}
		}

		if ((phaseTwo || phaseThree || phaseOneDefeated) && !ocellusPhaseOneEnded)
		{
			ocellusPhaseOneEnded = true;
			// Clear the old infoboxes on the transition tick, not a tick after.
			removeInfoBox();
		}
		if (ocellusPhaseOneEnded && defenceTracker.hasRememberedState(BossDefence.TFA_OCELLUS_P1))
		{
			applyEncounterReset(BossDefence.TFA_OCELLUS_P1, null,
				"Ocellus left Phase 1", true);
		}
		// P3 starts from its own Defence baseline, never from P2 Blade drains.
		// Reset only on the definitive P3 actor, not on a P2 portal/quadrant change.
		if (phaseThree && defenceTracker.hasRememberedState(BossDefence.TFA_OCELLUS_P2))
		{
			applyEncounterReset(BossDefence.TFA_OCELLUS_P2, null,
				"The Blade left Phase 2", true);
		}
	}

	/**
	 * Ocellus phase-2 actors mark the P1 boundary, even if temporarily
	 * non-attackable. The Blade prop (16683) is a transition marker only,
	 * and must NEVER receive spec credit or own a Defence state.
	 * IDs are name-guarded to avoid unrelated NPCs.
	 */
	static boolean isOcellusPhaseTwoActor(NPC npc)
	{
		if (npc == null)
		{
			return false;
		}
		// 16676 is the P2 combat actor regardless of its current display name.
		// The other two IDs only signal the P1->P2 transition; they never
		// receive Defence tracking or spec credit.
		if (npc.getId() == 16676)
		{
			return true;
		}
		if (npc.getName() == null)
		{
			return false;
		}
		String name = Text.removeTags(npc.getName()).trim();
		return ("Ocellus".equalsIgnoreCase(name) && npc.getId() == 16675)
			|| ("The Blade".equalsIgnoreCase(name) && npc.getId() == 16683);
	}

	/** Retained for source compatibility and for the already known Blade actor. */
	static boolean isOcellusPhaseTwoBlade(NPC npc)
	{
		return npc != null && npc.getId() == 16683 && npc.getName() != null
			&& "The Blade".equalsIgnoreCase(Text.removeTags(npc.getName()).trim());
	}

	/** An observed Phase 1 actor at zero HP is stronger evidence than disappearance. */
	static boolean isOcellusPhaseOneDefeated(NPC npc)
	{
		return npc != null && npc.getName() != null
			&& "Ocellus".equalsIgnoreCase(Text.removeTags(npc.getName()).trim())
			&& (npc.getId() == 16673 || npc.getId() == 16674)
			&& (npc.isDead() || npc.getHealthRatio() == 0);
	}

	private void checkTfaNewAttempt(NPC npc)
	{
		if (BossDefence.matchingNpc(npc) != BossDefence.TFA_ARCHIVE_GUARDIAN)
		{
			return;
		}
		if (ocellusPhaseOneEnded)
		{
			clearAllTfaState("new TFA attempt at Archive Guardian");
			return;
		}
		for (BossDefence boss : BossDefence.values())
		{
			if (isTfaBoss(boss) && boss != BossDefence.TFA_ARCHIVE_GUARDIAN
				&& defenceTracker.hasRememberedState(boss))
			{
				clearAllTfaState("fresh TFA run started at Archive Guardian");
				return;
			}
		}
	}

	private void clearAllTfaState(String reason)
	{
		ocellusPhaseOneEnded = false;
		boolean changed = false;
		for (BossDefence boss : BossDefence.values())
		{
			if (isTfaBoss(boss) && defenceTracker.hasRememberedState(boss))
			{
				applyEncounterReset(boss, null, reason, true);
				changed = true;
			}
		}
		if (changed)
		{
			removeInfoBox();
			initialSyncDisplayGate.clear();
			log.debug("Cleared TFA raid: {}", reason);
		}
	}

	private void reconcileRaidEncounterLifecycle()
	{
		for (BossDefence boss : defenceTracker.encounterLifecycleBosses())
		{
			if (!isRaidEncounterBoss(boss))
			{
				retainedRaidScopes.remove(boss);
				continue;
			}

			// CoX's public party/controller id is transport metadata, not encounter lifetime.
			// Jagex can clear/change RAIDS_PARTY_GROUPHOLDER while the client is still inside
			// the same live raid. DefenceTracker already owns the authoritative CoX lifetime
			// through RAIDS_CLIENT_INDUNGEON plus boss/phase mechanics, so never let a missing
			// controller wipe Tekton/Olm state. A newly available canonical controller may be
			// remembered for filtering, but its absence is not a reset signal.
			if (boss.has(BossDefence.Flag.COX_SCALED)
				&& client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1)
			{
				SyncScope currentCoxScope = currentRaidScopeForBoss(boss);
				if (currentCoxScope != null)
				{
					retainedRaidScopes.put(boss, currentCoxScope);
				}
				continue;
			}

			// TFA has no verified exposed shared controller/raid epoch yet. Scene
			// fingerprint varies as we move among five instanced rooms, so it
			// cannot by itself prove a new raid or a wipe. Preserve while in
			// an instance; reconcileTfaRaidExit() handles concrete exits.
			if (isTfaBoss(boss))
			{
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
				clearBossScopedReconciliationMetadata(boss);
			}
		}

		retainedRaidScopes.keySet().removeIf(boss -> !defenceTracker.hasRememberedState(boss));
	}

	private void rememberRaidScope(BossDefence boss, SyncScope scope)
	{
		if (!isRaidEncounterBoss(boss) || scope == null)
		{
			return;
		}

		// CoX/ToB can temporarily lose their public controller id while the raid instance is
		// still alive. We may use an INSTANCE scope as a transport fallback for snapshots, but
		// that fallback must never become lifecycle authority. Otherwise the raid lifecycle
		// reconciler can compare a retained INSTANCE scope against a missing controller scope
		// and incorrectly clear a live encounter (Tekton/Olm were the observed failure case).
		int canonicalRaidScope = raidScopeType(boss);
		if (canonicalRaidScope != -1 && scope.getType() != canonicalRaidScope)
		{
			return;
		}

		retainedRaidScopes.putIfAbsent(boss, scope);
	}

	private SyncScope currentRaidScopeForBoss(BossDefence boss)
	{
		int raidScope = raidScopeType(boss);
		if (raidScope != -1)
		{
			int controller = raidController(raidScope);
			return controller > 0 ? new SyncScope(raidScope, controller) : null;
		}

		if (!isToaBoss(boss) && !isTfaBoss(boss))
		{
			return null;
		}

		WorldView worldView = client.getTopLevelWorldView();
		return worldView != null && worldView.isInstance()
			? new SyncScope(BpdDefenceSync.SCOPE_INSTANCE, instanceFingerprint(worldView))
			: null;
	}

	static boolean isRaidEncounterBoss(BossDefence boss)
	{
		return raidScopeType(boss) != -1 || isToaBoss(boss) || isTfaBoss(boss);
	}

	private static boolean isTfaBoss(BossDefence boss)
	{
		return boss != null && boss.has(BossDefence.Flag.TFA);
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
		if (!partyService.isInParty())
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
				|| now - event.getSentAtMillis() > SYNC_MAX_AGE_MILLIS || snapshotPredatesReset(event, sync))
			{
				iterator.remove();
				continue;
			}

			NPC localBoss = findLiveNpcForBoss(entry.getKey(), event);
			if (localBoss == null || !peerSyncAllowedAtCurrentEncounter(entry.getKey())
				|| !incomingScopeMatches(event, entry.getKey(), localBoss))
			{
				continue;
			}

			if (!incomingVersionIsAcceptable(event, sync, localBoss)
				|| !incomingSyncIsUseful(sync, localBoss, event.getStateVersion()))
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
	private boolean incomingSyncIsUseful(DefenceTracker.SyncState incoming, NPC localBoss, long incomingVersion)
	{
		VersionedSyncState known = syncStateVersions.get(incoming.getBossType());
		if (incomingVersion > 0 && known != null && incomingVersion > known.getVersion())
		{
			// A strictly newer logical snapshot is useful even when history length is unchanged.
			return true;
		}
		if (incomingSyncAddsState(incoming))
		{
			return true;
		}

		return localBoss != null
			&& !defenceTracker.hasBoundNpc(incoming.getBossType());
	}

	/**
	 * Never use network arrival order as truth. With a full eight-person party several peers can
	 * answer the same continuity request at once. State versions are retained as logical bookkeeping; event identity and history
	 * evidence determine recovery rather than the largest peer-local number.
	 */
	private boolean incomingVersionIsAcceptable(BpdDefenceSync event, DefenceTracker.SyncState incoming, NPC localBoss)
	{
		if (defenceTracker.hasUnprocessedSpecsForBoss(incoming.getBossType())) { return false; }
		DefenceTracker.SyncState before = defenceTracker.syncStateForBoss(incoming.getBossType());
		// Event recovery is independent of Lamport snapshot order. A delayed lower-version
		// snapshot can contain a unique spec absent locally; never discard it on version alone.
		boolean matchedPublicEncounter = event.getScopeType() == BpdDefenceSync.SCOPE_WORLD;
		if (event.getEventVersion() == EncounterTimeline.VERSION && localBoss != null
			&& defenceTracker.mergeEventSyncState(incoming, localBoss, matchedPublicEncounter))
		{
			DefenceTracker.SyncState merged = defenceTracker.syncStateForBoss(incoming.getBossType());
			long next = Math.max(observeLocalStateVersion(merged), event.getStateVersion());
			syncStateVersions.put(incoming.getBossType(),
				new VersionedSyncState(next == Long.MAX_VALUE ? next : next + 1, syncSignature(merged)));
			rememberSyncedSpecs(merged, before, localBoss);
			return false;
		}
		if (before != null && EncounterTimeline.hasIdentifiedEvents(before.getHistory())
			&& !EncounterTimeline.containsIdentifiedEvents(incoming.getHistory(), before.getHistory()))
		{
			return false; // Legacy and future-version snapshots must also preserve local event IDs.
		}
		// Legacy/future snapshot numbers do not prove a shared combat chronology.
		// A verified prefix can catch up regardless of the sender's logical version;
		// divergent histories cannot erase or append guesses over accepted local evidence.
		SyncHistoryRelation.Result relation = SyncHistoryRelation.compare(before, incoming);
		return relation == SyncHistoryRelation.Result.INITIAL
			|| relation == SyncHistoryRelation.Result.EXTENSION
			|| (relation == SyncHistoryRelation.Result.SAME && localBoss != null
				&& !defenceTracker.hasBoundNpc(incoming.getBossType()));
	}

	private long observeLocalStateVersion(DefenceTracker.SyncState sync)
	{
		if (sync == null || sync.getBossType() == null)
		{
			return 0L;
		}

		int signature = syncSignature(sync);
		VersionedSyncState known = syncStateVersions.get(sync.getBossType());
		if (known != null && known.getSignature() == signature)
		{
			return known.getVersion();
		}

		int historySize = sync.getHistory() == null ? 0 : sync.getHistory().size();
		long version = known == null ? Math.max(1L, historySize)
			: Math.max(known.getVersion() == Long.MAX_VALUE ? Long.MAX_VALUE : known.getVersion() + 1L, historySize);
		syncStateVersions.put(sync.getBossType(), new VersionedSyncState(version, signature));
		return version;
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
		DefenceTracker.SyncState before = defenceTracker.syncStateForBoss(sync.getBossType());
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
			initialSyncDisplayGate.release(localBoss.getIndex(), sync.getBossType());
		}
		if (event.getStateVersion() > 0)
		{
			syncStateVersions.put(sync.getBossType(),
				new VersionedSyncState(event.getStateVersion(), syncSignature(sync)));
		}
		SyncScope receivedScope = new SyncScope(event.getScopeType(), event.getScopeId());
		rememberRaidScope(sync.getBossType(), receivedScope);
		// A raid INSTANCE fallback is transport-only. Do not let it drive active encounter
		// lifecycle/reset decisions; the existing boss mechanics and canonical raid lifecycle
		// remain authoritative. ToA legitimately uses INSTANCE as its canonical raid scope and
		// therefore keeps the existing active-scope behaviour.
		boolean transportOnlyRaidFallback = raidScopeType(sync.getBossType()) != -1
			&& event.getScopeType() == BpdDefenceSync.SCOPE_INSTANCE;
		activeSyncScope = localBoss == null || transportOnlyRaidFallback ? null
			: new ActiveSyncScope(event.getScopeType(), event.getScopeId(), sync.getBossType());

		rememberSyncedSpecs(sync, before, localBoss);
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

	private void rememberSyncedSpecs(DefenceTracker.SyncState sync,
		DefenceTracker.SyncState before, NPC localBoss)
	{
		if (localBoss == null || sync.getHistory() == null) { return; }
		// Multiset subtraction also handles event histories whose display order changed.
		List<DefenceTracker.SpecHistoryEntry> previous = new ArrayList<>(
			before == null ? java.util.Collections.emptyList() : before.getHistory());
		int tick = client.getTickCount();
		for (DefenceTracker.SpecHistoryEntry entry : sync.getHistory())
		{
			if (entry.getSpell() != null) { continue; }
			int match = -1;
			for (int i = 0; i < previous.size(); i++)
			{
				DefenceTracker.SpecHistoryEntry old = previous.get(i);
				if (entry.equals(old) || (entry.getEventId() == null || old.getEventId() == null)
					&& entry.getWeapon() == old.getWeapon() && entry.getSpell() == old.getSpell() && entry.getHit() == old.getHit()
					&& normalizePlayerName(entry.getPlayerName()).equals(normalizePlayerName(old.getPlayerName())))
				{ match = i; break; }
			}
			if (match >= 0) { previous.remove(match); continue; }
			recentSyncedSpecs.add(new SyncedSpecKey(client.getWorld(), localBoss.getIndex(),
				normalizePlayerName(entry.getPlayerName()), entry.getWeapon(),
				DefenceTracker.specCorrelationHit(entry.getWeapon(), entry.getHit())), tick);
		}
	}

	private static String normalizePlayerName(String name)
	{
		if (name == null)
		{
			return "";
		}
		return Text.toJagexName(Text.removeTags(name)).toLowerCase(java.util.Locale.ROOT);
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
			if (controller > 0)
			{
				return new SyncScope(raidScope, controller);
			}

			// CoX clears RAIDS_PARTY_GROUPHOLDER after the raid starts, so requiring the public
			// controller here disables absolute state snapshots for the actual encounter. Fall
			// back to the concrete live instance strictly for transport/authentication. This scope
			// is deliberately NOT retained as lifecycle state (see rememberRaidScope).
			WorldView raidWorldView = client.getTopLevelWorldView();
			if (raidWorldView != null && raidWorldView.isInstance())
			{
				return new SyncScope(BpdDefenceSync.SCOPE_INSTANCE, instanceFingerprint(raidWorldView));
			}
			return null;
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
		if (!scopeMatches(event.getScopeType(), event.getScopeId(), boss)) { return false; }
		if (event.getScopeType() != BpdDefenceSync.SCOPE_WORLD) { return true; }
		if (localBoss == null) { return false; }

		// Open-world NPC health is not guaranteed to be known merely because the actor is
		// rendered. Giant Mole is a common example: a late-arriving party member may see the
		// NPC before RuneLite has a usable health ratio, and the ratio becomes available only
		// after combat starts. Do not make HP a prerequisite for binding an already-authenticated
		// party snapshot. First require the same world-scene location; when both clients do know
		// HP, keep the HP tolerance as an additional anti-mismatch check.
		WorldPoint point = localBoss.getWorldLocation();
		WorldPoint remotePoint = event.getBossLocation();
		if (point == null || remotePoint == null || point.distanceTo(remotePoint) > NON_INSTANCE_POSITION_TOLERANCE)
		{
			return false;
		}

		return healthMatches(localBoss, event.getHealthPercent());
	}

	private static boolean healthMatches(NPC npc, int remoteHp)
	{
		int localHp = healthPercent(npc);
		return localHp < 0 || remoteHp < 0
			|| Math.abs(localHp - remoteHp) <= NON_INSTANCE_HP_TOLERANCE;
	}

	private boolean scopeMatches(int scopeType, int scopeId, BossDefence boss)
	{
		int expectedRaidScope = raidScopeType(boss);
		if (expectedRaidScope != -1)
		{
			// A peer may lose its public controller before this client does. Its concrete
			// instance fallback remains usable while our own controller is still present.
			if (scopeType == BpdDefenceSync.SCOPE_INSTANCE)
			{
				WorldView raidWorldView = client.getTopLevelWorldView();
				return raidWorldView != null && raidWorldView.isInstance()
					&& scopeId == instanceFingerprint(raidWorldView);
			}
			int controller = raidController(expectedRaidScope);
			return controller > 0 && scopeType == expectedRaidScope
				&& scopeId == controller;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return false;
		}
		if (scopeType == BpdDefenceSync.SCOPE_INSTANCE)
		{
			return worldView.isInstance() && instanceFingerprint(worldView) == scopeId;
		}
		return scopeType == BpdDefenceSync.SCOPE_WORLD && !worldView.isInstance();
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
			// authoritative death/reset/raid-scope lifecycle decide when it actually ends.
			activeSyncScope = null;
			return;
		}

		if (!syncScopeStillValid(activeSyncScope))
		{
			// TFA scene fingerprints can change at room boundaries even while the
			// SAME raid is active. A stale party transport scope is NOT proof that
			// the encounter ended; never reset stored Defence here.
			WorldView tfaWorldView = client.getTopLevelWorldView();
			if (isTfaBoss(activeSyncScope.getBoss())
				&& tfaWorldView != null && tfaWorldView.isInstance())
			{
				log.debug("Dropping stale TFA sync transport for {} without clearing live raid state",
					activeSyncScope.getBoss());
				activeSyncScope = null;

				return;
			}
			// A CoX controller becoming unavailable while RAIDS_CLIENT_INDUNGEON is still 1
			// only means the sync transport scope is stale. It does NOT mean Tekton/Olm ended.
			// Drop the transport association and let DefenceTracker's authoritative CoX
			// lifecycle decide when the encounter actually resets.
			if (activeSyncScope.getBoss() != null
				&& activeSyncScope.getBoss().has(BossDefence.Flag.COX_SCALED)
				&& client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1)
			{
				log.debug("Dropping stale CoX sync scope for {} without clearing live raid state",
					activeSyncScope.getBoss());
				activeSyncScope = null;

				return;
			}

			defenceTracker.resetInstancedState("BPD synced encounter ended locally");
			activeSyncScope = null;
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

	static int instanceFingerprint(WorldView worldView)
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


	private NPC findNpcByIndex(int index)
	{
		WorldView worldView = client.getTopLevelWorldView();
		return index < 0 || worldView == null || worldView.npcs() == null
			? null : worldView.npcs().byIndex(index);
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
		WorldView worldView = client.getTopLevelWorldView();
		return NpcTargetResolver.uniqueLiveNpc(worldView, boss);
	}

	/**
	 * A BPD snapshot carries the sender's observed boss tile. Use it to distinguish two live
	 * actors of the same type, but never fall back to an arbitrary first match.
	 */
	private NPC findLiveNpcForBoss(BossDefence boss, BpdDefenceSync event)
	{
		WorldView worldView = client.getTopLevelWorldView();
		WorldPoint point = event == null ? null : event.getBossLocation();
		if (point != null)
		{
			NPC nearby = NpcTargetResolver.uniqueLiveNpcNear(worldView, boss,
				point.getX(), point.getY(), point.getPlane(),
				NON_INSTANCE_POSITION_TOLERANCE);
			if (nearby != null)
			{
				return nearby;
			}
		}
		return NpcTargetResolver.uniqueLiveNpc(worldView, boss);
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

	/**
	 * The display-polish build briefly exposed health-bar pinning as a display mode.
	 * Preserve that saved placement now that the tracker is always attached to the NPC.
	 */
	private void migrateLegacyHealthBarDisplayMode()
	{
		String savedMode = configManager.getConfiguration(BetterPartyDefenceConfig.GROUP, "defenceDisplayMode");
		if ("NPC_HEALTH_BAR".equals(savedMode))
		{
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

	/**
	 * A real drained state is stronger evidence than the short first-render gate. This covers a
	 * local spec (and the standard party-spec path) that lands while we are waiting for a richer
	 * absolute snapshot, without making the gate part of tracker math.
	 */
	private void releaseInitialSyncGatesForDrainedStates()
	{
		for (DefenceTracker.DefenceState state : defenceTracker.states())
		{
			if (state == null || !state.isDrained() || state.getNpcIndex() < 0)
			{
				continue;
			}

			NPC stateNpc = findNpcByIndex(state.getNpcIndex());
			BossDefence boss = BossDefence.matchingNpc(stateNpc);
			if (boss != null)
			{
				initialSyncDisplayGate.release(state.getNpcIndex(), boss);
			}
		}
	}

	private void updateDefenceInfoBox()
	{
		DefenceTracker.DefenceState state = defenceTracker.state();
		boolean live = state != null && trackedNpcIsLive(state);
		boolean awaitingInitialSync = state != null
			&& initialSyncDisplayGate.isWaiting(state, client.getTickCount());
		boolean showDefence = config.defenceInfoBox() && live && !awaitingInitialSync
			&& (defenceTracker.hasDefenceSpecHistory() || config.defenceAlwaysShow());
		boolean showMagic = config.magicDefence() && config.magicDefenceInfoBox() && live && !awaitingInitialSync
			&& MagicDefenceVisibility.isVisible(state)
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

		updateSpecWeaponInfoBoxes(state, live && !awaitingInitialSync);
		if (bossEffectDisplay != null)
		{
			bossEffectDisplay.update(this, infoBoxManager, itemManager, skillIconManager,
				config.showRedKeris(), config.showShadowBarrage(), config.showVulnerability());
		}
	}

	/**
	 * Keep these counters tied to the same active encounter as BPD's Defence info box.
	 * Rebuilding from history each tick, rather than incrementing independent counters,
	 * automatically handles resets, swaps, new phases, late joins and absolute party sync.
	 */
	private void updateSpecWeaponInfoBoxes(DefenceTracker.DefenceState state, boolean live)
	{
		if (!config.specWeaponInfoBoxes() || !live || state == null || !state.isDrained())
		{
			clearSpecWeaponInfoBoxes();
			return;
		}

		Map<SpecialWeapon, SpecWeaponSummary> summaries = SpecWeaponSummary.fromHistory(
			defenceTracker.specHistoryForCurrentTarget(state.getNpcIndex()));
		for (SpecialWeapon existing : new ArrayList<>(specWeaponBoxes.keySet()))
		{
			if (!summaries.containsKey(existing))
			{
				infoBoxManager.removeInfoBox(specWeaponBoxes.remove(existing));
			}
		}
		for (Map.Entry<SpecialWeapon, SpecWeaponSummary> entry : summaries.entrySet())
		{
			SpecialWeapon weapon = entry.getKey();
			SpecWeaponInfoBox box = specWeaponBoxes.get(weapon);
			if (box == null)
			{
				int[] itemIds = weapon.getItemID();
				BufferedImage icon = itemIds != null && itemIds.length > 0
					? itemManager.getImage(itemIds[0]) : null;
				if (icon == null)
				{
					icon = skillIconManager.getSkillImage(Skill.ATTACK);
				}
				box = new SpecWeaponInfoBox(icon, this, weapon);
				specWeaponBoxes.put(weapon, box);
				infoBoxManager.addInfoBox(box);
			}
			box.update(entry.getValue());
		}
	}

	private void clearSpecWeaponInfoBoxes()
	{
		for (SpecWeaponInfoBox box : specWeaponBoxes.values())
		{
			infoBoxManager.removeInfoBox(box);
		}
		specWeaponBoxes.clear();
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
		if (!BossDefence.isLiveNpc(npc))
		{
			return false;
		}
		// A recycled NPC index must never display P1 numbers on the P2 actor.
		// Compare each Ocellus phase against its own base and floor. This also
		// permits the *actual* P2 target, instead of permitting only P3.
		if (state.isTfaBoss() && npc.getName() != null
			&& "Ocellus".equalsIgnoreCase(Text.removeTags(npc.getName()).trim()))
		{
			BossDefence actor = BossDefence.matchingNpc(npc);
			return actor != null
				&& (actor == BossDefence.TFA_OCELLUS_P1
					|| actor == BossDefence.TFA_OCELLUS_P2
					|| actor == BossDefence.TFA_OCELLUS_P3)
				&& actor.getBaseDef() == state.getBase()
				&& actor.getMinDef() == state.getMin()
				&& (actor != BossDefence.TFA_OCELLUS_P1 || !ocellusPhaseOneEnded);
		}
		return true;
	}

	private void removeInfoBox()
	{
		if (bossEffectDisplay != null)
		{
			bossEffectDisplay.hide(infoBoxManager);
		}
		clearSpecWeaponInfoBoxes();
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
	private static class VersionedSyncState
	{
		long version;
		int signature;
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
		long sentAtMillis;
	}

	@Value
	private static class SyncedSpecKey
	{
		int world;
		int npcIndex;
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

