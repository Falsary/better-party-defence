package net.betterpartydefence;

import java.util.HashMap;
import java.util.Map;

// Hide the first undrained frame while peer state loads.
final class InitialSyncDisplayGate {
	private static final class Gate {
		private final BossDefence boss;
		private final int expiresAtTick;

		private Gate(BossDefence boss, int expiresAtTick) {
			this.boss = boss;
			this.expiresAtTick = expiresAtTick;
		}
	}

	private final Map<Integer, Gate> gates = new HashMap<>();

	// Do not extend an active grace period.
	void arm(int npcIndex, BossDefence boss, int tick, int graceTicks) {
		boss = boss == null ? null : boss.trackingType();
		if (npcIndex < 0 || boss == null || graceTicks <= 0) {
			return;
		}

		Gate existing = gates.get(npcIndex);
		if (existing != null && tick < existing.expiresAtTick && existing.boss == boss) {
			return;
		}
		gates.put(npcIndex, new Gate(boss, tick + graceTicks));
	}

	// Validated party state releases the gate.
	void release(int npcIndex, BossDefence boss) {
		boss = boss == null ? null : boss.trackingType();
		Gate gate = gates.get(npcIndex);
		if (gate != null && gate.boss == boss) {
			gates.remove(npcIndex);
		}
	}

	// Clear gates for an ended encounter.
	void clearForBoss(BossDefence boss) {
		if (boss == null) {
			return;
		}
		BossDefence trackingBoss = boss.trackingType();
		gates.entrySet().removeIf(entry -> entry.getValue().boss == trackingBoss);
	}

	// Drained state is always displayable.
	boolean isWaiting(DefenceTracker.DefenceState state, int tick) {
		if (state == null || state.isDrained()) {
			return false;
		}
		Gate gate = gates.get(state.getNpcIndex());
		return gate != null && tick < gate.expiresAtTick;
	}

	void expire(int tick) {
		gates.entrySet().removeIf(entry -> tick >= entry.getValue().expiresAtTick);
	}

	void clear() {
		gates.clear();
	}
}
