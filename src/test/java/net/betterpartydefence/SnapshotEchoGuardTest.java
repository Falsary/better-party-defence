package net.betterpartydefence;

import org.junit.Test;
import static org.junit.Assert.*;

public class SnapshotEchoGuardTest
{
	@Test public void creditIsConsumedOnlyOnce()
	{
		SnapshotEchoGuard<String> guard = new SnapshotEchoGuard<>(2);
		guard.add("boss1/Alice/BGS/50", 10);
		assertTrue(guard.consume("boss1/Alice/BGS/50", 10));
		assertFalse(guard.consume("boss1/Alice/BGS/50", 11));
	}
	@Test public void repeatedNewRowsReceiveSeparateCredits()
	{
		SnapshotEchoGuard<String> guard = new SnapshotEchoGuard<>(2);
		guard.add("same", 10); guard.add("same", 10);
		assertTrue(guard.consume("same", 11));
		assertTrue(guard.consume("same", 11));
		assertFalse(guard.consume("same", 11));
	}
	@Test public void anotherTargetAndExpiredEchoCannotSuppressAHit()
	{
		SnapshotEchoGuard<String> guard = new SnapshotEchoGuard<>(2);
		guard.add("boss1", 10);
		assertFalse(guard.consume("boss2", 10));
		assertFalse(guard.consume("boss1", 13));
	}
	@Test public void resetAndTickRollbackDiscardCredits()
	{
		SnapshotEchoGuard<String> guard = new SnapshotEchoGuard<>(2);
		guard.add("a", 10); guard.clear();
		assertFalse(guard.consume("a", 11));
		guard.add("a", 10);
		assertFalse(guard.consume("a", 1));
	}
}
