package net.betterpartydefence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import net.runelite.client.util.Text;

/**
 * Tracks the live defence of the monster a party is draining with defence-lowering
 * special attacks. Both the physical Defence level and the magic-defence roll are
 * tracked. Drains arrive over RuneLite's built-in party websocket (each member's
 * SpecialCounterPlugin broadcasts its own), so the computed values reflect the
 * whole party's draining, not just our own.
 *
 * <p>The drain formulas and CoX party-size and Challenge Mode scaling mirror the OSRS
 * Wiki DPS calculator (github.com/weirdgloop/osrs-dps-calc, {@code lib/scaling/DefenceReduction.ts}
 * and {@code lib/scaling/ChambersOfXeric.ts}), including its integer truncation.
 *
 * <p>Magic defence follows the OSRS roll {@code (9 + Magic level) * (Magic-def bonus + 64)}.
 * Accursed sceptre and Seercull drain the Magic <em>level</em>; the Eye of ayak drains
 * the Magic-defence <em>bonus</em>. A few monsters ({@link BossDefence.Flag#MAGIC_USES_DEFENCE})
 * roll magic defence off their Defence level, so their physical drains lower it too.
 */
@Slf4j
@Singleton
public class DefenceTracker
{
	private static final int PERCENT = 100;

	/** The magic-defence roll is {@code (9 + Magic level) * (Magic-def bonus + 64)}. */
	private static final int MAGIC_ROLL_LEVEL_OFFSET = 9;
	private static final int MAGIC_ROLL_BONUS_OFFSET = 64;

	private static final int DWH_DRAIN_PCT = 30;
	private static final int ELDER_MAUL_DRAIN_PCT = 35;
	/** Condemn leaves 85% of the levels it touches, applied to the current values rather than the base. */
	private static final int CONDEMN_KEEPS_PCT = 85;
	private static final int ARCLIGHT_DRAIN_PCT = 5;
	private static final int ARCLIGHT_DEMON_DRAIN_PCT = 10;
	private static final int EMBERLIGHT_DRAIN_PCT = 5;
	private static final int EMBERLIGHT_DEMON_DRAIN_PCT = 15;
	/** Each landed Ralos glaive takes an eighth of the target's current Magic level off its Defence. */
	private static final int RALOS_GLAIVE_MAGIC_DIVISOR = 8;
	private static final int ANCHOR_DAMAGE_DIVISOR = 10;

	private static final int CM_SCALE_PCT = 50;
	private static final int TEKTON_CM_SMALL_PARTY = 4;
	private static final int TEKTON_CM_DEFENCE_PCT_SMALL_PARTY = 20;
	private static final int TEKTON_CM_DEFENCE_PCT = 35;

	private final Client client;
	private final BetterPartyDefenceConfig config;

	/** Independent live state for every concrete NPC we have tracked. */
	private final Map<Integer, SavedState> tracked = new LinkedHashMap<>();
	/** Synced state received before this client has a concrete NPC actor for that boss. */
	private final Map<BossDefence, SavedState> unboundTracked = new LinkedHashMap<>();

	/** -1 = nothing tracked. */
	private int bossIndex = -1;
	private String bossName = "";
	private BossDefence bossType;
	/** Last concrete NPC id bound to the encounter; used to detect phase transitions. */
	private int bossNpcId = -1;
	/** Kephri keeps drains through shield cycles, but resets them on the final enrage phase. */
	private boolean kephriFinalResetApplied;
	/** Sotetseg's encounter varbit: 1=combat, 2=maze, 0=encounter ended. */
	private int sotetsegEncounterState = -1;
	private long bossDef = -1;
	private long bossStartDef;
	private long minDef;

	/** Only tracked so an overkill Bandos godsword spec can spill through them into Magic. */
	private long atkLevel;
	private long strLevel;

	private long magicLevel;
	private long magicStartLevel;
	private long magicDefBonus;
	private long magicStartDefBonus;
	private boolean magicUsesDefence;
	private boolean demon;
	/** The accursed sceptre's curse doesn't stack, so it only ever lands once per monster. */
	private boolean accursedApplied;
	/** True once a special attack has actually landed on the tracked monster. */
	private boolean drained;
	private String localTickAnchor;
	private int localAnchorTick;
	/** Optional shared raid clock origin installed by the plugin on the client thread. */
	private String sharedTickAnchor;
	private int sharedTickAnchorTick;
	/** Elapsed tick at the shared origin; 0 for a boundary anchor, raid elapsed for a timer clock. */
	private long sharedTickAnchorValue;

	/** Specs which contributed to the current tracked encounter, shown in the info-box tooltip. */
	private final List<SpecHistoryEntry> specHistory = new ArrayList<>();
	/** Logical encounters this client authoritatively observed ending on a dead NPC actor. */
	private final Set<BossDefence> endedBosses = new LinkedHashSet<>();

	/** Filled from the socket reader thread, drained on the client thread. */
	private final ConcurrentLinkedDeque<Drain> pending = new ConcurrentLinkedDeque<>();

	/**
	 * Drains that landed on an NPC this client could not identify yet. A party member's spec can
	 * reach us before the monster is in our scene — most easily when two people spec the same tick
	 * — and dropping it would leave every client on a different defence. Held against the index
	 * that is waiting to be identified, and replayed the moment it is.
	 */
	private static final int HELD_DRAIN_TTL_TICKS = 3;
	private int queuedIndex = -1;
	private int queuedAtTick = -1;
	private final List<Drain> queuedDrains = new ArrayList<>();
	/** Tracks the CoX in-raid varbit so cleanup only fires on a real 1 -> 0 raid exit. */
	private boolean wasInCoxRaid;

	@Value
	public static class DefenceState
	{
		int npcIndex;
		/** The monster's name without colour tags, for the info box tooltip. */
		String name;
		long current;
		long min;
		long base;
		/** Current magic-defence roll and its starting value; percent = magicRoll / magicBaseRoll. */
		long magicRoll;
		long magicBaseRoll;
		/** The magic-defence bonus itself, which is what the Eye of ayak drains. */
		long magicDef;
		long magicBaseDef;
		/** The Magic level, which the accursed sceptre and Seercull drain. */
		long magicLevel;
		long magicBaseLevel;
		/** Whether at least one supported defence-draining spec has landed on this target. */
		boolean drained;
		/** Presentation-only flag from the identified boss, including party-synced targets. */
		boolean tfaBoss;
	}

	/** One accepted spec in the current tracked encounter, for the info-box hover history. */
	@Value
	@AllArgsConstructor
	public static class SpecHistoryEntry
	{
		String playerName;
		SpecialWeapon weapon;
		int hit;
		String eventId;
		String tickAnchor;
		long encounterTick;

		public SpecHistoryEntry(String playerName, SpecialWeapon weapon, int hit)
		{
			this(playerName, weapon, hit, null, null, -1);
		}
	}

	/** Absolute tracker state used by the optional BPD-to-BPD party sync. */
	@Value
	public static class SyncState
	{
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

	/** One bound target plus its absolute sync state, used for periodic multi-target rebroadcast. */
	@Value
	public static class SyncTarget
	{
		int npcIndex;
		SyncState state;
	}

	/** One defence-draining special attack landed on an NPC, from any party member. */
	@Value
	private static class Drain
	{
		SpecialWeapon weapon;
		int npcIndex;
		int hit;
		int world;
		String playerName;
		String eventId;
		int observedTick;
		String tickAnchor;
		int tickAnchorTick;
		long tickAnchorValue;
	}

	/** Mutable snapshot for one independently tracked target. */
	private static class SavedState
	{
		int npcIndex;
		String bossName;
		BossDefence bossType;
		int bossNpcId;
		boolean kephriFinalResetApplied;
		int sotetsegEncounterState;
		long bossDef;
		long bossStartDef;
		long minDef;
		long atkLevel;
		long strLevel;
		long magicLevel;
		long magicStartLevel;
		long magicDefBonus;
		long magicStartDefBonus;
		boolean magicUsesDefence;
		boolean demon;
		boolean accursedApplied;
		boolean drained;
		String localTickAnchor;
		int localAnchorTick;
		final List<SpecHistoryEntry> history = new ArrayList<>();
	}

	@Inject
	DefenceTracker(Client client, BetterPartyDefenceConfig config)
	{
		this.client = client;
		this.config = config;
	}

	/** Returns whether the RuneLite special-counter weapon changes a tracked defence stat. */
	public static boolean isSupportedWeapon(SpecialWeapon weapon)
	{
		if (weapon == null)
		{
			return false;
		}

		switch (weapon)
		{
			case DRAGON_WARHAMMER:
			case ELDER_MAUL:
			case BANDOS_GODSWORD:
			case ARCLIGHT:
			case DARKLIGHT:
			case EMBERLIGHT:
			case BARRELCHEST_ANCHOR:
			case BONE_DAGGER:
			case DORGESHUUN_CROSSBOW:
			case ACCURSED_SCEPTRE:
			case TONALZTICS_OF_RALOS:
			case SEERCULL:
			case EYE_OF_AYAK:
				return true;
			default:
				return false;
		}
	}

	/** Whether this supported spec directly reduces the target's normal Defence level. */
	public static boolean drainsDefence(SpecialWeapon weapon)
	{
		if (!isSupportedWeapon(weapon))
		{
			return false;
		}

		switch (weapon)
		{
			case SEERCULL:
			case EYE_OF_AYAK:
				return false;
			default:
				return true;
		}
	}

	/** Whether this supported spec directly reduces Magic level or Magic-defence bonus. */
	public static boolean drainsMagicDefence(SpecialWeapon weapon)
	{
		return weapon == SpecialWeapon.ACCURSED_SCEPTRE
			|| weapon == SpecialWeapon.SEERCULL
			|| weapon == SpecialWeapon.EYE_OF_AYAK;
	}

	/**
	 * Queue a defence-draining special attack for processing on the next tick.
	 * Elder maul applies its large reduction before other weapons landing the same
	 * tick, so it's ordered first (mirrors the reference plugin).
	 */
	public void queue(SpecialWeapon weapon, int npcIndex, int hit, int world)
	{
		queue(weapon, npcIndex, hit, world, null);
	}

	public void queue(SpecialWeapon weapon, int npcIndex, int hit, int world, String playerName)
	{
		if (!isSupportedWeapon(weapon))
		{
			return;
		}
		Drain drain = new Drain(weapon, npcIndex, hit, world, playerName, null, -1, null, -1, 0L);
		if (weapon == SpecialWeapon.ELDER_MAUL)
		{
			pending.addFirst(drain);
		}
		else
		{
			pending.addLast(drain);
		}
	}

	/** Only the originating detector allocates IDs. Standard party observations remain legacy. */
	public void queueLocal(SpecialWeapon weapon, int npcIndex, int hit, int world, String playerName)
	{
		if (isSupportedWeapon(weapon))
		{
			pending.addLast(new Drain(weapon, npcIndex, hit, world, playerName,
				java.util.UUID.randomUUID().toString(), client.getTickCount(), sharedTickAnchor,
				sharedTickAnchorTick, sharedTickAnchorValue));
		}
	}

	/** Set or clear the current shared encounter clock origin. Called before local spec capture. */
	void setSharedTickAnchor(String anchor, int tick)
	{
		setSharedTickAnchor(anchor, tick, 0L);
	}

	/**
	 * Install a shared clock origin whose first tick is {@code value}. A timer-based raid clock
	 * passes the raid's elapsed tick, so two clients with different login counters derive the
	 * same raid tick for the same event.
	 */
	void setSharedTickAnchor(String anchor, int tick, long value)
	{
		sharedTickAnchor = anchor;
		sharedTickAnchorTick = tick;
		sharedTickAnchorValue = anchor == null ? 0L : value;
	}

	/** Client thread. */
	public void onGameTick()
	{
		for (Drain drain = pending.poll(); drain != null; drain = pending.poll())
		{
			process(drain);
		}

		if (queuedIndex != -1)
		{
			NPC queuedNpc = npcByIndex(queuedIndex);
			if (queuedNpc != null && queuedNpc.getName() != null
				&& BossDefence.matchingNpc(queuedNpc) != null)
			{
				if (bossIndex != queuedIndex)
				{
					BossDefence queuedBoss = BossDefence.matchingNpc(queuedNpc);
					saveCurrent();
					if (!restore(queuedIndex, queuedBoss) && !restoreForBoss(queuedBoss, queuedNpc))
					{
						if (shouldRebindSameEncounter(queuedBoss))
						{
							rebindBoss(queuedNpc);
						}
						else
						{
							setBoss(queuedNpc.getName(), queuedIndex);
						}
					}
				}
				replayHeld(queuedIndex);
			}
			else if (queuedAtTick >= 0 && client.getTickCount() - queuedAtTick > HELD_DRAIN_TTL_TICKS)
			{
				log.debug("Dropping held drains for npc {} after {} ticks", queuedIndex, HELD_DRAIN_TTL_TICKS);
				clearHeld();
			}
		}

		boolean inCoxRaid = client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1;
		if (bossType != null && bossType.has(BossDefence.Flag.COX_SCALED)
			&& wasInCoxRaid && !inCoxRaid)
		{
			reset("Chambers raid ended");
		}
		wasInCoxRaid = inCoxRaid;

		if (bossType == BossDefence.SOTETSEG)
		{
			updateSotetsegEncounterState();
		}

		// A BPD sync snapshot can seed this tracker before the boss is loaded locally. Once the
		// same logical boss enters our scene, bind the existing synced state to that actor rather
		// than starting a fresh encounter.
		if (bossIndex == -1 && bossType != null)
		{
			NPC replacement = findNpcForBoss(bossType);
			if (replacement != null)
			{
				rebindBoss(replacement);
			}
		}

		if (bossIndex != -1)
		{
			NPC npc = npcByIndex(bossIndex);
			if (npc == null)
			{
				// NPCs are removed from the client's scene when they leave render distance. That is
				// not an encounter reset. Preserve the drained values/history and try to rebind to
				// the same logical boss if its actor is currently visible under another index.
				NPC replacement = findNpcForBoss(bossType);
				if (replacement != null)
				{
					rebindBoss(replacement);
					npc = replacement;
				}
			}

			if (npc != null)
			{
				handlePhaseNpc(npc);
				if (bossIndex != -1 && (npc.isDead() || npc.getHealthRatio() == 0))
				{
					BossMechanics mechanics = BossMechanics.forBoss(bossType);
					if (mechanics.preserveStateAtZeroHp(npc.getId()))
					{
						log.debug("Keeping {} state while transitional actor {} is at 0 HP", bossType, npc.getId());
					}
					else
					{
						if (bossType != null)
						{
							endedBosses.add(bossType);
						}
						removeCurrent("tracked NPC died");
					}
				}
			}
		}

		// Always allow interaction to switch back to a target which already has remembered state.
		// The config only controls creation of a brand-new pre-spec preview.
		followInteractingTarget();
		saveCurrent();
		pruneInactiveTargets();
	}

	/**
	 * Interaction may select an already remembered target even when the pre-spec preview is off.
	 * A brand-new undrained target is created only when "Show before first spec" is enabled.
	 */
	private void followInteractingTarget()
	{
		NPC target = interactingNpc();
		BossDefence targetBoss = target == null || target.getName() == null
			? null : BossDefence.matchingNpc(target);
		if (targetBoss == null || target.getIndex() == bossIndex)
		{
			return;
		}

		saveCurrent();
		if (restore(target.getIndex(), targetBoss) || restoreForBoss(targetBoss, target))
		{
			return;
		}
		if (shouldRebindSameEncounter(targetBoss))
		{
			rebindBoss(target);
			return;
		}
		if (config.defenceAlwaysShow())
		{
			setBoss(target.getName(), target.getIndex());
		}
	}

	private NPC interactingNpc()
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return null;
		}
		Actor target = local.getInteracting();
		return target instanceof NPC ? (NPC) target : null;
	}

	private void process(Drain drain)
	{
		if (drain.getWorld() != client.getWorld())
		{
			log.debug("{} hit {} dropped: world {} != ours {}",
				drain.getWeapon(), drain.getHit(), drain.getWorld(), client.getWorld());
			return;
		}

		int index = drain.getNpcIndex();
		NPC npc = npcByIndex(index);
		if (npc == null || npc.getName() == null)
		{
			log.debug("{} hit {} held: npc {} not in our scene yet", drain.getWeapon(), drain.getHit(), index);
			hold(drain);
			return;
		}
		String name = npc.getName();
		BossDefence incomingBoss = BossDefence.matchingNpc(npc);
		if (incomingBoss == null && bossIndex != index)
		{
			log.debug("{} hit {} dropped: '{}' (npc {}) is not a tracked monster",
				drain.getWeapon(), drain.getHit(), name, index);
			return;
		}
		if (bossIndex != index)
		{
			saveCurrent();
			if (!restore(index, incomingBoss) && !restoreForBoss(incomingBoss, npc))
			{
				if (shouldRebindSameEncounter(incomingBoss))
				{
					rebindBoss(npc);
				}
				else
				{
					setBoss(name, index);
				}
			}
			replayHeld(index);
		}
		handlePhaseNpc(npc);
		if (bossIndex != -1)
		{
			apply(drain);
		}
	}

	private void apply(Drain drain)
	{
		NPC npc = npcByIndex(drain.getNpcIndex());
		int hit = npc == null ? drain.getHit() : drain.getWeapon().computeHit(drain.getHit(), npc);
		drained = true;
		long before = bossDef;
		calculateDefence(drain.getWeapon(), hit, npc);
		recordSpecHistory(drain);
		if (EncounterTimeline.complete(specHistory)
			|| specHistory.size() > EncounterTimeline.MAX_EVENTS
				&& specHistory.stream().allMatch(e -> e.getEventId() != null))
		{
			replayTimeline(new ArrayList<>(specHistory), npc);
		}
		log.debug("{} hit {} on {}: def {} -> {} (base {}, floor {})",
			drain.getWeapon(), hit, bossName, before, bossDef, bossStartDef, minDef);
	}

	/** Keep a drain whose monster we can't see yet. Only one index is ever worth waiting on. */
	private void hold(Drain drain)
	{
		if (queuedIndex != drain.getNpcIndex())
		{
			clearHeld();
			queuedIndex = drain.getNpcIndex();
			queuedAtTick = client.getTickCount();
		}
		queuedDrains.add(drain);
	}

	/**
	 * Apply everything held for a monster that has just been identified. Order is preserved from
	 * {@link #queue}, so an elder maul still lands before the specs it was queued ahead of.
	 */
	private void replayHeld(int index)
	{
		if (queuedIndex == index)
		{
			for (Drain held : queuedDrains)
			{
				apply(held);
			}
		}
		clearHeld();
	}

	private void clearHeld()
	{
		queuedIndex = -1;
		queuedAtTick = -1;
		queuedDrains.clear();
	}

	private void setBoss(String name, int index)
	{
		NPC bossNpc = npcByIndex(index);
		BossDefence boss = BossDefence.matchingNpc(bossNpc);
		specHistory.clear();
		bossName = name;
		bossIndex = index;
		bossType = boss;
		NPC npc = npcByIndex(index);
		bossNpcId = npc == null ? -1 : npc.getId();
		kephriFinalResetApplied = boss == BossDefence.KEPHRI && isKephriFinalPhaseId(bossNpcId);
		sotetsegEncounterState = boss == BossDefence.SOTETSEG
			? client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_TYPE) : -1;
		initializeStats(boss);
		saveCurrent();
		log.debug("Tracking supported NPC '{}' index={} id={} baseDef={} floor={}",
			bossName, bossIndex, bossNpcId, bossStartDef, minDef);
	}

	/** Initialise (or restore) the tracked boss's combat stats without discarding its encounter binding. */
	private void initializeStats(BossDefence boss)
	{
		localTickAnchor = null;
		bossDef = boss != null ? boss.getBaseDef() : 0;
		minDef = boss != null ? boss.getMinDef() : 0;
		atkLevel = boss != null ? boss.getBaseAtk() : 0;
		strLevel = boss != null ? boss.getBaseStr() : 0;
		magicLevel = boss != null ? boss.getBaseMagic() : 0;
		magicDefBonus = boss != null ? boss.getBaseMagicDef() : 0;
		magicUsesDefence = boss != null && boss.has(BossDefence.Flag.MAGIC_USES_DEFENCE);
		demon = boss != null && boss.has(BossDefence.Flag.DEMON);
		accursedApplied = false;
		drained = false;

		// In CoX, the boss's combat levels are scaled up by the (scaled) party size and again
		// in Challenge Mode, but the magic-defence bonus is not. Defence always scales as a
		// defensive stat; Magic counts as defensive for a few monsters and offensive for the rest.
		if (boss != null && boss.has(BossDefence.Flag.COX_SCALED)
			&& client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1)
		{
			int partySize = Math.max(1, client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE_SCALED));
			int n = partySize - 1;
			int defensivePct = coxDefensivePct(n);
			int offensivePct = coxOffensivePct(n);
			boolean magicIsDefensive = boss.has(BossDefence.Flag.COX_MAGIC_IS_DEFENSIVE);

			bossDef = bossDef * defensivePct / PERCENT;
			magicLevel = magicLevel * (magicIsDefensive ? defensivePct : offensivePct) / PERCENT;

			if (client.getVarbitValue(VarbitID.RAIDS_CHALLENGE_MODE) == 1)
			{
				int cmDefencePct = boss.has(BossDefence.Flag.COX_CM_SMALL_DEFENCE_BUMP)
					? (partySize < TEKTON_CM_SMALL_PARTY
						? TEKTON_CM_DEFENCE_PCT_SMALL_PARTY : TEKTON_CM_DEFENCE_PCT)
					: CM_SCALE_PCT;
				bossDef = addPercent(bossDef, cmDefencePct);
				magicLevel = addPercent(magicLevel, magicIsDefensive ? cmDefencePct : CM_SCALE_PCT);
			}
		}
		bossStartDef = bossDef;
		magicStartLevel = magicLevel;
		magicStartDefBonus = magicDefBonus;
	}

	/** Rebind the overlay to a replacement NPC actor while preserving the encounter's drained stats. */
	private void rebindBoss(NPC npc)
	{
		if (npc == null)
		{
			return;
		}
		int oldIndex = bossIndex;
		int oldId = bossNpcId;
		if (oldIndex >= 0)
		{
			tracked.remove(oldIndex);
		}
		if (bossType != null)
		{
			unboundTracked.remove(bossType);
		}
		bossIndex = npc.getIndex();
		bossName = npc.getName();
		bossNpcId = npc.getId();
		log.debug("Rebound {} encounter npc {}:{} -> {}:{} without clearing defence",
			bossType, oldIndex, oldId, bossIndex, bossNpcId);
		handlePhaseNpc(npc);
		saveCurrent();
	}

	/**
	 * Kephri's shield/weak actors are one encounter and retain Defence drains. Her final
	 * enrage actor restores Defence to the encounter's starting value. Sotetseg uses the
	 * ToB encounter varbit for its maze restoration instead.
	 */
	private void handlePhaseNpc(NPC npc)
	{
		if (npc == null || bossType == null)
		{
			return;
		}

		bossNpcId = npc.getId();
		BossMechanics mechanics = BossMechanics.forBoss(bossType);
		if (mechanics.endsEncounterOnActorEntry(bossNpcId))
		{
			reset("boss final phase ended");
			return;
		}
		if (bossType == BossDefence.KEPHRI
			&& mechanics.resetsStatsOnActorEntry(bossNpcId) && !kephriFinalResetApplied)
		{
			specHistory.clear();
			initializeStats(bossType);
			kephriFinalResetApplied = true;
			log.debug("Kephri entered final phase; Defence restored to {}", bossDef);
		}
	}

	/**
	 * Sotetseg is special: the tracker must survive both maze actor swaps, but the game
	 * genuinely restores Sotetseg's Defence at each maze. Keep the encounter/overlay alive
	 * while restoring the stats, and only clear it once the encounter state reaches 0.
	 */
	private void updateSotetsegEncounterState()
	{
		int state = client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_TYPE);
		if (sotetsegEncounterState == -1)
		{
			sotetsegEncounterState = state;
			return;
		}

		if (state == 2 && sotetsegEncounterState != 2)
		{
			specHistory.clear();
			initializeStats(bossType);
			log.debug("Sotetseg maze started; Defence restored to {} without clearing encounter", bossDef);
		}
		else if (state == 0 && sotetsegEncounterState != 0)
		{
			reset("Sotetseg encounter ended");
			return;
		}
		sotetsegEncounterState = state;
	}

	private boolean shouldRebindSameEncounter(BossDefence incomingBoss)
	{
		return bossType != null && incomingBoss != null && incomingBoss == bossType
			&& (bossIndex == -1 || isPhasePersistentBoss(incomingBoss) || npcByIndex(bossIndex) == null);
	}

	private static boolean isPhasePersistentBoss(BossDefence boss)
	{
		return BossMechanics.forBoss(boss).isPhasePersistent();
	}

	private static boolean isKephriFinalPhaseId(int npcId)
	{
		return BossMechanics.KEPHRI.resetsStatsOnActorEntry(npcId);
	}

	/** Find the current actor for the same logical encounter after Jagex replaces its NPC index/id. */
	private NPC findNpcForBoss(BossDefence boss)
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
		if (iterator == null)
		{
			return null;
		}
		while (iterator.hasNext())
		{
			NPC npc = iterator.next();
			if (npc != null && npc.getName() != null && BossDefence.matchingNpc(npc) == boss)
			{
				return npc;
			}
		}
		return null;
	}

	private void recordSpecHistory(Drain drain)
	{
		String playerName = drain.getPlayerName();
		if (playerName == null || playerName.trim().isEmpty())
		{
			playerName = "Unknown";
		}
		else
		{
			playerName = Text.removeTags(playerName).trim();
		}
		if (drain.getEventId() == null)
		{
			specHistory.add(new SpecHistoryEntry(playerName, drain.getWeapon(), drain.getHit()));
			return;
		}
		String tickAnchor = drain.getTickAnchor();
		int tickAnchorTick = drain.getTickAnchorTick();
		long tickAnchorValue = drain.getTickAnchorValue();
		// Hitsplats for the boundary tick can arrive just before the first GameTick callback
		// which notices the raid transition. They still belong to that shared boundary.
		if (drain.getEventId() != null && tickAnchor == null && sharedTickAnchor != null
			&& drain.getObservedTick() == sharedTickAnchorTick)
		{
			tickAnchor = sharedTickAnchor;
			tickAnchorTick = sharedTickAnchorTick;
			tickAnchorValue = sharedTickAnchorValue;
		}
		if (tickAnchor != null)
		{
			long encounterTick = tickAnchorValue + (long) drain.getObservedTick() - tickAnchorTick;
			// A timer origin can precede the event by a tick (capture happens after the sample
			// on a boundary). Only a genuinely pre-raid negative is rejected.
			if (encounterTick >= 0)
			{
				specHistory.add(new SpecHistoryEntry(playerName, drain.getWeapon(), drain.getHit(),
					drain.getEventId(), tickAnchor, encounterTick));
				return;
			}
		}
		if (localTickAnchor == null || drain.getObservedTick() < localAnchorTick
			|| specHistory.stream().noneMatch(e -> localTickAnchor.equals(e.getEventId())))
		{
			localTickAnchor = drain.getEventId();
			localAnchorTick = drain.getObservedTick();
		}
		specHistory.add(new SpecHistoryEntry(playerName, drain.getWeapon(), drain.getHit(),
			drain.getEventId(), localTickAnchor, (long) drain.getObservedTick() - localAnchorTick));
	}

	/**
	 * Spec history for the exact target which is current at the time the caller renders it.
	 * A stale/previous display target must never inherit the active InfoBox history.
	 */
	public List<SpecHistoryEntry> specHistoryForCurrentTarget(int npcIndex)
	{
		if (bossType == null || bossDef < 0 || npcIndex < 0 || npcIndex != bossIndex)
		{
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(new ArrayList<>(specHistory));
	}

	/** Current active target history. Kept for tracker/tests and sync-facing callers. */
	public List<SpecHistoryEntry> specHistory()
	{
		return bossIndex < 0 ? Collections.emptyList() : specHistoryForCurrentTarget(bossIndex);
	}

	public boolean hasDefenceSpecHistory()
	{
		for (SpecHistoryEntry entry : specHistory)
		{
			if (entry != null && drainsDefence(entry.getWeapon()))
			{
				return true;
			}
		}
		return false;
	}

	public boolean hasMagicDefenceSpecHistory()
	{
		for (SpecHistoryEntry entry : specHistory)
		{
			if (entry != null && drainsMagicDefence(entry.getWeapon()))
			{
				return true;
			}
		}
		return false;
	}

	private void calculateDefence(SpecialWeapon weapon, int hit, NPC npc)
	{
		// DWH and Elder Maul reduce the target's current Defence. Arclight-family
		// drains use the encounter's starting (and, in CoX, scaled) Defence.
		long base = bossStartDef;
		switch (weapon)
		{
			case DRAGON_WARHAMMER:
				if (hit > 0)
				{
					bossDef -= bossDef * DWH_DRAIN_PCT / PERCENT;
				}
				else if (weapon.computeDrainPercent(hit, npc) > 0)
				{
					// RuneLite reports Tekton's guaranteed-on-miss DWH drain as a 0.95 multiplier.
					bossDef -= bossDef * 5 / PERCENT;
				}
				break;
			case ELDER_MAUL:
				if (hit > 0)
				{
					bossDef -= bossDef * ELDER_MAUL_DRAIN_PCT / PERCENT;
				}
				else if (weapon.computeDrainPercent(hit, npc) > 0)
				{
					// RuneLite reports Tekton's guaranteed-on-miss Elder Maul drain as a 0.95 multiplier.
					bossDef -= bossDef * 5 / PERCENT;
				}
				break;
			case BANDOS_GODSWORD:
				if (hit > 0)
				{
					// Corp / undowned Bloat take double the BGS drain.
					boolean doubled = bossName.equalsIgnoreCase("Corporeal Beast")
						|| bossName.equalsIgnoreCase("Pestilent Bloat");
					drainBandos(doubled ? hit * 2L : hit);
				}
				break;
			case TONALZTICS_OF_RALOS:
				// Ralos' Rise throws two glaives; each one that lands takes an eighth of
				// the target's current Magic level off its Defence. hit is the number of
				// glaives that connected, not damage.
				for (int i = 0; i < hit; i++)
				{
					bossDef -= magicLevel / RALOS_GLAIVE_MAGIC_DIVISOR;
				}
				break;
			case ARCLIGHT:
			case DARKLIGHT:
				if (hit > 0)
				{
					bossDef -= base * (demon ? ARCLIGHT_DEMON_DRAIN_PCT : ARCLIGHT_DRAIN_PCT) / PERCENT + 1;
				}
				break;
			case EMBERLIGHT:
				if (hit > 0)
				{
					bossDef -= base * (demon ? EMBERLIGHT_DEMON_DRAIN_PCT : EMBERLIGHT_DRAIN_PCT) / PERCENT + 1;
				}
				break;
			case BARRELCHEST_ANCHOR:
				bossDef -= hit / ANCHOR_DAMAGE_DIVISOR;
				break;
			case BONE_DAGGER:
			case DORGESHUUN_CROSSBOW:
				if (bossDef >= base)
				{
					bossDef -= hit;
				}
				break;
			case ACCURSED_SCEPTRE:
				// Condemn takes 15% off the Defence and Magic levels the monster has right
				// now, so landing it after a warhammer drains more than landing it first.
				// The curse doesn't stack, so only the first one to land does anything.
				if (hit > 0 && !accursedApplied)
				{
					accursedApplied = true;
					bossDef = bossDef * CONDEMN_KEEPS_PCT / PERCENT;
					magicLevel = magicLevel * CONDEMN_KEEPS_PCT / PERCENT;
				}
				break;
			case SEERCULL:
				// Soulshot lowers Magic level by the damage dealt, and stacks.
				magicLevel -= hit;
				break;
			case EYE_OF_AYAK:
				// Soul Rend lowers the Magic-defence bonus by the damage dealt,
				// stacking down to a floor of 0 (negative bonuses are left as-is).
				if (hit > 0 && magicDefBonus > 0)
				{
					magicDefBonus = Math.max(0, magicDefBonus - hit);
				}
				break;
			default:
				return; // weapon doesn't drain defence
		}
		bossDef = Math.max(bossDef, minDef);
		magicLevel = Math.max(magicLevel, 0);
	}

	/**
	 * The Bandos godsword drains Defence by the damage dealt, and any damage left over
	 * once Defence bottoms out rolls on into Strength, then Attack, then Magic. A skill
	 * that stops short of zero — including because it hit its floor — ends the spill.
	 */
	private void drainBandos(long damage)
	{
		long start = bossDef;
		bossDef = Math.max(minDef, bossDef - damage);
		damage = bossDef > 0 ? 0 : damage - start;

		if (damage > 0)
		{
			start = strLevel;
			strLevel = Math.max(0, strLevel - damage);
			damage = strLevel > 0 ? 0 : damage - start;
		}
		if (damage > 0)
		{
			start = atkLevel;
			atkLevel = Math.max(0, atkLevel - damage);
			damage = atkLevel > 0 ? 0 : damage - start;
		}
		if (damage > 0)
		{
			magicLevel = Math.max(0, magicLevel - damage);
		}
	}

	/** Integer percentage increase, truncated, as the game applies it. */
	private static long addPercent(long value, int percent)
	{
		return value + value * percent / PERCENT;
	}

	/** CoX defensive stats scale by sqrt(n) + 0.7n percent, for n players beyond the first. */
	private static int coxDefensivePct(int extraPlayers)
	{
		return PERCENT + (int) Math.sqrt(extraPlayers) + extraPlayers * 7 / 10;
	}

	/** CoX offensive stats scale by 7*sqrt(n) + n percent, for n players beyond the first. */
	private static int coxOffensivePct(int extraPlayers)
	{
		return PERCENT + (int) Math.sqrt(extraPlayers) * 7 + extraPlayers;
	}

	private NPC npcByIndex(int index)
	{
		WorldView worldView = client.getTopLevelWorldView();
		return worldView == null ? null : worldView.npcs().byIndex(index);
	}

	private DefenceState toDefenceState(SavedState saved)
	{
		if (saved == null || saved.bossType == null || saved.bossDef < 0)
		{
			return null;
		}
		long roll = (MAGIC_ROLL_LEVEL_OFFSET + (saved.magicUsesDefence ? saved.bossDef : saved.magicLevel))
			* (saved.magicDefBonus + MAGIC_ROLL_BONUS_OFFSET);
		long baseRoll = (MAGIC_ROLL_LEVEL_OFFSET + (saved.magicUsesDefence ? saved.bossStartDef : saved.magicStartLevel))
			* (saved.magicStartDefBonus + MAGIC_ROLL_BONUS_OFFSET);
		return new DefenceState(saved.npcIndex, Text.removeTags(saved.bossName), saved.bossDef, saved.minDef,
			saved.bossStartDef, roll, baseRoll, saved.magicDefBonus, saved.magicStartDefBonus,
			saved.magicLevel, saved.magicStartLevel, saved.drained,
			saved.bossType.has(BossDefence.Flag.TFA));
	}

	private SyncState toSyncState(SavedState saved)
	{
		if (saved == null || saved.bossType == null || saved.bossDef < 0)
		{
			return null;
		}
		return new SyncState(
			Text.removeTags(saved.bossName),
			saved.bossType,
			saved.bossDef,
			saved.minDef,
			saved.bossStartDef,
			saved.atkLevel,
			saved.strLevel,
			saved.magicLevel,
			saved.magicStartLevel,
			saved.magicDefBonus,
			saved.magicStartDefBonus,
			saved.magicUsesDefence,
			saved.demon,
			saved.accursedApplied,
			saved.drained,
			Collections.unmodifiableList(new ArrayList<>(saved.history)));
	}

	/** Current/most recently selected target, used by the detached display and info box. */
	public DefenceState state()
	{
		if (bossType == null || bossDef < 0)
		{
			return null;
		}
		SavedState current = snapshotCurrent();
		return toDefenceState(current);
	}

	/** Every concrete target retained in memory, used by the attached NPC display. */
	public List<DefenceState> states()
	{
		saveCurrent();
		List<DefenceState> out = new ArrayList<>();
		for (SavedState saved : tracked.values())
		{
			DefenceState state = toDefenceState(saved);
			if (state != null)
			{
				out.add(state);
			}
		}
		return out;
	}

	/** Snapshot the active target for BPD-to-BPD party sync. */
	public SyncState syncState()
	{
		return toSyncState(snapshotCurrent());
	}

	/** Every bound drained target this client can authoritatively rebroadcast to the party. */
	public List<SyncTarget> syncTargets()
	{
		saveCurrent();
		List<SyncTarget> out = new ArrayList<>();
		for (SavedState saved : tracked.values())
		{
			SyncState sync = toSyncState(saved);
			if (sync != null && sync.isDrained())
			{
				out.add(new SyncTarget(saved.npcIndex, sync));
			}
		}
		return out;
	}

	/** The remembered state for one logical boss, even when another target is currently active. */
	public SyncState syncStateForBoss(BossDefence boss)
	{
		if (boss == null)
		{
			return null;
		}
		saveCurrent();
		if (bossType == boss)
		{
			return toSyncState(snapshotCurrent());
		}
		SavedState unbound = unboundTracked.get(boss);
		if (unbound != null)
		{
			return toSyncState(unbound);
		}
		SavedState best = null;
		for (SavedState saved : tracked.values())
		{
			if (saved.bossType == boss && (best == null || saved.history.size() > best.history.size()))
			{
				best = saved;
			}
		}
		return toSyncState(best);
	}

	/**
	 * Reconcile concurrent BPD snapshots only where their accepted spec histories prove
	 * a shared encounter prefix and contain unambiguous, non-overlapping new entries.
	 * The caller has already validated world, raid scope, freshness and equal version.
	 *
	 * <p>Never merge identical or unidentifiable events: without per-event IDs such
	 * cases cannot be distinguished from network duplicates. False Defence drains
	 * are worse than declining a merge which a later authoritative snapshot can fix.
	 *
	 * @return true only if the local state was changed by a safe reconciliation.
	 */
	public boolean mergeConcurrentSyncState(SyncState incoming, NPC localNpc)
	{
		if (incoming == null || localNpc == null || incoming.getBossType() == null
			|| BossDefence.matchingNpc(localNpc) != incoming.getBossType()
			|| !BossDefence.isLiveNpc(localNpc))
		{
			return false;
		}

		SyncState local = syncStateForBoss(incoming.getBossType());
		if (local == null || !local.isDrained() || !incoming.isDrained()
			|| local.getBase() != incoming.getBase()
			|| local.getMin() != incoming.getMin()
			|| local.getMagicBaseLevel() != incoming.getMagicBaseLevel()
			|| local.getMagicBaseDef() != incoming.getMagicBaseDef()
			|| local.isMagicUsesDefence() != incoming.isMagicUsesDefence()
			|| local.isDemon() != incoming.isDemon())
		{
			return false;
		}

		// The legacy reconciliation contract compares payloads, not the optional identity fields.
		List<SpecHistoryEntry> localLegacy = new ArrayList<>();
		List<SpecHistoryEntry> incomingLegacy = new ArrayList<>();
		for (SpecHistoryEntry entry : local.getHistory())
		{
			localLegacy.add(new SpecHistoryEntry(entry.getPlayerName(), entry.getWeapon(), entry.getHit()));
		}
		for (SpecHistoryEntry entry : incoming.getHistory())
		{
			incomingLegacy.add(new SpecHistoryEntry(entry.getPlayerName(), entry.getWeapon(), entry.getHit()));
		}
		List<SpecHistoryEntry> additions = ConcurrentSyncHistory.incomingOnly(localLegacy, incomingLegacy);
		if (additions == null)
		{
			return false;
		}
		for (SpecHistoryEntry event : additions)
		{
			if (event.getWeapon() == null || !isSupportedWeapon(event.getWeapon())
				|| event.getHit() < 0)
			{
				return false;
			}
		}

		// Do not merge over a pending local spec: wait for its ordinary tick processing,
		// rather than risk replaying the same attack from two transports.
		for (Drain queued : pending)
		{
			if (queued != null && queued.getNpcIndex() == localNpc.getIndex())
			{
				return false;
			}
		}
		// Operate on the bound target's existing state without applySyncState(), which
		// clears pending hits and changes the user's active display selection.
		saveCurrent();
		SavedState target = tracked.get(localNpc.getIndex());
		if (target == null || target.bossType != incoming.getBossType()
			|| !target.history.equals(local.getHistory()))
		{
			return false;
		}
		SavedState selectedBefore = snapshotCurrent();
		if (selectedBefore == null)
		{
			// Do not select a boss solely because a passive party update arrived.
			return false;
		}
		boolean restoreSelection = selectedBefore.npcIndex != localNpc.getIndex()
			|| selectedBefore.bossType != incoming.getBossType();
		loadSavedState(target);
		for (SpecHistoryEntry event : additions)
		{
			// Preserve RuneLite's weapon-specific hit conversion, including Ralos.
			int hit = event.getWeapon().computeHit(event.getHit(), localNpc);
			calculateDefence(event.getWeapon(), hit, localNpc);
			specHistory.add(event);
		}
		drained = true;
		saveCurrent();
		if (restoreSelection)
		{
			loadSavedState(selectedBefore);
		}
		log.debug("Merged {} distinct concurrent spec(s) for {} history={}",
			additions.size(), incoming.getBossType(), local.getHistory().size() + additions.size());
		return true;
	}

	boolean hasUnprocessedSpecs()
	{
		return !pending.isEmpty() || !queuedDrains.isEmpty();
	}

	/** Unknown actors remain conservative; a known different target cannot block this boss. */
	boolean hasUnprocessedSpecsForBoss(BossDefence boss)
	{
		for (Drain drain : pending)
		{
			NPC npc = npcByIndex(drain.getNpcIndex());
			if (npc == null || BossDefence.matchingNpc(npc) == null
				|| BossDefence.matchingNpc(npc) == boss) { return true; }
		}
		if (!queuedDrains.isEmpty())
		{
			NPC npc = npcByIndex(queuedIndex);
			return npc == null || BossDefence.matchingNpc(npc) == null
				|| BossDefence.matchingNpc(npc) == boss;
		}
		return false;
	}

	boolean hasPendingFor(int npcIndex)
	{
		return queuedIndex == npcIndex && !queuedDrains.isEmpty()
			|| pending.stream().anyMatch(drain -> drain.getNpcIndex() == npcIndex);
	}

	/** Recover identified events before snapshot version comparisons: lower versions can have missing IDs. */
	public boolean mergeEventSyncState(SyncState incoming, NPC npc)
	{
		if (incoming == null || npc == null || !BossDefence.isLiveNpc(npc)
			|| BossDefence.matchingNpc(npc) != incoming.getBossType()) { return false; }
		saveCurrent();
		SavedState target = tracked.get(npc.getIndex());
		if (target == null || target.bossType != incoming.getBossType()
			|| target.bossStartDef != incoming.getBase() || target.minDef != incoming.getMin()
			|| target.magicStartLevel != incoming.getMagicBaseLevel()
			|| target.magicStartDefBonus != incoming.getMagicBaseDef()
			|| target.magicUsesDefence != incoming.isMagicUsesDefence()
			|| target.demon != incoming.isDemon()) { return false; }
		List<SpecHistoryEntry> merged = EncounterTimeline.union(target.history, incoming.getHistory());
		if (merged == null || new java.util.HashSet<>(merged).equals(new java.util.HashSet<>(target.history))) { return false; }
		// Without a shared identified event there is no proof that the two stat epochs agree.
		boolean shared = target.history.stream().anyMatch(a -> incoming.getHistory().stream()
			.anyMatch(b -> a.equals(b) || (a.getEventId() == null || b.getEventId() == null)
				&& EncounterTimeline.samePayload(a, b)));
		if (!shared) { return false; }
		if (hasPendingFor(npc.getIndex())) { return false; }
		SavedState selected = snapshotCurrent();
		loadSavedState(target);
		replayTimeline(merged, npc);
		saveCurrent();
		if (selected != null && selected.npcIndex != npc.getIndex()) { loadSavedState(selected); }
		return true;
	}

	/** Reuse the existing weapon formulas and the actual encounter's scaled baseline. */
	private void replayTimeline(List<SpecHistoryEntry> events, NPC npc)
	{
		events.sort(EncounterTimeline.DISPLAY_ORDER);
		SavedState baseline = snapshotCurrent();
		baseline.bossDef = baseline.bossStartDef;
		baseline.atkLevel = bossType.getBaseAtk();
		baseline.strLevel = bossType.getBaseStr();
		baseline.magicLevel = baseline.magicStartLevel;
		baseline.magicDefBonus = baseline.magicStartDefBonus;
		baseline.accursedApplied = false;
		baseline.history.clear();
		SavedState result = EncounterTimeline.replay(events, baseline, new EncounterTimeline.Replay<SavedState>()
		{
			@Override public SavedState apply(SavedState state, SpecHistoryEntry event)
			{
				loadSavedState(state);
				int hit = npc == null ? event.getHit() : event.getWeapon().computeHit(event.getHit(), npc);
				calculateDefence(event.getWeapon(), hit, npc);
				return snapshotCurrent();
			}

			@Override public SavedState conservative(SavedState a, SavedState b)
			{
				// Independent maxima also keep magic and BGS spill conservative. These are display
				// bounds only; every later rebuild starts from baseline and retains all alternatives.
				a.bossDef = Math.max(a.bossDef, b.bossDef);
				a.atkLevel = Math.max(a.atkLevel, b.atkLevel);
				a.strLevel = Math.max(a.strLevel, b.strLevel);
				a.magicLevel = Math.max(a.magicLevel, b.magicLevel);
				a.magicDefBonus = Math.max(a.magicDefBonus, b.magicDefBonus);
				a.accursedApplied &= b.accursedApplied;
				return a;
			}
		});
		loadSavedState(result);
		specHistory.addAll(events);
		drained = true;
	}

	/** Whether this target already has a live actor binding on this client. */
	public boolean hasBoundNpc(BossDefence boss)
	{
		if (boss == null)
		{
			return false;
		}
		if (bossType == boss && hasBoundNpc())
		{
			return true;
		}
		for (SavedState saved : tracked.values())
		{
			if (saved.bossType == boss)
			{
				NPC npc = npcByIndex(saved.npcIndex);
				if (BossDefence.isLiveNpc(npc))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Cache an absolute party snapshot without changing the current/selected target. This is used
	 * for a remote open-world target that is not yet rendered locally: the state is remembered for
	 * later binding, but detached/InfoBox presentation remains on the user's current local target.
	 */
	public void cacheSyncState(SyncState sync)
	{
		if (sync == null || sync.getBossType() == null || !sync.isDrained())
		{
			return;
		}

		SavedState saved = new SavedState();
		saved.npcIndex = -1;
		saved.bossName = sync.getBossName() == null ? sync.getBossType().getNpcName() : sync.getBossName();
		saved.bossType = sync.getBossType();
		saved.bossNpcId = -1;
		saved.kephriFinalResetApplied = false;
		saved.sotetsegEncounterState = sync.getBossType() == BossDefence.SOTETSEG
			? client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_TYPE) : -1;
		saved.bossDef = sync.getCurrent();
		saved.bossStartDef = sync.getBase();
		saved.minDef = sync.getMin();
		saved.atkLevel = sync.getAttackLevel();
		saved.strLevel = sync.getStrengthLevel();
		saved.magicLevel = sync.getMagicLevel();
		saved.magicStartLevel = sync.getMagicBaseLevel();
		saved.magicDefBonus = sync.getMagicDef();
		saved.magicStartDefBonus = sync.getMagicBaseDef();
		saved.magicUsesDefence = sync.isMagicUsesDefence();
		saved.demon = sync.isDemon();
		saved.accursedApplied = sync.isAccursedApplied();
		saved.drained = true;
		if (sync.getHistory() != null)
		{
			saved.history.addAll(sync.getHistory());
		}

		unboundTracked.put(saved.bossType, saved);
		log.debug("Cached passive BPD sync for {} def={}/{} specs={}",
			saved.bossType, saved.bossDef, saved.bossStartDef, saved.history.size());
	}

	/**
	 * Seed/update one target from another BPD user. The previously active target is saved first,
	 * so a DWH on one Olm hand followed by Ayak/Ralos/etc. on another target does not erase it.
	 * A null localNpc stores the state unbound until that boss enters this client's scene.
	 */
	public void applySyncState(SyncState sync, NPC localNpc)
	{
		if (sync == null || sync.getBossType() == null || !sync.isDrained())
		{
			return;
		}

		saveCurrent();
		SavedState previousTarget = localNpc == null ? null : tracked.get(localNpc.getIndex());
		localTickAnchor = previousTarget == null ? null : previousTarget.localTickAnchor;
		localAnchorTick = previousTarget == null ? 0 : previousTarget.localAnchorTick;
		bossType = sync.getBossType();
		bossName = sync.getBossName() == null ? bossType.getNpcName() : sync.getBossName();
		bossIndex = localNpc == null ? -1 : localNpc.getIndex();
		bossNpcId = localNpc == null ? -1 : localNpc.getId();
		bossDef = sync.getCurrent();
		minDef = sync.getMin();
		bossStartDef = sync.getBase();
		atkLevel = sync.getAttackLevel();
		strLevel = sync.getStrengthLevel();
		magicLevel = sync.getMagicLevel();
		magicStartLevel = sync.getMagicBaseLevel();
		magicDefBonus = sync.getMagicDef();
		magicStartDefBonus = sync.getMagicBaseDef();
		magicUsesDefence = sync.isMagicUsesDefence();
		demon = sync.isDemon();
		accursedApplied = sync.isAccursedApplied();
		drained = true;
		kephriFinalResetApplied = bossType == BossDefence.KEPHRI && isKephriFinalPhaseId(bossNpcId);
		sotetsegEncounterState = bossType == BossDefence.SOTETSEG
			? client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_TYPE) : -1;
		wasInCoxRaid = client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1;

		specHistory.clear();
		if (sync.getHistory() != null)
		{
			specHistory.addAll(sync.getHistory());
		}
		if (localNpc != null)
		{
			unboundTracked.remove(bossType);
		}
		saveCurrent();
		// Snapshot adoption must not erase independent observations on another target.
		log.debug("Applied BPD sync for {} actor={} def={}/{} specs={}",
			bossType, bossIndex, bossDef, bossStartDef, specHistory.size());
	}

	public BossDefence trackedBossType()
	{
		return bossType;
	}

	public boolean hasBoundNpc()
	{
		return bossIndex >= 0 && npcByIndex(bossIndex) != null;
	}

	private SavedState snapshotCurrent()
	{
		if (bossType == null || bossDef < 0)
		{
			return null;
		}
		SavedState s = new SavedState();
		s.npcIndex = bossIndex;
		s.bossName = bossName;
		s.bossType = bossType;
		s.bossNpcId = bossNpcId;
		s.kephriFinalResetApplied = kephriFinalResetApplied;
		s.sotetsegEncounterState = sotetsegEncounterState;
		s.bossDef = bossDef;
		s.bossStartDef = bossStartDef;
		s.minDef = minDef;
		s.atkLevel = atkLevel;
		s.strLevel = strLevel;
		s.magicLevel = magicLevel;
		s.magicStartLevel = magicStartLevel;
		s.magicDefBonus = magicDefBonus;
		s.magicStartDefBonus = magicStartDefBonus;
		s.magicUsesDefence = magicUsesDefence;
		s.demon = demon;
		s.accursedApplied = accursedApplied;
		s.drained = drained;
		s.localTickAnchor = localTickAnchor;
		s.localAnchorTick = localAnchorTick;
		s.history.addAll(specHistory);
		return s;
	}

	private void saveCurrent()
	{
		SavedState s = snapshotCurrent();
		if (s == null)
		{
			return;
		}
		if (s.npcIndex >= 0)
		{
			tracked.put(s.npcIndex, s);
			unboundTracked.remove(s.bossType);
		}
		else
		{
			unboundTracked.put(s.bossType, s);
		}
	}

	private boolean restore(int index, BossDefence expectedBoss)
	{
		SavedState s = tracked.get(index);
		if (s == null || (expectedBoss != null && s.bossType != expectedBoss))
		{
			return false;
		}
		loadSavedState(s);
		return true;
	}

	/** Bind a previously unbound/out-of-scene state to the newly rendered matching boss. */
	private boolean restoreForBoss(BossDefence boss, NPC npc)
	{
		if (boss == null || npc == null)
		{
			return false;
		}

		SavedState unbound = unboundTracked.remove(boss);
		if (unbound != null)
		{
			loadSavedState(unbound);
			bossIndex = npc.getIndex();
			bossNpcId = npc.getId();
			bossName = npc.getName();
			saveCurrent();
			return true;
		}

		// If an actor left render distance and came back with another index, only auto-move a
		// unique missing state of the same logical boss. This avoids conflating two live copies.
		Integer oldKey = null;
		SavedState candidate = null;
		for (Map.Entry<Integer, SavedState> entry : tracked.entrySet())
		{
			SavedState saved = entry.getValue();
			if (saved.bossType == boss && npcByIndex(saved.npcIndex) == null)
			{
				if (candidate != null)
				{
					return false;
				}
				candidate = saved;
				oldKey = entry.getKey();
			}
		}
		if (candidate == null)
		{
			return false;
		}
		tracked.remove(oldKey);
		loadSavedState(candidate);
		bossIndex = npc.getIndex();
		bossNpcId = npc.getId();
		bossName = npc.getName();
		saveCurrent();
		return true;
	}

	private void loadSavedState(SavedState s)
	{
		bossIndex = s.npcIndex;
		bossName = s.bossName;
		bossType = s.bossType;
		bossNpcId = s.bossNpcId;
		kephriFinalResetApplied = s.kephriFinalResetApplied;
		sotetsegEncounterState = s.sotetsegEncounterState;
		bossDef = s.bossDef;
		bossStartDef = s.bossStartDef;
		minDef = s.minDef;
		atkLevel = s.atkLevel;
		strLevel = s.strLevel;
		magicLevel = s.magicLevel;
		magicStartLevel = s.magicStartLevel;
		magicDefBonus = s.magicDefBonus;
		magicStartDefBonus = s.magicStartDefBonus;
		magicUsesDefence = s.magicUsesDefence;
		demon = s.demon;
		accursedApplied = s.accursedApplied;
		drained = s.drained;
		localTickAnchor = s.localTickAnchor;
		localAnchorTick = s.localAnchorTick;
		specHistory.clear();
		specHistory.addAll(s.history);
	}

	private void pruneInactiveTargets()
	{
		Iterator<Map.Entry<Integer, SavedState>> it = tracked.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Integer, SavedState> entry = it.next();
			if (entry.getKey() == bossIndex)
			{
				continue;
			}
			NPC npc = npcByIndex(entry.getKey());
			if (npc != null && (npc.isDead() || npc.getHealthRatio() == 0))
			{
				SavedState ended = entry.getValue();
				BossMechanics mechanics = BossMechanics.forBoss(ended == null ? null : ended.bossType);
				if (mechanics.preserveStateAtZeroHp(npc.getId()))
				{
					continue;
				}
				if (ended != null && ended.bossType != null)
				{
					endedBosses.add(ended.bossType);
				}
				it.remove();
			}
		}
	}

	/**
	 * Return and clear logical boss types whose concrete NPC actor was observed dead this tick.
	 * The plugin decides which encounter types are safe to broadcast as authoritative resets.
	 */
	public Set<BossDefence> consumeEndedBosses()
	{
		Set<BossDefence> ended = new LinkedHashSet<>(endedBosses);
		endedBosses.clear();
		return ended;
	}

	private void clearActiveFields()
	{
		localTickAnchor = null;
		bossIndex = -1;
		bossName = "";
		bossType = null;
		bossNpcId = -1;
		kephriFinalResetApplied = false;
		sotetsegEncounterState = -1;
		bossDef = -1;
		bossStartDef = 0;
		minDef = 0;
		atkLevel = 0;
		strLevel = 0;
		magicLevel = 0;
		magicStartLevel = 0;
		magicDefBonus = 0;
		magicStartDefBonus = 0;
		magicUsesDefence = false;
		demon = false;
		accursedApplied = false;
		drained = false;
		specHistory.clear();
	}

	private void removeCurrent(String reason)
	{
		BossDefence removedBoss = bossType;
		int removedIndex = bossIndex;
		if (removedIndex >= 0)
		{
			tracked.remove(removedIndex);
		}
		if (removedBoss != null)
		{
			unboundTracked.remove(removedBoss);
		}
		log.debug("Remove tracked target {}:{}: {}", removedBoss, removedIndex, reason);
		clearActiveFields();

		List<SavedState> remaining = new ArrayList<>(tracked.values());
		for (int i = remaining.size() - 1; i >= 0; i--)
		{
			SavedState saved = remaining.get(i);
			NPC npc = npcByIndex(saved.npcIndex);
			if (BossDefence.isLiveNpc(npc))
			{
				loadSavedState(saved);
				return;
			}
		}
	}

	/** Every logical boss with remembered drained state, bound or temporarily out of scene. */
	public Set<BossDefence> drainedBosses()
	{
		saveCurrent();
		Set<BossDefence> bosses = new LinkedHashSet<>();
		for (SavedState saved : tracked.values())
		{
			if (saved != null && saved.bossType != null && saved.drained)
			{
				bosses.add(saved.bossType);
			}
		}
		for (SavedState saved : unboundTracked.values())
		{
			if (saved != null && saved.bossType != null && saved.drained)
			{
				bosses.add(saved.bossType);
			}
		}
		return bosses;
	}

	public boolean hasRememberedState(BossDefence boss)
	{
		if (boss == null)
		{
			return false;
		}
		if (bossType == boss && bossDef >= 0)
		{
			return true;
		}
		if (unboundTracked.containsKey(boss))
		{
			return true;
		}
		for (SavedState saved : tracked.values())
		{
			if (saved != null && saved.bossType == boss)
			{
				return true;
			}
		}
		return false;
	}

	/** Remove only one logical boss while retaining other simultaneous/remembered targets. */
	public void clearBossState(BossDefence boss, String reason)
	{
		if (boss == null)
		{
			return;
		}

		boolean clearingActive = bossType == boss;
		tracked.entrySet().removeIf(entry -> entry.getValue() != null && entry.getValue().bossType == boss);
		unboundTracked.remove(boss);

		if (!clearingActive)
		{
			return;
		}

		log.debug("Clear tracked boss {}: {}", boss, reason);
		clearActiveFields();

		List<SavedState> remaining = new ArrayList<>(tracked.values());
		for (int i = remaining.size() - 1; i >= 0; i--)
		{
			SavedState saved = remaining.get(i);
			NPC npc = npcByIndex(saved.npcIndex);
			if (BossDefence.isLiveNpc(npc))
			{
				loadSavedState(saved);
				return;
			}
		}

		SavedState fallback = null;
		for (SavedState saved : unboundTracked.values())
		{
			fallback = saved;
		}
		if (fallback != null)
		{
			loadSavedState(fallback);
		}
	}

	public void reset()
	{
		reset("manual reset");
	}

	public void reset(String reason)
	{
		if (bossType != null || bossIndex != -1 || !tracked.isEmpty() || !unboundTracked.isEmpty()
			|| !pending.isEmpty() || queuedIndex != -1)
		{
			log.debug("Reset defence tracker: {}", reason);
		}
		clearActiveFields();
		tracked.clear();
		unboundTracked.clear();
		endedBosses.clear();
		pending.clear();
		clearHeld();
	}

}
