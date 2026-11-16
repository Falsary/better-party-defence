package net.betterpartydefence;

import net.runelite.api.WorldView;

/**
 * Static encounter classification shared by tracking, transport, and UI code.
 */
final class EncounterScopes
{
	static final int POSITION_TOLERANCE = 16;
	static final int HEALTH_PERCENT_TOLERANCE = 20;

	private EncounterScopes()
	{
	}

	static boolean isRaidEncounterBoss(BossDefence boss)
	{
		return raidScopeType(boss) != -1 || isToaBoss(boss) || isTfaBoss(boss);
	}

	static boolean isTfaBoss(BossDefence boss)
	{
		return boss != null && boss.has(BossDefence.Flag.TFA);
	}

	static boolean isToaBoss(BossDefence boss)
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

	static int raidScopeType(BossDefence boss)
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
		if (chunks == null)
		{
			return hash;
		}
		for (int[][] plane : chunks)
		{
			if (plane == null)
			{
				continue;
			}
			for (int[] row : plane)
			{
				if (row == null)
				{
					continue;
				}
				for (int chunk : row)
				{
					hash = 31 * hash + chunk;
				}
			}
		}
		return hash;
	}
}
