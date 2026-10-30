package net.betterpartydefence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;

/**
 * Event identity and clock-domain rules for the optional event extension. A tick is relative to
 * an explicit origin event, never to packet arrival or another client's RuneLite login counter.
 * Unknown clock alignment is deliberately unordered. IDs break presentation ties, not game ties.
 */
final class EncounterTimeline
{
	static final int VERSION = 1;
	static final int MAX_EVENTS = 256;
	static final int MAX_REPLAY_STEPS = 100000;
	static final Comparator<SpecHistoryEntry> DISPLAY_ORDER = Comparator
		.comparing(SpecHistoryEntry::getTickAnchor)
		.thenComparingLong(SpecHistoryEntry::getEncounterTick)
		.thenComparing(SpecHistoryEntry::getEventId);

	private EncounterTimeline() {}

	static boolean hasIdentifiedEvents(List<SpecHistoryEntry> events)
	{
		return events != null && events.stream().anyMatch(e -> e != null && e.getEventId() != null);
	}

	static boolean containsIdentifiedEvents(List<SpecHistoryEntry> superset, List<SpecHistoryEntry> subset)
	{
		return subset == null || subset.stream().filter(e -> e != null && e.getEventId() != null)
			.allMatch(superset::contains);
	}

	static boolean complete(List<SpecHistoryEntry> events)
	{
		if (events == null || events.isEmpty() || events.size() > MAX_EVENTS)
		{
			return false;
		}
		Map<String, SpecHistoryEntry> ids = new HashMap<>();
		for (SpecHistoryEntry event : events)
		{
			if (event == null || event.getEventId() == null || event.getTickAnchor() == null
				|| event.getEventId().isEmpty() || event.getEventId().length() > 128
				|| event.getTickAnchor().isEmpty() || event.getTickAnchor().length() > 128 || event.getEncounterTick() < 0
				|| !DefenceTracker.isSupportedWeapon(event.getWeapon()) || event.getHit() < 0
				|| ids.put(event.getEventId(), event) != null)
			{
				return false;
			}
		}
		return true;
	}

	/** Union is idempotent. Conflicting payloads for one ID invalidate the entire extension. */
	static List<SpecHistoryEntry> union(List<SpecHistoryEntry> local, List<SpecHistoryEntry> incoming)
	{
		if (local == null || incoming == null || local.size() + incoming.size() > MAX_EVENTS * 2)
		{
			return null;
		}
		List<SpecHistoryEntry> candidates = new ArrayList<>(local);
		candidates.addAll(incoming);
		local = identify(local, candidates);
		incoming = identify(incoming, candidates);
		if (!complete(local) || !complete(incoming))
		{
			return null;
		}
		Map<String, SpecHistoryEntry> ids = new HashMap<>();
		for (SpecHistoryEntry event : local)
		{
			ids.put(event.getEventId(), event);
		}
		for (SpecHistoryEntry event : incoming)
		{
			SpecHistoryEntry old = ids.putIfAbsent(event.getEventId(), event);
			if (old != null && !old.equals(event))
			{
				return null;
			}
		}
		List<SpecHistoryEntry> result = new ArrayList<>(ids.values());
		result.sort(DISPLAY_ORDER);
		return complete(result) ? result : null;
	}

	/** A standard-message echo may acquire identity only with a unique one-to-one payload match. */
	private static List<SpecHistoryEntry> identify(List<SpecHistoryEntry> events, List<SpecHistoryEntry> candidates)
	{
		List<SpecHistoryEntry> result = new ArrayList<>();
		for (SpecHistoryEntry event : events)
		{
			if (event == null) { return null; }
			if (event.getEventId() != null)
			{
				result.add(event);
				continue;
			}
			SpecHistoryEntry match = null;
			for (SpecHistoryEntry candidate : candidates)
			{
				if (candidate == null || candidate.getEventId() == null || !samePayload(event, candidate)) { continue; }
				if (match != null && !match.equals(candidate)) { return null; }
				match = candidate;
			}
			if (match == null || result.contains(match)) { return null; }
			// Repeated identical legacy rows cannot be assigned to individual source IDs safely.
			int count = 0;
			for (SpecHistoryEntry other : events)
			{
				if (other != null && samePayload(event, other)) { count++; }
			}
			if (count != 1) { return null; }
			result.add(match);
		}
		return result;
	}

	static boolean samePayload(SpecHistoryEntry a, SpecHistoryEntry b)
	{
		return Objects.equals(normalizedPlayer(a.getPlayerName()), normalizedPlayer(b.getPlayerName()))
			&& a.getWeapon() == b.getWeapon() && a.getHit() == b.getHit();
	}

	private static String normalizedPlayer(String name)
	{
		return name == null ? "" : net.runelite.client.util.Text.toJagexName(
			net.runelite.client.util.Text.removeTags(name)).toLowerCase(java.util.Locale.ROOT);
	}

	/**
	 * Order only when the two intervals cannot be the same game tick. ToA/TFA domains are
	 * exact; a CoX domain carries its sampling uncertainty suffix, so ticks that overlap
	 * within that radius remain unordered and are replayed conservatively.
	 */
	static boolean before(SpecHistoryEntry a, SpecHistoryEntry b)
	{
		if (!Objects.equals(a.getTickAnchor(), b.getTickAnchor()))
		{
			return false;
		}
		long uncertainty = tickUncertainty(a.getTickAnchor());
		return a.getEncounterTick() + uncertainty < b.getEncounterTick();
	}

	static long tickUncertainty(String anchor)
	{
		return anchor != null && anchor.endsWith(RaidTimerClock.TIMER_UNCERTAINTY_SUFFIX)
			? RaidTimerClock.TIMER_UNCERTAINTY_TICKS : 0L;
	}

	interface Replay<S>
	{
		S apply(S state, SpecHistoryEntry event);
		S conservative(S a, S b);
	}

	/**
	 * Explore all legal orders, preserving all intermediate alternatives (a greedy per-tick
	 * maximum is unsafe for BGS spill, Ralos and non-stacking curses). On budget exhaustion
	 * use the undrained baseline: a bounded, conservative upper bound, never a partial search.
	 */
	static <S> S replay(List<SpecHistoryEntry> events, S baseline, Replay<S> replay)
	{
		return replay(events, baseline, replay, null);
	}

	/**
	 * As {@link #replay(List, Object, Replay)}, but collapses interchangeable choices. Two
	 * available events with the same non-null effect key produce the same next state from the
	 * same state, so exploring one of them represents both. This is exact symmetry reduction,
	 * not a heuristic: it only removes permutations that cannot change the result.
	 */
	static <S> S replay(List<SpecHistoryEntry> events, S baseline, Replay<S> replay,
		Function<SpecHistoryEntry, String> effectKey)
	{
		// Do not construct an unbounded graph or guess an order beyond the supported capacity.
		if (events.size() > MAX_EVENTS) { return baseline; }
		Search<S> search = new Search<>(events, replay, effectKey);
		search.visit(baseline, new boolean[events.size()], 0);
		return search.exhausted ? baseline : search.best;
	}

	private static final class Search<S>
	{
		private final List<SpecHistoryEntry> events;
		private final Replay<S> replay;
		private final Function<SpecHistoryEntry, String> effectKey;
		private final List<List<Integer>> successors = new ArrayList<>();
		private final int[] remainingPredecessors;
		private S best;
		private int steps;
		private boolean exhausted;

		private Search(List<SpecHistoryEntry> events, Replay<S> replay,
			Function<SpecHistoryEntry, String> effectKey)
		{
			this.events = events;
			this.replay = replay;
			this.effectKey = effectKey;
			remainingPredecessors = new int[events.size()];
			for (int i = 0; i < events.size(); i++)
			{
				List<Integer> next = new ArrayList<>();
				for (int j = 0; j < events.size(); j++)
				{
					if (before(events.get(i), events.get(j)))
					{
						next.add(j);
						remainingPredecessors[j]++;
					}
				}
				successors.add(next);
			}
		}

		private void visit(S state, boolean[] used, int depth)
		{
			if (++steps > MAX_REPLAY_STEPS)
			{
				exhausted = true;
				return;
			}
			if (depth == events.size())
			{
				best = best == null ? state : replay.conservative(best, state);
				return;
			}
			Set<String> triedEffects = effectKey == null ? null : new HashSet<>();
			for (int i = 0; i < events.size() && !exhausted; i++)
			{
				if (!used[i] && remainingPredecessors[i] == 0)
				{
					if (triedEffects != null)
					{
						String key = effectKey.apply(events.get(i));
						if (key != null)
						{
							// Equal state effects are not enough: choosing two events can release
							// different successors. Only collapse events whose static successor
							// sets match as well; then the remaining graph is isomorphic.
							key = key + "|" + successors.get(i);
							if (!triedEffects.add(key))
							{
								// This branch is a permutation of one already explored from this
								// exact state and with the same released successors.
								continue;
							}
						}
					}
					used[i] = true;
					for (int next : successors.get(i)) { remainingPredecessors[next]--; }
					visit(replay.apply(state, events.get(i)), used, depth + 1);
					for (int next : successors.get(i)) { remainingPredecessors[next]++; }
					used[i] = false;
				}
			}
		}
	}
}
