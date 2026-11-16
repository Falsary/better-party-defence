package net.betterpartydefence;

import java.util.Objects;
import java.util.UUID;

// Converts verified raid timer readings to shared elapsed ticks.
// ToA/TFA use root scripts; CoX uses the locally interpolated RAIDS_TIMER.
// ToB has no verified shared timer.
final class RaidTimerClock {
	static final int SOURCE_TOA = 1;
	static final int SOURCE_TFA = 2;
	static final int SOURCE_COX = 3;

	// Root scripts expose [scriptId, elapsedTicks, stopped].
	static final int TOA_TIMER_SCRIPT = 6580;
	static final int TFA_TIMER_SCRIPT = 10036;

	static final long NO_TICK = Long.MIN_VALUE;

	// Allow small backwards corrections.
	static final int RESET_BACKWARD_TOLERANCE = 2;
	// Timer readings can differ by two ticks between clients.
	// Events with overlapping possible times stay unordered.
	static final String TIMER_UNCERTAINTY_SUFFIX = ":u2";
	static final int TIMER_UNCERTAINTY_TICKS = 2;
	// Sanity cap of about 33 hours.
	static final int MAX_ELAPSED_TICKS = 200_000;
	private static final int MAX_DOMAIN_LENGTH = 128;

	// One validated timer-script reading.
	static final class TimerInput {
		final int source;
		final int elapsedTicks;
		final boolean stopped;

		private TimerInput(int source, int elapsedTicks, boolean stopped) {
			this.source = source;
			this.elapsedTicks = elapsedTicks;
			this.stopped = stopped;
		}
	}

	private int source = -1;
	private String scopeKey;
	private String domain;
	private int anchorLocalTick;
	private int anchorRaidTick;
	private boolean stopped;
	private boolean active;
	// Starts a new timing range after an untrusted backwards jump.
	private int generation;
	// Add a random client token so separate corrections never share an id.
	private final String clientToken = UUID.randomUUID().toString().replace("-", "");

	// Check only the documented root-script arguments.
	static TimerInput parseTimerScript(int scriptId, Object[] arguments) {
		int source = sourceForScript(scriptId);
		if (source == -1 || arguments == null || arguments.length != 3) {
			return null;
		}
		Integer id = asInt(arguments[0]);
		Integer elapsed = asInt(arguments[1]);
		Integer stopped = asInt(arguments[2]);
		if (id == null || id != scriptId || elapsed == null || stopped == null
				|| stopped < 0 || stopped > 1
				|| elapsed < -1 || elapsed > MAX_ELAPSED_TICKS) {
			return null;
		}
		return new TimerInput(source, elapsed, stopped != 0);
	}

	private static Integer asInt(Object value) {
		return value instanceof Number ? ((Number) value).intValue() : null;
	}

	static int sourceForScript(int scriptId) {
		switch (scriptId) {
			case TOA_TIMER_SCRIPT :
				return SOURCE_TOA;
			case TFA_TIMER_SCRIPT :
				return SOURCE_TFA;
			default :
				return -1;
		}
	}

	static String sourceName(int source) {
		switch (source) {
			case SOURCE_TOA :
				return "toa";
			case SOURCE_TFA :
				return "tfa";
			case SOURCE_COX :
				return "cox";
			default :
				return "unknown";
		}
	}

	// Record a validated timer reading.
	boolean sample(int source, String scopeKey, int raidTick, boolean stopped, int localTick) {
		if (!isKnownSource(source) || scopeKey == null || scopeKey.isEmpty()
				|| localTick < 0 || raidTick > MAX_ELAPSED_TICKS) {
			return false;
		}
		if (raidTick < 0) {
			if (active && this.source == source) {
				clear();
			}
			return false;
		}
		// A stopped-only sample cannot start a clock.
		if (stopped && !active) {
			return false;
		}
		if (active && this.source != source) {
			clear();
		}
		String nextDomain = domain(source, scopeKey);
		int epochLength = generation > 0
				? (":" + clientToken + ":g" + generation).length()
				: 0;
		if (nextDomain.length() + epochLength > MAX_DOMAIN_LENGTH) {
			return false;
		}
		if (!active) {
			// Keep the current timing range separated.
			establish(source, nextDomain, scopeKey, raidTick, stopped, localTick);
			return true;
		}

		boolean scopeChanged = !Objects.equals(scopeKey, this.scopeKey);
		if (scopeChanged) {
			// Switch to the new instance id without trusting old timestamps.
			int expected = anchorRaidTick + (localTick - anchorLocalTick);
			if (raidTick < expected - RESET_BACKWARD_TOLERANCE) {
				// A large backwards jump in a new scene starts a new range.
				generation++;
			}
			establish(source, nextDomain, scopeKey, raidTick, stopped, localTick);
			return true;
		}

		boolean resumed = this.stopped && !stopped;
		int expected = anchorRaidTick + (localTick - anchorLocalTick);
		if (raidTick < expected) {
			if (resumed) {
				// Resuming a stopped timer starts a new range.
				generation++;
				establish(source, nextDomain, scopeKey, raidTick, stopped, localTick);
				return true;
			}
			if (expected - raidTick > RESET_BACKWARD_TOLERANCE) {
				// Unknown backwards jump; disable the clock but keep the range counter.
				invalidate();
				return false;
			}
			// Small correction; keep the existing tick mapping.
			this.stopped = stopped;
			return true;
		}

		// Move the tick anchor and keep the range separated.
		this.domain = domainWithGeneration(nextDomain);
		this.scopeKey = scopeKey;
		anchorLocalTick = localTick;
		anchorRaidTick = raidTick;
		this.stopped = stopped;
		return true;
	}

	// Stop the clock without extending it.
	void markStopped() {
		if (active) {
			stopped = true;
		}
	}

	void clear() {
		source = -1;
		scopeKey = null;
		domain = null;
		anchorLocalTick = 0;
		anchorRaidTick = 0;
		stopped = false;
		active = false;
		generation = 0;
	}

	// Disable after an unknown jump; keep the range counter.
	private void invalidate() {
		source = -1;
		domain = null;
		anchorLocalTick = 0;
		anchorRaidTick = 0;
		stopped = false;
		active = false;
		generation++;
		// Keep the instance id to detect a real change.
	}

	// Convert a local tick to elapsed raid ticks.
	long tickAt(int localTick) {
		if (!active || localTick < 0) {
			return NO_TICK;
		}
		if (stopped && localTick != anchorLocalTick) {
			return NO_TICK;
		}
		long tick = (long) anchorRaidTick + ((long) localTick - anchorLocalTick);
		return tick >= 0 ? tick : NO_TICK;
	}

	boolean isActive() {
		return active;
	}

	boolean isActiveFor(int source) {
		return active && this.source == source;
	}

	boolean isStopped() {
		return active && stopped;
	}

	String domain() {
		return active ? domain : null;
	}

	int anchorLocalTick() {
		return anchorLocalTick;
	}

	int anchorRaidTick() {
		return anchorRaidTick;
	}

	private void establish(int source, String domain, String scopeKey, int raidTick,
			boolean stopped, int localTick) {
		this.source = source;
		this.domain = domainWithGeneration(domain);
		this.scopeKey = scopeKey;
		this.anchorLocalTick = localTick;
		this.anchorRaidTick = raidTick;
		this.stopped = stopped;
		this.active = true;
	}

	// Add the client token only to corrected timing ranges.
	private String domainWithGeneration(String base) {
		return generation == 0 ? base : base + ":" + clientToken + ":g" + generation;
	}

	private static String domain(int source, String scopeKey) {
		return "raid-time:v1:" + sourceName(source) + ":" + scopeKey + TIMER_UNCERTAINTY_SUFFIX;
	}

	private static boolean isKnownSource(int source) {
		return source == SOURCE_TOA || source == SOURCE_TFA || source == SOURCE_COX;
	}
}
