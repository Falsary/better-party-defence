package net.betterpartydefence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.VarbitID;

// Applies one reset when every occupied ToA party orb is dead.
final class ToaEncounterCoordinator {
	private static final int PARTY_MEMBER_DEAD = 30;
	private static final int[] PARTY_HEALTH_VARBITS = {
			VarbitID.TOA_CLIENT_P0, VarbitID.TOA_CLIENT_P1, VarbitID.TOA_CLIENT_P2, VarbitID.TOA_CLIENT_P3,
			VarbitID.TOA_CLIENT_P4, VarbitID.TOA_CLIENT_P5, VarbitID.TOA_CLIENT_P6, VarbitID.TOA_CLIENT_P7
	};

	private final Client client;
	private final DefenceTracker tracker;
	private final Consumer<BossDefence> resetEncounter;
	private boolean partyWasFullyDead;

	ToaEncounterCoordinator(Client client, DefenceTracker tracker, Consumer<BossDefence> resetEncounter) {
		this.client = client;
		this.tracker = tracker;
		this.resetEncounter = resetEncounter;
	}

	void onGameTick() {
		List<BossDefence> remembered = rememberedBosses();
		if (!isPartyFullyDead()) {
			partyWasFullyDead = false;
			return;
		}
		if (partyWasFullyDead || remembered.isEmpty()) {
			partyWasFullyDead = true;
			return;
		}

		partyWasFullyDead = true;
		for (BossDefence boss : remembered) {
			resetEncounter.accept(boss);
		}
	}

	void reset() {
		partyWasFullyDead = false;
	}

	private List<BossDefence> rememberedBosses() {
		List<BossDefence> bosses = new ArrayList<>();
		for (BossDefence boss : BossDefence.values()) {
			if (EncounterScopes.isToaBoss(boss) && tracker.hasRememberedState(boss)) {
				bosses.add(boss);
			}
		}
		return bosses;
	}

	private boolean isPartyFullyDead() {
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || !worldView.isInstance()) {
			return false;
		}

		boolean anyOccupiedSlot = false;
		for (int varbit : PARTY_HEALTH_VARBITS) {
			int health = client.getVarbitValue(varbit);
			if (health == 0) {
				continue;
			}
			anyOccupiedSlot = true;
			if (health != PARTY_MEMBER_DEAD) {
				return false;
			}
		}
		return anyOccupiedSlot;
	}
}
