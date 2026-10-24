package net.betterpartydefence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reconcile two equal-version spec histories only when a shared prefix and
 * disjoint suffixes prove that one peer has events absent from the other.
 * There is no reliable per-spec UUID in older BPD sync packets. Consequently,
 * uncertain overlaps must be rejected instead of double-counted.
 */
final class ConcurrentSyncHistory
{
	private ConcurrentSyncHistory() {}

	/**
	 * @return incoming-only events, or null if safe reconciliation is impossible.
	 */
	static <T> List<T> incomingOnly(List<T> local, List<T> incoming)
	{
		if (local == null || incoming == null || local.isEmpty() || incoming.isEmpty())
		{
			return null;
		}
		int prefix = 0;
		int min = Math.min(local.size(), incoming.size());
		while (prefix < min && java.util.Objects.equals(local.get(prefix), incoming.get(prefix)))
		{
			prefix++;
		}
		// Prefix-only updates are handled by the ordinary version/length logic.
		if (prefix == 0 || prefix == local.size() || prefix == incoming.size())
		{
			return null;
		}
		List<T> existingTail = local.subList(prefix, local.size());
		List<T> newTail = incoming.subList(prefix, incoming.size());
		for (T event : newTail)
		{
			if (event == null || existingTail.contains(event))
			{
				// Matching suffixes might be the same event observed in different orders.
				return null;
			}
		}
		return Collections.unmodifiableList(new ArrayList<>(newTail));
	}
}
