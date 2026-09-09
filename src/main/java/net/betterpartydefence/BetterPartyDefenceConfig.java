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

	@ConfigSection(name = "Display", description = "Where and when the tracker is shown.", position = 0)
	String DISPLAY = "display";

	@ConfigSection(name = "Text & icons", description = "Font and icon appearance.", position = 1)
	String TEXT = "text";

	@ConfigSection(name = "Colours", description = "Tracker colours and thresholds.", position = 2)
	String COLOURS = "colours";

	@ConfigSection(name = "Magic defence", description = "Magic-defence display options.", position = 3)
	String MAGIC = "magic";

	@ConfigSection(name = "Experimental", description = "Experimental Better Party Defence features.", position = 4)
	String EXPERIMENTAL = "experimental";

	@ConfigItem(keyName = "defenceHpBar", name = "Show defence tracker", description = "Display the tracked monster's live defence.", position = 1, section = DISPLAY)
	default boolean defenceHpBar() { return true; }

	@ConfigItem(keyName = "defenceDisplayMode", name = "Display location", description = "Attach the tracker to the NPC or detach it as a movable overlay.", position = 2, section = DISPLAY)
	default DefenceDisplayMode defenceDisplayMode() { return DefenceDisplayMode.NPC; }

	@ConfigItem(keyName = "defenceHpBarPosition", name = "Attached position", description = "Where the attached display sits, including directly to the right of the HP bar.", position = 3, section = DISPLAY)
	default DefenceOverlayPosition defenceHpBarPosition() { return DefenceOverlayPosition.ABOVE_HP_BAR; }

	@Range(min = -200, max = 200)
	@Units(Units.PIXELS)
	@ConfigItem(keyName = "defenceHpBarYOffset", name = "Vertical nudge", description = "Shift the attached display up by this many pixels; negative values move it down.", position = 4, section = DISPLAY)
	default int defenceHpBarYOffset() { return 0; }

	@ConfigItem(keyName = "hideOverlappingDefenceDisplays", name = "Hide overlapping defence displays", description = "Hide RuneLite Special Attack Counter infoboxes that overlap with Better Party Defence without disabling its spec detection or party messages.", position = 5, section = DISPLAY)
	default boolean hideOverlappingDefenceDisplays() { return true; }

	@ConfigItem(keyName = "defenceInfoBox", name = "Show info box", description = "Also show the tracked Defence value in the RuneLite info-box bar.", position = 6, section = DISPLAY)
	default boolean defenceInfoBox() { return true; }

	@ConfigItem(keyName = "defenceInfoBoxValue", name = "Info box shows", description = "Choose the single value displayed in the info box.", position = 7, section = DISPLAY)
	default DefenceInfoBoxValue defenceInfoBoxValue() { return DefenceInfoBoxValue.CURRENT; }

	@ConfigItem(keyName = "extraInfoInInfoBox", name = "Extra info in info box", description = "Show who used each tracked defence-draining special attack when you hover the info box.", position = 8, section = DISPLAY)
	default boolean extraInfoInInfoBox() { return true; }

	@ConfigItem(keyName = "defenceAlwaysShow", name = "Show before first spec", description = "Show the last supported monster you targeted at its starting Defence before the first drain lands.", position = 9, section = DISPLAY)
	default boolean defenceAlwaysShow() { return false; }

	@ConfigItem(keyName = "defenceValueFormat", name = "Defence shown as", description = "Current value, current/base, percent remaining, or current with percent.", position = 10, section = DISPLAY)
	default DefenceValueFormat defenceValueFormat() { return DefenceValueFormat.CURRENT; }

	@ConfigItem(keyName = "defenceDrainFormat", name = "Drain shown as", description = "Show drained levels, drained percent, or hide the drain suffix.", position = 11, section = DISPLAY)
	default DefenceDrainFormat defenceDrainFormat() { return DefenceDrainFormat.AMOUNT; }

	@ConfigItem(keyName = "defenceShowFullLevel", name = "Show full level", description = "For monsters with a Defence floor, show the full level instead of only the drainable amount.", position = 12, section = DISPLAY)
	default boolean defenceShowFullLevel() { return true; }

	@ConfigItem(keyName = "defenceShowIcons", name = "Show skill icons", description = "Draw Defence and Magic skill icons in front of their readouts.", position = 1, section = TEXT)
	default boolean defenceShowIcons() { return true; }

	@ConfigItem(keyName = "defenceUseThemeSkillIcons", name = "Use theme skill icons", description = "Use Defence and Magic icons from the active RuneLite theme when available; otherwise use the standard icons.", position = 2, section = TEXT)
	default boolean defenceUseThemeSkillIcons() { return true; }

	@ConfigItem(keyName = "defenceFont", name = "Font", description = "Font used by the Defence tracker.", position = 3, section = TEXT)
	default TrackerFont defenceFont() { return TrackerFont.RUNESCAPE; }

	@Range(min = 8, max = 48)
	@ConfigItem(keyName = "defenceFontSizePx", name = "Font size", description = "Tracker font size in pixels.", position = 4, section = TEXT)
	default int defenceFontSize() { return 15; }

	@ConfigItem(keyName = "defenceBoldText", name = "Bold text", description = "Use bold text for the Defence tracker.", position = 5, section = TEXT)
	default boolean defenceBoldText() { return false; }

	@ConfigItem(keyName = "customFontPath", name = "Custom font file", description = "Local font selected by Add custom font.", position = 6, section = TEXT, hidden = true)
	default String customFontPath() { return ""; }

	@ConfigItem(keyName = "defenceTextPlate", name = "Text background", description = "Draw a translucent background behind the tracker text.", position = 7, section = TEXT)
	default boolean defenceTextPlate() { return false; }

	@Range(min = 0, max = 500)
	@ConfigItem(keyName = "defenceLowThreshold", name = "Low defence threshold", description = "Defence at or below this threshold uses the low-defence colour.", position = 1, section = COLOURS)
	default int defenceLowThreshold() { return 10; }

	@ConfigItem(keyName = "defenceLowThresholdUnit", name = "Threshold unit", description = "Interpret the threshold as levels or percent of the drainable Defence.", position = 2, section = COLOURS)
	default DefenceThresholdUnit defenceLowThresholdUnit() { return DefenceThresholdUnit.LEVELS; }

	@Alpha
	@ConfigItem(keyName = "defenceHighColor", name = "High defence colour", description = "Colour used above the low-defence threshold.", position = 3, section = COLOURS)
	default Color defenceHighColor() { return Color.WHITE; }

	@Alpha
	@ConfigItem(keyName = "defenceLowColor", name = "Low defence colour", description = "Colour used at or below the low-defence threshold.", position = 4, section = COLOURS)
	default Color defenceLowColor() { return new Color(0xC0, 0xAB, 0x46); }

	@Alpha
	@ConfigItem(keyName = "defenceCappedColor", name = "Capped defence colour", description = "Colour used when the target has reached its Defence floor.", position = 5, section = COLOURS)
	default Color defenceCappedColor() { return new Color(0x57, 0x95, 0x49); }

	@Alpha
	@ConfigItem(keyName = "defenceDrainColor", name = "Drain colour", description = "Colour used for the drain arrow and drained amount.", position = 6, section = COLOURS)
	default Color defenceDrainColor() { return new Color(0xAD, 0x14, 0x59); }

	@ConfigItem(keyName = "magicDefence", name = "Show magic defence", description = "Also show magic-defence changes from supported special attacks.", position = 1, section = MAGIC)
	default boolean magicDefence() { return true; }

	@ConfigItem(keyName = "magicDefenceInfoBox", name = "Show magic info box", description = "Also show the tracked Magic-defence value in the RuneLite info-box bar.", position = 2, section = MAGIC)
	default boolean magicDefenceInfoBox() { return true; }

	@ConfigItem(keyName = "magicDefenceDisplay", name = "Magic defence as", description = "Choose magic-defence bonus, Magic level, percent of starting roll, or bonus plus percent.", position = 3, section = MAGIC)
	default MagicDefenceDisplay magicDefenceDisplay() { return MagicDefenceDisplay.BONUS; }

	@ConfigItem(keyName = "magicDefenceSameRow", name = "Magic on same row", description = "Draw the magic-defence readout beside Defence instead of on a second line.", position = 4, section = MAGIC)
	default boolean magicDefenceSameRow() { return false; }

	@Alpha
	@ConfigItem(keyName = "magicDefenceColor", name = "Magic defence colour", description = "Colour used for the magic-defence readout.", position = 5, section = MAGIC)
	default Color magicDefenceColor() { return new Color(0x22, 0x5E, 0xA8); }

	@ConfigItem(
		keyName = "syncWithOtherPartyDefenceUsers",
		name = "Sync with other Party Defence users in party",
		description = "Share Better Party Defence tracker state with other BPD users in the same party and encounter.",
		position = 1,
		section = EXPERIMENTAL)
	default boolean syncWithOtherPartyDefenceUsers() { return true; }
}

