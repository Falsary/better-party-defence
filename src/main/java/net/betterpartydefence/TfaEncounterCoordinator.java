package net.betterpartydefence;

import java.util.function.BiPredicate;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;

// Tracks TFA runs while room and actor transitions replace scene NPCs.
@Slf4j
final class TfaEncounterCoordinator {
	private final Client client;
	private final DefenceTracker tracker;
	private final BiPredicate<BossDefence, String> resetEncounter;
	private final Runnable clearDisplay;
	private final Runnable clearDisplayGates;

	private boolean phaseOneEnded;

	TfaEncounterCoordinator(Client client, DefenceTracker tracker,
			BiPredicate<BossDefence, String> resetEncounter,
			Runnable clearDisplay, Runnable clearDisplayGates) {
		this.client = client;
		this.tracker = tracker;
		this.resetEncounter = resetEncounter;
		this.clearDisplay = clearDisplay;
		this.clearDisplayGates = clearDisplayGates;
	}

	void onGameTick() {
		reconcileRaidExit();
		reconcilePhaseChange();
	}

	void onNpcSpawned(NPC npc) {
		if (BossDefence.matchingNpc(npc) != BossDefence.TFA_ARCHIVE_GUARDIAN) {
			return;
		}
		if (phaseOneEnded) {
			clearAll("new TFA attempt at Archive Guardian");
			return;
		}
		for (BossDefence boss : BossDefence.values()) {
			if (EncounterScopes.isTfaBoss(boss) && boss != BossDefence.TFA_ARCHIVE_GUARDIAN
					&& tracker.hasRememberedState(boss)) {
				clearAll("fresh TFA run started at Archive Guardian");
				return;
			}
		}
	}

	boolean phaseOneEnded() {
		return phaseOneEnded;
	}

	void reset() {
		phaseOneEnded = false;
	}

	// TFA can unload NPCs during movement without ending the raid.
	private void reconcileRaidExit() {
		WorldView world = client.getTopLevelWorldView();
		if (world == null || world.isInstance()) {
			return;
		}
		clearAll("left The Fractured Archive instance");
	}

	// Ocellus phase 2 uses its own defence pool.
	// Do not carry phase 1 values into the Blade phase.
	private void reconcilePhaseChange() {
		WorldView world = client.getTopLevelWorldView();
		if (world == null || !world.isInstance()) {
			return;
		}

		TfaEncounterMechanics.PhasePresence phase = TfaEncounterMechanics.scan(world);
		if (phase.hasLeftPhaseOne() && !phaseOneEnded) {
			phaseOneEnded = true;
			// Clear old boxes on the transition tick.
			clearDisplay.run();
		}
		if (phaseOneEnded && tracker.hasRememberedState(BossDefence.TFA_OCELLUS_P1)) {
			applyReset(BossDefence.TFA_OCELLUS_P1, "Ocellus left Phase 1");
		}
		// P3 starts from its own baseline.
		// Reset only when the P3 boss NPC appears.
		if (phase.phaseThree && tracker.hasRememberedState(BossDefence.TFA_OCELLUS_P2)) {
			applyReset(BossDefence.TFA_OCELLUS_P2, "The Blade left Phase 2");
		}
	}

	private void clearAll(String reason) {
		phaseOneEnded = false;
		boolean changed = false;
		for (BossDefence boss : BossDefence.values()) {
			if (EncounterScopes.isTfaBoss(boss) && tracker.hasRememberedState(boss)) {
				applyReset(boss, reason);
				changed = true;
			}
		}
		if (changed) {
			clearDisplay.run();
			clearDisplayGates.run();
			log.debug("Cleared TFA raid: {}", reason);
		}
	}

	private void applyReset(BossDefence boss, String reason) {
		resetEncounter.test(boss, reason);
	}
}
