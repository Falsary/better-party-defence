package net.betterpartydefence;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ConcurrentSyncHistoryTest
{
	@Test public void mergesDistinctDivergentTails()
	{
		List<String> only = ConcurrentSyncHistory.incomingOnly(
			Arrays.asList("earlier", "BGS"), Arrays.asList("earlier", "DWH"));
		assertEquals(Arrays.asList("DWH"), only);
	}

	@Test public void rejectsUnknownPrefix()
	{
		assertNull(ConcurrentSyncHistory.incomingOnly(
			Arrays.asList("BGS"), Arrays.asList("DWH")));
	}

	@Test public void rejectsSameEventInBothTails()
	{
		assertNull(ConcurrentSyncHistory.incomingOnly(
			Arrays.asList("earlier", "BGS", "DWH"),
			Arrays.asList("earlier", "DWH", "BGS")));
	}

	@Test public void rejectsOrdinaryPrefixExtension()
	{
		assertNull(ConcurrentSyncHistory.incomingOnly(
			Arrays.asList("earlier"), Arrays.asList("earlier", "DWH")));
	}

	@Test public void rejectsIdenticalSnapshots()
	{
		assertNull(ConcurrentSyncHistory.incomingOnly(
			Arrays.asList("earlier", "DWH"), Arrays.asList("earlier", "DWH")));
	}

	@Test public void rejectsNoHistory()
	{
		assertNull(ConcurrentSyncHistory.incomingOnly(
			java.util.Collections.emptyList(), Arrays.asList("DWH")));
	}
}
