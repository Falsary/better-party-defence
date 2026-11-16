package net.betterpartydefence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.betterpartydefence.DefenceTracker.SyncState;
import net.runelite.client.util.Text;

// Rules for matching events and comparing their timing.
// Ticks are relative to an event origin, not packet arrival.
// Use observation timestamps when clients have different clocks.
final class EncounterTimeline {
	static final int VERSION = 1;
	static final int MAX_EVENTS = 256;
	static final int MAX_REPLAY_STEPS = 100000;
	enum HistoryRelation {
		INITIAL, SAME, EXTENSION, STALE, CONFLICT
	}

	static final Comparator<SpecHistoryEntry> DISPLAY_ORDER = Comparator
			.comparing(SpecHistoryEntry::getTickAnchor)
			.thenComparingLong(SpecHistoryEntry::getEncounterTick)
			.thenComparing(SpecHistoryEntry::getEventId);

	private EncounterTimeline() {
	}

	static boolean hasIdentifiedEvents(List<SpecHistoryEntry> events) {
		return events != null && events.stream().anyMatch(e -> e != null && e.getEventId() != null);
	}

	static boolean containsIdentifiedEvents(List<SpecHistoryEntry> superset, List<SpecHistoryEntry> subset) {
		return subset == null || subset.stream().filter(e -> e != null && e.getEventId() != null)
				.allMatch(superset::contains);
	}

	static boolean complete(List<SpecHistoryEntry> events) {
		if (events == null || events.isEmpty() || events.size() > MAX_EVENTS) {
			return false;
		}
		Map<String, SpecHistoryEntry> ids = new HashMap<>();
		for (SpecHistoryEntry event : events) {
			if (event == null || event.getEventId() == null || event.getTickAnchor() == null
					|| event.getEventId().isEmpty() || event.getEventId().length() > 128
					|| event.getTickAnchor().isEmpty() || event.getTickAnchor().length() > 128
					|| event.getEncounterTick() < 0
					|| !event.isSupported()
					|| ids.put(event.getEventId(), event) != null) {
				return false;
			}
		}
		return true;
	}

	// Reject a merge if one event ID has different data.
	static List<SpecHistoryEntry> union(List<SpecHistoryEntry> local, List<SpecHistoryEntry> incoming) {
		if (local == null || incoming == null || local.size() + incoming.size() > MAX_EVENTS * 2) {
			return null;
		}
		List<SpecHistoryEntry> candidates = new ArrayList<>(local);
		candidates.addAll(incoming);
		local = identify(local, candidates);
		incoming = identify(incoming, candidates);
		if (!complete(local) || !complete(incoming)) {
			return null;
		}
		Map<String, SpecHistoryEntry> ids = new HashMap<>();
		for (SpecHistoryEntry event : local) {
			ids.put(event.getEventId(), event);
		}
		for (SpecHistoryEntry event : incoming) {
			SpecHistoryEntry old = ids.putIfAbsent(event.getEventId(), event);
			if (old != null) {
				if (!compatibleEvent(old, event)) {
					return null;
				}
				if (old.getObservedAtMillis() == 0L) {
					ids.put(event.getEventId(), event);
				}
			}
		}
		List<SpecHistoryEntry> result = new ArrayList<>(ids.values());
		result.sort(DISPLAY_ORDER);
		return complete(result) ? result : null;
	}

	// Assign an event ID to a standard echo only when one row matches.
	private static List<SpecHistoryEntry> identify(List<SpecHistoryEntry> events, List<SpecHistoryEntry> candidates) {
		List<SpecHistoryEntry> result = new ArrayList<>();
		for (SpecHistoryEntry event : events) {
			if (event == null) {
				return null;
			}
			if (event.getEventId() != null) {
				result.add(event);
				continue;
			}
			SpecHistoryEntry match = null;
			for (SpecHistoryEntry candidate : candidates) {
				if (candidate == null || candidate.getEventId() == null || !samePayload(event, candidate)) {
					continue;
				}
				if (match != null && !compatibleEvent(match, candidate)) {
					return null;
				}
				if (match == null || match.getObservedAtMillis() == 0L) {
					match = candidate;
				}
			}
			if (match == null || result.contains(match)) {
				return null;
			}
			// Identical old rows cannot be matched to one event ID.
			int count = 0;
			for (SpecHistoryEntry other : events) {
				if (other != null && samePayload(event, other)) {
					count++;
				}
			}
			if (count != 1) {
				return null;
			}
			result.add(match);
		}
		return result;
	}

	// Older peers may omit the observation timestamp.
	static boolean compatibleEvent(SpecHistoryEntry a, SpecHistoryEntry b) {
		return a.equals(b)
				|| ((a.getObservedAtMillis() == 0L || b.getObservedAtMillis() == 0L)
						&& a.withObservedAtMillis(0L).equals(b.withObservedAtMillis(0L)));
	}

	static boolean samePayload(SpecHistoryEntry a, SpecHistoryEntry b) {
		return Objects.equals(normalizedPlayer(a.getPlayerName()), normalizedPlayer(b.getPlayerName()))
				&& a.getWeapon() == b.getWeapon() && a.getSpell() == b.getSpell() && a.getHit() == b.getHit();
	}

	static String normalizedPlayer(String name) {
		return name == null ? "" : Text.toJagexName(Text.removeTags(name)).toLowerCase(Locale.ROOT);
	}

	// Order only when possible tick intervals do not overlap.
	// CoX sampling uncertainty stays unordered.
	static boolean before(SpecHistoryEntry a, SpecHistoryEntry b) {
		if (!Objects.equals(a.getTickAnchor(), b.getTickAnchor())) {
			return false;
		}
		long uncertainty = tickUncertainty(a.getTickAnchor());
		return a.getEncounterTick() + uncertainty < b.getEncounterTick();
	}

	// Use observation time for independent clocks.
	// Equal timestamps stay unordered; there is no minimum gap.
	private static boolean crossClockBefore(SpecHistoryEntry a, SpecHistoryEntry b) {
		return !Objects.equals(a.getTickAnchor(), b.getTickAnchor())
				&& a.getObservedAtMillis() > 0L && b.getObservedAtMillis() > 0L
				&& a.getObservedAtMillis() < b.getObservedAtMillis();
	}

	static long tickUncertainty(String anchor) {
		// The uncertainty suffix is not always at the end of the id.
		return anchor != null && anchor.contains(RaidTimerClock.TIMER_UNCERTAINTY_SUFFIX)
				? RaidTimerClock.TIMER_UNCERTAINTY_TICKS
				: 0L;
	}

	interface Replay<S> {
		S apply(S state, SpecHistoryEntry event);
		S conservative(S a, S b);
	}

	// Try every valid event order.
	// A single greedy order can miscalculate BGS spill and curses.
	// If the search limit is reached, return the undrained baseline.
	static <S> S replay(List<SpecHistoryEntry> events, S baseline, Replay<S> replay) {
		return replay(events, baseline, replay, null);
	}

	// Ignore orders that produce the same state and next events.
	static <S> S replay(List<SpecHistoryEntry> events, S baseline, Replay<S> replay,
			Function<SpecHistoryEntry, String> effectKey) {
		// Do not guess beyond the replay budget.
		if (events.size() > MAX_EVENTS) {
			return baseline;
		}
		Search<S> search = new Search<>(events, replay, effectKey);
		search.visit(baseline, new boolean[events.size()], 0);
		return search.exhausted ? baseline : search.best;
	}

	static HistoryRelation compareLegacy(SyncState local, SyncState incoming) {
		if (local == null) {
			return HistoryRelation.INITIAL;
		}
		if (local.getBossType() != incoming.getBossType()
				|| local.getBase() != incoming.getBase()
				|| local.getMin() != incoming.getMin()
				|| local.getMagicBaseLevel() != incoming.getMagicBaseLevel()
				|| local.getMagicBaseDef() != incoming.getMagicBaseDef()
				|| local.isMagicUsesDefence() != incoming.isMagicUsesDefence()
				|| local.isDemon() != incoming.isDemon()) {
			return HistoryRelation.CONFLICT;
		}

		List<SpecHistoryEntry> localHistory = local.getHistory();
		List<SpecHistoryEntry> incomingHistory = incoming.getHistory();
		if (localHistory == null || incomingHistory == null) {
			return HistoryRelation.CONFLICT;
		}

		// Any mismatch inside the shared prefix is a conflict.
		int prefix = Math.min(localHistory.size(), incomingHistory.size());
		for (int i = 0; i < prefix; i++) {
			SpecHistoryEntry localEvent = localHistory.get(i);
			SpecHistoryEntry incomingEvent = incomingHistory.get(i);
			if (localEvent == null
					|| incomingEvent == null
					|| localEvent.getWeapon() != incomingEvent.getWeapon()
					|| localEvent.getSpell() != incomingEvent.getSpell()
					|| localEvent.getHit() != incomingEvent.getHit()
					|| !normalizedPlayer(localEvent.getPlayerName())
							.equals(normalizedPlayer(incomingEvent.getPlayerName()))) {
				return HistoryRelation.CONFLICT;
			}
		}

		if (localHistory.size() > incomingHistory.size()) {
			return HistoryRelation.STALE;
		}
		if (localHistory.size() < incomingHistory.size()) {
			return HistoryRelation.EXTENSION;
		}
		return local.getCurrent() == incoming.getCurrent()
				&& local.getAttackLevel() == incoming.getAttackLevel()
				&& local.getStrengthLevel() == incoming.getStrengthLevel()
				&& local.getMagicLevel() == incoming.getMagicLevel()
				&& local.getMagicDef() == incoming.getMagicDef()
				&& local.isAccursedApplied() == incoming.isAccursedApplied()
				&& local.isDrained() == incoming.isDrained()
						? HistoryRelation.SAME
						: HistoryRelation.CONFLICT;
	}

	private static final class Search<S> {
		private final List<SpecHistoryEntry> events;
		private final Replay<S> replay;
		private final Function<SpecHistoryEntry, String> effectKey;
		private final List<List<Integer>> successors = new ArrayList<>();
		private final int[] remainingPredecessors;
		private S best;
		private int steps;
		private boolean exhausted;

		private Search(List<SpecHistoryEntry> events, Replay<S> replay,
				Function<SpecHistoryEntry, String> effectKey) {
			this.events = events;
			this.replay = replay;
			this.effectKey = effectKey;
			remainingPredecessors = new int[events.size()];
			if (buildGraph(true) && hasCycle()) {
				// Wall-clock corrections can contradict tick order.
				buildGraph(false);
			}
		}

		private boolean buildGraph(boolean useWallClock) {
			successors.clear();
			Arrays.fill(remainingPredecessors, 0);
			boolean usedWallClock = false;
			for (int i = 0; i < events.size(); i++) {
				List<Integer> next = new ArrayList<>();
				for (int j = 0; j < events.size(); j++) {
					SpecHistoryEntry a = events.get(i);
					SpecHistoryEntry b = events.get(j);
					boolean estimated = useWallClock && crossClockBefore(a, b);
					if (estimated || before(a, b)) {
						usedWallClock |= estimated;
						next.add(j);
						remainingPredecessors[j]++;
					}
				}
				successors.add(next);
			}
			return usedWallClock;
		}

		private boolean hasCycle() {
			int[] predecessors = remainingPredecessors.clone();
			ArrayDeque<Integer> ready = new ArrayDeque<>();
			for (int i = 0; i < predecessors.length; i++) {
				if (predecessors[i] == 0) {
					ready.addLast(i);
				}
			}
			int visited = 0;
			while (!ready.isEmpty()) {
				visited++;
				for (int next : successors.get(ready.removeFirst())) {
					if (--predecessors[next] == 0) {
						ready.addLast(next);
					}
				}
			}
			return visited != events.size();
		}

		private void visit(S state, boolean[] used, int depth) {
			if (++steps > MAX_REPLAY_STEPS) {
				exhausted = true;
				return;
			}
			if (depth == events.size()) {
				best = best == null ? state : replay.conservative(best, state);
				return;
			}
			Set<String> triedEffects = effectKey == null ? null : new HashSet<>();
			for (int i = 0; i < events.size() && !exhausted; i++) {
				if (!used[i] && remainingPredecessors[i] == 0) {
					if (triedEffects != null) {
						String key = effectKey.apply(events.get(i));
						if (key != null) {
							// Only combine events that release the same next events.
							key = key + "|" + successors.get(i);
							if (!triedEffects.add(key)) {
								// Same state and next events.
								continue;
							}
						}
					}
					used[i] = true;
					for (int next : successors.get(i)) {
						remainingPredecessors[next]--;
					}
					visit(replay.apply(state, events.get(i)), used, depth + 1);
					for (int next : successors.get(i)) {
						remainingPredecessors[next]++;
					}
					used[i] = false;
				}
			}
		}
	}
}
