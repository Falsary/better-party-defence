package net.betterpartydefence;

import java.util.HashMap;
import java.util.Map;

/**
 * Presentation-only grace period for a newly seen party boss.
 *
 * <p>When a boss appears, the plugin requests an authoritative party snapshot. Suppress
 * the initial undrained display for a few game ticks so it does not briefly show the
 * boss's full Defence while a previously drained state is in flight. This class does
 * not alter DefenceTracker state, drain calculations, or party messages.
 */
final class InitialSyncDisplayGate
{
	private static final class Gate
	{
		private final BossDefence boss;
		private final int expiresAtTick;

		private Gate(BossDefence boss, int expiresAtTick)
		{
			this.boss = boss;
			this.expiresAtTick = expiresAtTick;
		}
	}

	private final Map<Integer, Gate> gates = new HashMap<>();

	/** Do not restart an existing grace period when a presence update is repeated. */
	void arm(int npcIndex, BossDefence boss, int tick, int graceTicks)
	{
		if (npcIndex < 0 || boss == null || graceTicks <= 0)
		{
			return;
		}

		Gate existing = gates.get(npcIndex);
		if (existing != null && tick < existing.expiresAtTick && existing.boss == boss)
		{
			return;
		}
		gates.put(npcIndex, new Gate(boss, tick + graceTicks));
	}

	/** A verified peer snapshot removes the waiting state immediately. */
	void release(int npcIndex, BossDefence boss)
	{
		Gate gate = gates.get(npcIndex);
		if (gate != null && gate.boss == boss)
		{
			gates.remove(npcIndex);
		}
	}

	/** A local or synced drain must never be hidden behind the presentation grace period. */
	boolean isWaiting(DefenceTracker.DefenceState state, int tick)
	{
		if (state == null || state.isDrained())
		{
			return false;
		}
		Gate gate = gates.get(state.getNpcIndex());
		return gate != null && tick < gate.expiresAtTick;
	}

	void expire(int tick)
	{
		gates.entrySet().removeIf(entry -> tick >= entry.getValue().expiresAtTick);
	}

	void clear()
	{
		gates.clear();
	}
}
