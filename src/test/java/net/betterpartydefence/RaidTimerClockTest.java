package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class RaidTimerClockTest
{
	private static final String TOA_SCOPE = "i731";

	private RaidTimerClock running(int localTick, int elapsed)
	{
		RaidTimerClock clock = new RaidTimerClock();
		assertTrue(clock.sample(RaidTimerClock.SOURCE_TOA, TOA_SCOPE, elapsed, false, localTick));
		return clock;
	}

	@Test public void differentLoginCountersShareTheSameElapsedTick()
	{
		RaidTimerClock first = running(5_000, 0);
		RaidTimerClock second = running(120, 0);

		assertEquals(7L, first.tickAt(5_007));
		assertEquals(7L, second.tickAt(127));
		assertEquals(first.domain(), second.domain());
	}

	@Test public void lateJoinUsesTheElapsedValueItActuallySaw()
	{
		RaidTimerClock late = running(9_000, 512);

		assertEquals(521L, late.tickAt(9_009));
		assertEquals(512L, late.tickAt(9_000));
		// An event witnessed long before the late join is still a different, earlier tick.
		assertTrue(late.tickAt(9_000) < 600);
	}

	@Test public void lateJoinerAndEarlyClientAgreeOnTheSameRealTick()
	{
		RaidTimerClock early = running(1_000, 0);
		RaidTimerClock late = running(9_000, 500);

		// The early client reached elapsed 500 at local tick 1500; the late client
		// sampled elapsed 500 at local tick 9000. Nine ticks later both must agree.
		assertEquals(509L, early.tickAt(1_509));
		assertEquals(509L, late.tickAt(9_009));
		for (int offset = 0; offset < 30; offset++)
		{
			assertEquals(early.tickAt(1_500 + offset), late.tickAt(9_000 + offset));
		}
	}

	@Test public void resampleCorrectionRebasesFutureTicksWithoutHistory()
	{
		RaidTimerClock clock = running(1_000, 0);

		assertEquals(100L, clock.tickAt(1_100));
		assertTrue(clock.sample(RaidTimerClock.SOURCE_TOA, TOA_SCOPE, 101, false, 1_100));
		assertEquals(101L, clock.tickAt(1_100));
		assertEquals(106L, clock.tickAt(1_105));
	}

	@Test public void stoppedTimerNeverExtrapolates()
	{
		RaidTimerClock clock = running(1_000, 0);
		assertTrue(clock.sample(RaidTimerClock.SOURCE_TOA, TOA_SCOPE, 40, true, 1_040));

		assertTrue(clock.isStopped());
		assertEquals(40L, clock.tickAt(1_040));
		assertEquals(RaidTimerClock.NO_TICK, clock.tickAt(1_041));
	}

	@Test public void clearedTimerDeactivatesAndReleasesItsDomain()
	{
		RaidTimerClock clock = running(1_000, 25);
		assertTrue(clock.isActive());

		assertFalse(clock.sample(RaidTimerClock.SOURCE_TOA, TOA_SCOPE, -1, false, 1_010));
		assertFalse(clock.isActive());
		assertNull(clock.domain());
		assertEquals(RaidTimerClock.NO_TICK, clock.tickAt(1_010));
	}

	@Test public void backwardResetStartsANewElapsedEpoch()
	{
		RaidTimerClock clock = running(1_000, 0);
		assertTrue(clock.sample(RaidTimerClock.SOURCE_TOA, TOA_SCOPE, 500, false, 1_500));
		assertEquals(505L, clock.tickAt(1_505));

		assertTrue(clock.sample(RaidTimerClock.SOURCE_TOA, "i999", 0, false, 2_000));
		assertEquals(5L, clock.tickAt(2_005));
		assertEquals("raid-time:v1:toa:i999", clock.domain());
	}

	@Test public void switchingRaidTypeCannotReuseTheOldDomain()
	{
		RaidTimerClock clock = running(1_000, 10);
		String toaDomain = clock.domain();

		assertTrue(clock.sample(RaidTimerClock.SOURCE_TFA, "c77", 0, false, 1_001));
		assertTrue(clock.isActiveFor(RaidTimerClock.SOURCE_TFA));
		assertEquals("raid-time:v1:tfa:c77", clock.domain());
		assertNotEquals(toaDomain, clock.domain());
	}

	@Test public void stoppedSampleWithoutARunningClockIsIgnored()
	{
		RaidTimerClock clock = new RaidTimerClock();
		assertFalse(clock.sample(RaidTimerClock.SOURCE_TOA, TOA_SCOPE, 90, true, 1_000));
		assertFalse(clock.isActive());
	}

	@Test public void anotherSourcesClearCannotClearTheActiveClock()
	{
		RaidTimerClock clock = running(1_000, 10);
		assertFalse(clock.sample(RaidTimerClock.SOURCE_COX, "i731", -1, false, 1_001));
		assertTrue(clock.isActiveFor(RaidTimerClock.SOURCE_TOA));
	}

	@Test public void negativeLocalTickIsRejected()
	{
		RaidTimerClock clock = new RaidTimerClock();
		assertFalse(clock.sample(RaidTimerClock.SOURCE_TOA, TOA_SCOPE, 0, false, -1));
	}

	@Test public void domainStaysWithinTheWireLimitEvenForALongScope()
	{
		RaidTimerClock clock = new RaidTimerClock();
		String scope = new String(new char[120]).replace('\0', 'x');
		assertFalse(clock.sample(RaidTimerClock.SOURCE_TOA, scope, 0, false, 1_000));
	}

	@Test public void pausedTimerResumesFromItsNewSample()
	{
		RaidTimerClock clock = running(1_000, 0);
		assertTrue(clock.sample(RaidTimerClock.SOURCE_TOA, TOA_SCOPE, 10, true, 1_010));
		assertEquals(RaidTimerClock.NO_TICK, clock.tickAt(1_020));

		assertTrue(clock.sample(RaidTimerClock.SOURCE_TOA, TOA_SCOPE, 10, false, 1_050));
		assertFalse(clock.isStopped());
		assertEquals(15L, clock.tickAt(1_055));
	}

	@Test public void smallResyncKeepsTheEstablishedDomain()
	{
		RaidTimerClock clock = running(1_000, 0);
		String domain = clock.domain();

		assertTrue(clock.sample(RaidTimerClock.SOURCE_TOA, TOA_SCOPE, 99, false, 1_100));
		assertEquals(domain, clock.domain());
		assertEquals(104L, clock.tickAt(1_105));
	}

	@Test public void displayCallbacksAndNestedScriptsAreNotTimerInputs()
	{
		assertNull(RaidTimerClock.parseTimerScript(6581, new Object[]{6581, 0, 0}));
		assertNull(RaidTimerClock.parseTimerScript(6582, new Object[]{6582, 0, 0}));
		assertNull(RaidTimerClock.parseTimerScript(10037, new Object[]{10037, 0, 0}));
		assertNull(RaidTimerClock.parseTimerScript(10038, new Object[]{10038, 0, 0}));
	}

	@Test public void tobHudScriptsAreNotTimerInputs()
	{
		// ToB's HUD is room progress, not an elapsed timer; none of these may be treated
		// as a sample even when they carry integers in the same argument positions.
		assertNull(RaidTimerClock.parseTimerScript(2296, new Object[]{2296, 0, 0}));
		assertNull(RaidTimerClock.parseTimerScript(2304, new Object[]{2304, 0, 0}));
		assertNull(RaidTimerClock.parseTimerScript(4490, new Object[]{4490, 0, 0}));
	}

	@Test public void timerScriptArgumentsAreStrictlyValidated()
	{
		assertNotNull(RaidTimerClock.parseTimerScript(RaidTimerClock.TOA_TIMER_SCRIPT,
			new Object[]{RaidTimerClock.TOA_TIMER_SCRIPT, 12, 0}));
		assertNotNull(RaidTimerClock.parseTimerScript(RaidTimerClock.TFA_TIMER_SCRIPT,
			new Object[]{RaidTimerClock.TFA_TIMER_SCRIPT, -1, 1}));

		assertNull(RaidTimerClock.parseTimerScript(RaidTimerClock.TOA_TIMER_SCRIPT, null));
		assertNull(RaidTimerClock.parseTimerScript(RaidTimerClock.TOA_TIMER_SCRIPT,
			new Object[]{RaidTimerClock.TOA_TIMER_SCRIPT, 12}));
		assertNull(RaidTimerClock.parseTimerScript(RaidTimerClock.TOA_TIMER_SCRIPT,
			new Object[]{9999, 12, 0}));
		assertNull(RaidTimerClock.parseTimerScript(RaidTimerClock.TOA_TIMER_SCRIPT,
			new Object[]{RaidTimerClock.TOA_TIMER_SCRIPT, "12", 0}));
		assertNull(RaidTimerClock.parseTimerScript(RaidTimerClock.TOA_TIMER_SCRIPT,
			new Object[]{RaidTimerClock.TOA_TIMER_SCRIPT, -2, 0}));
		assertNull(RaidTimerClock.parseTimerScript(RaidTimerClock.TOA_TIMER_SCRIPT,
			new Object[]{RaidTimerClock.TOA_TIMER_SCRIPT, RaidTimerClock.MAX_ELAPSED_TICKS + 1, 0}));
		assertNull(RaidTimerClock.parseTimerScript(RaidTimerClock.TOA_TIMER_SCRIPT,
			new Object[]{RaidTimerClock.TOA_TIMER_SCRIPT, 12, 0, 99}));
	}

	@Test public void parsedStoppedFlagUsesTheRawTickValue()
	{
		RaidTimerClock.TimerInput input = RaidTimerClock.parseTimerScript(
			RaidTimerClock.TOA_TIMER_SCRIPT,
			new Object[]{RaidTimerClock.TOA_TIMER_SCRIPT, 1_234, 1});

		assertNotNull(input);
		assertEquals(RaidTimerClock.SOURCE_TOA, input.source);
		assertEquals(1_234, input.elapsedTicks);
		assertTrue(input.stopped);
	}
}
