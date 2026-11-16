package net.betterpartydefence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.With;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.betterpartydefence.boss.BossLifecycle;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import net.runelite.client.util.Text;

// Tracks defence levels, magic defence, and accepted spec history.
// Uses local observations plus standard party specs.
// Formulas follow osrs-dps-calc with integer truncation.
// Magic roll: (9 + level) * (magic-def bonus + 64).
@Slf4j
@Singleton
public class DefenceTracker {
	enum SpellDrain {
		VULNERABILITY(100), VULNERABILITY_TOME(150), SHADOW_BARRAGE(165);

		private final int perThousand;

		SpellDrain(int perThousand) {
			this.perThousand = perThousand;
		}

		long reduce(long current, long base, long floor) {
			return current == base
					? Math.max(floor, current - current * perThousand / 1000)
					: current;
		}
	}

	private final Client client;
	private final BetterPartyDefenceConfig config;

	// Track live state by NPC scene index.
	private final Map<Integer, SavedState> tracked = new LinkedHashMap<>();
	// Party state saved until a matching NPC appears.
	private final Map<BossDefence, SavedState> unboundTracked = new LinkedHashMap<>();
	// Open-world state after its NPC index changes.
	private final List<SavedState> detachedPublicTargets = new ArrayList<>();

	// -1 means no active target.
	private int bossIndex = -1;
	private String bossName = "";
	private BossDefence bossType;
	private int publicWorld;
	private Integer privateInstance;
	// Last NPC id attached to this encounter.
	private int bossNpcId = -1;
	// Last observed target tile.
	private WorldPoint targetLocation;
	// The local NPC object; never use it to identify the NPC on another client.
	private NPC boundActor;
	// Kephri keeps drains until final enrage.
	private boolean kephriFinalResetApplied;
	// Sotetseg state: 1 combat, 2 maze, 0 ended.
	private int sotetsegEncounterState = -1;
	private CombatStats stats = new CombatStats();
	private EffectState effects;
	private String localTickAnchor;
	private int localAnchorTick;
	// Shared raid clock origin.
	private String sharedTickAnchor;
	private int sharedTickAnchorTick;
	// Raid tick at the shared origin.
	private long sharedTickAnchorValue;

	// Accepted history for the current target.
	private final List<SpecHistoryEntry> specHistory = new ArrayList<>();
	private List<SpecHistoryEntry> specHistorySnapshot;
	private final List<AppliedSpec> localSpecs = new ArrayList<>();
	private static final int STANDARD_SPEC_CORRELATION_TICKS = 2;
	private final Map<SpecHistoryEntry, Integer> recentStandardSpecs = new IdentityHashMap<>();
	// Boss types seen ending on this client.
	private final Set<BossDefence> endedBosses = new LinkedHashSet<>();
	private final Set<BossDefence> endedPublicBosses = new LinkedHashSet<>();

	// Hold specs whose local target is not identifiable yet.
	// Replay keeps simultaneous party hits from diverging.
	private static final int REMEMBERED_TARGET_DISTANCE = 8;
	private final PendingSpecQueue pendingSpecs;
	// Detect a real CoX raid exit.
	private boolean wasInCoxRaid;

	@Value
	public static class DefenceState {
		int npcIndex;
		// Display name without colour tags.
		String name;
		long current;
		long min;
		long base;
		// Current and starting magic roll.
		long magicRoll;
		long magicBaseRoll;
		// Magic-defence bonus drained by Eye of Ayak.
		long magicDef;
		long magicBaseDef;
		// Magic level drained by Accursed and Seercull.
		long magicLevel;
		long magicBaseLevel;
		// True after a supported spec lands.
		boolean drained;
		// Mark TFA bosses for display rules.
		boolean tfaBoss;
	}

	// One accepted encounter spec.
	@Value
	@AllArgsConstructor
	public static class SpecHistoryEntry {
		String playerName;
		SpecialWeapon weapon;
		SpellDrain spell;
		int hit;
		String eventId;
		String tickAnchor;
		long encounterTick;
		@With
		long observedAtMillis;

		public SpecHistoryEntry(String playerName, SpecialWeapon weapon, int hit) {
			this(playerName, weapon, hit, null, null, -1);
		}

		public SpecHistoryEntry(String playerName, SpecialWeapon weapon, int hit,
				String eventId, String tickAnchor, long encounterTick) {
			this(playerName, weapon, null, hit, eventId, tickAnchor, encounterTick);
		}

		public SpecHistoryEntry(String playerName, SpecialWeapon weapon, SpellDrain spell, int hit,
				String eventId, String tickAnchor, long encounterTick) {
			this(playerName, weapon, spell, hit, eventId, tickAnchor, encounterTick, 0L);
		}

		boolean isSupported() {
			return spell == null ? isSupportedWeapon(weapon) && hit >= 0 : weapon == null && hit == 1;
		}
	}

	// Absolute state for party sync.
	@Value
	public static class SyncState {
		String bossName;
		BossDefence bossType;
		long current;
		long min;
		long base;
		long attackLevel;
		long strengthLevel;
		long magicLevel;
		long magicBaseLevel;
		long magicDef;
		long magicBaseDef;
		boolean magicUsesDefence;
		boolean demon;
		boolean accursedApplied;
		boolean drained;
		List<SpecHistoryEntry> history;
	}

	@Value
	static class AppliedSpec {
		NPC npc;
		BossDefence boss;
		SpecHistoryEntry entry;
		String previousId;
	}

	// Mutable state for one target.
	static final class EffectState {
		boolean vulnerabilityLanded;
		boolean shadowLanded;
		boolean shadowLandedOnReducedDefence;
		int kerisExpiry;
		long kerisHitAtMillis;
	}

	private static class SavedState {
		int npcIndex;
		String bossName;
		BossDefence bossType;
		int publicWorld;
		Integer privateInstance;
		int bossNpcId;
		WorldPoint targetLocation;
		// The NPC object saved with this state.
		NPC boundActor;
		boolean kephriFinalResetApplied;
		int sotetsegEncounterState;
		CombatStats stats;
		String localTickAnchor;
		int localAnchorTick;
		EffectState effects;
		List<SpecHistoryEntry> history = Collections.emptyList();
	}

	@Inject
	DefenceTracker(Client client, BetterPartyDefenceConfig config) {
		this.client = client;
		this.config = config;
		this.pendingSpecs = new PendingSpecQueue(client);
	}

	public static boolean isSupportedWeapon(SpecialWeapon weapon) {
		return SpecialWeaponRules.isSupported(weapon);
	}

	public static boolean drainsDefence(SpecialWeapon weapon) {
		return SpecialWeaponRules.drainsDefence(weapon);
	}

	public static boolean drainsMagicDefence(SpecialWeapon weapon) {
		return SpecialWeaponRules.drainsMagicDefence(weapon);
	}

	// Queue a spec for the next tick.
	// Elder Maul applies before other same-tick specs.
	public void queue(SpecialWeapon weapon, int npcIndex, int hit, int world) {
		queue(weapon, npcIndex, hit, world, null);
	}

	public void queue(SpecialWeapon weapon, int npcIndex, int hit, int world, String playerName) {
		queue(weapon, npcIndex, hit, world, playerName, null);
	}

	// Store the NPC visible when a party spec arrives.
	public void queue(
			SpecialWeapon weapon, int npcIndex, int hit, int world, String playerName, NPC target) {
		if (!isSupportedWeapon(weapon)) {
			return;
		}
		int tick = client.getTickCount();
		PendingSpecQueue.Observation observation = new PendingSpecQueue.Observation(
				weapon, null, npcIndex, hit, world, playerName, null, -1, 0L, null, -1, 0L,
				PendingSpecQueue.captureTarget(target), tick, null);
		pendingSpecs.add(observation, weapon == SpecialWeapon.ELDER_MAUL);
	}

	// Standard party messages do not carry event IDs.
	public void queueLocal(SpecialWeapon weapon, int npcIndex, int hit, int world, String playerName) {
		queueLocal(weapon, npcIndex, hit, world, playerName, null);
	}

	public void queueLocal(
			SpecialWeapon weapon, int npcIndex, int hit, int world, String playerName, NPC target) {
		queueLocal(weapon, npcIndex, hit, world, playerName, target, System.currentTimeMillis());
	}

	void queueLocal(SpecialWeapon weapon, int npcIndex, int hit, int world, String playerName,
			NPC target, long observedAtMillis) {
		if (isSupportedWeapon(weapon)) {
			int tick = client.getTickCount();
			pendingSpecs.add(new PendingSpecQueue.Observation(weapon, null, npcIndex, hit, world, playerName,
					java.util.UUID.randomUUID().toString(), tick, observedAtMillis, sharedTickAnchor,
					sharedTickAnchorTick, sharedTickAnchorValue, PendingSpecQueue.captureTarget(target), tick, null),
					false);
		}
	}

	void queueSpell(SpellDrain spell, NPC npc, int tick) {
		Player local = client.getLocalPlayer();
		pendingSpecs.add(new PendingSpecQueue.Observation(null, spell, npc.getIndex(), 1, client.getWorld(),
				local == null ? null : local.getName(), java.util.UUID.randomUUID().toString(),
				tick, System.currentTimeMillis(), sharedTickAnchor, sharedTickAnchorTick, sharedTickAnchorValue,
				PendingSpecQueue.captureTarget(npc), tick, null), false);
	}

	void setSharedTickAnchor(String anchor, int tick) {
		setSharedTickAnchor(anchor, tick, 0L);
	}

	// Align different login counters to one raid tick.
	void setSharedTickAnchor(String anchor, int tick, long value) {
		sharedTickAnchor = anchor;
		sharedTickAnchorTick = tick;
		sharedTickAnchorValue = anchor == null ? 0L : value;
	}

	// Run on the client thread.
	public void onGameTick() {
		localSpecs.clear();
		reconcileSceneBindings();
		int currentTick = client.getTickCount();
		recentStandardSpecs.values().removeIf(tick -> currentTick - tick > STANDARD_SPEC_CORRELATION_TICKS);
		for (PendingSpecQueue.Observation observation = pendingSpecs.poll();
				observation != null;
				observation = pendingSpecs.poll()) {
			process(observation);
		}

		pendingSpecs.replay(this::processResolvedDrain);

		boolean inCoxRaid = client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1;
		if (bossType != null && bossType.has(BossDefence.Flag.COX_SCALED)
				&& wasInCoxRaid && !inCoxRaid) {
			resetInstancedState("Chambers raid ended");
		}
		wasInCoxRaid = inCoxRaid;

		if (bossType == BossDefence.SOTETSEG) {
			updateSotetsegEncounterState();
		}

		reconcileTrackedNpc();

		// Interaction may restore remembered targets.
		followInteractingTarget();
		saveCurrent();
		pruneInactiveTargets();
	}

	// Keep saved state attached to the correct live NPC.
	private void reconcileTrackedNpc() {
		if (bossIndex == -1 && bossType != null && publicWorld == 0) {
			NPC replacement = findNpcForBoss(bossType);
			if (replacement != null) {
				rebindBoss(replacement);
			}
		}

		if (bossIndex != -1) {
			NPC npc = npcByIndex(bossIndex);
			if (npc == null) {
				if (boundActor != null && boundActor.isDead()
						&& !isPhasePersistentBoss(bossType)) {
					// If a dead NPC disappears, end the encounter.
					// Do not treat it as out of render.
					if (bossType != null) {
						endedBosses.add(bossType);
					}
					removeCurrent("bound NPC died while out of render");
					npc = null;
				} else {
					// Leaving render does not reset the encounter.
					// Try to attach the state to the same boss encounter.
					NPC replacement = findNpcForBoss(bossType);
					if (replacement != null) {
						rebindBoss(replacement);
						npc = replacement;
					}
				}
			} else if (boundActor != null && npc != boundActor
					&& !isPhaseActorTransition(npc)
					&& (boundActor.isDead() || !TargetResolver.matches(
							npc, bossType, bossNpcId, targetLocation, REMEMBERED_TARGET_DISTANCE))) {
				// Never transfer state to a different actor at a reused index.
				removeCurrent("npc index now refers to a different actor");
				npc = null;
			}

			if (npc != null) {
				observeBoundActor(npc);
				handlePhaseNpc(npc);
				if (bossIndex != -1 && (npc.isDead() || npc.getHealthRatio() == 0)) {
					BossLifecycle mechanics = BossLifecycle.forBoss(bossType);
					if (mechanics.preserveStateAtZeroHp(npc.getId())) {
						log.debug("Keeping {} state while transitional actor {} is at 0 HP", bossType, npc.getId());
					} else {
						if (bossType != null) {
							recordBossDeath(bossType, publicWorld);
						}
						removeCurrent("tracked NPC died");
					}
				}
			}
		}
	}

	// Create undrained previews only when configured.
	private void followInteractingTarget() {
		NPC target = interactingNpc();
		BossDefence targetBoss = target == null || target.getName() == null
				? null
				: BossDefence.matchingNpc(target);
		if (targetBoss == null || target.getIndex() == bossIndex) {
			return;
		}

		saveCurrent();
		if (restore(target.getIndex(), targetBoss) || restoreForBoss(targetBoss, target)) {
			return;
		}
		if (shouldRebindSameEncounter(targetBoss)) {
			rebindBoss(target);
			return;
		}
		if (config.defenceAlwaysShow()) {
			setBoss(target.getName(), target.getIndex());
		}
	}

	private NPC interactingNpc() {
		Player local = client.getLocalPlayer();
		if (local == null) {
			return null;
		}
		Actor target = local.getInteracting();
		return target instanceof NPC ? (NPC) target : null;
	}

	private void process(PendingSpecQueue.Observation drain) {
		if (drain.getWorld() != client.getWorld()) {
			log.debug("{} hit {} dropped: world {} != ours {}",
					drain.getWeapon(), drain.getHit(), drain.getWorld(), client.getWorld());
			return;
		}

		// The exact cast actor may stop being live before processing.
		// Keep the spent spec on its original encounter.
		// Only an observed kill clears it.
		PendingSpecQueue.TargetEvidence evidence = drain.getTarget();
		if (drain.getEventId() != null && evidence != null && evidence.getBossType() != null) {
			BossDefence bossType = evidence.getBossType();
			NPC cast = evidence.getActor();
			if (cast != null && cast.isDead()) {
				// A genuinely dead NPC ends the encounter.
				// Leaving render does not.
				int world = publicWorldFor(cast);
				recordBossDeath(bossType, world);
				if (world > 0) {
					clearPublicState(bossType, world, "confirmed spec on a dead actor");
				}
				return;
			}
			NPC indexed = npcByIndex(drain.getNpcIndex());
			if (cast != null && indexed != cast && publicWorldFor(cast) == drain.getWorld()) {
				// Same actor in a new scene slot.
				if (npcByIndex(cast.getIndex()) == cast) {
					processResolvedDrain(drain, cast);
					return;
				}
				// If the old slot is empty, save the state without assigning it to a new NPC.
				// Never adopt a slot already used by another actor.
				if (indexed == null) {
					applyLocalSpecToDetachedBoss(drain, cast);
					return;
				}
			}
		}

		NPC target = pendingSpecs.resolveTarget(drain);
		if (target == null) {
			log.debug("{} hit {} held: no unique local target for npc {}",
					drain.getWeapon(), drain.getHit(), drain.getNpcIndex());
			pendingSpecs.hold(drain);
			return;
		}
		processResolvedDrain(drain, target);
	}

	// Apply a spent local spec after its actor leaves scene.
	// Change only the NPC that was targeted.
	// Restore the live selection afterward.
	private void applyLocalSpecToDetachedBoss(PendingSpecQueue.Observation drain, NPC cast) {
		SavedState selected = snapshotCurrent();
		saveCurrent();
		for (SavedState saved : detachedPublicTargets) {
			if (saved.boundActor == cast) {
				loadSavedState(saved);
				break;
			}
		}
		if (boundActor != cast) {
			setBoss(cast.getName(), cast.getIndex(), cast);
		}
		bossIndex = cast.getIndex();
		apply(drain, cast);
		SavedState occupant = tracked.get(drain.getNpcIndex());
		if (occupant != null && occupant.boundActor == cast) {
			tracked.remove(drain.getNpcIndex());
		}
		bossIndex = -1;
		rememberDetachedPublicTarget(snapshotCurrent());
		if (selected != null && selected.boundActor != cast) {
			loadSavedState(selected);
		}
	}

	// A local spec includes the NPC object.
	// Legacy party specs require one unambiguous local target.
	private void processResolvedDrain(PendingSpecQueue.Observation drain, NPC npc) {
		if (npc == null || !BossDefence.isLiveNpc(npc)) {
			pendingSpecs.hold(drain);
			return;
		}

		BossDefence incomingBoss = BossDefence.matchingNpc(npc);
		if (incomingBoss == null) {
			log.debug("{} hit {} dropped: {} is not a tracked monster",
					drain.getWeapon(), drain.getHit(), npc.getName());
			return;
		}
		int index = npc.getIndex();
		if (bossIndex != index || bossType != incomingBoss) {
			saveCurrent();
			if (!restore(index, incomingBoss) && !restoreForBoss(incomingBoss, npc)) {
				if (shouldRebindSameEncounter(incomingBoss)) {
					rebindBoss(npc);
				} else {
					setBoss(npc.getName(), index);
				}
			}
		}
		handlePhaseNpc(npc);
		if (bossIndex != -1) {
			apply(drain, npc);
		}
	}

	List<AppliedSpec> localSpecs() {
		return localSpecs;
	}

	void clearStandardSpecCorrelation() {
		recentStandardSpecs.clear();
	}

	boolean hasPartySpec(NPC npc, String eventId) {
		return historyForActor(npc).stream().anyMatch(entry -> eventId.equals(entry.getEventId()));
	}

	private List<SpecHistoryEntry> historyForActor(NPC npc) {
		if (boundActor == npc) {
			return specHistory;
		}
		SavedState state = tracked.get(npc.getIndex());
		if (state != null && state.boundActor == npc) {
			return state.history;
		}
		for (SavedState detached : detachedPublicTargets) {
			if (detached.boundActor == npc) {
				return detached.history;
			}
		}
		return Collections.emptyList();
	}

	enum PartySpecResult {
		ADDED, MATCHED_STANDARD, IGNORED
	}

	PartySpecResult applyPartySpec(NPC npc, SpecHistoryEntry entry) {
		int previousCount = historyForActor(npc).size();
		int tick = client.getTickCount();
		processResolvedDrain(new PendingSpecQueue.Observation(
				entry.getWeapon(), entry.getSpell(), npc.getIndex(), entry.getHit(),
				client.getWorld(), entry.getPlayerName(), entry.getEventId(), -1, 0, null, -1, 0,
				PendingSpecQueue.captureTarget(npc), tick, entry), npc);
		if (!hasPartySpec(npc, entry.getEventId())) {
			return PartySpecResult.IGNORED;
		}
		return historyForActor(npc).size() > previousCount ? PartySpecResult.ADDED : PartySpecResult.MATCHED_STANDARD;
	}

	static int specCorrelationHit(SpecialWeapon weapon, int hit) {
		return SpecialWeaponRules.correlationHit(weapon, hit);
	}

	private boolean sameStandardSpec(SpecHistoryEntry standard, SpecHistoryEntry origin) {
		Integer receivedTick = recentStandardSpecs.get(standard);
		if (receivedTick == null || client.getTickCount() - receivedTick > STANDARD_SPEC_CORRELATION_TICKS) {
			return false;
		}
		if (origin.getWeapon() == SpecialWeapon.TONALZTICS_OF_RALOS && standard.getWeapon() == origin.getWeapon()) {
			standard = new SpecHistoryEntry(standard.getPlayerName(), standard.getWeapon(), origin.getHit());
		}
		return EncounterTimeline.samePayload(standard, origin);
	}

	private void apply(PendingSpecQueue.Observation drain, NPC npc) {
		SpecHistoryEntry partyEntry = drain.getPartyEntry();
		if (partyEntry != null) {
			int match = -1;
			for (int i = 0; i < specHistory.size(); i++) {
				SpecHistoryEntry prior = specHistory.get(i);
				if (partyEntry.getEventId().equals(prior.getEventId())) {
					return;
				}
				if (sameStandardSpec(prior, partyEntry)) {
					if (match >= 0) {
						return;
					}
					match = i;
				}
			}
			if (match >= 0) {
				List<SpecHistoryEntry> corrected = new ArrayList<>(specHistory);
				recentStandardSpecs.remove(corrected.set(match, partyEntry));
				if (shouldReplayTimeline(corrected)) {
					replayTimeline(corrected, npc);
				} else if (specHistory.get(match).getHit() != partyEntry.getHit()) {
					// Correct the Ralos hit count in place.
					loadSavedState(timelineBaseline());
					for (SpecHistoryEntry event : corrected) {
						applyHistoryEvent(event, npc);
					}
					appendSpecHistory(corrected);
				} else {
					specHistory.set(match, partyEntry);
					specHistorySnapshot = null;
				}
				return;
			}
		}

		int hit = drain.getSpell() == null ? drain.getWeapon().computeHit(drain.getHit(), npc) : drain.getHit();
		stats.drained = true;
		long before = stats.defence;
		if (drain.getSpell() == null) {
			stats.applyWeapon(drain.getWeapon(), hit, npc, bossName);
		} else {
			if (drain.getSpell() == SpellDrain.SHADOW_BARRAGE) {
				EffectState observed = effectState(npc, true);
				if (observed != null) {
					observed.shadowLanded = true;
					observed.shadowLandedOnReducedDefence = before < stats.startDefence;
				}
			}
			stats.applySpell(drain.getSpell(), bossType);
		}
		if (partyEntry == null) {
			recordSpecHistory(drain);
			SpecHistoryEntry entry = specHistory.get(specHistory.size() - 1);
			if (entry.getEventId() == null) {
				recentStandardSpecs.put(entry, drain.getReceivedTick());
			} else {
				String previousId = null;
				for (int i = specHistory.size() - 2; i >= 0 && previousId == null; i--) {
					previousId = specHistory.get(i).getEventId();
				}
				localSpecs.add(new AppliedSpec(npc, bossType, entry, previousId));
			}
		} else {
			addSpecHistory(partyEntry);
		}
		if (shouldReplayTimeline(specHistory)) {
			replayTimeline(new ArrayList<>(specHistory), npc);
		}
		log.debug("{} hit {} on {}: def {} -> {} (base {}, floor {})",
				drain.getSpell() == null ? drain.getWeapon() : drain.getSpell(),
				hit, bossName, before, stats.defence, stats.startDefence, stats.minDefence);
	}

	// Drop unresolved specs for a removed NPC.
	private void clearDeferredForTarget(int npcIndex, NPC actor) {
		pendingSpecs.clearDeferredForTarget(npcIndex, actor);
	}

	// Drop unresolved specs for a reset boss.
	private void clearDeferredForBoss(BossDefence boss) {
		pendingSpecs.clearDeferredForBoss(boss);
	}

	private void setBoss(String name, int index) {
		setBoss(name, index, npcByIndex(index));
	}

	private void setBoss(String name, int index, NPC bossNpc) {
		BossDefence boss = BossDefence.matchingNpc(bossNpc);
		clearSpecHistory();
		bossName = name;
		bossIndex = index;
		bossType = boss;
		publicWorld = publicWorldFor(bossNpc);
		privateInstance = privateInstanceFor(boss, bossNpc);
		bossNpcId = bossNpc == null ? -1 : bossNpc.getId();
		observeBoundActor(bossNpc);
		kephriFinalResetApplied = boss == BossDefence.KEPHRI && isKephriFinalPhaseId(bossNpcId);
		sotetsegEncounterState = initialSotetsegState(boss);
		initializeStats(boss);
		saveCurrent();
		log.debug("Tracking supported NPC '{}' index={} id={} baseDef={} floor={}",
				bossName, bossIndex, bossNpcId, stats.startDefence, stats.minDefence);
	}

	// Reset combat stats without changing the current NPC.
	private void initializeStats(BossDefence boss) {
		effects = null;
		localTickAnchor = null;
		stats = CombatStats.initial(
				boss,
				client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE_SCALED),
				client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1,
				client.getVarbitValue(VarbitID.RAIDS_CHALLENGE_MODE) == 1);
	}

	// A new phase can restore stats without ending the encounter.
	private void resetCurrentPhaseStats() {
		clearSpecHistory();
		clearDeferredForBoss(bossType);
		clearDeferredForTarget(bossIndex, boundActor);
		initializeStats(bossType);
	}

	// Rebind a replacement actor without losing defence.
	private void rebindBoss(NPC npc) {
		if (npc == null) {
			return;
		}
		int oldIndex = bossIndex;
		int oldId = bossNpcId;
		if (oldIndex >= 0) {
			tracked.remove(oldIndex);
		}
		if (bossType != null) {
			if (publicWorld > 0) {
				NPC previousActor = boundActor;
				detachedPublicTargets.removeIf(saved -> saved.boundActor == previousActor);
			} else {
				unboundTracked.remove(bossType);
			}
		}
		bossIndex = npc.getIndex();
		bossName = npc.getName();
		bossNpcId = npc.getId();
		observeBoundActor(npc);
		log.debug("Rebound {} encounter npc {}:{} -> {}:{} without clearing defence",
				bossType, oldIndex, oldId, bossIndex, bossNpcId);
		handlePhaseNpc(npc);
		saveCurrent();
	}

	// Kephri shield cycles keep drains.
	// Final enrage restores base defence.
	private void handlePhaseNpc(NPC npc) {
		if (npc == null || bossType == null) {
			return;
		}

		bossNpcId = npc.getId();
		observeBoundActor(npc);
		BossLifecycle mechanics = BossLifecycle.forBoss(bossType);
		if (mechanics.endsEncounterOnActorEntry(bossNpcId)) {
			resetInstancedState("boss final phase ended");
			return;
		}
		if (bossType == BossDefence.KEPHRI
				&& mechanics.resetsStatsOnActorEntry(bossNpcId) && !kephriFinalResetApplied) {
			resetCurrentPhaseStats();
			kephriFinalResetApplied = true;
			log.debug("Kephri entered final phase; Defence restored to {}", stats.defence);
		}
	}

	private int initialSotetsegState(BossDefence boss) {
		return boss == BossDefence.SOTETSEG
				? client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_TYPE)
				: -1;
	}

	// Sotetseg keeps its encounter through actor swaps.
	// Each maze restores defence; state reaches zero only at the end.
	private void updateSotetsegEncounterState() {
		int state = client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_TYPE);
		if (sotetsegEncounterState == -1) {
			sotetsegEncounterState = state;
			return;
		}

		if (state == 2 && sotetsegEncounterState != 2) {
			resetCurrentPhaseStats();
			log.debug("Sotetseg maze started; Defence restored to {} without clearing encounter", stats.defence);
		} else if (state == 0 && sotetsegEncounterState != 0) {
			resetInstancedState("Sotetseg encounter ended");
			return;
		}
		sotetsegEncounterState = state;
	}

	private boolean shouldRebindSameEncounter(BossDefence incomingBoss) {
		return bossType != null && incomingBoss != null && incomingBoss == bossType
				&& isPhasePersistentBoss(incomingBoss);
	}

	private static boolean isPhasePersistentBoss(BossDefence boss) {
		return BossLifecycle.forBoss(boss).isPhasePersistent();
	}

	// A changed phase actor ID marks a transition.
	// The same NPC ID at a reused index can still be a different actor.
	private boolean isPhaseActorTransition(NPC replacement) {
		return boundActor != null && replacement != null && bossType != null
				&& isPhasePersistentBoss(bossType)
				&& BossDefence.matchingNpc(replacement) == bossType
				&& replacement.getId() != bossNpcId;
	}

	private static boolean isKephriFinalPhaseId(int npcId) {
		return BossLifecycle.KEPHRI.resetsStatsOnActorEntry(npcId);
	}

	// Find a replaced actor for the same encounter.
	private NPC findNpcForBoss(BossDefence boss) {
		if (boss == null) {
			return null;
		}
		WorldView worldView = client.getTopLevelWorldView();
		NPC remembered = TargetResolver.uniqueLiveNpcFor(
				worldView, boss, bossNpcId, targetLocation, REMEMBERED_TARGET_DISTANCE);
		if (remembered != null) {
			return remembered;
		}
		// A different same-type NPC does not mean the original changed.
		// Bosses with several NPCs keep state with the original NPC.
		// Some phases keep one encounter while replacing the NPC.
		if (boundActor != null && !isPhasePersistentBoss(boss)) {
			return null;
		}
		return TargetResolver.uniqueLiveNpc(worldView, boss);
	}

	private void recordSpecHistory(PendingSpecQueue.Observation drain) {
		String playerName = drain.getPlayerName();
		if (playerName == null || playerName.trim().isEmpty()) {
			playerName = "Unknown";
		} else {
			playerName = Text.removeTags(playerName).trim();
		}
		if (drain.getEventId() == null) {
			addSpecHistory(new SpecHistoryEntry(playerName, drain.getWeapon(), drain.getHit()));
			return;
		}
		String tickAnchor = drain.getTickAnchor();
		int tickAnchorTick = drain.getTickAnchorTick();
		long tickAnchorValue = drain.getTickAnchorValue();
		// Boundary hits can arrive before the raid anchor is installed.
		if (tickAnchor == null && sharedTickAnchor != null
				&& drain.getObservedTick() == sharedTickAnchorTick) {
			tickAnchor = sharedTickAnchor;
			tickAnchorTick = sharedTickAnchorTick;
			tickAnchorValue = sharedTickAnchorValue;
		}
		if (tickAnchor != null) {
			long encounterTick = tickAnchorValue + (long) drain.getObservedTick() - tickAnchorTick;
			// Reject only genuinely pre-raid ticks.
			if (encounterTick >= 0) {
				addSpecHistory(new SpecHistoryEntry(playerName, drain.getWeapon(), drain.getSpell(), drain.getHit(),
						drain.getEventId(), tickAnchor, encounterTick, drain.getObservedAtMillis()));
				return;
			}
		}
		if (localTickAnchor == null || drain.getObservedTick() < localAnchorTick
				|| specHistory.stream().noneMatch(e -> localTickAnchor.equals(e.getEventId()))) {
			localTickAnchor = drain.getEventId();
			localAnchorTick = drain.getObservedTick();
		}
		addSpecHistory(new SpecHistoryEntry(playerName, drain.getWeapon(), drain.getSpell(), drain.getHit(),
				drain.getEventId(), localTickAnchor, (long) drain.getObservedTick() - localAnchorTick,
				drain.getObservedAtMillis()));
	}

	private void addSpecHistory(SpecHistoryEntry entry) {
		specHistory.add(entry);
		specHistorySnapshot = null;
	}

	private void appendSpecHistory(List<SpecHistoryEntry> entries) {
		if (!entries.isEmpty()) {
			specHistory.addAll(entries);
			specHistorySnapshot = null;
		}
	}

	private void clearSpecHistory() {
		specHistory.clear();
		specHistorySnapshot = null;
	}

	// Return history only for the exact current target.
	public List<SpecHistoryEntry> specHistoryForCurrentTarget(int npcIndex) {
		if (bossType == null || stats.defence < 0 || npcIndex < 0 || npcIndex != bossIndex) {
			return Collections.emptyList();
		}
		return specHistory();
	}

	// Immutable active history snapshot.
	public List<SpecHistoryEntry> specHistory() {
		if (specHistorySnapshot == null) {
			specHistorySnapshot = specHistory.isEmpty()
					? Collections.emptyList()
					: Collections.unmodifiableList(new ArrayList<>(specHistory));
		}
		return specHistorySnapshot;
	}

	public boolean hasDefenceSpecHistory() {
		for (SpecHistoryEntry entry : specHistory) {
			if (entry != null && (entry.getSpell() != null || drainsDefence(entry.getWeapon()))) {
				return true;
			}
		}
		return false;
	}

	public boolean hasMagicDefenceSpecHistory() {
		for (SpecHistoryEntry entry : specHistory) {
			if (entry != null && drainsMagicDefence(entry.getWeapon())) {
				return true;
			}
		}
		return false;
	}

	private void applyHistoryEvent(SpecHistoryEntry event, NPC npc) {
		stats.applyHistoryEvent(event, npc, bossType, bossName);
	}

	private NPC npcByIndex(int index) {
		WorldView worldView = client.getTopLevelWorldView();
		return worldView == null || worldView.npcs() == null ? null : worldView.npcs().byIndex(index);
	}

	private int publicWorldFor(NPC npc) {
		if (npc == null || EncounterScopes.isRaidEncounterBoss(BossDefence.matchingNpc(npc))) {
			return 0;
		}
		WorldView view = client.getTopLevelWorldView();
		WorldView actorView = npc.getWorldView();
		return view != null && !view.isInstance() && (actorView == null || actorView == view)
				? client.getWorld()
				: 0;
	}

	private Integer privateInstanceFor(BossDefence boss, NPC npc) {
		WorldView view = client.getTopLevelWorldView();
		return npc != null && view != null && view.isInstance() && !EncounterScopes.isRaidEncounterBoss(boss)
				? EncounterScopes.instanceFingerprint(view)
				: null;
	}

	private void reconcileSceneBindings() {
		int world = client.getWorld();
		WorldView view = client.getTopLevelWorldView();
		boolean inInstance = view != null && view.isInstance();
		reconcileBoundTargets(world, view, inInstance);
		pruneDetachedPublicTargets(world);
		rebindDetachedPublicTargets(view, inInstance);
	}

	private void reconcileBoundTargets(int world, WorldView view, boolean inInstance) {
		Integer instance = null;
		for (Iterator<SavedState> it = tracked.values().iterator(); it.hasNext();) {
			SavedState saved = it.next();
			if (inInstance && saved.privateInstance != null && instance == null) {
				instance = EncounterScopes.instanceFingerprint(view);
			}
			if (view != null && saved.privateInstance != null && !saved.privateInstance.equals(instance)) {
				it.remove();
				if (bossIndex == saved.npcIndex && boundActor == saved.boundActor) {
					clearActiveFields();
				}
				continue;
			}
			if (saved.publicWorld <= 0 || saved.boundActor == null) {
				continue;
			}
			NPC indexed = npcByIndex(saved.npcIndex);
			if (saved.publicWorld == world && view != null && !inInstance && indexed == saved.boundActor) {
				continue;
			}

			it.remove();
			boolean selected = bossIndex == saved.npcIndex && boundActor == saved.boundActor;
			if (saved.boundActor.isDead() || saved.publicWorld != world) {
				if (saved.publicWorld == world) {
					endedPublicBosses.add(saved.bossType);
				}
				if (selected) {
					clearActiveFields();
				}
				continue;
			}
			if (selected) {
				bossIndex = -1;
				saved = snapshotCurrent();
			}
			if (saved.stats.drained || saved.effects != null) {
				rememberDetachedPublicTarget(saved);
			} else if (selected) {
				clearActiveFields();
			}
		}
	}

	private void pruneDetachedPublicTargets(int world) {
		detachedPublicTargets.removeIf(saved -> {
			if (saved.publicWorld != world) {
				return true;
			}
			if (saved.boundActor.isDead()) {
				endedPublicBosses.add(saved.bossType);
				return true;
			}
			return false;
		});
		if (publicWorld > 0 && (publicWorld != world || bossIndex < 0 && endedPublicBosses.contains(bossType))) {
			clearActiveFields();
		}
	}

	private void rebindDetachedPublicTargets(WorldView view, boolean inInstance) {
		if (view == null || inInstance || detachedPublicTargets.isEmpty()) {
			return;
		}
		if (bossIndex < 0 && publicWorld > 0) {
			for (NPC npc : TargetResolver.liveCandidates(view, bossType)) {
				SavedState saved = detachedPublicTarget(bossType, npc);
				if (saved != null && saved.boundActor == boundActor) {
					rebindBoss(npc);
					break;
				}
			}
		} else if (bossType == null) {
			// Restore a detached public encounter.
			Set<BossDefence> bosses = new LinkedHashSet<>();
			for (SavedState saved : detachedPublicTargets) {
				bosses.add(saved.bossType);
			}
			for (BossDefence boss : bosses) {
				for (NPC npc : TargetResolver.liveCandidates(view, boss)) {
					SavedState saved = detachedPublicTarget(boss, npc);
					if (saved != null) {
						loadSavedState(saved);
						rebindBoss(npc);
						return;
					}
				}
			}
		}
	}

	private void rememberDetachedPublicTarget(SavedState saved) {
		saved.npcIndex = -1;
		detachedPublicTargets.removeIf(old -> old.boundActor == saved.boundActor);
		detachedPublicTargets.add(saved);
	}

	private SavedState detachedPublicTarget(BossDefence boss, NPC npc) {
		int world = publicWorldFor(npc);
		if (world <= 0 || !BossDefence.isLiveNpc(npc) || endedPublicBosses.contains(boss)) {
			return null;
		}
		SavedState candidate = null;
		boolean ambiguous = false;
		for (SavedState saved : detachedPublicTargets) {
			if (saved.bossType != boss || saved.publicWorld != world || saved.boundActor.isDead()) {
				continue;
			}
			if (saved.boundActor == npc) {
				return saved;
			}
			if (candidate != null) {
				ambiguous = true;
			}
			candidate = saved;
		}
		WorldPoint tile = npc.getWorldLocation();
		if (ambiguous || candidate == null || TargetResolver.uniqueLiveNpc(client.getTopLevelWorldView(), boss) != npc
				|| candidate.targetLocation != null && tile != null
						&& candidate.targetLocation.getPlane() != tile.getPlane()) {
			return null;
		}
		for (SavedState saved : tracked.values()) {
			if (saved.bossType == boss && saved.publicWorld == world && saved.boundActor != npc) {
				return null;
			}
		}
		return candidate;
	}

	private void observeBoundActor(NPC npc) {
		boundActor = npc;
		targetLocation = npc == null ? null : npc.getWorldLocation();
	}

	private DefenceState toDefenceState(SavedState saved) {
		if (saved == null || saved.bossType == null || saved.stats == null || saved.stats.defence < 0) {
			return null;
		}
		return toDefenceState(saved.npcIndex, saved.bossName, saved.bossType, saved.stats);
	}

	private DefenceState toDefenceState(int npcIndex, String name, BossDefence boss, CombatStats stats) {
		long roll = DefenceMath.magicRoll(
				stats.magicUsesDefence ? stats.defence : stats.magicLevel, stats.magicDefenceBonus);
		long baseRoll = DefenceMath.magicRoll(
				stats.magicUsesDefence ? stats.startDefence : stats.startMagicLevel,
				stats.startMagicDefenceBonus);
		return new DefenceState(npcIndex, Text.removeTags(name), stats.defence, stats.minDefence,
				stats.startDefence, roll, baseRoll, stats.magicDefenceBonus, stats.startMagicDefenceBonus,
				stats.magicLevel, stats.startMagicLevel, stats.drained, boss.has(BossDefence.Flag.TFA));
	}

	private SyncState toSyncState(SavedState saved) {
		if (saved == null || saved.bossType == null || saved.stats == null || saved.stats.defence < 0) {
			return null;
		}
		return new SyncState(
				Text.removeTags(saved.bossName),
				saved.bossType,
				saved.stats.defence,
				saved.stats.minDefence,
				saved.stats.startDefence,
				saved.stats.attackLevel,
				saved.stats.strengthLevel,
				saved.stats.magicLevel,
				saved.stats.startMagicLevel,
				saved.stats.magicDefenceBonus,
				saved.stats.startMagicDefenceBonus,
				saved.stats.magicUsesDefence,
				saved.stats.demon,
				saved.stats.accursedApplied,
				saved.stats.drained,
				Collections.unmodifiableList(new ArrayList<>(saved.history)));
	}

	// Current or most recently selected target.
	public DefenceState state() {
		if (bossType == null || stats.defence < 0) {
			return null;
		}
		return toDefenceState(bossIndex, bossName, bossType, stats);
	}

	// Every concrete target retained in memory.
	public List<DefenceState> states() {
		// Use live stats for the selected target.
		DefenceState current = bossIndex >= 0 ? state() : null;
		List<DefenceState> out = new ArrayList<>();
		for (SavedState saved : tracked.values()) {
			DefenceState state = current != null && saved.npcIndex == bossIndex
					? current
					: toDefenceState(saved);
			if (state != null) {
				out.add(state);
			}
		}
		if (current != null && !tracked.containsKey(bossIndex)) {
			out.add(current);
		}
		return out;
	}

	// Snapshot the active target for party sync.
	public SyncState syncState() {
		return toSyncState(snapshotCurrent());
	}

	// Share history only while its NPC is still attached.
	SyncState syncStateForActor(NPC npc) {
		if (npc == boundActor) {
			return syncState();
		}
		SavedState saved = tracked.get(npc.getIndex());
		return saved != null && saved.boundActor == npc ? toSyncState(saved) : null;
	}

	// Rebuild a target from specs that survived a reset.
	void replaySpecsAfterReset(NPC npc, List<SpecHistoryEntry> surviving) {
		SavedState selected = snapshotCurrent();
		SavedState target = npc == boundActor ? selected : tracked.get(npc.getIndex());
		loadSavedState(target);
		replayTimeline(surviving, npc);
		saveCurrent();
		if (selected != null && selected.npcIndex != npc.getIndex()) {
			loadSavedState(selected);
		}
	}

	// Remembered state for one boss type.
	public SyncState syncStateForBoss(BossDefence boss) {
		if (boss == null) {
			return null;
		}
		saveCurrent();
		WorldView view = client.getTopLevelWorldView();
		boolean inPublicWorld = view != null && !view.isInstance();
		if (bossType == boss && (privateInstance == null || !inPublicWorld)
				&& (publicWorld == 0 || inPublicWorld && publicWorld == client.getWorld()
						&& (boundActor == null || !boundActor.isDead()))) {
			return toSyncState(snapshotCurrent());
		}
		SavedState unbound = unboundTracked.get(boss);
		if (unbound != null) {
			return toSyncState(unbound);
		}
		SavedState best = null;
		for (SavedState saved : tracked.values()) {
			if (saved.bossType == boss && (saved.privateInstance == null || !inPublicWorld)
					&& (saved.publicWorld == 0
							|| inPublicWorld && saved.publicWorld == client.getWorld())
					&& (saved.publicWorld == 0 || saved.boundActor == null || !saved.boundActor.isDead())
					&& (best == null || saved.history.size() > best.history.size())) {
				best = saved;
			}
		}
		return toSyncState(best);
	}

	// Use world location to match a saved open-world NPC.
	SyncState publicSyncStateForBoss(BossDefence boss, int world, WorldPoint point, int tolerance) {
		if (world <= 0 || point == null || endedPublicBosses.contains(boss)) {
			return null;
		}
		saveCurrent();
		SavedState candidate = null;
		List<SavedState> candidates = new ArrayList<>(tracked.values());
		candidates.addAll(detachedPublicTargets);
		for (SavedState saved : candidates) {
			if (saved.publicWorld != world || saved.bossType != boss || !saved.stats.drained
					|| !BossDefence.isLiveNpc(saved.boundActor)) {
				continue;
			}
			if (candidate != null) {
				return null;
			}
			candidate = saved;
		}
		return candidate != null && candidate.targetLocation != null
				&& candidate.targetLocation.distanceTo(point) <= tolerance ? toSyncState(candidate) : null;
	}

	boolean hasPublicState(BossDefence boss, int world) {
		return tracked.values().stream().anyMatch(saved -> saved.bossType == boss && saved.publicWorld == world)
				|| detachedPublicTargets.stream()
						.anyMatch(saved -> saved.bossType == boss && saved.publicWorld == world);
	}

	void clearPublicState(BossDefence boss, int world, String reason) {
		// Clear only hits attached to removed public actors.
		List<SavedState> candidates = new ArrayList<>(tracked.values());
		candidates.addAll(detachedPublicTargets);
		for (SavedState saved : candidates) {
			if (saved.bossType == boss && saved.publicWorld == world) {
				clearDeferredForTarget(-1, saved.boundActor);
			}
		}
		detachedPublicTargets.removeIf(saved -> saved.bossType == boss && saved.publicWorld == world);
		tracked.values().removeIf(saved -> saved.bossType == boss && saved.publicWorld == world);
		if (bossType == boss && publicWorld == world) {
			clearActiveFields();
		}
		log.debug("Cleared public {} state in world {}: {}", boss, world, reason);
	}

	boolean hasUnprocessedSpecs() {
		return !pendingSpecs.isEmpty();
	}

	// Ignore pending specs for a known different boss.
	boolean hasUnprocessedSpecsForBoss(BossDefence boss) {
		return pendingSpecs.hasForBoss(boss);
	}

	boolean hasPendingFor(int npcIndex) {
		return pendingSpecs.hasForTarget(npcIndex);
	}

	// Merge specs with event IDs before comparing versions.
	public boolean mergeEventSyncState(SyncState incoming, NPC npc) {
		return mergeEventSyncState(incoming, npc, false);
	}

	boolean mergeEventSyncState(SyncState incoming, NPC npc, boolean matchedPublicEncounter) {
		if (incoming == null || npc == null || !BossDefence.isLiveNpc(npc)
				|| BossDefence.matchingNpc(npc) != incoming.getBossType()) {
			return false;
		}
		saveCurrent();
		SavedState target = tracked.get(npc.getIndex());
		if (target == null || target.bossType != incoming.getBossType()
				|| target.stats.startDefence != incoming.getBase()
				|| target.stats.minDefence != incoming.getMin()
				|| target.stats.startMagicLevel != incoming.getMagicBaseLevel()
				|| target.stats.startMagicDefenceBonus != incoming.getMagicBaseDef()
				|| target.stats.magicUsesDefence != incoming.isMagicUsesDefence()
				|| target.stats.demon != incoming.isDemon()) {
			return false;
		}
		List<SpecHistoryEntry> merged = EncounterTimeline.union(target.history, incoming.getHistory());
		if (merged == null || new java.util.HashSet<>(merged).equals(new java.util.HashSet<>(target.history))) {
			return false;
		}
		// A unique visible target can identify the encounter.
		boolean shared = target.history.stream().anyMatch(a -> incoming.getHistory().stream()
				.anyMatch(b -> EncounterTimeline.compatibleEvent(a, b)
						|| (a.getEventId() == null || b.getEventId() == null)
								&& EncounterTimeline.samePayload(a, b)));
		if (!shared && !(target.boundActor == npc && target.publicWorld == client.getWorld()
				&& (matchedPublicEncounter && merged.stream().allMatch(entry -> entry.getObservedAtMillis() > 0)
						&& TargetResolver.uniqueLiveNpc(client.getTopLevelWorldView(), target.bossType) == npc
						|| onlyNoOpEvents(target.history, npc) || onlyNoOpEvents(incoming.getHistory(), npc)))) {
			return false;
		}
		if (hasPendingFor(npc.getIndex())) {
			return false;
		}
		SavedState selected = snapshotCurrent();
		loadSavedState(target);
		replayTimeline(merged, npc);
		saveCurrent();
		if (selected != null && selected.npcIndex != npc.getIndex()) {
			loadSavedState(selected);
		}
		return true;
	}

	// True when no row can change defence.
	// Tekton hammer and maul misses can still drain.
	// Spells and undated rows are excluded.
	static boolean onlyNoOpEvents(List<SpecHistoryEntry> history, NPC npc) {
		return !history.isEmpty() && history.stream()
				.allMatch(entry -> entry.getSpell() == null && entry.getWeapon() != null && entry.getHit() == 0
						&& entry.getObservedAtMillis() > 0
						&& entry.getWeapon().computeDrainPercent(0, npc) <= 0);
	}

	// Skip equivalent orders that unlock the same events.
	// Hits stay distinct when the formula uses their value.
	String effectKey(SpecHistoryEntry event, NPC npc) {
		return CombatStats.effectKey(event, npc);
	}

	private boolean shouldReplayTimeline(List<SpecHistoryEntry> events) {
		return EncounterTimeline.complete(events) || events.size() > EncounterTimeline.MAX_EVENTS
				&& events.stream().allMatch(e -> e.getEventId() != null);
	}

	private SavedState timelineBaseline() {
		SavedState baseline = snapshotCurrent();
		baseline.stats = baseline.stats.undrainedBaseline(bossType);
		baseline.history = Collections.emptyList();
		return baseline;
	}

	// Replay from the actual scaled baseline.
	private void replayTimeline(List<SpecHistoryEntry> events, NPC npc) {
		events.sort(EncounterTimeline.DISPLAY_ORDER);
		SavedState baseline = timelineBaseline();
		SavedState result = EncounterTimeline.replay(events, baseline, new EncounterTimeline.Replay<SavedState>() {
			@Override
			public SavedState apply(SavedState state, SpecHistoryEntry event) {
				loadSavedState(state);
				applyHistoryEvent(event, npc);
				return snapshotCurrent();
			}

			@Override
			public SavedState conservative(SavedState a, SavedState b) {
				// Keep the highest legal value for each stat.
				// Later rebuilds still try every event order.
				a.stats.keepConservativeMaximum(b.stats);
				return a;
			}
		}, event -> effectKey(event, npc));
		loadSavedState(result);
		appendSpecHistory(events);
		stats.drained = true;
	}

	// Check that the current target has a live NPC.
	public boolean hasBoundNpc(BossDefence boss) {
		if (boss == null) {
			return false;
		}
		if (bossType == boss && hasBoundNpc()) {
			return true;
		}
		for (SavedState saved : tracked.values()) {
			if (saved != null && saved.bossType == boss) {
				NPC npc = npcByIndex(saved.npcIndex);
				if (BossDefence.isLiveNpc(npc) && (saved.boundActor == null || saved.boundActor == npc)) {
					return true;
				}
			}
		}
		return false;
	}

	// Cache remote state without changing the selected target.
	// Attach it when the NPC appears.
	public void cacheSyncState(SyncState sync) {
		if (sync == null || sync.getBossType() == null || !sync.isDrained()) {
			return;
		}

		SavedState saved = new SavedState();
		saved.npcIndex = -1;
		saved.bossName = sync.getBossName() == null ? sync.getBossType().getNpcName() : sync.getBossName();
		saved.bossType = sync.getBossType();
		saved.bossNpcId = -1;
		saved.kephriFinalResetApplied = false;
		saved.sotetsegEncounterState = initialSotetsegState(sync.getBossType());
		saved.stats = CombatStats.fromSync(sync);
		if (sync.getHistory() != null) {
			saved.history = Collections.unmodifiableList(new ArrayList<>(sync.getHistory()));
		}

		unboundTracked.put(saved.bossType, saved);
		log.debug("Cached passive BPD sync for {} def={}/{} specs={}",
				saved.bossType, saved.stats.defence, saved.stats.startDefence, saved.history.size());
	}

	// Apply snapshot state to one target.
	// Save the current target first.
	// A null NPC stores state until the boss appears.
	public void applySyncState(SyncState sync, NPC localNpc) {
		if (sync == null || sync.getBossType() == null || !sync.isDrained()) {
			return;
		}

		saveCurrent();
		SavedState previousTarget = localNpc == null ? null : tracked.get(localNpc.getIndex());
		SavedState effectTarget = localNpc == null && bossIndex < 0 && bossType == sync.getBossType()
				? unboundTracked.get(bossType)
				: previousTarget;
		effects = effectTarget != null && effectTarget.bossType == sync.getBossType()
				&& effectTarget.boundActor == localNpc ? effectTarget.effects : null;
		localTickAnchor = previousTarget == null ? null : previousTarget.localTickAnchor;
		localAnchorTick = previousTarget == null ? 0 : previousTarget.localAnchorTick;
		bossType = sync.getBossType();
		publicWorld = publicWorldFor(localNpc);
		privateInstance = privateInstanceFor(bossType, localNpc);
		bossName = sync.getBossName() == null ? bossType.getNpcName() : sync.getBossName();
		bossIndex = localNpc == null ? -1 : localNpc.getIndex();
		bossNpcId = localNpc == null ? -1 : localNpc.getId();
		observeBoundActor(localNpc);
		stats = CombatStats.fromSync(sync);
		kephriFinalResetApplied = bossType == BossDefence.KEPHRI && isKephriFinalPhaseId(bossNpcId);
		sotetsegEncounterState = initialSotetsegState(bossType);
		wasInCoxRaid = client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1;

		clearSpecHistory();
		if (sync.getHistory() != null) {
			appendSpecHistory(sync.getHistory());
		}
		if (localNpc != null) {
			unboundTracked.remove(bossType);
		}
		saveCurrent();
		// Keep independent observations on other targets.
		log.debug("Applied BPD sync for {} actor={} def={}/{} specs={}",
				bossType, bossIndex, stats.defence, stats.startDefence, specHistory.size());
	}

	public BossDefence trackedBossType() {
		return bossType;
	}

	public boolean hasBoundNpc() {
		NPC npc = bossIndex < 0 ? null : npcByIndex(bossIndex);
		return BossDefence.isLiveNpc(npc) && (boundActor == null || boundActor == npc);
	}

	private SavedState snapshotCurrent() {
		if (bossType == null || stats.defence < 0) {
			return null;
		}
		SavedState s = new SavedState();
		s.npcIndex = bossIndex;
		s.bossName = bossName;
		s.bossType = bossType;
		s.publicWorld = publicWorld;
		s.privateInstance = privateInstance;
		s.bossNpcId = bossNpcId;
		s.targetLocation = targetLocation;
		s.boundActor = boundActor;
		s.kephriFinalResetApplied = kephriFinalResetApplied;
		s.sotetsegEncounterState = sotetsegEncounterState;
		s.stats = stats.copy();
		s.localTickAnchor = localTickAnchor;
		s.localAnchorTick = localAnchorTick;
		s.effects = effects;
		s.history = specHistory();
		return s;
	}

	private void saveCurrent() {
		SavedState s = snapshotCurrent();
		if (s == null) {
			return;
		}
		if (s.npcIndex >= 0) {
			tracked.put(s.npcIndex, s);
			unboundTracked.remove(s.bossType);
		} else if (s.publicWorld > 0) {
			// Keep saved open-world state until attached to an NPC.
			return;
		} else {
			unboundTracked.put(s.bossType, s);
		}
	}

	private boolean restore(int index, BossDefence expectedBoss) {
		SavedState s = tracked.get(index);
		if (s == null || (expectedBoss != null && s.bossType != expectedBoss)) {
			return false;
		}
		NPC current = npcByIndex(index);
		if (s.boundActor != null) {
			if (s.boundActor.isDead()) {
				return false;
			}
			if (current != null && current != s.boundActor
					&& !TargetResolver.matches(
							current, expectedBoss, s.bossNpcId, s.targetLocation, REMEMBERED_TARGET_DISTANCE)) {
				return false;
			}
		}
		loadSavedState(s);
		if (current != null) {
			observeBoundActor(current);
		}
		return true;
	}

	// Attach saved state when the NPC appears.
	private boolean restoreForBoss(BossDefence boss, NPC npc) {
		if (boss == null || npc == null) {
			return false;
		}

		SavedState detached = detachedPublicTarget(boss, npc);
		if (detached != null) {
			loadSavedState(detached);
			rebindBoss(npc);
			return true;
		}

		SavedState unbound = unboundTracked.remove(boss);
		if (unbound != null) {
			loadSavedState(unbound);
			bossIndex = npc.getIndex();
			bossNpcId = npc.getId();
			bossName = npc.getName();
			publicWorld = publicWorldFor(npc);
			privateInstance = privateInstanceFor(boss, npc);
			observeBoundActor(npc);
			saveCurrent();
			return true;
		}

		// Move state only when exactly one matching boss is missing.
		Integer oldKey = null;
		SavedState candidate = null;
		for (Map.Entry<Integer, SavedState> entry : tracked.entrySet()) {
			SavedState saved = entry.getValue();
			NPC oldActor = npcByIndex(saved.npcIndex);
			boolean terminalDeath = saved.boundActor != null && saved.boundActor.isDead()
					&& !isPhasePersistentBoss(saved.bossType);
			boolean oldActorGone = oldActor == null
					|| oldActor == saved.boundActor && !BossDefence.isLiveNpc(oldActor);
			boolean rememberedActorMatch = TargetResolver.matches(
					npc, saved.bossType, saved.bossNpcId, saved.targetLocation, REMEMBERED_TARGET_DISTANCE);
			boolean phaseActorTransition = isPhasePersistentBoss(saved.bossType)
					&& npc.getId() != saved.bossNpcId;
			if (saved.bossType == boss && oldActorGone && !terminalDeath
					&& (rememberedActorMatch || phaseActorTransition)) {
				if (candidate != null) {
					return false;
				}
				candidate = saved;
				oldKey = entry.getKey();
			}
		}
		if (candidate == null) {
			return false;
		}
		tracked.remove(oldKey);
		loadSavedState(candidate);
		bossIndex = npc.getIndex();
		bossNpcId = npc.getId();
		bossName = npc.getName();
		observeBoundActor(npc);
		saveCurrent();
		return true;
	}

	private void loadSavedState(SavedState s) {
		bossIndex = s.npcIndex;
		bossName = s.bossName;
		bossType = s.bossType;
		publicWorld = s.publicWorld;
		privateInstance = s.privateInstance;
		bossNpcId = s.bossNpcId;
		targetLocation = s.targetLocation;
		boundActor = s.boundActor;
		kephriFinalResetApplied = s.kephriFinalResetApplied;
		sotetsegEncounterState = s.sotetsegEncounterState;
		stats = s.stats.copy();
		localTickAnchor = s.localTickAnchor;
		localAnchorTick = s.localAnchorTick;
		effects = s.effects;
		if (specHistorySnapshot != s.history) {
			specHistory.clear();
			specHistory.addAll(s.history);
			specHistorySnapshot = s.history;
		}
	}

	private void pruneInactiveTargets() {
		Iterator<Map.Entry<Integer, SavedState>> it = tracked.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, SavedState> entry = it.next();
			if (entry.getKey() == bossIndex) {
				continue;
			}
			NPC npc = npcByIndex(entry.getKey());
			SavedState saved = entry.getValue();
			if (npc != null && saved != null && saved.boundActor != null && npc != saved.boundActor
					&& (saved.boundActor.isDead() || !TargetResolver.matches(
							npc, saved.bossType, saved.bossNpcId, saved.targetLocation, REMEMBERED_TARGET_DISTANCE))) {
				clearDeferredForTarget(entry.getKey(), saved.boundActor);
				it.remove();
				continue;
			}
			if (npc != null && (npc.isDead() || npc.getHealthRatio() == 0)) {
				SavedState ended = saved;
				BossLifecycle mechanics = BossLifecycle.forBoss(
						ended == null ? null : ended.bossType);
				if (mechanics.preserveStateAtZeroHp(npc.getId())) {
					continue;
				}
				if (ended != null && ended.bossType != null) {
					recordBossDeath(ended.bossType, ended.publicWorld);
				}
				clearDeferredForTarget(entry.getKey(), saved == null ? null : saved.boundActor);
				it.remove();
			}
		}
	}

	// Return boss types seen ending and clear the set.
	// The plugin decides which resets should be sent to peers.
	public Set<BossDefence> consumeEndedBosses() {
		Set<BossDefence> ended = new LinkedHashSet<>(endedBosses);
		endedBosses.clear();
		return ended;
	}

	private void recordBossDeath(BossDefence boss, int world) {
		(world > 0 ? endedPublicBosses : endedBosses).add(boss);
	}

	Set<BossDefence> consumeEndedPublicBosses() {
		Set<BossDefence> ended = new LinkedHashSet<>(endedPublicBosses);
		endedPublicBosses.clear();
		return ended;
	}

	private void clearActiveFields() {
		effects = null;
		localTickAnchor = null;
		bossIndex = -1;
		bossName = "";
		bossType = null;
		publicWorld = 0;
		privateInstance = null;
		bossNpcId = -1;
		targetLocation = null;
		boundActor = null;
		kephriFinalResetApplied = false;
		sotetsegEncounterState = -1;
		stats = new CombatStats();
		clearSpecHistory();
	}

	private void removeCurrent(String reason) {
		BossDefence removedBoss = bossType;
		int removedIndex = bossIndex;
		NPC removedActor = boundActor;
		if (removedIndex >= 0) {
			tracked.remove(removedIndex);
		}
		if (removedBoss != null) {
			unboundTracked.remove(removedBoss);
		}
		clearDeferredForTarget(removedIndex, removedActor);
		log.debug("Remove tracked target {}:{}: {}", removedBoss, removedIndex, reason);
		clearActiveFields();

		List<SavedState> remaining = new ArrayList<>(tracked.values());
		for (int i = remaining.size() - 1; i >= 0; i--) {
			SavedState saved = remaining.get(i);
			NPC npc = npcByIndex(saved.npcIndex);
			if (BossDefence.isLiveNpc(npc)) {
				loadSavedState(saved);
				return;
			}
		}
	}

	void resetInstancedState(String reason) {
		List<SavedState> publicTargets = new ArrayList<>();
		for (SavedState saved : tracked.values()) {
			if (saved.publicWorld > 0) {
				publicTargets.add(saved);
			}
		}
		publicTargets.addAll(detachedPublicTargets);
		Set<BossDefence> publicDeaths = new LinkedHashSet<>(endedPublicBosses);
		reset(reason);
		publicTargets.forEach(this::rememberDetachedPublicTarget);
		endedPublicBosses.addAll(publicDeaths);
	}

	// Use the target's saved effect state.
	EffectState effectState(NPC npc, boolean remember) {
		if (!BossDefence.isLiveNpc(npc) || npcByIndex(npc.getIndex()) != npc) {
			return null;
		}
		BossDefence boss = BossDefence.matchingNpc(npc);
		if (boss == null) {
			return null;
		}
		if (boundActor == npc && bossType == boss) {
			if (remember && effects == null) {
				effects = new EffectState();
				saveCurrent();
			}
			return effects;
		}
		SavedState saved = tracked.get(npc.getIndex());
		if (saved != null && saved.boundActor == npc && saved.bossType == boss) {
			if (remember && saved.effects == null) {
				saved.effects = new EffectState();
			}
			return saved.effects;
		}
		if (!remember) {
			return null;
		}

		SavedState selected = snapshotCurrent();
		saveCurrent();
		if (!restore(npc.getIndex(), boss) && !restoreForBoss(boss, npc)) {
			setBoss(npc.getName(), npc.getIndex());
		}
		if (effects == null) {
			effects = new EffectState();
		}
		EffectState result = effects;
		saveCurrent();
		if (selected == null) {
			clearActiveFields();
		} else if (selected.npcIndex != bossIndex || selected.bossType != bossType) {
			loadSavedState(selected);
		}
		return result;
	}

	boolean hasLandedSpell(NPC npc, SpellDrain spell) {
		if (!BossDefence.isLiveNpc(npc) || npcByIndex(npc.getIndex()) != npc) {
			return false;
		}
		SavedState saved = tracked.get(npc.getIndex());
		List<SpecHistoryEntry> history = boundActor == npc
				? specHistory
				: saved != null && saved.boundActor == npc ? saved.history : Collections.emptyList();
		return history.stream().anyMatch(entry -> entry.getSpell() == spell);
	}

	Set<BossDefence> encounterLifecycleBosses() {
		return rememberedBosses(true);
	}

	// Bosses with remembered drained state.
	public Set<BossDefence> drainedBosses() {
		return rememberedBosses(false);
	}

	private Set<BossDefence> rememberedBosses(boolean includeEffects) {
		saveCurrent();
		Set<BossDefence> bosses = new LinkedHashSet<>();
		for (SavedState saved : tracked.values()) {
			if (saved != null && saved.bossType != null
					&& (saved.stats.drained || includeEffects && saved.effects != null)) {
				bosses.add(saved.bossType);
			}
		}
		for (SavedState saved : unboundTracked.values()) {
			if (saved != null && saved.bossType != null
					&& (saved.stats.drained || includeEffects && saved.effects != null)) {
				bosses.add(saved.bossType);
			}
		}
		for (SavedState saved : detachedPublicTargets) {
			if (saved.stats.drained || includeEffects && saved.effects != null) {
				bosses.add(saved.bossType);
			}
		}
		return bosses;
	}

	public boolean hasRememberedState(BossDefence boss) {
		if (boss == null) {
			return false;
		}
		if (bossType == boss && stats.defence >= 0) {
			return true;
		}
		if (unboundTracked.containsKey(boss)) {
			return true;
		}
		if (detachedPublicTargets.stream().anyMatch(saved -> saved.bossType == boss)) {
			return true;
		}
		for (SavedState saved : tracked.values()) {
			if (saved != null && saved.bossType == boss) {
				return true;
			}
		}
		return false;
	}

	// Remove one boss and retain other targets.
	public void clearBossState(BossDefence boss, String reason) {
		clearBossState(boss, reason, true);
	}

	void clearInstancedBossState(BossDefence boss, String reason) {
		clearBossState(boss, reason, false);
	}

	private void clearBossState(BossDefence boss, String reason, boolean includePublic) {
		if (boss == null) {
			return;
		}

		boolean clearingActive = bossType == boss && (includePublic || publicWorld == 0);
		Set<Integer> clearedIndices = new LinkedHashSet<>();
		Set<NPC> clearedActors = new LinkedHashSet<>();
		if (clearingActive) {
			if (bossIndex >= 0) {
				clearedIndices.add(bossIndex);
			}
			if (boundActor != null) {
				clearedActors.add(boundActor);
			}
		}
		tracked.entrySet().removeIf(entry -> {
			SavedState saved = entry.getValue();
			if (saved == null || saved.bossType != boss || !includePublic && saved.publicWorld > 0) {
				return false;
			}
			clearedIndices.add(entry.getKey());
			if (saved.boundActor != null) {
				clearedActors.add(saved.boundActor);
			}
			return true;
		});
		unboundTracked.remove(boss);
		if (includePublic) {
			detachedPublicTargets.removeIf(saved -> saved.bossType == boss);
		}
		clearDeferredForBoss(boss);
		for (Integer index : clearedIndices) {
			clearDeferredForTarget(index, null);
		}
		for (NPC actor : clearedActors) {
			clearDeferredForTarget(-1, actor);
		}

		if (!clearingActive) {
			return;
		}

		log.debug("Clear tracked boss {}: {}", boss, reason);
		clearActiveFields();

		List<SavedState> remaining = new ArrayList<>(tracked.values());
		for (int i = remaining.size() - 1; i >= 0; i--) {
			SavedState saved = remaining.get(i);
			NPC npc = npcByIndex(saved.npcIndex);
			if (BossDefence.isLiveNpc(npc)) {
				loadSavedState(saved);
				return;
			}
		}

		SavedState fallback = null;
		for (SavedState saved : unboundTracked.values()) {
			fallback = saved;
		}
		if (fallback != null) {
			loadSavedState(fallback);
		}
	}

	public void reset(String reason) {
		recentStandardSpecs.clear();
		localSpecs.clear();
		if (bossType != null || bossIndex != -1 || !tracked.isEmpty() || !unboundTracked.isEmpty()
				|| !detachedPublicTargets.isEmpty() || !pendingSpecs.isEmpty()) {
			log.debug("Reset defence tracker: {}", reason);
		}
		clearActiveFields();
		tracked.clear();
		unboundTracked.clear();
		detachedPublicTargets.clear();
		endedBosses.clear();
		endedPublicBosses.clear();
		pendingSpecs.clear();
	}

}
