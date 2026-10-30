package net.betterpartydefence;

import java.util.Objects;

/**
 * Shared raid elapsed-time clock built only from timing values the game already keeps for its
 * own raid HUDs.
 *
 * <p>ToA (script 6580) and TFA (script 10036) run a root clientscript with
 * {@code (elapsedTicks, stopped)}. The value is already in 600 ms game ticks; the rounded HUD
 * text is never parsed. CoX exposes {@code RAIDS_TIMER}, which is the same elapsed-tick counter
 * used by its "Time:" overlay, but the client interpolates it locally between server updates.
 * CoX domains therefore carry an explicit uncertainty suffix; the timeline orders CoX events
 * only when their possible tick intervals do not overlap.
 *
 * <p>Every client maps the shared elapsed value onto its own login counter at the moment it
 * observes a sample. Two clients with different login counters therefore derive the same
 * elapsed tick for the same game tick, and a late joiner only needs the current elapsed value.
 *
 * <p>ToB is deliberately absent. Its HUD publishes room progress, not elapsed time, and no
 * verified live shared ToB timer was found. ToB therefore keeps the existing conservative
 * ordering instead of inventing a clock contract.
 */
final class RaidTimerClock
{
	static final int SOURCE_TOA = 1;
	static final int SOURCE_TFA = 2;
	static final int SOURCE_COX = 3;

	/** Verified root clientscripts: {@code [scriptId, elapsedTicks, stopped]}. */
	static final int TOA_TIMER_SCRIPT = 6580;
	static final int TFA_TIMER_SCRIPT = 10036;

	static final long NO_TICK = Long.MIN_VALUE;

	/** A one/two tick backwards resync is a correction; a larger jump is uncertain. */
	static final int RESET_BACKWARD_TOLERANCE = 2;
	/**
	 * Every timer-derived domain carries an explicit uncertainty radius. It bounds the CoX
	 * local-interpolation phase and a same-scope server correction: a client can be up to two
	 * ticks ahead of another, so events whose possible intervals overlap stay unordered.
	 */
	static final String TIMER_UNCERTAINTY_SUFFIX = ":u2";
	static final int TIMER_UNCERTAINTY_TICKS = 2;
	/** The game's raid timers cannot legitimately exceed this (about 33 hours of ticks). */
	static final int MAX_ELAPSED_TICKS = 200_000;
	private static final int MAX_DOMAIN_LENGTH = 128;

	/** One validated root timer-script invocation. */
	static final class TimerInput
	{
		final int source;
		final int elapsedTicks;
		final boolean stopped;

		private TimerInput(int source, int elapsedTicks, boolean stopped)
		{
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
	/** Bumped when a same-scope backwards jump is too large to trust; isolates the next epoch. */
	private int generation;

	/**
	 * Validate a script event without trusting anything but the root script's documented
	 * {@code [scriptId, elapsedTicks, stopped]} layout. Display callbacks and nested scripts
	 * have different shapes and are rejected.
	 */
	static TimerInput parseTimerScript(int scriptId, Object[] arguments)
	{
		int source = sourceForScript(scriptId);
		if (source == -1 || arguments == null || arguments.length != 3)
		{
			return null;
		}
		Integer id = asInt(arguments[0]);
		Integer elapsed = asInt(arguments[1]);
		Integer stopped = asInt(arguments[2]);
		if (id == null || id != scriptId || elapsed == null || stopped == null
			|| stopped < 0 || stopped > 1
			|| elapsed < -1 || elapsed > MAX_ELAPSED_TICKS)
		{
			return null;
		}
		return new TimerInput(source, elapsed, stopped != 0);
	}

	private static Integer asInt(Object value)
	{
		return value instanceof Number ? ((Number) value).intValue() : null;
	}

	static int sourceForScript(int scriptId)
	{
		switch (scriptId)
		{
			case TOA_TIMER_SCRIPT:
				return SOURCE_TOA;
			case TFA_TIMER_SCRIPT:
				return SOURCE_TFA;
			default:
				return -1;
		}
	}

	static String sourceName(int source)
	{
		switch (source)
		{
			case SOURCE_TOA:
				return "toa";
			case SOURCE_TFA:
				return "tfa";
			case SOURCE_COX:
				return "cox";
			default:
				return "unknown";
		}
	}

	/**
	 * Record a sample from a verified timer input.
	 *
	 * @param source    one of the {@code SOURCE_*} constants
	 * @param scopeKey  a stable shared raid/instance key, identical on every client in the raid
	 * @param raidTick  elapsed raid ticks; {@code -1} clears the timer
	 * @param stopped   the game froze the timer at this value
	 * @param localTick this client's game-tick counter at sampling
	 * @return true if the clock is active after the call
	 */
	boolean sample(int source, String scopeKey, int raidTick, boolean stopped, int localTick)
	{
		if (!isKnownSource(source) || scopeKey == null || scopeKey.isEmpty()
			|| localTick < 0 || raidTick > MAX_ELAPSED_TICKS)
		{
			return false;
		}
		if (raidTick < 0)
		{
			if (active && this.source == source)
			{
				clear();
			}
			return false;
		}
		// A final value alone cannot anchor anything; only transition a running clock to stopped.
		if (stopped && !active)
		{
			return false;
		}
		if (active && this.source != source)
		{
			clear();
		}
		String nextDomain = domain(source, scopeKey);
		int generationLength = generation > 0 ? (":g" + generation).length() : 0;
		if (nextDomain.length() + generationLength > MAX_DOMAIN_LENGTH)
		{
			return false;
		}
		if (!active)
		{
			establish(source, nextDomain, scopeKey, raidTick, stopped, localTick);
			return true;
		}

		boolean scopeChanged = !Objects.equals(scopeKey, this.scopeKey);
		boolean resumed = this.stopped && !stopped;
		int expected = anchorRaidTick + (localTick - anchorLocalTick);
		if (raidTick < expected)
		{
			if (scopeChanged)
			{
				// A new room/raid identity is its own epoch; reset any correction generation
				// so a client arriving in the new scope can share this domain.
				generation = 0;
				establish(source, nextDomain, scopeKey, raidTick, stopped, localTick);
				return true;
			}
			if (resumed)
			{
				// A timer resuming after a stop is a new epoch. Bump the generation so
				// events from before the stop cannot be ordered against the resumed mapping.
				generation++;
				establish(source, nextDomain, scopeKey, raidTick, stopped, localTick);
				return true;
			}
			if (expected - raidTick > RESET_BACKWARD_TOLERANCE)
			{
				// Same identity but too large a backwards jump to distinguish a correction
				// from a new epoch. Invalidate the exact clock rather than publish an unsafe
				// shared mapping; the next forward sample starts an isolated generation.
				clear();
				generation++;
				return false;
			}
			// A small same-scope correction: keep the monotonic mapping. Timer domains carry
			// an uncertainty radius that bounds the cross-client disagreement this creates.
			this.stopped = stopped;
			return true;
		}

		// Follow the current scope so a client arriving in the same room derives the same
		// domain. TFA keeps a stable controller scope, so this does not move across its rooms.
		this.domain = nextDomain;
		this.scopeKey = scopeKey;
		anchorLocalTick = localTick;
		anchorRaidTick = raidTick;
		this.stopped = stopped;
		return true;
	}

	/** Mark the current clock final (CoX progress complete) without extending it. */
	void markStopped()
	{
		if (active)
		{
			stopped = true;
		}
	}

	void clear()
	{
		source = -1;
		scopeKey = null;
		domain = null;
		anchorLocalTick = 0;
		anchorRaidTick = 0;
		stopped = false;
		active = false;
		generation = 0;
	}

	/** Shared elapsed tick for a local game tick, or {@link #NO_TICK} when not extrapolable. */
	long tickAt(int localTick)
	{
		if (!active || localTick < 0)
		{
			return NO_TICK;
		}
		if (stopped && localTick != anchorLocalTick)
		{
			return NO_TICK;
		}
		long tick = (long) anchorRaidTick + ((long) localTick - anchorLocalTick);
		return tick >= 0 ? tick : NO_TICK;
	}

	boolean isActive()
	{
		return active;
	}

	boolean isActiveFor(int source)
	{
		return active && this.source == source;
	}

	boolean isStopped()
	{
		return active && stopped;
	}

	String domain()
	{
		return active ? domain : null;
	}

	int anchorLocalTick()
	{
		return anchorLocalTick;
	}

	int anchorRaidTick()
	{
		return anchorRaidTick;
	}

	private void establish(int source, String domain, String scopeKey, int raidTick,
		boolean stopped, int localTick)
	{
		this.source = source;
		this.domain = generation == 0 ? domain : domain + ":g" + generation;
		this.scopeKey = scopeKey;
		this.anchorLocalTick = localTick;
		this.anchorRaidTick = raidTick;
		this.stopped = stopped;
		this.active = true;
	}

	private static String domain(int source, String scopeKey)
	{
		return "raid-time:v1:" + sourceName(source) + ":" + scopeKey + TIMER_UNCERTAINTY_SUFFIX;
	}

	/** Reject arbitrary source ints passed by a future caller. */
	private static boolean isKnownSource(int source)
	{
		return source == SOURCE_TOA || source == SOURCE_TFA || source == SOURCE_COX;
	}
}
