package net.betterpartydefence;

import net.runelite.api.gameval.NpcID;

/**
 * Optional per-boss lifecycle rules layered over the generic defence tracker.
 *
 * <p>The default profile deliberately preserves the tracker behaviour used by existing bosses.
 * Bosses only opt in here when their NPC actor lifecycle does not map cleanly to an encounter
 * lifecycle (for example, Kephri temporarily reaching 0 HP between shield phases).</p>
 */
enum BossMechanics
{
	STANDARD(false),
	KEPHRI(true)
	{
		@Override
		boolean preserveStateAtZeroHp(int npcId)
		{
			// Shielded/weak Kephri can sit at 0 HP while the same encounter continues.
			// The final enrage actor is intentionally excluded so its death remains terminal.
			return npcId == NpcID.TOA_KEPHRI_BOSS_SHIELDED
				|| npcId == NpcID.TOA_KEPHRI_BOSS_WEAK;
		}

		@Override
		boolean resetsStatsOnActorEntry(int npcId)
		{
			return npcId == NpcID.TOA_KEPHRI_BOSS_ENRAGE;
		}

		@Override
		boolean endsEncounterOnActorEntry(int npcId)
		{
			return npcId == NpcID.TOA_KEPHRI_BOSS_DEAD;
		}
	},
	SOTETSEG(true),
	/**
	 * Boss actor changes/temporary zero-health during phases are not a raid reset.
	 * P3 Ocellus must retain reductions over every quadrant (21 Oct hotfix).
	 * Guard the live state until the authoritative raid exit/new-attempt reset.
	 */
	TFA_PHASE_PERSISTENT(true)
	{
		@Override
		boolean preserveStateAtZeroHp(int npcId)
		{
			return true;
		}
	};

	private final boolean phasePersistent;

	BossMechanics(boolean phasePersistent)
	{
		this.phasePersistent = phasePersistent;
	}

	boolean isPhasePersistent()
	{
		return phasePersistent;
	}

	boolean preserveStateAtZeroHp(int npcId)
	{
		return false;
	}

	boolean resetsStatsOnActorEntry(int npcId)
	{
		return false;
	}

	boolean endsEncounterOnActorEntry(int npcId)
	{
		return false;
	}

	static BossMechanics forBoss(BossDefence boss)
	{
		if (boss == BossDefence.KEPHRI)
		{
			return KEPHRI;
		}
		if (boss == BossDefence.SOTETSEG)
		{
			return SOTETSEG;
		}
		if (boss == BossDefence.TFA_ARCHIVE_GUARDIAN
			|| boss == BossDefence.TFA_XARTH
			|| boss == BossDefence.TFA_OCELLUS_P1
			|| boss == BossDefence.TFA_OCELLUS_P2
			|| boss == BossDefence.TFA_OCELLUS_P3)
		{
			return TFA_PHASE_PERSISTENT;
		}
		return STANDARD;
	}
}
