package net.betterpartydefence;

import java.util.Iterator;
import net.betterpartydefence.boss.OcellusActors;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;

// The Fractured Archive actor transitions that affect encounter ownership.
final class TfaEncounterMechanics {
	private TfaEncounterMechanics() {
	}

	static PhasePresence scan(WorldView world) {
		boolean phaseThree = world != null
				&& TargetResolver.uniqueLiveNpc(world, BossDefence.TFA_OCELLUS_P3) != null;
		boolean phaseTwo = false;
		boolean phaseOneDefeated = false;
		if (world != null && world.npcs() != null) {
			Iterator<? extends NPC> iterator = world.npcs().iterator();
			while (iterator != null && iterator.hasNext()) {
				NPC npc = iterator.next();
				phaseTwo |= isOcellusPhaseTwoActor(npc);
				phaseOneDefeated |= isOcellusPhaseOneDefeated(npc);
			}
		}
		return new PhasePresence(phaseTwo, phaseThree, phaseOneDefeated);
	}

	static boolean isOcellusPhaseTwoActor(NPC npc) {
		return OcellusActors.marksPhaseTwo(npc);
	}

	static boolean isOcellusPhaseOneDefeated(NPC npc) {
		return OcellusActors.isPhaseOneDefeated(npc);
	}

	static final class PhasePresence {
		final boolean phaseTwo;
		final boolean phaseThree;
		final boolean phaseOneDefeated;

		private PhasePresence(boolean phaseTwo, boolean phaseThree, boolean phaseOneDefeated) {
			this.phaseTwo = phaseTwo;
			this.phaseThree = phaseThree;
			this.phaseOneDefeated = phaseOneDefeated;
		}

		boolean hasLeftPhaseOne() {
			return phaseTwo || phaseThree || phaseOneDefeated;
		}
	}
}
