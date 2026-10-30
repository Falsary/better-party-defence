package net.betterpartydefence;

import java.util.Arrays;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.betterpartydefence.DefenceTracker.SyncState;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;
import static org.junit.Assert.*;
import static net.betterpartydefence.SyncHistoryRelation.Result.*;

public class SyncHistoryRelationTest
{
	private final SpecHistoryEntry a = new SpecHistoryEntry("Alice", SpecialWeapon.DRAGON_WARHAMMER, 1);
	private final SpecHistoryEntry b = new SpecHistoryEntry("Bob", SpecialWeapon.BANDOS_GODSWORD, 50);
	private final SpecHistoryEntry c = new SpecHistoryEntry("Carol", SpecialWeapon.BANDOS_GODSWORD, 20);

	private SyncState state(long defence, SpecHistoryEntry... events)
	{
		return new SyncState("Chaos Elemental", BossDefence.CHAOS_ELEMENTAL, defence, 0, 270,
			100, 100, 100, 100, 0, 0, false, false, false, true, Arrays.asList(events));
	}
	@Test public void reversedTeamOrderIsConflictInBothDirections()
	{
		assertEquals(CONFLICT, SyncHistoryRelation.compare(state(139,a,b),state(154,b,a)));
		assertEquals(CONFLICT, SyncHistoryRelation.compare(state(154,b,a),state(139,a,b)));
	}
	@Test public void sharedPrefixWithDifferentSuffixesCannotBeAppendedInArrivalOrder()
	{
		assertEquals(CONFLICT, SyncHistoryRelation.compare(state(139,a,b),state(169,a,c)));
		assertEquals(CONFLICT, SyncHistoryRelation.compare(state(169,a,c),state(139,a,b)));
	}
	@Test public void longerHistoryIsNotProofWhenItOmitsALocalSpec()
	{
		assertEquals(CONFLICT, SyncHistoryRelation.compare(state(220,b),state(169,a,c)));
	}
	@Test public void realExtensionSupportsCatchupAndRejectsRollback()
	{
		assertEquals(INITIAL, SyncHistoryRelation.compare(null,state(139,a,b)));
		assertEquals(EXTENSION, SyncHistoryRelation.compare(state(189,a),state(139,a,b)));
		assertEquals(STALE, SyncHistoryRelation.compare(state(139,a,b),state(189,a)));
		assertEquals(SAME, SyncHistoryRelation.compare(state(139,a,b),state(139,a,b)));
	}
	@Test public void sameHistoryButDifferentValuesIsAlsoAConflict()
	{
		assertEquals(CONFLICT, SyncHistoryRelation.compare(state(139,a,b),state(154,a,b)));
	}
}
