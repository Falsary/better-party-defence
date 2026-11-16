package net.betterpartydefence.boss;

import net.betterpartydefence.BossDefence;
import net.runelite.api.gameval.NpcID;

// Encounter lifetime rules that do not belong in the boss stat catalogue.
public enum BossLifecycle {
	STANDARD(false),
	TEKTON(true),
	DREADNOTH(true),
	KEPHRI(true) {
		@Override
		public boolean preserveStateAtZeroHp(int npcId) {
			// Shielded and weak Kephri can sit at zero HP mid-encounter.
			return npcId == NpcID.TOA_KEPHRI_BOSS_SHIELDED
					|| npcId == NpcID.TOA_KEPHRI_BOSS_WEAK;
		}

		@Override
		public boolean resetsStatsOnActorEntry(int npcId) {
			return npcId == NpcID.TOA_KEPHRI_BOSS_ENRAGE;
		}

		@Override
		public boolean endsEncounterOnActorEntry(int npcId) {
			return npcId == NpcID.TOA_KEPHRI_BOSS_DEAD;
		}
	},
	SOTETSEG(true),
	TFA_PHASE_PERSISTENT(true) {
		@Override
		public boolean preserveStateAtZeroHp(int npcId) {
			return true;
		}
	};

	private final boolean phasePersistent;

	BossLifecycle(boolean phasePersistent) {
		this.phasePersistent = phasePersistent;
	}

	public boolean isPhasePersistent() {
		return phasePersistent;
	}

	public boolean preserveStateAtZeroHp(int npcId) {
		return false;
	}

	public boolean resetsStatsOnActorEntry(int npcId) {
		return false;
	}

	public boolean endsEncounterOnActorEntry(int npcId) {
		return false;
	}

	public static BossLifecycle forBoss(BossDefence boss) {
		if (boss == null) {
			return STANDARD;
		}
		switch (boss.trackingType()) {
			case TEKTON:
				return TEKTON;
			case TFA_DREADNOTH:
				return DREADNOTH;
			case KEPHRI:
				return KEPHRI;
			case SOTETSEG:
				return SOTETSEG;
			case TFA_ARCHIVE_GUARDIAN:
			case TFA_XARTH:
			case TFA_OCELLUS_P1:
			case TFA_OCELLUS_P2:
			case TFA_OCELLUS_P3:
				return TFA_PHASE_PERSISTENT;
			default:
				return STANDARD;
		}
	}
}
