package net.betterpartydefence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.NpcID;
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

	/** Specs which contributed to the current tracked encounter, shown in the info-box tooltip. */
	private final List<SpecHistoryEntry> specHistory = new ArrayList<>();

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
	}

	/** One accepted spec in the current tracked encounter, for the info-box hover history. */
	@Value
	public static class SpecHistoryEntry
	{
		String playerName;
		SpecialWeapon weapon;
		int hit;
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
		Drain drain = new Drain(weapon, npcIndex, hit, world, playerName);
		if (weapon == SpecialWeapon.ELDER_MAUL)
		{
			pending.addFirst(drain);
		}
		else
		{
			pending.addLast(drain);
		}
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
				&& BossDefence.matchingNpcName(queuedNpc.getName()) != null)
			{
				if (bossIndex != queuedIndex)
				{
					BossDefence queuedBoss = BossDefence.matchingNpcName(queuedNpc.getName());
					if (shouldRebindSameEncounter(queuedBoss))
					{
						rebindBoss(queuedNpc);
					}
					else
					{
						setBoss(queuedNpc.getName(), queuedIndex);
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
					reset("tracked NPC died");
				}
			}
		}

		if (config.defenceAlwaysShow())
		{
			followInteractingTarget();
		}
	}

	/**
	 * With "show before any spec" on, display the monster we're attacking at its starting
	 * levels. Once anything has actually been drained we stop following our target, so the
	 * drained monster stays on screen until it dies even if we look away from it.
	 */
	private void followInteractingTarget()
	{
		NPC target = interactingNpc();
		BossDefence targetBoss = target == null || target.getName() == null
			? null : BossDefence.matchingNpcName(target.getName());

		// Keep the last supported target latched when the player yellow-clicks away, moves,
		// or the NPC temporarily leaves render distance. Confirmed death/encounter resets clear it.
		if (targetBoss == null)
		{
			return;
		}

		if (target.getIndex() != bossIndex && shouldRebindSameEncounter(targetBoss))
		{
			rebindBoss(target);
			return;
		}

		if (drained)
		{
			return;
		}
		if (target.getIndex() != bossIndex)
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
		BossDefence incomingBoss = BossDefence.matchingNpcName(name);
		if (incomingBoss == null && bossIndex != index)
		{
			log.debug("{} hit {} dropped: '{}' (npc {}) is not a tracked monster",
				drain.getWeapon(), drain.getHit(), name, index);
			return;
		}
		if (bossIndex != index)
		{
			if (shouldRebindSameEncounter(incomingBoss))
			{
				rebindBoss(npc);
			}
			else
			{
				setBoss(name, index);
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
		BossDefence boss = BossDefence.matchingNpcName(name);
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
		log.debug("Tracking supported NPC '{}' index={} id={} baseDef={} floor={}",
			bossName, bossIndex, bossNpcId, bossStartDef, minDef);
	}

	/** Initialise (or restore) the tracked boss's combat stats without discarding its encounter binding. */
	private void initializeStats(BossDefence boss)
	{
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
		bossIndex = npc.getIndex();
		bossName = npc.getName();
		bossNpcId = npc.getId();
		log.debug("Rebound {} encounter npc {}:{} -> {}:{} without clearing defence",
			bossType, oldIndex, oldId, bossIndex, bossNpcId);
		handlePhaseNpc(npc);
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
		if (bossType == BossDefence.KEPHRI)
		{
			if (bossNpcId == NpcID.TOA_KEPHRI_BOSS_DEAD)
			{
				reset("Kephri final phase ended");
				return;
			}
			if (isKephriFinalPhaseId(bossNpcId) && !kephriFinalResetApplied)
			{
				specHistory.clear();
				initializeStats(bossType);
				kephriFinalResetApplied = true;
				log.debug("Kephri entered final phase; Defence restored to {}", bossDef);
			}
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
		return bossIndex != -1 && incomingBoss != null && incomingBoss == bossType
			&& (isPhasePersistentBoss(incomingBoss) || npcByIndex(bossIndex) == null);
	}

	private static boolean isPhasePersistentBoss(BossDefence boss)
	{
		return boss == BossDefence.KEPHRI || boss == BossDefence.SOTETSEG;
	}

	private static boolean isKephriFinalPhaseId(int npcId)
	{
		return npcId == NpcID.TOA_KEPHRI_BOSS_ENRAGE;
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
			if (npc != null && npc.getName() != null && BossDefence.matchingNpcName(npc.getName()) == boss)
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
		specHistory.add(new SpecHistoryEntry(playerName, drain.getWeapon(), drain.getHit()));
	}

	public List<SpecHistoryEntry> specHistory()
	{
		return Collections.unmodifiableList(new ArrayList<>(specHistory));
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

	public DefenceState state()
	{
		if (bossIndex == -1 || bossDef < 0)
		{
			return null;
		}
		long roll = (MAGIC_ROLL_LEVEL_OFFSET + (magicUsesDefence ? bossDef : magicLevel))
			* (magicDefBonus + MAGIC_ROLL_BONUS_OFFSET);
		long baseRoll = (MAGIC_ROLL_LEVEL_OFFSET + (magicUsesDefence ? bossStartDef : magicStartLevel))
			* (magicStartDefBonus + MAGIC_ROLL_BONUS_OFFSET);
		return new DefenceState(bossIndex, Text.removeTags(bossName), bossDef, minDef, bossStartDef, roll, baseRoll,
			magicDefBonus, magicStartDefBonus, magicLevel, magicStartLevel);
	}

	public void reset()
	{
		reset("manual reset");
	}

	public void reset(String reason)
	{
		if (bossIndex != -1 || !pending.isEmpty() || queuedIndex != -1)
		{
			log.debug("Reset defence tracker: {}", reason);
		}
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
		pending.clear();
		clearHeld();
	}
}
