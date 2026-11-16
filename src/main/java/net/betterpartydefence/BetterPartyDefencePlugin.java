/*
 * Copyright (c) 2026, Maxwell
 * All rights reserved.
 *
 * Better Party Defence
 *
 * Uses RuneLite's shared PartyService session.
 * Does not create or enable a second party.
 *
 * Reads standard SpecialCounterUpdate party messages.
 * Detects supported local defence specs directly.
 * See ATTRIBUTION.md.
 */
package net.betterpartydefence;

import com.google.inject.Provides;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.betterpartydefence.BetterPartyDefenceConfig.DefenceOverlayPosition;
import net.betterpartydefence.BetterPartyDefenceConfig.TrackerFont;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
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
import net.runelite.client.party.messages.PartyMemberMessage;
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

// Tracks boss Defence from local and party special attacks.
// Uses RuneLite's existing party service.
@Slf4j
@Singleton
@PluginDescriptor(name = "Better Party Defence", description = "Tracks boss Defence from special attacks shared through your Hub Party Panel party.", tags = {
		"defence", "defense", "drain", "party", "hub", "boss", "special"})
public class BetterPartyDefencePlugin extends Plugin {
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
	private DefenceTracker defenceTracker;

	@Inject
	private LocalDefenceSpecDetector localSpecDetector;

	@Inject
	private BetterPartyDefenceConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private SkillIconManager skillIconManager;

	private NpcDefenceOverlay defenceOverlay;
	private DefenceDisplayController display;
	private EncounterScopeResolver encounterScopeResolver;
	private TfaEncounterCoordinator tfaEncounters;
	private ToaEncounterCoordinator toaEncounters;
	private static final int DUPLICATE_WINDOW_TICKS = 1;
	private static final int SYNC_HISTORY_DUPLICATE_WINDOW_TICKS = 2;
	private static final long SYNC_MAX_AGE_MILLIS = 5_000L;
	// Maximum delay before showing an unverified new boss.
	private static final int INITIAL_SYNC_DISPLAY_GRACE_TICKS = 4;
	// If presence data is too old, use normal target checks.
	private static final long ENCOUNTER_PRESENCE_MAX_AGE_MILLIS = 6_000L;
	private final Map<SpecEventKey, Integer> recentSpecEvents = new HashMap<>();
	private final SnapshotEchoGuard<SyncedSpecKey> recentSyncedSpecs = new SnapshotEchoGuard<>(
			SYNC_HISTORY_DUPLICATE_WINDOW_TICKS);
	private final Deque<BpdDefenceSync> pendingSnapshots = new ArrayDeque<>();
	private volatile long partyEpoch;
	// Use the last party id while PartyService briefly reports zero.
	private Long currentPartyId;
	private boolean wasInParty;
	private final Deque<BpdKerisTimer> pendingKerisTimers = new ArrayDeque<>();
	private RaidTimerCoordinator raidTimers;
	private final Deque<BpdSpecEvent> pendingPartySpecs = new ArrayDeque<>();
	private final Set<BossDefence> bossesNeedingCatchUp = new HashSet<>();
	private final Set<BossDefence> visibleTrackedBosses = new HashSet<>();
	// Snapshot version per boss.
	private final Map<BossDefence, VersionedSyncState> syncStateVersions = new HashMap<>();
	// Remember the instance id for each raid boss.
	private final Map<BossDefence, SyncScope> retainedRaidScopes = new HashMap<>();
	private final Map<Long, SenderEncounterPresence> senderEncounterPresence = new HashMap<>();
	// Latest valid reset for each boss and encounter.
	private final Map<EncounterResetKey, Long> encounterResetTimes = new HashMap<>();
	// Party members known to use BPD.
	private final Set<Long> knownBpdPartyMembers = new HashSet<>();
	private ActiveSyncScope activeSyncScope;
	private SyncScope lastPresenceScope;
	// Recheck visible bosses after local party join.
	private boolean revalidateBossesAfterPartyJoin;
	private final Set<BossDefence> pendingJoinHistoryOffers = new HashSet<>();
	// Suppress the first unverified display frame.
	private final InitialSyncDisplayGate initialSyncDisplayGate = new InitialSyncDisplayGate();

	@Provides
	@Singleton
	BetterPartyDefenceConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(BetterPartyDefenceConfig.class);
	}

	@Override
	protected void startUp() {
		migrateLegacyHealthBarDisplayMode();
		display = new DefenceDisplayController(
				client, clientThread, config, defenceTracker, infoBoxManager, itemManager,
				skillIconManager, initialSyncDisplayGate);
		display.start(this);

		// Decode standard specs even when Special Attack Counter is inactive.
		// WSClient deduplicates message registrations by class.
		wsClient.registerMessage(SpecialCounterUpdate.class);
		// Register BPD sync messages.
		wsClient.registerMessage(BpdDefenceSync.class);
		wsClient.registerMessage(BpdSpecEvent.class);
		wsClient.registerMessage(BpdEncounterPresence.class);
		wsClient.registerMessage(BpdBossPresence.class);
		wsClient.registerMessage(BpdEncounterReset.class);
		wsClient.registerMessage(BpdKerisTimer.class);

		defenceOverlay = new NpcDefenceOverlay(client, defenceTracker, config, display.iconSource(),
				initialSyncDisplayGate);
		overlayManager.add(defenceOverlay);

		wasInParty = partyService.isInParty();
		log.info("Better Party Defence started; Hub Party session active={}", wasInParty);
		if (!isSpecialCounterActive()) {
			log.info("Special Attack Counter is disabled; Better Party Defence will detect and broadcast "
					+ "this client's supported defence specs itself.");
		}
	}

	@Override
	protected void shutDown() {
		partyEpoch++;
		clearRaidClock();
		if (defenceOverlay != null) {
			overlayManager.remove(defenceOverlay);
			defenceOverlay = null;
		}
		if (display != null) {
			display.shutdown();
			display = null;
		}
		encounterScopeResolver = null;
		tfaEncounters = null;
		toaEncounters = null;
		clearPartyCaches();
		clearEncounterSessionState();
		wsClient.unregisterMessage(BpdEncounterReset.class);
		wsClient.unregisterMessage(BpdKerisTimer.class);
		wsClient.unregisterMessage(BpdEncounterPresence.class);
		wsClient.unregisterMessage(BpdDefenceSync.class);
		wsClient.unregisterMessage(BpdSpecEvent.class);
		wsClient.unregisterMessage(BpdBossPresence.class);
		// Special Attack Counter still needs this message class while active.
		if (!isSpecialCounterActive()) {
			wsClient.unregisterMessage(SpecialCounterUpdate.class);
		}
		localSpecDetector.reset();
		defenceTracker.reset("plugin shutdown");
		wasInParty = false;
		log.info("Better Party Defence shut down");
	}

	// Clear caches from the previous party session.
	private void clearPartyCaches() {
		recentSpecEvents.clear();
		pendingSnapshots.clear();
		pendingPartySpecs.clear();
		bossesNeedingCatchUp.clear();
		pendingJoinHistoryOffers.clear();
		visibleTrackedBosses.clear();
		pendingKerisTimers.clear();
		recentSyncedSpecs.clear();
		senderEncounterPresence.clear();
		knownBpdPartyMembers.clear();
		initialSyncDisplayGate.clear();
		revalidateBossesAfterPartyJoin = false;
		if (toaEncounters != null) {
			toaEncounters.reset();
		}
		activeSyncScope = null;
		lastPresenceScope = null;
	}

	// Clear data tied to the current world and raid.
	private void clearEncounterSessionState() {
		retainedRaidScopes.clear();
		encounterResetTimes.clear();
		if (tfaEncounters != null) {
			tfaEncounters.reset();
		}
		syncStateVersions.clear();
	}

	private void clearRaidClock() {
		raidTimers().clear();
	}

	private RaidTimerCoordinator raidTimers() {
		if (raidTimers == null) {
			raidTimers = new RaidTimerCoordinator(client, defenceTracker, this::raidBegunScopeKey);
		}
		return raidTimers;
	}

	private EncounterScopeResolver encounterScopes() {
		if (encounterScopeResolver == null) {
			encounterScopeResolver = new EncounterScopeResolver(client, partyService);
		}
		return encounterScopeResolver;
	}

	private TfaEncounterCoordinator tfa() {
		if (tfaEncounters == null) {
			tfaEncounters = new TfaEncounterCoordinator(
					client,
					defenceTracker,
					(boss, reason) -> applyEncounterReset(boss, null, reason, true),
					this::removeInfoBox,
					initialSyncDisplayGate::clear);
		}
		return tfaEncounters;
	}

	private ToaEncounterCoordinator toa() {
		if (toaEncounters == null) {
			toaEncounters = new ToaEncounterCoordinator(client, defenceTracker,
					boss -> resetEncounterAndBroadcast(
							boss, currentRaidScopeForBoss(boss), "ToA full-party wipe"));
		}
		return toaEncounters;
	}

	private String raidBegunScopeKey() {
		return encounterScopes().raidBegunScopeKey();
	}

	@Subscribe(priority = -100f)
	public void onGameTick(GameTick event) {
		boolean inParty = partyService.isInParty();
		if (inParty != wasInParty) {
			log.debug("Hub Party session state changed: {}", inParty ? "joined" : "left");
			wasInParty = inParty;
		}

		int tick = client.getTickCount();
		raidTimers().tick(tick);
		recentSpecEvents.entrySet().removeIf(entry -> tick - entry.getValue() > DUPLICATE_WINDOW_TICKS);
		recentSyncedSpecs.prune(tick);
		long now = System.currentTimeMillis();
		senderEncounterPresence.entrySet().removeIf(
				entry -> now - entry.getValue().getReceivedAtMillis() > ENCOUNTER_PRESENCE_MAX_AGE_MILLIS);

		// Party membership is not required for local tracking.
		localSpecDetector.onGameTick();
		defenceTracker.onGameTick();
		tfa().onGameTick();
		initialSyncDisplayGate.expire(tick);
		releaseInitialSyncGatesForDrainedStates();
		reconcileAuthoritativeNpcDeaths();
		toa().onGameTick();
		reconcilePartySpecEvents();
		reconcileQueuedSnapshots();
		reconcileActiveSyncScope();
		maybeBroadcastEncounterPresence();
		broadcastLocalSpecEvents();
		reconcileVisibleBosses();
		reconcileRaidEncounterLifecycle();
		if (display != null) {
			display.settleKeris();
			reconcileKerisTimers();
		}
		if (display != null) {
			display.update(tfa().phaseOneEnded());
		}

	}

	// CoX starts on the shared raid notification, not lobby entry.
	@Subscribe
	public void onChatMessage(ChatMessage event) {
		raidTimers().onChatMessage(event);
	}

	// ToA/TFA root scripts expose raw elapsed ticks and stop state.
	// Display callbacks are not timer samples.
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event) {
		raidTimers().onScriptPreFired(event);
	}

	void updateRaidTimerClock(int tick) {
		raidTimers().pollCoxTimer(tick);
	}

	private String raidTimerScope(int source, WorldView worldView) {
		return raidTimers().timerScope(source, worldView);
	}

	// Prefer elapsed raid ticks.
	// Use the raid-begun tick when no timer is available.
	void applySharedRaidTickAnchor(int tick) {
		raidTimers().applySharedTickAnchor(tick);
	}

	void updateSharedRaidTickAnchor(int tick) {
		raidTimers().updateFallbackTickAnchor(tick);
	}

	// Request defence history when a boss appears.
	// No click or interaction is required.
	@Subscribe
	public void onNpcSpawned(NpcSpawned event) {
		if (event != null) {
			tfa().onNpcSpawned(event.getNpc());
			visibleTrackedBosses.remove(BossDefence.matchingNpc(event.getNpc()));
		}
	}

	@Subscribe
	public void onFakeXpDrop(FakeXpDrop event) {
		localSpecDetector.onFakeXpDrop(event);
	}

	@Subscribe
	public void onStatChanged(StatChanged event) {
		localSpecDetector.onStatChanged(event);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		localSpecDetector.onVarbitChanged(event);
		if (display != null && event.getVarpId() == VarPlayerID.SA_ENERGY) {
			display.onSpecialEnergy(event.getValue());
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event) {
		localSpecDetector.onHitsplatApplied(event);
		if (display != null) {
			display.onHitsplatApplied(event);
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event) {
		if (display != null) {
			display.onAnimationChanged(event.getActor());
		}
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event) {
		if (display != null) {
			display.onProjectileMoved(event.getProjectile());
		}
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event) {
		if (display != null) {
			display.onGraphicChanged(event);
		}
	}

	@Subscribe
	public void onPartyChanged(PartyChanged event) {
		partyEpoch++;
		Long changedPartyId = event.getPartyId();
		boolean partySessionChanged = !Objects.equals(currentPartyId, changedPartyId);
		currentPartyId = changedPartyId;
		if (partySessionChanged) {
			// Clear version and sync data when the party changes.
			syncStateVersions.clear();
		}
		if (defenceTracker != null) {
			defenceTracker.clearStandardSpecCorrelation();
		}
		// Party leave does not end the encounter.
		// Clear party caches only.
		clearPartyCaches();
		wasInParty = changedPartyId != null;
		log.debug("Hub Party session changed: partyId={}", changedPartyId);
	}

	@Subscribe
	public void onUserJoin(UserJoin event) {
		PartyMember localMember = partyService.getLocalMember();
		if (localMember == null || event.getMemberId() != localMember.getMemberId()
				|| event.getPartyId() != partyService.getPartyId()) {
			return;
		}

		long receivedInPartyEpoch = partyEpoch;
		clientThread.invoke(() -> {
			if (receivedInPartyEpoch == partyEpoch) {
				revalidateBossesAfterPartyJoin = true;
			}
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState state = event.getGameState();
		if (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST) {
			partyEpoch++;
			clearRaidClock();
			localSpecDetector.reset();
			defenceTracker.reset("game state " + state);
			if (display != null) {
				display.reset();
			}
			clearPartyCaches();
			clearEncounterSessionState();
		}
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event) {
		if (!(event.getPlugin() instanceof SpecialCounterPlugin)) {
			return;
		}

		if (event.isLoaded()) {
			log.debug("Special Attack Counter enabled: it will own the standard party spec broadcast");
		} else {
			// SAC unregisters on shutdown; keep decoding peer specs.
			wsClient.registerMessage(SpecialCounterUpdate.class);
			log.debug(
					"Special Attack Counter disabled: Better Party Defence will broadcast supported local drains itself");
		}
	}

	// Receive standard Special Attack Counter specs.
	// The built-in Party sidebar is not required.
	@Subscribe
	public void onSpecialCounterUpdate(SpecialCounterUpdate event) {
		if (!partyService.isInParty()) {
			return;
		}

		PartyMember localMember = partyService.getLocalMember();
		if (localMember != null && localMember.getMemberId() == event.getMemberId()) {
			// Local detector already applied this spec.
			return;
		}

		PartyMember sender = partyService.getMemberById(event.getMemberId());
		if (sender == null) {
			// Ignore packets from departed senders.
			return;
		}
		SpecialWeapon weapon = event.getWeapon();
		if (!DefenceTracker.isSupportedWeapon(weapon)) {
			return;
		}

		String senderName = sender.getDisplayName();
		// Move websocket callbacks onto the client thread.
		final long receivedInPartyEpoch = partyEpoch;
		clientThread.invoke(() -> {
			if (receivedInPartyEpoch != partyEpoch || !partyService.isInParty()
					|| partyService.getMemberById(event.getMemberId()) == null) {
				return;
			}

			if (!standardPartySpecScopeMatches(
					event.getMemberId(), event.getWorld(), event.getNpcIndex())) {
				return;
			}

			SpecEventKey key = new SpecEventKey(
					event.getMemberId(), event.getWorld(), event.getNpcIndex(), event.getPlayerId(), weapon,
					event.getHit());
			int tick = client.getTickCount();
			Integer lastSeen = recentSpecEvents.get(key);
			if (lastSeen != null && tick - lastSeen <= DUPLICATE_WINDOW_TICKS) {
				log.debug("Ignoring duplicate party spec event {}", key);
				return;
			}
			SyncedSpecKey syncedKey = new SyncedSpecKey(event.getWorld(), event.getNpcIndex(),
					EncounterTimeline.normalizedPlayer(senderName), weapon,
					DefenceTracker.specCorrelationHit(weapon, event.getHit()));
			if (recentSyncedSpecs.consume(syncedKey, tick)) {
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
	public void onConfigChanged(ConfigChanged event) {
		if (!BetterPartyDefenceConfig.GROUP.equals(event.getGroup())) {
			return;
		}

		if (BetterPartyDefenceConfig.KEY_INFO_BOX_THEME_ICON.equals(event.getKey())) {
			if (display != null && display.hasDefenceInfoBoxes()) {
				display.removeAll();
				display.update(tfa().phaseOneEnded());
			}
			return;
		}

		if (BetterPartyDefenceConfig.KEY_DEFENCE_FONT.equals(event.getKey())
				&& config.defenceFont() == TrackerFont.ADD_CUSTOM) {
			new CustomFontChooser(config, configManager).choose();
		}
	}

	@Subscribe
	public void onBpdEncounterPresence(BpdEncounterPresence event) {
		if (!partyService.isInParty()
				|| event == null || event.getProtocolVersion() != BpdEncounterPresence.PROTOCOL_VERSION
				|| !partyMessageBelongsToCurrentParty(event.getPartyId())) {
			return;
		}
		if (!isCurrentRemoteSender(event)) {
			return;
		}

		final long receivedInPartyEpoch = partyEpoch;
		clientThread.invoke(() -> {
			if (receivedInPartyEpoch != partyEpoch) {
				return;
			}
			rememberSenderEncounterPresence(event.getMemberId(), event.getWorld(),
					event.getScopeType(), event.getScopeId(), event.getSentAtMillis());
		});
	}

	// Reply when another member asks for defence history.
	// Peers can answer from remembered state.
	@Subscribe
	public void onBpdBossPresence(BpdBossPresence event) {
		if (!partyService.isInParty()
				|| event == null || event.getProtocolVersion() != BpdBossPresence.PROTOCOL_VERSION
				|| !partyMessageBelongsToCurrentParty(event.getPartyId())) {
			return;
		}
		if (!isCurrentRemoteSender(event)) {
			return;
		}

		final long receivedInPartyEpoch = partyEpoch;
		clientThread.invoke(() -> {
			if (receivedInPartyEpoch != partyEpoch) {
				return;
			}
			knownBpdPartyMembers.add(event.getMemberId());
			if (event.getWorld() != client.getWorld()) {
				return;
			}

			if (!isFresh(event.getSentAtMillis())) {
				return;
			}

			BossDefence boss = event.toBossType();
			if (boss == null) {
				return;
			}
			DefenceTracker.SyncState sync;
			if (event.getScopeType() == BpdDefenceSync.SCOPE_WORLD && !isRaidEncounterBoss(boss)) {
				if (event.getScopeId() != 0) {
					return;
				}
				WorldView view = client.getTopLevelWorldView();
				if (view != null && !view.isInstance()) {
					sync = defenceTracker.syncStateForBoss(boss);
				} else {
					if (!isPublicFallbackResponder(event.getMemberId())) {
						return;
					}
					WorldPoint requestedTile = new WorldPoint(event.getBossX(), event.getBossY(), event.getBossPlane());
					sync = defenceTracker.publicSyncStateForBoss(boss, event.getWorld(), requestedTile,
							EncounterScopes.POSITION_TOLERANCE);
				}
			} else {
				if (!bossPresenceScopeMatches(event, boss)) {
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
							new WorldPoint(event.getBossX(), event.getBossY(), event.getBossPlane()))) {
				offeredState = offered.toSyncState();
				if (offeredState != null && offeredState.isDrained()) {
					offered.setMemberId(event.getMemberId());
					onBpdDefenceSync(offered);
				}
			}

			if (sync == null || !sync.isDrained() || historyCovers(offeredState, sync)) {
				return;
			}

			// Return the requester's boss tile so it can attach the state.
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

	private static boolean historyCovers(DefenceTracker.SyncState offered, DefenceTracker.SyncState local) {
		if (offered == null || !offered.isDrained()) {
			return false;
		}
		List<DefenceTracker.SpecHistoryEntry> union = EncounterTimeline.union(offered.getHistory(), local.getHistory());
		if (union != null) {
			return union.size() == offered.getHistory().size();
		}
		EncounterTimeline.HistoryRelation relation = EncounterTimeline.compareLegacy(local, offered);
		return relation == EncounterTimeline.HistoryRelation.SAME
				|| relation == EncounterTimeline.HistoryRelation.EXTENSION;
	}

	// Allow only one peer outside the encounter to reply.
	private boolean isPublicFallbackResponder(long requester) {
		PartyMember local = partyService.getLocalMember();
		if (local == null) {
			return false;
		}
		for (PartyMember member : partyService.getMembers()) {
			if (member.getMemberId() != requester && member.getMemberId() < local.getMemberId()) {
				return false;
			}
		}
		return true;
	}

	void broadcastKeris(NPC npc, int hitTick, long hitAtMillis) {
		if (!partyService.isInParty()) {
			return;
		}

		BossDefence boss = BossDefence.matchingNpc(npc);
		SyncScope scope = boss == null ? null : localSyncScope(boss, npc);
		WorldPoint point = npc.getWorldLocation();
		if (scope == null || point == null || !peerSyncAllowedAtCurrentEncounter(boss)) {
			return;
		}

		long raidTick = raidTimers().isActiveFor(RaidTimerClock.SOURCE_TOA)
				? raidTimers().tickAt(hitTick)
				: RaidTimerClock.NO_TICK;
		String clockScope = raidTick >= 0
				? raidTimerScope(RaidTimerClock.SOURCE_TOA,
						client.getTopLevelWorldView())
				: null;
		rememberRaidScope(boss, scope);
		partyService.send(new BpdKerisTimer(partyService.getPartyId(), client.getWorld(), boss,
				scope.getType(), scope.getId(), npc.getId(), point, hitAtMillis, clockScope, raidTick));
	}

	@Subscribe
	public void onBpdKerisTimer(BpdKerisTimer event) {
		if (event == null || !partyService.isInParty()
				|| event.getProtocolVersion() != BpdKerisTimer.PROTOCOL_VERSION
				|| !partyMessageBelongsToCurrentParty(event.getPartyId())) {
			return;
		}
		if (!isCurrentRemoteSender(event)) {
			return;
		}

		long receivedInPartyEpoch = partyEpoch;
		clientThread.invoke(() -> {
			if (receivedInPartyEpoch != partyEpoch || !acceptsKerisTimer(event)) {
				return;
			}
			queueBounded(pendingKerisTimers, event, 32);
		});
	}

	private boolean acceptsKerisTimer(BpdKerisTimer event) {
		return partyService.isInParty()
				&& partyMessageBelongsToCurrentParty(event.getPartyId())
				&& partyService.getMemberById(event.getMemberId()) != null
				&& event.getWorld() == client.getWorld() && event.getBoss() != null;
	}

	private void reconcileKerisTimers() {
		if (pendingKerisTimers.isEmpty()) {
			return;
		}

		int tick = client.getTickCount();
		long now = System.currentTimeMillis();
		WorldView worldView = client.getTopLevelWorldView();
		long raidTick = raidTimers().isActiveFor(RaidTimerClock.SOURCE_TOA)
				? raidTimers().tickAt(tick)
				: RaidTimerClock.NO_TICK;
		String clockScope = raidTick >= 0 ? raidTimerScope(RaidTimerClock.SOURCE_TOA, worldView) : null;
		for (Iterator<BpdKerisTimer> it = pendingKerisTimers.iterator(); it.hasNext();) {
			BpdKerisTimer timer = it.next();
			BossDefence boss = timer.getBoss();
			if (!acceptsKerisTimer(timer)) {
				it.remove();
				continue;
			}
			NPC npc = TargetResolver.uniqueLiveNpcNear(worldView, boss,
					timer.getBossX(), timer.getBossY(), timer.getBossPlane(), EncounterScopes.POSITION_TOLERANCE);
			SyncScope scope = localSyncScope(boss, npc);
			int remaining = timer.remainingTicks(now, clockScope, raidTick);
			Long resetAt = encounterResetTimes.get(new EncounterResetKey(client.getWorld(), boss,
					timer.getScopeType(), timer.getScopeId()));
			if (remaining <= 0 || scope == null || scope.getType() != timer.getScopeType()
					|| scope.getId() != timer.getScopeId() || !peerSyncAllowedAtCurrentEncounter(boss)
					|| resetAt != null && timer.getHitAtMillis() <= resetAt) {
				it.remove();
				continue;
			}
			if (npc != null) {
				it.remove();
				if (npc.getId() == timer.getNpcId()) {
					rememberRaidScope(boss, scope);
					display.applyKerisTimer(npc, tick + remaining, timer.getHitAtMillis());
				}
			}
		}
	}

	// Reset a boss when another member reports the encounter ended.
	@Subscribe
	public void onBpdEncounterReset(BpdEncounterReset event) {
		if (!partyService.isInParty()
				|| event == null || event.getProtocolVersion() != BpdEncounterReset.PROTOCOL_VERSION
				|| !partyMessageBelongsToCurrentParty(event.getPartyId())) {
			return;
		}
		if (!isCurrentRemoteSender(event)) {
			return;
		}

		final long receivedInPartyEpoch = partyEpoch;
		clientThread.invoke(() -> {
			if (receivedInPartyEpoch != partyEpoch) {
				return;
			}
			knownBpdPartyMembers.add(event.getMemberId());
			if (!partyService.isInParty()
					|| partyService.getMemberById(event.getMemberId()) == null
					|| event.getWorld() != client.getWorld()) {
				return;
			}

			if (!isFresh(event.getSentAtMillis())) {
				return;
			}

			BossDefence boss = event.toBossType();
			if (boss == null || !incomingEncounterResetScopeMatches(event, boss)) {
				return;
			}

			rememberSenderEncounterPresence(event.getMemberId(), event.getWorld(),
					event.getScopeType(), event.getScopeId(), event.getSentAtMillis());
			applyEncounterReset(boss, new SyncScope(event.getScopeType(), event.getScopeId()),
					"party encounter reset from member " + event.getMemberId(), false, event.getSentAtMillis());
		});
	}

	private void maybeBroadcastEncounterPresence() {
		if (!partyService.isInParty()
				|| client.getGameState() != GameState.LOGGED_IN) {
			lastPresenceScope = null;
			return;
		}

		SyncScope scope = currentEncounterScope();
		if (scope == null || scope.equals(lastPresenceScope)) {
			return;
		}

		// Tell peers when the encounter changes, not on idle-world entry.
		if (lastPresenceScope == null && scope.getType() == BpdDefenceSync.SCOPE_WORLD && !supportedBossVisible()) {
			return;
		}

		partyService.send(new BpdEncounterPresence(client.getWorld(), scope.getType(), scope.getId())
				.withPartyId(partyService.getPartyId()));
		lastPresenceScope = scope;
	}

	// Check for a live supported boss.
	private boolean supportedBossVisible() {
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || worldView.npcs() == null) {
			return false;
		}

		Iterator<? extends NPC> iterator = worldView.npcs().iterator();
		while (iterator != null && iterator.hasNext()) {
			NPC npc = iterator.next();
			if (BossDefence.isLiveNpc(npc) && npc.getName() != null && BossDefence.matchingNpc(npc) != null) {
				return true;
			}
		}
		return false;
	}

	private boolean isCurrentRemoteSender(PartyMemberMessage event) {
		if (!partyService.isInParty() || event == null
				|| partyService.getMemberById(event.getMemberId()) == null) {
			return false;
		}
		PartyMember local = partyService.getLocalMember();
		return local == null || local.getMemberId() != event.getMemberId();
	}

	private static boolean isFresh(long sentAtMillis) {
		return isFresh(sentAtMillis, System.currentTimeMillis());
	}

	private static boolean isFresh(long sentAtMillis, long now) {
		return sentAtMillis > 0L && Math.abs(now - sentAtMillis) <= SYNC_MAX_AGE_MILLIS;
	}

	private static <T> void queueBounded(Deque<T> queue, T value, int capacity) {
		if (queue.size() >= capacity) {
			queue.removeFirst();
		}
		queue.addLast(value);
	}

	private boolean partyMessageBelongsToCurrentParty(long eventPartyId) {
		if (eventPartyId == 0L) {
			// Apply the existing world and encounter checks to old packets.
			return true;
		}
		if (!partyService.isInParty()) {
			return false;
		}
		long livePartyId = partyService.getPartyId();
		if (livePartyId != 0L) {
			return eventPartyId == livePartyId;
		}
		// Use the last PartyChanged id while PartyService briefly reports zero.
		// Unknown sessions keep legacy compatibility.
		return currentPartyId == null || currentPartyId == 0L || eventPartyId == currentPartyId;
	}

	private void rememberSenderEncounterPresence(long memberId, int world, int scopeType, int scopeId,
			long sentAtMillis) {
		// Valid BPD messages identify BPD peers for this session.
		knownBpdPartyMembers.add(memberId);
		if (world != client.getWorld() || sentAtMillis <= 0) {
			return;
		}
		long age = Math.abs(System.currentTimeMillis() - sentAtMillis);
		if (age > ENCOUNTER_PRESENCE_MAX_AGE_MILLIS) {
			return;
		}
		SenderEncounterPresence existing = senderEncounterPresence.get(memberId);
		if (existing != null && sentAtMillis < existing.getSentAtMillis()) {
			log.debug("Ignoring out-of-order encounter heartbeat from member={} sent={} current={}",
					memberId, sentAtMillis, existing.getSentAtMillis());
			return;
		}
		senderEncounterPresence.put(memberId, new SenderEncounterPresence(
				world, scopeType, scopeId, System.currentTimeMillis(), sentAtMillis));
	}

	// Match special attacks to the current fight.
	// If heartbeat data is missing, use world and target information.
	private boolean standardPartySpecScopeMatches(long memberId, int eventWorld, int npcIndex) {
		SenderEncounterPresence senderScope = senderEncounterPresence.get(memberId);
		if (senderScope == null) {
			return standardPartySpecWithoutFreshHeartbeat(
					memberId, eventWorld, npcIndex, "missing");
		}

		long age = System.currentTimeMillis() - senderScope.getReceivedAtMillis();
		if (age > ENCOUNTER_PRESENCE_MAX_AGE_MILLIS) {
			senderEncounterPresence.remove(memberId);
			return standardPartySpecWithoutFreshHeartbeat(
					memberId, eventWorld, npcIndex, "stale (" + age + "ms)");
		}
		if (eventWorld != client.getWorld() || senderScope.getWorld() != client.getWorld()) {
			log.debug("Ignoring BPD party spec from member={} due to world mismatch sender={} event={} local={}",
					memberId, senderScope.getWorld(), eventWorld, client.getWorld());
			return false;
		}

		SyncScope localScope = currentEncounterScope();
		boolean match = localScope != null
				&& localScope.getType() == senderScope.getScopeType()
				&& localScope.getId() == senderScope.getScopeId();
		if (!match && senderScope.getScopeType() == BpdDefenceSync.SCOPE_INSTANCE) {
			WorldView worldView = client.getTopLevelWorldView();
			match = worldView != null && worldView.isInstance()
					&& instanceFingerprint(worldView) == senderScope.getScopeId();
		}
		if (!match && standardPartySpecTargetEvidenceMatches(npcIndex)) {
			// Different instance ids do not always mean different fights.
			// One unique local target can safely resolve a mismatch.
			log.debug("Accepting BPD party spec from member={} despite scope mismatch sender={}:{} local={}: "
					+ "one unique local target is present",
					memberId, senderScope.getScopeType(), senderScope.getScopeId(), localScope);
			return true;
		}
		if (!match) {
			log.debug("Ignoring BPD party spec from member={} due to encounter mismatch sender={}:{} local={}",
					memberId, senderScope.getScopeType(), senderScope.getScopeId(), localScope);
		}
		return match;
	}

	// Missing encounter data does not invalidate a spec.
	// Keep a unique target; save absent or ambiguous specs for later.
	private boolean standardPartySpecWithoutFreshHeartbeat(
			long memberId, int eventWorld, int npcIndex, String heartbeatState) {
		if (eventWorld != client.getWorld()) {
			log.debug("Ignoring party spec from member={} due to wrong world event={} local={}",
					memberId, eventWorld, client.getWorld());
			return false;
		}

		if (knownBpdPartyMembers.contains(memberId)) {
			if (standardPartySpecTargetEvidenceMatches(npcIndex)) {
				log.debug("Accepting BPD party spec from member={} with {} heartbeat and one unique local target",
						memberId, heartbeatState);
			} else {
				log.debug("Retaining BPD party spec from member={} with {} heartbeat for deferred target association",
						memberId, heartbeatState);
			}
		}
		return true;
	}

	// Accept different instance ids only with one local target.
	// The sender does not need to be visible.
	private boolean standardPartySpecTargetEvidenceMatches(int npcIndex) {
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || worldView.npcs() == null) {
			return false;
		}

		NPC indexedTarget = worldView.npcs().byIndex(npcIndex);
		if (indexedTarget == null || !BossDefence.isLiveNpc(indexedTarget)) {
			return false;
		}
		BossDefence boss = BossDefence.matchingNpc(indexedTarget);
		if (boss == null || TargetResolver.uniqueLiveNpc(worldView, boss) != indexedTarget) {
			return false;
		}
		return true;
	}

	// Receive an absolute BPD snapshot.
	@Subscribe
	public void onBpdDefenceSync(BpdDefenceSync event) {
		if (!partyService.isInParty()
				|| event == null || !event.isSupportedProtocol()
				|| !partyMessageBelongsToCurrentParty(event.getPartyId())) {
			return;
		}
		if (!isCurrentRemoteSender(event)) {
			return;
		}

		final long receivedInPartyEpoch = partyEpoch;
		clientThread.invoke(() -> {
			if (receivedInPartyEpoch != partyEpoch || !partyService.isInParty()
					|| partyService.getMemberById(event.getMemberId()) == null) {
				return;
			}
			knownBpdPartyMembers.add(event.getMemberId());
			rememberSenderEncounterPresence(event.getMemberId(), event.getWorld(),
					event.getScopeType(), event.getScopeId(), event.getSentAtMillis());
			queueBounded(pendingSnapshots, event, 128);
		});
	}

	// Process queued local and standard specs first.
	private void reconcileQueuedSnapshots() {
		// Avoid blocking unrelated bosses.
		int count = pendingSnapshots.size();
		for (int i = 0; i < count; i++) {
			BpdDefenceSync snapshot = pendingSnapshots.pollFirst();
			if (partyService.getMemberById(snapshot.getMemberId()) == null) {
				continue;
			}
			DefenceTracker.SyncState state = snapshot.toSyncState();
			if (state == null) {
				continue;
			}
			if (defenceTracker.hasUnprocessedSpecsForBoss(state.getBossType())) {
				pendingSnapshots.addLast(snapshot);
				continue;
			}
			acceptBpdDefenceSync(snapshot);
		}
	}

	private void acceptBpdDefenceSync(BpdDefenceSync event) {
		if (!partyService.isInParty()
				|| event.getWorld() != client.getWorld()) {
			return;
		}
		if (!isFresh(event.getSentAtMillis())) {
			log.debug("Ignoring stale BPD sync sentAt={}", event.getSentAtMillis());
			return;
		}

		DefenceTracker.SyncState sync = event.toSyncState();
		if (sync == null || sync.getBossType() == null || !sync.isDrained()) {
			return;
		}

		BossDefence boss = sync.getBossType();
		if (boss == BossDefence.TFA_OCELLUS_P1 && tfa().phaseOneEnded()) {
			return; // A late party snapshot must not bring back P1 in P2/P3.
		}
		NPC localBoss = findLiveNpcForBoss(boss, event);
		if (localBoss != null && !peerSyncAllowedAtCurrentEncounter(boss)) {
			log.debug("Ignoring BPD sync for {} in a solo/single-combat encounter", boss);
			return;
		}
		if (snapshotPredatesReset(event, sync)) {
			log.debug("Ignoring BPD sync for {} with history from before the encounter reset", boss);
			return;
		}

		// Open-world snapshots require a visible boss NPC.
		// Wait for the boss before applying another player's open-world state.
		if (event.getScopeType() == BpdDefenceSync.SCOPE_WORLD && localBoss == null) {
			// The normal boss-presence request will recover it later.
			return;
		}

		if (!incomingScopeMatches(event, boss, localBoss)) {
			return;
		}

		// Keep independent targets such as Olm claws separate.
		// The tracker saves the current target before switching.
		if (!incomingVersionIsAcceptable(event, sync, localBoss)) {
			return;
		}
		if (!incomingSyncIsUseful(sync, localBoss, event.getStateVersion())) {
			return;
		}

		applyAcceptedSync(event, sync, localBoss);
	}

	// A dead public NPC ends its encounter.
	// Clear saved encounter data and tell peers before respawn.
	private void reconcileAuthoritativeNpcDeaths() {
		for (BossDefence boss : defenceTracker.consumeEndedPublicBosses()) {
			resetEncounterAndBroadcast(boss, new SyncScope(BpdDefenceSync.SCOPE_WORLD, 0), "local NPC death");
		}
		for (BossDefence boss : defenceTracker.consumeEndedBosses()) {
			if (boss == null || isRaidEncounterBoss(boss)) {
				continue;
			}
			resetEncounterAndBroadcast(boss, currentEncounterScope(), "local NPC death");
		}
	}

	private void resetEncounterAndBroadcast(BossDefence boss, SyncScope scope, String reason) {
		BpdEncounterReset event = scope == null
				? null
				: new BpdEncounterReset(client.getWorld(), boss, scope.getType(), scope.getId());
		long resetAt = event == null ? System.currentTimeMillis() : event.getSentAtMillis();
		if (!applyEncounterReset(boss, scope, reason, true, resetAt)) {
			return;
		}

		if (event != null && partyService.isInParty()) {
			partyService.send(event.withPartyId(partyService.getPartyId()));
			log.debug("Broadcast BPD encounter reset boss={} scope={}:{} reason={}",
					boss, scope.getType(), scope.getId(), reason);
		}
	}

	// Clear defence, history, and party bookkeeping together.
	private boolean applyEncounterReset(BossDefence boss, SyncScope scope, String reason, boolean authoritativeLocal) {
		return applyEncounterReset(boss, scope, reason, authoritativeLocal, System.currentTimeMillis());
	}

	private boolean applyEncounterReset(BossDefence boss, SyncScope scope, String reason,
			boolean authoritativeLocal, long resetAt) {
		if (boss == null) {
			return false;
		}

		EncounterResetKey key = scope == null
				? null
				: new EncounterResetKey(client.getWorld(), boss, scope.getType(), scope.getId());
		if (key != null) {
			Long lastReset = encounterResetTimes.get(key);
			if (!authoritativeLocal && lastReset != null && resetAt <= lastReset) {
				return false;
			}
			encounterResetTimes.merge(key, resetAt, Math::max);
			if (!authoritativeLocal) {
				NPC liveBoss = findLiveNpcForBoss(boss);
				DefenceTracker.SyncState current = liveBoss == null ? null : defenceTracker.syncStateForActor(liveBoss);
				if (current != null && scopeMatches(scope.getType(), scope.getId(), boss)) {
					List<DefenceTracker.SpecHistoryEntry> surviving = current.getHistory().stream()
							.filter(spec -> spec.getObservedAtMillis() > resetAt)
							.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
					if (surviving.size() != current.getHistory().size() && EncounterTimeline.complete(surviving)) {
						defenceTracker.replaySpecsAfterReset(liveBoss, surviving);
						syncStateVersions.remove(boss);
						return true;
					}
					if (!surviving.isEmpty() && surviving.size() == current.getHistory().size()) {
						return false; // The next encounter already has accepted spec data.
					}
				}
			}
		}

		WorldView view = client.getTopLevelWorldView();
		if (scope != null && scope.getType() == BpdDefenceSync.SCOPE_WORLD
				&& !isRaidEncounterBoss(boss) && (view == null || view.isInstance())) {
			defenceTracker.clearPublicState(boss, client.getWorld(), reason);
			pendingSnapshots.removeIf(snapshot -> boss.name().equals(snapshot.getBossType())
					&& snapshot.getScopeType() == BpdDefenceSync.SCOPE_WORLD
					&& !snapshotHasOnlyNewSpecs(snapshot, resetAt));
			pendingPartySpecs.removeIf(spec -> spec.getBoss() == boss
					&& spec.getScopeType() == BpdDefenceSync.SCOPE_WORLD && !specWasObservedAfter(spec, resetAt));
			return true;
		}
		if (scope != null && scope.getType() == BpdDefenceSync.SCOPE_INSTANCE && !isRaidEncounterBoss(boss)) {
			defenceTracker.clearInstancedBossState(boss, reason);
		} else {
			defenceTracker.clearBossState(boss, reason);
		}
		clearBossScopedReconciliationMetadata(boss, scope, resetAt);
		log.debug("Reset encounter state for {}: {}", boss, reason);
		return true;
	}

	// Remove bookkeeping for one ended encounter.
	// The tracker owns boss lifetime.
	private void clearBossScopedReconciliationMetadata(BossDefence boss) {
		clearBossScopedReconciliationMetadata(boss, null, 0);
	}

	private void clearBossScopedReconciliationMetadata(BossDefence boss, SyncScope scope, long resetAt) {
		if (boss == null) {
			return;
		}
		if (display != null) {
			display.clearBoss(boss);
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
		syncStateVersions.remove(boss);
		retainedRaidScopes.remove(boss);
		initialSyncDisplayGate.clearForBoss(boss);
		if (activeSyncScope != null && activeSyncScope.getBoss() == boss) {
			activeSyncScope = null;
		}
	}

	private static boolean specWasObservedAfter(BpdSpecEvent spec, long resetAt) {
		Long observedAt = spec.getObservedAt();
		return observedAt != null && observedAt > resetAt && observedAt <= spec.getSentAt();
	}

	private static boolean snapshotHasOnlyNewSpecs(BpdDefenceSync snapshot, long resetAt) {
		DefenceTracker.SyncState state = snapshot.toSyncState();
		return state != null && !state.getHistory().isEmpty()
				&& state.getHistory().stream().allMatch(spec -> spec.getObservedAtMillis() > resetAt);
	}

	private boolean observationPredatesReset(BossDefence boss, int scopeType, int scopeId, long observedAt) {
		Long resetAt = encounterResetTimes.get(new EncounterResetKey(client.getWorld(), boss, scopeType, scopeId));
		// Untimestamped observations cannot cross a reset.
		return resetAt != null && observedAt <= resetAt;
	}

	private boolean snapshotPredatesReset(BpdDefenceSync event, DefenceTracker.SyncState state) {
		// Fresh packets can still contain pre-reset hits.
		return state.getHistory().stream().anyMatch(spec -> observationPredatesReset(
				state.getBossType(), event.getScopeType(), event.getScopeId(), spec.getObservedAtMillis()));
	}

	private boolean incomingEncounterResetScopeMatches(BpdEncounterReset event, BossDefence boss) {
		int expectedRaidScope = raidScopeType(boss);
		if (expectedRaidScope != -1) {
			return event.getScopeType() == expectedRaidScope
					&& event.getScopeId() > 0
					&& raidController(expectedRaidScope) == event.getScopeId();
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (event.getScopeType() == BpdDefenceSync.SCOPE_WORLD) {
			return event.getScopeId() == 0 && (worldView != null && !worldView.isInstance()
					|| !isRaidEncounterBoss(boss) && defenceTracker.hasPublicState(boss, event.getWorld()));
		}
		if (worldView == null) {
			return false;
		}
		if (event.getScopeType() == BpdDefenceSync.SCOPE_INSTANCE) {
			return worldView.isInstance() && instanceFingerprint(worldView) == event.getScopeId();
		}
		return false;
	}

	// Group missed-history requests into one catch-up pass.
	private void reconcileVisibleBosses() {
		if (!partyService.isInParty() || partyService.getLocalMember() == null
				|| client.getGameState() != GameState.LOGGED_IN) {
			return;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || worldView.npcs() == null) {
			return;
		}

		if (revalidateBossesAfterPartyJoin) {
			pendingJoinHistoryOffers.addAll(defenceTracker.drainedBosses());
		}
		Set<BossDefence> seen = new HashSet<>();
		Iterator<? extends NPC> iterator = worldView.npcs().iterator();
		while (iterator != null && iterator.hasNext()) {
			reconcileVisibleBoss(iterator.next(), seen);
		}
		for (BossDefence boss : visibleTrackedBosses) {
			if (!seen.contains(boss) && defenceTracker.hasRememberedState(boss)) {
				bossesNeedingCatchUp.add(boss);
			}
		}
		visibleTrackedBosses.clear();
		visibleTrackedBosses.addAll(seen);
		revalidateBossesAfterPartyJoin = false;
	}

	private void reconcileVisibleBoss(NPC npc, Set<BossDefence> seen) {
		if (!BossDefence.isLiveNpc(npc) || npc.getName() == null) {
			return;
		}

		BossDefence boss = BossDefence.matchingNpc(npc);
		if (!peerSyncAllowedAtCurrentEncounter(boss) || !seen.add(boss)) {
			return;
		}

		boolean offerOnJoin = pendingJoinHistoryOffers.contains(boss);
		boolean revalidate = revalidateBossesAfterPartyJoin || offerOnJoin || bossesNeedingCatchUp.contains(boss);
		if (!revalidate && visibleTrackedBosses.contains(boss)) {
			return;
		}

		DefenceTracker.SyncState local = defenceTracker.syncStateForBoss(boss);
		if (!revalidate && local != null && local.isDrained()) {
			return;
		}

		SyncScope scope = localSyncScope(boss, npc);
		WorldPoint point = npc.getWorldLocation();
		if (scope == null || point == null) {
			// Wait for the NPC position and instance id.
			seen.remove(boss);
			if (revalidate) {
				bossesNeedingCatchUp.add(boss);
			}
			return;
		}

		if (!revalidate) {
			initialSyncDisplayGate.arm(npc.getIndex(), boss, client.getTickCount(),
					INITIAL_SYNC_DISPLAY_GRACE_TICKS);
		}
		BpdBossPresence request = new BpdBossPresence(
				client.getWorld(), boss, scope.getType(), scope.getId(),
				point.getX(), point.getY(), point.getPlane(), healthPercent(npc))
				.withPartyId(partyService.getPartyId());
		DefenceTracker.SyncState offer = revalidate ? defenceTracker.syncStateForActor(npc) : null;
		if (offer != null && offer.isDrained()) {
			request.offerHistory(new BpdDefenceSync(client.getWorld(), offer, scope.getType(), scope.getId(),
					point.getX(), point.getY(), point.getPlane(), request.getHealthPercent(),
					observeLocalStateVersion(offer)));
		}
		partyService.send(request);
		pendingJoinHistoryOffers.remove(boss);
		bossesNeedingCatchUp.remove(boss);
		log.debug("Broadcast BPD boss presence boss={} scope={}:{} actor={}",
				boss, scope.getType(), scope.getId(), npc.getIndex());
	}

	// Share state only in raids or multicombat worlds.
	// Keep solo and private instances isolated.
	private boolean peerSyncAllowedAtCurrentEncounter(BossDefence boss) {
		if (boss == null) {
			return false;
		}
		if (isRaidEncounterBoss(boss)) {
			return true;
		}

		return client.getVarbitValue(VarbitID.MULTIWAY_INDICATOR) != 0;
	}

	private boolean bossPresenceScopeMatches(BpdBossPresence event, BossDefence boss) {
		if (event.getWorld() != client.getWorld()) {
			return false;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null) {
			return false;
		}

		if (event.getScopeType() == BpdDefenceSync.SCOPE_WORLD) {
			// Match open-world encounters by boss, world, and recent time.
			// Peers can still answer after leaving render.
			return !worldView.isInstance();
		}

		SyncScope localScope = localSyncScope(boss, null);
		return localScope != null
				&& localScope.getType() == event.getScopeType()
				&& localScope.getId() == event.getScopeId();
	}

	// Send each local spec once.
	private void broadcastLocalSpecEvents() {
		if (!partyService.isInParty()) {
			return;
		}
		for (DefenceTracker.AppliedSpec applied : defenceTracker.localSpecs()) {
			NPC npc = applied.getNpc();
			BossDefence boss = applied.getBoss();
			if (!BossDefence.isLiveNpc(npc) || !peerSyncAllowedAtCurrentEncounter(boss)
					|| !defenceTracker.hasPartySpec(npc, applied.getEntry().getEventId())) {
				continue;
			}
			SyncScope scope = localSyncScope(boss, npc);
			WorldPoint point = npc.getWorldLocation();
			Integer tile = point == null
					? null
					: BpdDefenceSync.packBossLocation(point.getX(), point.getY(), point.getPlane());
			if (scope == null || tile == null) {
				continue;
			}
			rememberRaidScope(boss, scope);
			partyService.send(new BpdSpecEvent(client.getWorld(), scope.getType(), scope.getId(), boss,
					npc.getIndex(), tile, scope.getType() == BpdDefenceSync.SCOPE_WORLD ? healthPercent(npc) : -1,
					applied.getEntry(), applied.getPreviousId()));
		}
	}

	@Subscribe
	public void onBpdSpecEvent(BpdSpecEvent event) {
		if (event == null || !isCurrentRemoteSender(event)) {
			return;
		}

		long epoch = partyEpoch;
		clientThread.invoke(() -> {
			if (epoch != partyEpoch || !partyService.isInParty()
					|| partyService.getMemberById(event.getMemberId()) == null) {
				return;
			}
			queueBounded(pendingPartySpecs, event, 128);
		});
	}

	private void reconcilePartySpecEvents() {
		for (BpdSpecEvent event = pendingPartySpecs.pollFirst(); event != null; event = pendingPartySpecs.pollFirst()) {
			reconcilePartySpec(event);
		}
	}

	private void reconcilePartySpec(BpdSpecEvent event) {
		PartyMember sender = partyService.getMemberById(event.getMemberId());
		BossDefence boss = event.getBoss();
		long now = System.currentTimeMillis();
		if (sender == null || boss == null || event.getWorld() != client.getWorld()
				|| !isFresh(event.getSentAt(), now)
				|| boss == BossDefence.TFA_OCELLUS_P1 && tfa().phaseOneEnded()) {
			return;
		}
		String player = Objects.toString(sender.getDisplayName(), "Unknown");
		DefenceTracker.SpecHistoryEntry entry = event.historyEntry(player);
		WorldPoint tile = event.location();
		if (entry == null || tile == null || !scopeMatches(event.getScopeType(), event.getScopeId(), boss)
				|| observationPredatesReset(boss, event.getScopeType(), event.getScopeId(),
						entry.getObservedAtMillis())) {
			return;
		}
		WorldView view = client.getTopLevelWorldView();
		if (view == null || view.npcs() == null) {
			return;
		}
		NPC npc = view.npcs().byIndex(event.getNpcIndex());
		if (!BossDefence.isLiveNpc(npc) || BossDefence.matchingNpc(npc) != boss
				|| npc.getWorldLocation() == null
				|| npc.getWorldLocation().distanceTo(tile) > EncounterScopes.POSITION_TOLERANCE) {
			npc = TargetResolver.uniqueLiveNpcNear(
					view, boss, tile.getX(), tile.getY(), tile.getPlane(),
					EncounterScopes.POSITION_TOLERANCE);
		}
		if (npc == null) {
			bossesNeedingCatchUp.add(boss);
			return;
		}
		if (!peerSyncAllowedAtCurrentEncounter(boss) || defenceTracker.hasPartySpec(npc, entry.getEventId())) {
			return;
		}
		if (event.getScopeType() == BpdDefenceSync.SCOPE_WORLD && !healthMatches(npc, event.getHealthPercent())) {
			// Revalidate after packet delay; do not bind to the wrong fight.
			bossesNeedingCatchUp.add(boss);
			return;
		}
		boolean hasPrevious = event.getPreviousId() == null
				|| defenceTracker.hasPartySpec(npc, event.getPreviousId());
		DefenceTracker.PartySpecResult result = defenceTracker.applyPartySpec(npc, entry);
		if (result == DefenceTracker.PartySpecResult.IGNORED) {
			bossesNeedingCatchUp.add(boss);
			return;
		}
		rememberSenderEncounterPresence(event.getMemberId(), event.getWorld(), event.getScopeType(),
				event.getScopeId(), event.getSentAt());
		rememberRaidScope(boss, new SyncScope(event.getScopeType(), event.getScopeId()));
		if (result == DefenceTracker.PartySpecResult.ADDED && entry.getWeapon() != null) {
			recentSyncedSpecs.add(new SyncedSpecKey(client.getWorld(), npc.getIndex(),
					EncounterTimeline.normalizedPlayer(player), entry.getWeapon(),
					DefenceTracker.specCorrelationHit(entry.getWeapon(), entry.getHit())), client.getTickCount());
		}
		initialSyncDisplayGate.release(npc.getIndex(), boss);
		if (!hasPrevious) {
			bossesNeedingCatchUp.add(boss);
		}
	}

	private void reconcileRaidEncounterLifecycle() {
		for (BossDefence boss : defenceTracker.encounterLifecycleBosses()) {
			if (!isRaidEncounterBoss(boss)) {
				retainedRaidScopes.remove(boss);
				continue;
			}

			// CoX controller ids only identify party members.
			// A missing id does not end Tekton or Olm.
			if (boss.has(BossDefence.Flag.COX_SCALED)
					&& client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1) {
				SyncScope currentCoxScope = currentRaidScopeForBoss(boss);
				if (currentCoxScope != null) {
					retainedRaidScopes.put(boss, currentCoxScope);
				}
				continue;
			}

			// Changing TFA rooms does not create a new raid.
			// Preserve state until an instance exit.
			if (isTfaBoss(boss)) {
				continue;
			}

			SyncScope currentScope = currentRaidScopeForBoss(boss);
			SyncScope retainedScope = retainedRaidScopes.get(boss);
			if (retainedScope == null) {
				if (currentScope != null) {
					retainedRaidScopes.put(boss, currentScope);
				}
				continue;
			}

			if (!retainedScope.equals(currentScope)) {
				log.debug("Clearing {} because raid scope ended/changed from {}:{} to {}",
						boss, retainedScope.getType(), retainedScope.getId(), currentScope);
				defenceTracker.clearBossState(boss, "raid encounter scope ended or changed");
				clearBossScopedReconciliationMetadata(boss);
			}
		}

		retainedRaidScopes.keySet().removeIf(boss -> !defenceTracker.hasRememberedState(boss));
	}

	private void rememberRaidScope(BossDefence boss, SyncScope scope) {
		if (!isRaidEncounterBoss(boss) || scope == null) {
			return;
		}

		// Use the instance id only to match party packets.
		// Do not use it to decide when the encounter ends.
		int canonicalRaidScope = raidScopeType(boss);
		if (canonicalRaidScope != -1 && scope.getType() != canonicalRaidScope) {
			return;
		}

		retainedRaidScopes.putIfAbsent(boss, scope);
	}

	private SyncScope currentRaidScopeForBoss(BossDefence boss) {
		return encounterScopes().currentRaidScopeForBoss(boss);
	}

	static boolean isRaidEncounterBoss(BossDefence boss) {
		return EncounterScopes.isRaidEncounterBoss(boss);
	}

	private static boolean isTfaBoss(BossDefence boss) {
		return EncounterScopes.isTfaBoss(boss);
	}

	private static boolean isToaBoss(BossDefence boss) {
		return EncounterScopes.isToaBoss(boss);
	}

	// A duplicate snapshot can still attach to a newly rendered NPC.
	private boolean incomingSyncIsUseful(DefenceTracker.SyncState incoming, NPC localBoss, long incomingVersion) {
		VersionedSyncState known = syncStateVersions.get(incoming.getBossType());
		if (incomingVersion > 0 && known != null && incomingVersion > known.getVersion()) {
			// Newer state versions are useful without history growth.
			return true;
		}
		if (incomingSyncAddsState(incoming)) {
			return true;
		}

		return localBoss != null
				&& !defenceTracker.hasBoundNpc(incoming.getBossType());
	}

	// Network arrival order is not combat order.
	// Use event IDs to recover history, not the largest version number.
	private boolean incomingVersionIsAcceptable(BpdDefenceSync event, DefenceTracker.SyncState incoming,
			NPC localBoss) {
		if (defenceTracker.hasUnprocessedSpecsForBoss(incoming.getBossType())) {
			return false;
		}
		DefenceTracker.SyncState before = defenceTracker.syncStateForBoss(incoming.getBossType());
		// A delayed lower-version snapshot can contain a unique event.
		boolean matchedPublicEncounter = event.getScopeType() == BpdDefenceSync.SCOPE_WORLD;
		if (event.getEventVersion() == EncounterTimeline.VERSION && localBoss != null
				&& defenceTracker.mergeEventSyncState(incoming, localBoss, matchedPublicEncounter)) {
			DefenceTracker.SyncState merged = defenceTracker.syncStateForBoss(incoming.getBossType());
			long next = Math.max(observeLocalStateVersion(merged), event.getStateVersion());
			syncStateVersions.put(incoming.getBossType(),
					new VersionedSyncState(next == Long.MAX_VALUE ? next : next + 1, syncSignature(merged)));
			rememberSyncedSpecs(merged, before, localBoss);
			return false;
		}
		if (before != null && EncounterTimeline.hasIdentifiedEvents(before.getHistory())
				&& !EncounterTimeline.containsIdentifiedEvents(incoming.getHistory(), before.getHistory())) {
			return false; // Keep local event IDs for old and future packet versions.
		}
		// Old version numbers do not prove event order.
		// Accept valid extensions; keep local events on conflict.
		EncounterTimeline.HistoryRelation relation = EncounterTimeline.compareLegacy(before, incoming);
		return relation == EncounterTimeline.HistoryRelation.INITIAL
				|| relation == EncounterTimeline.HistoryRelation.EXTENSION
				|| (relation == EncounterTimeline.HistoryRelation.SAME && localBoss != null
						&& !defenceTracker.hasBoundNpc(incoming.getBossType()));
	}

	private long observeLocalStateVersion(DefenceTracker.SyncState sync) {
		if (sync == null || sync.getBossType() == null) {
			return 0L;
		}

		int signature = syncSignature(sync);
		VersionedSyncState known = syncStateVersions.get(sync.getBossType());
		if (known != null && known.getSignature() == signature) {
			return known.getVersion();
		}

		int historySize = sync.getHistory() == null ? 0 : sync.getHistory().size();
		long version = known == null
				? Math.max(1L, historySize)
				: Math.max(known.getVersion() == Long.MAX_VALUE ? Long.MAX_VALUE : known.getVersion() + 1L,
						historySize);
		syncStateVersions.put(sync.getBossType(), new VersionedSyncState(version, signature));
		return version;
	}

	private boolean incomingSyncAddsState(DefenceTracker.SyncState incoming) {
		DefenceTracker.SyncState local = defenceTracker.syncStateForBoss(incoming.getBossType());
		if (local == null) {
			return true;
		}

		int localHistory = local.getHistory() == null ? 0 : local.getHistory().size();
		int incomingHistory = incoming.getHistory() == null ? 0 : incoming.getHistory().size();
		if (incomingHistory != localHistory) {
			return incomingHistory > localHistory;
		}
		return !local.isDrained() && incoming.isDrained();
	}

	private void applyAcceptedSync(BpdDefenceSync event, DefenceTracker.SyncState sync, NPC localBoss) {
		DefenceTracker.SyncState before = defenceTracker.syncStateForBoss(sync.getBossType());
		if (localBoss == null) {
			if (defenceTracker.trackedBossType() == sync.getBossType() && !defenceTracker.hasBoundNpc()) {
				// Update the same selected target without stealing focus.
				defenceTracker.applySyncState(sync, null);
			} else {
				// Cache remote state without changing the display target.
				defenceTracker.cacheSyncState(sync);
			}
		} else {
			defenceTracker.applySyncState(sync, localBoss);
			initialSyncDisplayGate.release(localBoss.getIndex(), sync.getBossType());
		}
		if (event.getStateVersion() > 0) {
			syncStateVersions.put(sync.getBossType(),
					new VersionedSyncState(event.getStateVersion(), syncSignature(sync)));
		}
		SyncScope receivedScope = new SyncScope(event.getScopeType(), event.getScopeId());
		rememberRaidScope(sync.getBossType(), receivedScope);
		// Use the instance id only to match party packets.
		// Raid events still decide when the encounter ends.
		boolean transportOnlyRaidFallback = raidScopeType(sync.getBossType()) != -1
				&& event.getScopeType() == BpdDefenceSync.SCOPE_INSTANCE;
		activeSyncScope = localBoss == null || transportOnlyRaidFallback
				? null
				: new ActiveSyncScope(event.getScopeType(), event.getScopeId(), sync.getBossType());

		rememberSyncedSpecs(sync, before, localBoss);
		log.debug("Accepted BPD sync from member={} boss={} scope={}:{} actor={} def={}/{}",
				event.getMemberId(), sync.getBossType(), event.getScopeType(), event.getScopeId(),
				localBoss == null ? -1 : localBoss.getIndex(), sync.getCurrent(), sync.getBase());
	}

	private int syncSignature(DefenceTracker.SyncState sync) {
		return Objects.hash(
				sync.getBossType(), sync.getCurrent(), sync.getMin(), sync.getBase(),
				sync.getAttackLevel(), sync.getStrengthLevel(), sync.getMagicLevel(),
				sync.getMagicBaseLevel(), sync.getMagicDef(), sync.getMagicBaseDef(),
				sync.isAccursedApplied(), sync.isDrained(), sync.getHistory());
	}

	private void rememberSyncedSpecs(DefenceTracker.SyncState sync,
			DefenceTracker.SyncState before, NPC localBoss) {
		if (localBoss == null || sync.getHistory() == null) {
			return;
		}
		// Match specs even if their display order changed.
		List<DefenceTracker.SpecHistoryEntry> previous = new ArrayList<>(
				before == null ? java.util.Collections.emptyList() : before.getHistory());
		int tick = client.getTickCount();
		for (DefenceTracker.SpecHistoryEntry entry : sync.getHistory()) {
			if (entry.getSpell() != null) {
				continue;
			}
			int match = -1;
			for (int i = 0; i < previous.size(); i++) {
				DefenceTracker.SpecHistoryEntry old = previous.get(i);
				if (entry.equals(old) || (entry.getEventId() == null || old.getEventId() == null)
						&& entry.getWeapon() == old.getWeapon() && entry.getSpell() == old.getSpell()
						&& entry.getHit() == old.getHit()
						&& EncounterTimeline.normalizedPlayer(entry.getPlayerName())
								.equals(EncounterTimeline.normalizedPlayer(old.getPlayerName()))) {
					match = i;
					break;
				}
			}
			if (match >= 0) {
				previous.remove(match);
				continue;
			}
			recentSyncedSpecs.add(new SyncedSpecKey(client.getWorld(), localBoss.getIndex(),
					EncounterTimeline.normalizedPlayer(entry.getPlayerName()), entry.getWeapon(),
					DefenceTracker.specCorrelationHit(entry.getWeapon(), entry.getHit())), tick);
		}
	}

	private SyncScope currentEncounterScope() {
		return encounterScopes().current();
	}

	private SyncScope localSyncScope(BossDefence boss, NPC npc) {
		return encounterScopes().forBoss(boss, npc);
	}

	private boolean incomingScopeMatches(BpdDefenceSync event, BossDefence boss, NPC localBoss) {
		return encounterScopes().incomingScopeMatches(event, boss, localBoss);
	}

	private static boolean healthMatches(NPC npc, int remoteHp) {
		return EncounterScopeResolver.healthMatches(npc, remoteHp);
	}

	private boolean scopeMatches(int scopeType, int scopeId, BossDefence boss) {
		return encounterScopes().scopeMatches(scopeType, scopeId, boss);
	}

	private void reconcileActiveSyncScope() {
		if (activeSyncScope == null) {
			return;
		}

		DefenceTracker.DefenceState state = defenceTracker.state();
		BossDefence tracked = defenceTracker.trackedBossType();
		if (state == null) {
			activeSyncScope = null;
			return;
		}
		if (tracked != activeSyncScope.getBoss()) {
			// Keep the locally selected target.
			activeSyncScope = null;
			return;
		}
		if (!partyService.isInParty()) {
			// Party membership does not end an encounter.
			activeSyncScope = null;
			return;
		}

		if (!syncScopeStillValid(activeSyncScope)) {
			// TFA room changes do not end the raid.
			// Never reset defence from an old encounter id.
			WorldView tfaWorldView = client.getTopLevelWorldView();
			if (isTfaBoss(activeSyncScope.getBoss())
					&& tfaWorldView != null && tfaWorldView.isInstance()) {
				log.debug("Dropping stale TFA sync transport for {} without clearing live raid state",
						activeSyncScope.getBoss());
				activeSyncScope = null;

				return;
			}
			// A missing CoX controller is not an encounter end.
			// Forget the old party mapping and keep defence.
			if (activeSyncScope.getBoss() != null
					&& activeSyncScope.getBoss().has(BossDefence.Flag.COX_SCALED)
					&& client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1) {
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

	private boolean syncScopeStillValid(ActiveSyncScope scope) {
		return encounterScopes().syncScopeStillValid(scope.getType(), scope.getId());
	}

	private int raidController(int scopeType) {
		return encounterScopes().raidController(scopeType);
	}

	private static int raidScopeType(BossDefence boss) {
		return EncounterScopes.raidScopeType(boss);
	}

	static int instanceFingerprint(WorldView worldView) {
		return EncounterScopes.instanceFingerprint(worldView);
	}
	private NPC findNpcByIndex(int index) {
		WorldView worldView = client.getTopLevelWorldView();
		return index < 0 || worldView == null || worldView.npcs() == null
				? null
				: worldView.npcs().byIndex(index);
	}

	private NPC findLiveNpcForBoss(BossDefence boss) {
		return findLiveNpcForBoss(boss, null);
	}

	// Use the sender tile to distinguish same-type actors.
	private NPC findLiveNpcForBoss(BossDefence boss, BpdDefenceSync event) {
		WorldView worldView = client.getTopLevelWorldView();
		WorldPoint point = event == null ? null : event.getBossLocation();
		if (point != null) {
			NPC nearby = TargetResolver.uniqueLiveNpcNear(worldView, boss,
					point.getX(), point.getY(), point.getPlane(),
					EncounterScopes.POSITION_TOLERANCE);
			if (nearby != null) {
				return nearby;
			}
		}
		return TargetResolver.uniqueLiveNpc(worldView, boss);
	}

	private static int healthPercent(NPC npc) {
		return EncounterScopeResolver.healthPercent(npc);
	}

	private void migrateLegacyHealthBarDisplayMode() {
		String savedMode = configManager.getConfiguration(BetterPartyDefenceConfig.GROUP, "defenceDisplayMode");
		if ("NPC_HEALTH_BAR".equals(savedMode)) {
			configManager.setConfiguration(BetterPartyDefenceConfig.GROUP, "defenceHpBarPosition",
					DefenceOverlayPosition.RIGHT_OF_HP_BAR);
		}
	}

	private boolean isSpecialCounterActive() {
		return SpecialCounterState.isActive(pluginManager);
	}

	// Show drained state immediately during initial sync.
	private void releaseInitialSyncGatesForDrainedStates() {
		for (DefenceTracker.DefenceState state : defenceTracker.states()) {
			if (state == null || !state.isDrained() || state.getNpcIndex() < 0) {
				continue;
			}

			NPC stateNpc = findNpcByIndex(state.getNpcIndex());
			BossDefence boss = BossDefence.matchingNpc(stateNpc);
			if (boss != null) {
				initialSyncDisplayGate.release(state.getNpcIndex(), boss);
			}
		}
	}

	private void removeInfoBox() {
		if (display != null) {
			display.removeAll();
		}
	}

	@Value
	private static class EncounterResetKey {
		int world;
		BossDefence boss;
		int scopeType;
		int scopeId;
	}

	@Value
	private static class VersionedSyncState {
		long version;
		int signature;
	}

	@Value
	private static class ActiveSyncScope {
		int type;
		int id;
		BossDefence boss;
	}

	@Value
	private static class SenderEncounterPresence {
		int world;
		int scopeType;
		int scopeId;
		long receivedAtMillis;
		long sentAtMillis;
	}

	@Value
	private static class SyncedSpecKey {
		int world;
		int npcIndex;
		String playerName;
		SpecialWeapon weapon;
		int hit;
	}

	@Value
	private static class SpecEventKey {
		long memberId;
		int world;
		int npcIndex;
		int playerId;
		SpecialWeapon weapon;
		int hit;
	}
}
