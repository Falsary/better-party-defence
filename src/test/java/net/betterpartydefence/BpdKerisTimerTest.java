package net.betterpartydefence;

import static org.junit.Assert.assertEquals;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class BpdKerisTimerTest
{
	private final BpdKerisTimer timer = new BpdKerisTimer(1, 301, BossDefence.CORPOREAL_BEAST,
		BpdDefenceSync.SCOPE_INSTANCE, 42, 123, new WorldPoint(100, 100, 0),
		10_000, "c42", 90);

	@Test
	public void raidClockUsesOriginalHitTick()
	{
		assertEquals(7, timer.remainingTicks(10_100, "c42", 93));
		assertEquals(1, timer.remainingTicks(15_000, "c42", 99));
		assertEquals(0, timer.remainingTicks(15_001, "c42", 100));
	}

	@Test
	public void fallbackExpiresFromImpactRatherThanPacketReceipt()
	{
		assertEquals(8, timer.remainingTicks(11_200, "c43", 90));
		assertEquals(1, timer.remainingTicks(15_999, null, RaidTimerClock.NO_TICK));
		assertEquals(0, timer.remainingTicks(16_000, null, RaidTimerClock.NO_TICK));
		assertEquals(0, timer.remainingTicks(9_399, null, RaidTimerClock.NO_TICK));
	}
}
