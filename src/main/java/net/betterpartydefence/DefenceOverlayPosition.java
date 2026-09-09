package net.betterpartydefence;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Where the attached Defence display sits relative to the monster. */
@Getter
@RequiredArgsConstructor
public enum DefenceOverlayPosition
{
	ABOVE_HP_BAR("Above HP bar", 1.0, 55, 0),
	RIGHT_OF_HP_BAR("Right of HP bar", 1.0, 55, 38),
	CENTRE_OF_NPC("Centre of NPC", 0.5, 0, 0),
	AT_NPC_FEET("At NPC feet", 0.0, 0, 0);

	private final String label;
	private final double heightFactor;
	private final int heightOffset;
	private final int xNudge;

	@Override
	public String toString()
	{
		return label;
	}
}
