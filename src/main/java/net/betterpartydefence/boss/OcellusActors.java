package net.betterpartydefence.boss;

import net.betterpartydefence.BossDefence;
import net.runelite.api.NPC;
import net.runelite.client.util.Text;

// Ocellus actor identity is separate from encounter lifecycle and base stats.
public final class OcellusActors {
	private static final int PHASE_ONE_ACTOR = 16673;
	private static final int PHASE_ONE_ALT_ACTOR = 16674;
	private static final int TRANSITION_ACTOR = 16675;
	private static final int PHASE_TWO_ACTOR = 16676;
	private static final int BLADE_PROP = 16683;
	private static final int PHASE_THREE_COMBAT_LEVEL = 1197;
	private static final int PHASE_THREE_ALT_COMBAT_LEVEL = 1601;
	private static final String OCELLUS_NAME = "Ocellus";
	private static final String BLADE_NAME = "The Blade";

	private OcellusActors() {
	}

	// Only this actor owns the P2 defence pool, regardless of its name.
	public static boolean isAttackablePhaseTwo(NPC npc) {
		return npc != null && npc.getId() == PHASE_TWO_ACTOR;
	}

	static boolean isOcellusName(String name) {
		return OCELLUS_NAME.equalsIgnoreCase(name);
	}

	static boolean isBladeName(String name) {
		return BLADE_NAME.equalsIgnoreCase(name);
	}

	static BossDefence matchingOcellus(NPC npc) {
		if (npc.getId() == TRANSITION_ACTOR) {
			return null;
		}
		if (npc.getCombatLevel() == PHASE_THREE_COMBAT_LEVEL
				|| npc.getCombatLevel() == PHASE_THREE_ALT_COMBAT_LEVEL) {
			return BossDefence.TFA_OCELLUS_P3;
		}
		return isPhaseOneId(npc.getId()) ? BossDefence.TFA_OCELLUS_P1 : null;
	}

	// Props mark the phase boundary but never own defence state.
	public static boolean marksPhaseTwo(NPC npc) {
		if (npc == null) {
			return false;
		}
		if (isAttackablePhaseTwo(npc)) {
			return true;
		}
		if (npc.getName() == null) {
			return false;
		}
		String name = Text.removeTags(npc.getName()).trim();
		return (isOcellusName(name) && npc.getId() == TRANSITION_ACTOR)
				|| (isBladeName(name) && npc.getId() == BLADE_PROP);
	}

	// Seeing P1 at zero health ends P1 even if the NPC disappears.
	public static boolean isPhaseOneDefeated(NPC npc) {
		return npc != null && npc.getName() != null
				&& isOcellusName(Text.removeTags(npc.getName()).trim())
				&& isPhaseOneId(npc.getId())
				&& (npc.isDead() || npc.getHealthRatio() == 0);
	}

	private static boolean isPhaseOneId(int npcId) {
		return npcId == PHASE_ONE_ACTOR || npcId == PHASE_ONE_ALT_ACTOR;
	}
}
