package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import java.util.Collections;
import org.junit.Test;

public class BpdDefenceSyncVersionTest
{
	@Test public void versionedAndLegacyConstructorsRemainCompatible()
	{
		DefenceTracker.SyncState state = new DefenceTracker.SyncState(
			"Bork", BossDefence.TFA_BORK, 180, 180, 300,
			100, 100, 100, 100, 0, 0,
			false, false, false, true, Collections.emptyList());
		BpdDefenceSync modern = new BpdDefenceSync(
			301, state, BpdDefenceSync.SCOPE_INSTANCE, 42, 5, 6, 0, 100, 7L);
		assertEquals(7L, modern.getStateVersion());
		BpdDefenceSync legacy = new BpdDefenceSync(
			301, state, BpdDefenceSync.SCOPE_INSTANCE, 42, 5, 6, 0, 100);
		assertEquals(0L, legacy.getStateVersion());
		assertEquals(0L, new BpdDefenceSync().getStateVersion());
	}
}
