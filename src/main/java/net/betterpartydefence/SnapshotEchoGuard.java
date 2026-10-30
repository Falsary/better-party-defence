package net.betterpartydefence;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Short-lived, consumable credits for newly adopted snapshot rows only. */
final class SnapshotEchoGuard<K>
{
	private final Map<K, ArrayDeque<Integer>> credits = new HashMap<>();
	private final int ttl;

	SnapshotEchoGuard(int ttl) { this.ttl = ttl; }

	void add(K key, int tick) { credits.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(tick); }

	boolean consume(K key, int tick)
	{
		prune(tick);
		ArrayDeque<Integer> queue = credits.get(key);
		if (queue == null) { return false; }
		queue.removeFirst();
		if (queue.isEmpty()) { credits.remove(key); }
		return true;
	}

	void prune(int tick)
	{
		credits.entrySet().removeIf(entry ->
		{
			ArrayDeque<Integer> queue = entry.getValue();
			while (!queue.isEmpty() && (tick < queue.peekFirst() || tick - queue.peekFirst() > ttl))
			{
				queue.removeFirst();
			}
			return queue.isEmpty();
		});
	}

	void clear() { credits.clear(); }
}
