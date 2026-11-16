package net.betterpartydefence;

import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.betterpartydefence.DefenceTracker.SpellDrain;
import net.betterpartydefence.DefenceTracker.SyncState;
import net.runelite.api.NPC;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

// Mutable stats for one encounter. The tracker owns target and party state.
final class CombatStats {
	private static final int DWH_DRAIN_PCT = 30;
	private static final int ELDER_MAUL_DRAIN_PCT = 35;
	// Condemn keeps 85% of current values.
	private static final int CONDEMN_KEEPS_PCT = 85;
	private static final int ARCLIGHT_DRAIN_PCT = 5;
	private static final int ARCLIGHT_DEMON_DRAIN_PCT = 10;
	private static final int EMBERLIGHT_DRAIN_PCT = 5;
	private static final int EMBERLIGHT_DEMON_DRAIN_PCT = 15;
	// Each Ralos glaive drains one eighth of current Magic.
	private static final int RALOS_GLAIVE_MAGIC_DIVISOR = 8;
	private static final int ANCHOR_DAMAGE_DIVISOR = 10;

	private static final int CM_SCALE_PCT = 50;
	private static final int TEKTON_CM_SMALL_PARTY = 4;
	private static final int TEKTON_CM_DEFENCE_PCT_SMALL_PARTY = 20;
	private static final int TEKTON_CM_DEFENCE_PCT = 35;

	long defence = -1;
	long startDefence;
	long minDefence;
	long attackLevel;
	long strengthLevel;
	long magicLevel;
	long startMagicLevel;
	long magicDefenceBonus;
	long startMagicDefenceBonus;
	boolean magicUsesDefence;
	boolean demon;
	boolean accursedApplied;
	boolean drained;

	static CombatStats initial(BossDefence boss, int partySize, boolean inCoxRaid, boolean challengeMode) {
		CombatStats stats = new CombatStats();
		if (boss == null) {
			return stats;
		}

		stats.defence = boss.getBaseDef();
		stats.minDefence = boss.getMinDef();
		stats.attackLevel = boss.getBaseAtk();
		stats.strengthLevel = boss.getBaseStr();
		stats.magicLevel = boss.getBaseMagic();
		stats.magicDefenceBonus = boss.getBaseMagicDef();
		stats.magicUsesDefence = boss.has(BossDefence.Flag.MAGIC_USES_DEFENCE);
		stats.demon = boss.has(BossDefence.Flag.DEMON);

		// CoX scales combat levels, not the magic-defence bonus.
		if (boss.has(BossDefence.Flag.COX_SCALED) && inCoxRaid) {
			int players = Math.max(1, partySize);
			int extraPlayers = players - 1;
			int defensivePercent = DefenceMath.coxDefensivePercent(extraPlayers);
			int offensivePercent = DefenceMath.coxOffensivePercent(extraPlayers);
			boolean magicIsDefensive = boss.has(BossDefence.Flag.COX_MAGIC_SCALED);

			stats.defence = stats.defence * defensivePercent / DefenceMath.PERCENT;
			stats.magicLevel = stats.magicLevel
					* (magicIsDefensive ? defensivePercent : offensivePercent) / DefenceMath.PERCENT;

			if (challengeMode) {
				int challengeModeDefence = boss.has(BossDefence.Flag.TEKTON_CM)
						? (players < TEKTON_CM_SMALL_PARTY
								? TEKTON_CM_DEFENCE_PCT_SMALL_PARTY
								: TEKTON_CM_DEFENCE_PCT)
						: CM_SCALE_PCT;
				stats.defence = DefenceMath.addPercent(stats.defence, challengeModeDefence);
				stats.magicLevel = DefenceMath.addPercent(
						stats.magicLevel, magicIsDefensive ? challengeModeDefence : CM_SCALE_PCT);
			}
		}

		stats.startDefence = stats.defence;
		stats.startMagicLevel = stats.magicLevel;
		stats.startMagicDefenceBonus = stats.magicDefenceBonus;
		return stats;
	}

	static CombatStats fromSync(SyncState sync) {
		CombatStats stats = new CombatStats();
		stats.defence = sync.getCurrent();
		stats.minDefence = sync.getMin();
		stats.startDefence = sync.getBase();
		stats.attackLevel = sync.getAttackLevel();
		stats.strengthLevel = sync.getStrengthLevel();
		stats.magicLevel = sync.getMagicLevel();
		stats.startMagicLevel = sync.getMagicBaseLevel();
		stats.magicDefenceBonus = sync.getMagicDef();
		stats.startMagicDefenceBonus = sync.getMagicBaseDef();
		stats.magicUsesDefence = sync.isMagicUsesDefence();
		stats.demon = sync.isDemon();
		stats.accursedApplied = sync.isAccursedApplied();
		stats.drained = true;
		return stats;
	}

	CombatStats copy() {
		CombatStats copy = new CombatStats();
		copy.defence = defence;
		copy.startDefence = startDefence;
		copy.minDefence = minDefence;
		copy.attackLevel = attackLevel;
		copy.strengthLevel = strengthLevel;
		copy.magicLevel = magicLevel;
		copy.startMagicLevel = startMagicLevel;
		copy.magicDefenceBonus = magicDefenceBonus;
		copy.startMagicDefenceBonus = startMagicDefenceBonus;
		copy.magicUsesDefence = magicUsesDefence;
		copy.demon = demon;
		copy.accursedApplied = accursedApplied;
		copy.drained = drained;
		return copy;
	}

	CombatStats undrainedBaseline(BossDefence boss) {
		CombatStats baseline = copy();
		baseline.defence = baseline.startDefence;
		baseline.attackLevel = boss.getBaseAtk();
		baseline.strengthLevel = boss.getBaseStr();
		baseline.magicLevel = baseline.startMagicLevel;
		baseline.magicDefenceBonus = baseline.startMagicDefenceBonus;
		baseline.accursedApplied = false;
		baseline.drained = false;
		return baseline;
	}

	void applyHistoryEvent(SpecHistoryEntry event, NPC npc, BossDefence bossType, String bossName) {
		if (event.getSpell() != null) {
			applySpell(event.getSpell(), bossType);
			return;
		}
		int hit = npc == null ? event.getHit() : event.getWeapon().computeHit(event.getHit(), npc);
		applyWeapon(event.getWeapon(), hit, npc, bossName);
	}

	void applySpell(SpellDrain spell, BossDefence bossType) {
		defence = spell.reduce(defence, startDefence, minDefence);
		if (spell == SpellDrain.SHADOW_BARRAGE) {
			attackLevel = spell.reduce(attackLevel, bossType.getBaseAtk(), 0);
			strengthLevel = spell.reduce(strengthLevel, bossType.getBaseStr(), 0);
		}
	}

	void applyWeapon(SpecialWeapon weapon, int hit, NPC npc, String bossName) {
		// DWH and Elder Maul use current defence.
		// Arclight-family drains use the encounter base.
		long base = startDefence;
		switch (weapon) {
			case DRAGON_WARHAMMER:
				drainCurrentDefence(weapon, hit, npc, DWH_DRAIN_PCT);
				break;
			case ELDER_MAUL:
				drainCurrentDefence(weapon, hit, npc, ELDER_MAUL_DRAIN_PCT);
				break;
			case BANDOS_GODSWORD:
				if (hit > 0) {
					// Corp and undowned Bloat take double BGS.
					boolean doubled = bossName.equalsIgnoreCase("Corporeal Beast")
							|| bossName.equalsIgnoreCase("Pestilent Bloat");
					applyBandos(doubled ? hit * 2L : hit);
				}
				break;
			case TONALZTICS_OF_RALOS:
				// hit is the number of connected glaives, not damage.
				defence -= (magicLevel / RALOS_GLAIVE_MAGIC_DIVISOR) * Math.max(0, hit);
				break;
			case ARCLIGHT:
			case DARKLIGHT:
				drainBaseDefence(hit, ARCLIGHT_DRAIN_PCT, ARCLIGHT_DEMON_DRAIN_PCT);
				break;
			case EMBERLIGHT:
				drainBaseDefence(hit, EMBERLIGHT_DRAIN_PCT, EMBERLIGHT_DEMON_DRAIN_PCT);
				break;
			case BARRELCHEST_ANCHOR:
				defence -= hit / ANCHOR_DAMAGE_DIVISOR;
				break;
			case BONE_DAGGER:
			case DORGESHUUN_CROSSBOW:
				if (defence >= base) {
					defence -= hit;
				}
				break;
			case ACCURSED_SCEPTRE:
				// Only the first landed Condemn applies.
				if (hit > 0 && !accursedApplied) {
					accursedApplied = true;
					defence = defence * CONDEMN_KEEPS_PCT / DefenceMath.PERCENT;
					magicLevel = magicLevel * CONDEMN_KEEPS_PCT / DefenceMath.PERCENT;
				}
				break;
			case SEERCULL:
				// Soulshot drains Magic by damage and stacks.
				magicLevel -= hit;
				break;
			case EYE_OF_AYAK:
				// Soul Rend drains magic-defence bonus down to zero.
				if (hit > 0 && magicDefenceBonus > 0) {
					magicDefenceBonus = Math.max(0, magicDefenceBonus - hit);
				}
				break;
			default:
				return;
		}
		defence = Math.max(defence, minDefence);
		magicLevel = Math.max(magicLevel, 0);
	}

	void keepConservativeMaximum(CombatStats other) {
		defence = Math.max(defence, other.defence);
		attackLevel = Math.max(attackLevel, other.attackLevel);
		strengthLevel = Math.max(strengthLevel, other.strengthLevel);
		magicLevel = Math.max(magicLevel, other.magicLevel);
		magicDefenceBonus = Math.max(magicDefenceBonus, other.magicDefenceBonus);
		accursedApplied &= other.accursedApplied;
	}

	// Skip equivalent orders that unlock the same events.
	static String effectKey(SpecHistoryEntry event, NPC npc) {
		if (event != null && event.getSpell() != null) {
			return event.getSpell().name();
		}
		if (event == null || event.getWeapon() == null) {
			return null;
		}
		SpecialWeapon weapon = event.getWeapon();
		int hit = npc == null ? event.getHit() : weapon.computeHit(event.getHit(), npc);
		switch (weapon) {
			case DRAGON_WARHAMMER:
			case ELDER_MAUL:
				// Only whether the hit landed and Tekton's miss drain affect the result.
				return hit > 0
						? weapon.name() + ":positive"
						: weapon.name() + ":zero:" + (weapon.computeDrainPercent(hit, npc) > 0 ? 1 : 0);
			case ARCLIGHT:
			case DARKLIGHT:
			case EMBERLIGHT:
				// Drain uses encounter base defence.
				return weapon.name() + (hit > 0 ? ":positive" : ":zero");
			case ACCURSED_SCEPTRE:
				// Only the first Condemn matters.
				return weapon.name() + (hit > 0 ? ":positive" : ":zero");
			default:
				// These formulas use the hit value.
				return weapon.name() + ":" + hit;
		}
	}

	private void drainCurrentDefence(SpecialWeapon weapon, int hit, NPC npc, int percent) {
		if (hit > 0) {
			defence -= defence * percent / DefenceMath.PERCENT;
		} else if (weapon.computeDrainPercent(hit, npc) > 0) {
			// Tekton hammer and maul misses drain 5%.
			defence -= defence * 5 / DefenceMath.PERCENT;
		}
	}

	private void drainBaseDefence(int hit, int percent, int demonPercent) {
		if (hit > 0) {
			defence -= startDefence * (demon ? demonPercent : percent) / DefenceMath.PERCENT + 1;
		}
	}

	private void applyBandos(long damage) {
		DefenceMath.BandosResult result = DefenceMath.applyBandos(
				defence, minDefence, strengthLevel, attackLevel, magicLevel, damage);
		defence = result.getDefence();
		strengthLevel = result.getStrength();
		attackLevel = result.getAttack();
		magicLevel = result.getMagic();
	}
}
