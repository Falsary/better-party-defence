package net.betterpartydefence;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

/** Display-only settings for Better Party Defence. */
@ConfigGroup(BetterPartyDefenceConfig.GROUP)
public interface BetterPartyDefenceConfig extends Config
{
	String GROUP = "betterpartydefence";

	@ConfigSection(
		name = "Defence display",
		description = "How the tracked boss defence is displayed.",
		position = 0
	)
	String DEFENCE = "defence";

	@ConfigItem(
		keyName = "defenceHpBar",
		name = "Show next to HP bar",
		description = "Display the tracked monster's live defence in the scene.",
		position = 1,
		section = DEFENCE
	)
	default boolean defenceHpBar()
	{
		return true;
	}

	@ConfigItem(
		keyName = "defenceHpBarPosition",
		name = "Overlay position",
		description = "Where the scene defence display sits relative to the monster.",
		position = 2,
		section = DEFENCE
	)
	default DefenceOverlayPosition defenceHpBarPosition()
	{
		return DefenceOverlayPosition.ABOVE_HP_BAR;
	}

	@Range(min = -200, max = 200)
	@Units(Units.PIXELS)
	@ConfigItem(
		keyName = "defenceHpBarYOffset",
		name = "Vertical nudge",
		description = "Shift the scene defence display up by this many pixels; negative values move it down.",
		position = 3,
		section = DEFENCE
	)
	default int defenceHpBarYOffset()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "defenceInfoBox",
		name = "Show info box",
		description = "Also show the tracked Defence value in the RuneLite info-box bar.",
		position = 4,
		section = DEFENCE
	)
	default boolean defenceInfoBox()
	{
		return false;
	}

	@ConfigItem(
		keyName = "defenceInfoBoxValue",
		name = "Info box shows",
		description = "Choose the single value displayed in the info box.",
		position = 5,
		section = DEFENCE
	)
	default DefenceInfoBoxValue defenceInfoBoxValue()
	{
		return DefenceInfoBoxValue.CURRENT;
	}

	@ConfigItem(
		keyName = "defenceAlwaysShow",
		name = "Show before first spec",
		description = "Show a supported monster's starting Defence while you are interacting with it, before the first drain lands.",
		position = 6,
		section = DEFENCE
	)
	default boolean defenceAlwaysShow()
	{
		return true;
	}

	@ConfigItem(
		keyName = "defenceValueFormat",
		name = "Defence shown as",
		description = "Current value, current/base, percent remaining, or current with percent.",
		position = 7,
		section = DEFENCE
	)
	default DefenceValueFormat defenceValueFormat()
	{
		return DefenceValueFormat.CURRENT;
	}

	@ConfigItem(
		keyName = "defenceDrainFormat",
		name = "Drain shown as",
		description = "Show drained levels, drained percent, or hide the drain suffix.",
		position = 8,
		section = DEFENCE
	)
	default DefenceDrainFormat defenceDrainFormat()
	{
		return DefenceDrainFormat.AMOUNT;
	}

	@ConfigItem(
		keyName = "defenceShowFullLevel",
		name = "Show full level",
		description = "For monsters with a Defence floor, show the full level instead of only the drainable amount.",
		position = 9,
		section = DEFENCE
	)
	default boolean defenceShowFullLevel()
	{
		return false;
	}

	@ConfigItem(
		keyName = "defenceShowIcons",
		name = "Show skill icons",
		description = "Draw Defence and Magic skill icons in front of their readouts.",
		position = 10,
		section = DEFENCE
	)
	default boolean defenceShowIcons()
	{
		return true;
	}

	@ConfigItem(
		keyName = "defenceFontSize",
		name = "Scene text size",
		description = "Font size for the on-scene defence display.",
		position = 11,
		section = DEFENCE
	)
	default SceneFontSize defenceFontSize()
	{
		return SceneFontSize.SMALL;
	}

	@ConfigItem(
		keyName = "defenceTextPlate",
		name = "Text background",
		description = "Draw a translucent background behind the scene text.",
		position = 12,
		section = DEFENCE
	)
	default boolean defenceTextPlate()
	{
		return false;
	}

	@Range(min = 0, max = 500)
	@ConfigItem(
		keyName = "defenceLowThreshold",
		name = "Low defence threshold",
		description = "Defence at or below this threshold is drawn using the low-defence colour.",
		position = 13,
		section = DEFENCE
	)
	default int defenceLowThreshold()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "defenceLowThresholdUnit",
		name = "Threshold unit",
		description = "Interpret the threshold as levels or percent of the drainable Defence.",
		position = 14,
		section = DEFENCE
	)
	default DefenceThresholdUnit defenceLowThresholdUnit()
	{
		return DefenceThresholdUnit.LEVELS;
	}

	@Alpha
	@ConfigItem(
		keyName = "defenceHighColor",
		name = "High defence colour",
		description = "Colour used above the low-defence threshold.",
		position = 15,
		section = DEFENCE
	)
	default Color defenceHighColor()
	{
		return Color.WHITE;
	}

	@Alpha
	@ConfigItem(
		keyName = "defenceLowColor",
		name = "Low defence colour",
		description = "Colour used at or below the low-defence threshold.",
		position = 16,
		section = DEFENCE
	)
	default Color defenceLowColor()
	{
		return Color.YELLOW;
	}

	@Alpha
	@ConfigItem(
		keyName = "defenceCappedColor",
		name = "Capped defence colour",
		description = "Colour used when the target has reached its Defence floor.",
		position = 17,
		section = DEFENCE
	)
	default Color defenceCappedColor()
	{
		return Color.GREEN;
	}

	@Alpha
	@ConfigItem(
		keyName = "defenceDrainColor",
		name = "Drain colour",
		description = "Colour used for the drain arrow and drained amount.",
		position = 18,
		section = DEFENCE
	)
	default Color defenceDrainColor()
	{
		return new Color(255, 80, 80);
	}

	@ConfigItem(
		keyName = "magicDefence",
		name = "Show magic defence",
		description = "Also show magic-defence changes from supported special attacks.",
		position = 19,
		section = DEFENCE
	)
	default boolean magicDefence()
	{
		return true;
	}

	@ConfigItem(
		keyName = "magicDefenceDisplay",
		name = "Magic defence as",
		description = "Choose magic-defence bonus, Magic level, percent of starting roll, or bonus plus percent.",
		position = 20,
		section = DEFENCE
	)
	default MagicDefenceDisplay magicDefenceDisplay()
	{
		return MagicDefenceDisplay.BONUS;
	}

	@ConfigItem(
		keyName = "magicDefenceSameRow",
		name = "Magic on same row",
		description = "Draw the magic-defence readout beside Defence instead of on a second line.",
		position = 21,
		section = DEFENCE
	)
	default boolean magicDefenceSameRow()
	{
		return false;
	}

	@Alpha
	@ConfigItem(
		keyName = "magicDefenceColor",
		name = "Magic defence colour",
		description = "Colour used for the magic-defence readout.",
		position = 22,
		section = DEFENCE
	)
	default Color magicDefenceColor()
	{
		return new Color(120, 180, 255);
	}
}
