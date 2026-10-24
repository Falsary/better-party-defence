package net.betterpartydefence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InitialSyncDisplayGateTest
{
	private DefenceTracker.DefenceState state(int index, boolean drained)
	{
		return new DefenceTracker.DefenceState(index, "Example boss",
			100, 0, 100, 0, 0, 0, 0, 0, 0, drained);
	}

	@Test
	public void waitsOnlyForTheShortInitialPeriod()
	{
		InitialSyncDisplayGate gate = new InitialSyncDisplayGate();
		gate.arm(7, BossDefence.TEKTON, 100, 4);
		assertTrue(gate.isWaiting(state(7, false), 100));
		assertTrue(gate.isWaiting(state(7, false), 103));
		assertFalse(gate.isWaiting(state(7, false), 104));
		gate.expire(104);
		assertFalse(gate.isWaiting(state(7, false), 104));
	}

	@Test
	public void doesNotHideDrainAndDoesNotAffectOtherTargets()
	{
		InitialSyncDisplayGate gate = new InitialSyncDisplayGate();
		gate.arm(7, BossDefence.TEKTON, 100, 4);
		assertFalse(gate.isWaiting(state(7, true), 101));
		assertFalse(gate.isWaiting(state(8, false), 101));
	}

	@Test
	public void releaseRequiresMatchingBoss()
	{
		InitialSyncDisplayGate gate = new InitialSyncDisplayGate();
		gate.arm(7, BossDefence.TEKTON, 100, 4);
		gate.release(7, BossDefence.TEKTON_ENRAGED);
		assertTrue(gate.isWaiting(state(7, false), 101));
		gate.release(7, BossDefence.TEKTON);
		assertFalse(gate.isWaiting(state(7, false), 101));
	}

	@Test
	public void duplicatePresenceDoesNotExtendGracePeriod()
	{
		InitialSyncDisplayGate gate = new InitialSyncDisplayGate();
		gate.arm(7, BossDefence.TEKTON, 100, 4);
		gate.arm(7, BossDefence.TEKTON, 102, 4);
		assertFalse(gate.isWaiting(state(7, false), 104));
	}

	@Test
	public void clearOnRaidExitOrReset()
	{
		InitialSyncDisplayGate gate = new InitialSyncDisplayGate();
		gate.arm(7, BossDefence.TEKTON, 100, 4);
		gate.clear();
		assertFalse(gate.isWaiting(state(7, false), 100));
	}
}
