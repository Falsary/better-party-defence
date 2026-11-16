package net.betterpartydefence;

import java.awt.Color;
import net.betterpartydefence.BetterPartyDefenceConfig.DefenceDrainFormat;
import net.betterpartydefence.BetterPartyDefenceConfig.DefenceThresholdUnit;
import net.betterpartydefence.BetterPartyDefenceConfig.DefenceValueFormat;
import net.betterpartydefence.DefenceTracker.DefenceState;

// Shared Defence text, colour, and threshold formatting.
final class DefenceReadout {
	private static final int PERCENT = 100;

	private DefenceReadout() {
	}

	static String value(DefenceValueFormat format, long current, long base) {
		switch (format) {
			case CURRENT_OF_BASE :
				return current + "/" + base;
			case PERCENT :
				return percentRemaining(current, base) + "%";
			case CURRENT_AND_PERCENT :
				return current + " (" + percentRemaining(current, base) + "%)";
			case CURRENT :
			default :
				return Long.toString(current);
		}
	}

	// Return the drain suffix, or null when hidden or unchanged.
	static String drain(DefenceDrainFormat format, long current, long base) {
		long drained = base - current;
		if (drained <= 0 || format == DefenceDrainFormat.OFF) {
			return null;
		}
		return format == DefenceDrainFormat.PERCENT
				? percent(drained, base) + "%"
				: Long.toString(drained);
	}

	// Empty base values count as untouched.
	static long percentRemaining(long current, long base) {
		return base > 0 ? percent(Math.max(0, current), base) : PERCENT;
	}

	// Display full level or remaining defence above the floor.
	static long shownDefence(DefenceState state, boolean fullLevel) {
		return Math.max(0, fullLevel ? state.getCurrent() : state.getCurrent() - state.getMin());
	}

	static long shownBaseDefence(DefenceState state, boolean fullLevel) {
		return Math.max(0, fullLevel ? state.getBase() : state.getBase() - state.getMin());
	}

	// A target at its floor is fully drained.
	static boolean isFullyDrained(DefenceState state) {
		return state.getCurrent() <= state.getMin();
	}

	// Hide Magic Defence in TFA.
	static boolean magicDefenceVisible(DefenceState state) {
		return state != null && !state.isTfaBoss();
	}

	// Use the same low-defence threshold for overlay and info box.
	static boolean isLowDefence(DefenceState state, BetterPartyDefenceConfig config) {
		long aboveFloor = Math.max(state.getCurrent() - state.getMin(), 0);
		long threshold = config.defenceLowThresholdUnit() == DefenceThresholdUnit.PERCENT
				? (state.getBase() - state.getMin()) * Math.min(PERCENT, config.defenceLowThreshold()) / PERCENT
				: config.defenceLowThreshold();
		return aboveFloor > 0 && aboveFloor <= threshold;
	}

	static Color defenceColor(DefenceState state, BetterPartyDefenceConfig config) {
		if (isFullyDrained(state)) {
			return config.defenceCappedColor();
		}
		return isLowDefence(state, config) ? config.defenceLowColor() : config.defenceHighColor();
	}

	private static long percent(long part, long whole) {
		return Math.round(part * 100.0 / whole);
	}
}
