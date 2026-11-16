package net.betterpartydefence;

import lombok.Value;

/**
 * Stateless Defence formulas shared by tracking and synchronization.
 */
final class DefenceMath
{
	static final int PERCENT = 100;

	private static final int MAGIC_ROLL_LEVEL_OFFSET = 9;
	private static final int MAGIC_ROLL_BONUS_OFFSET = 64;

	private DefenceMath()
	{
	}

	static long magicRoll(long level, long bonus)
	{
		return (MAGIC_ROLL_LEVEL_OFFSET + level) * (bonus + MAGIC_ROLL_BONUS_OFFSET);
	}

	// Apply an integer percentage increase with truncation.
	static long addPercent(long value, int percent)
	{
		return value + value * percent / PERCENT;
	}

	// CoX defensive scaling for party size.
	static int coxDefensivePercent(int extraPlayers)
	{
		return PERCENT + (int) Math.sqrt(extraPlayers) + extraPlayers * 7 / 10;
	}

	// CoX offensive scaling for party size.
	static int coxOffensivePercent(int extraPlayers)
	{
		return PERCENT + (int) Math.sqrt(extraPlayers) * 7 + extraPlayers;
	}

	/**
	 * BGS drains Defence, then Strength, Attack, and Magic until damage is exhausted.
	 */
	static BandosResult applyBandos(long defence, long defenceFloor, long strength, long attack,
		long magic, long damage)
	{
		long remaining = damage;
		long nextDefence = Math.max(defenceFloor, defence - remaining);
		remaining = nextDefence > 0 ? 0 : remaining - defence;

		long nextStrength = strength;
		if (remaining > 0)
		{
			nextStrength = Math.max(0, strength - remaining);
			remaining = nextStrength > 0 ? 0 : remaining - strength;
		}

		long nextAttack = attack;
		if (remaining > 0)
		{
			nextAttack = Math.max(0, attack - remaining);
			remaining = nextAttack > 0 ? 0 : remaining - attack;
		}

		long nextMagic = remaining > 0 ? Math.max(0, magic - remaining) : magic;
		return new BandosResult(nextDefence, nextStrength, nextAttack, nextMagic);
	}

	@Value
	static class BandosResult
	{
		long defence;
		long strength;
		long attack;
		long magic;
	}
}
