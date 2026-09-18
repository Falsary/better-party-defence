package net.betterpartydefence;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.betterpartydefence.DefenceTracker.DefenceState;

/**
 * Presentation-only first-render gate used while a newly visible party-eligible boss asks peers
 * for a better absolute state. It never mutates DefenceTracker state or encounter lifecycle.
 */
final class InitialSyncDisplayGate
{
	private final Map<Integer, Gate> gatesByNpcIndex = new HashMap<>();

	void arm(int npcIndex, BossDefence boss, int currentTick, int graceTicks)
	{
		if (npcIndex < 0 || boss == null || graceTicks <= 0)
		{
			return;
		}

		// Never extend an existing wait merely because another immediate scan ran.
		gatesByNpcIndex.putIfAbsent(npcIndex, new Gate(boss, currentTick + graceTicks));
	}

	void release(int npcIndex, BossDefence boss)
	{
		Gate gate = gatesByNpcIndex.get(npcIndex);
		if (gate != null && gate.boss == boss)
		{
		gatesByNpcIndex.remove(npcIndex);
		}
	}

	boolean isWaiting(DefenceState state, int currentTick)
	{
		if (state == null || state.getNpcIndex() < 0)
		{
			return false;
		}

		Gate gate = gatesByNpcIndex.get(state.getNpcIndex());
		if (gate == null)
		{
			return false;
		}

		if (currentTick >= gate.expiresAtTick)
		{
			gatesByNpcIndex.remove(state.getNpcIndex());
			return false;
		}

		BossDefence stateBoss = BossDefence.matchingNpcName(state.getName());
		if (stateBoss != gate.boss)
		{
			gatesByNpcIndex.remove(state.getNpcIndex());
			return false;
		}

		return true;
	}

	void expire(int currentTick)
	{
		Iterator<Map.Entry<Integer, Gate>> iterator = gatesByNpcIndex.entrySet().iterator();
		while (iterator.hasNext())
		{
			if (currentTick >= iterator.next().getValue().expiresAtTick)
			{
				iterator.remove();
			}
		}
	}

	void clear()
	{
		gatesByNpcIndex.clear();
	}

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
}
