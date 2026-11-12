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
		name = "General",
		description = "Core tracker behaviour and compatibility options.",
		position = 0)
	String GENERAL = "general";

	@ConfigSection(
		name = "Tracker Position",
		description = "Placement and formatting of Defence readouts.",
		position = 1)
	String MAIN_TRACKER = "mainTracker";

	@ConfigSection(
		name = "Info Boxes",
		description = "RuneLite status-bar info boxes for Defence, Magic defence, and BPD weapon counters.",
		position = 2,
		closedByDefault = true)
	String INFO_BOXES = "infoBoxes";

	@ConfigSection(
		name = "Text & Icons",
		description = "Font, skill icons, and text appearance for Defence readouts.",
		position = 3,
		closedByDefault = true)
	String TEXT = "text";

	@ConfigSection(
		name = "Colours",
		description = "Colours and low-Defence threshold for Defence readouts.",
		position = 4,
		closedByDefault = true)
	String COLOURS = "colours";

	@ConfigSection(
		name = "Magic Defence",
		description = "Magic-defence display settings.",
		position = 5,
		closedByDefault = true)
	String MAGIC = "magic";

	@ConfigSection(
		name = "Other infoboxes",
		description = "Infoboxes for landed boss effects.",
		position = 6,
		closedByDefault = true)
	String OTHER_INFOBOXES = "otherInfoboxes";

	@ConfigItem(
		keyName = "showRedKeris",
		name = "Show red keris",
		description = "Red keris timer infobox on spec",
		position = 1,
		section = OTHER_INFOBOXES)
	default boolean showRedKeris() { return true; }

	@ConfigItem(
		keyName = "showShadowBarrage",
		name = "Show shadow barrage",
		description = "Must have shadow sceptre equipped",
		position = 2,
		section = OTHER_INFOBOXES)
	default boolean showShadowBarrage() { return true; }

	@ConfigItem(
		keyName = "showVulnerability",
		name = "Show vulnerability",
		description = "Shows a vulnerability infobox on successful hit",
		position = 3,
		section = OTHER_INFOBOXES)
	default boolean showVulnerability() { return true; }

	@ConfigItem(
		keyName = "defenceHpBar",
		name = "Show defence tracker",
		description = "Enable the Better Party Defence tracker.",
		position = 1,
		section = GENERAL)
	default boolean defenceHpBar() { return true; }

	@ConfigItem(
		keyName = "defenceAlwaysShow",
		name = "Display before special att",
		description = "Show the target's at its starting Defence before any tracked defence-draining special attack lands.",
		position = 2,
		section = GENERAL)
	default boolean defenceAlwaysShow() { return false; }

	@ConfigItem(
		keyName = "defenceHpBarPosition",
		name = "Position on NPC",
		description = "Where the Defence readout appears on each NPC.",
		position = 2,
		section = MAIN_TRACKER)
	default DefenceOverlayPosition defenceHpBarPosition() { return DefenceOverlayPosition.ABOVE_HP_BAR; }

	@Range(min = -200, max = 200)
	@Units(Units.PIXELS)
	@ConfigItem(
		keyName = "defenceHpBarYOffset",
		name = "Vertical offset",
		description = "Move Defence readouts vertically. Positive values move them up; negative values move them down.",
		position = 3,
		section = MAIN_TRACKER)
	default int defenceHpBarYOffset() { return 0; }

	@ConfigItem(
		keyName = "defenceValueFormat",
		name = "Defence value format",
		description = "Choose a Defence format, such as 142, 142/200, 71%, or 142 (71%).",
		position = 4,
		section = MAIN_TRACKER)
	default DefenceValueFormat defenceValueFormat() { return DefenceValueFormat.CURRENT; }

	@ConfigItem(
		keyName = "defenceDrainFormat",
		name = "Drain suffix",
		description = "Choose what appears after the down arrow: levels drained, percent drained, or nothing.",
		position = 5,
		section = MAIN_TRACKER)
	default DefenceDrainFormat defenceDrainFormat() { return DefenceDrainFormat.AMOUNT; }

	@ConfigItem(
		keyName = "defenceShowFullLevel",
		name = "Show full Defence",
		description = "Off (default): show only drainable Defence, On: show the actual Defence level down to its floor.",
		position = 6,
		section = MAIN_TRACKER)
	default boolean defenceShowFullLevel() { return false; }

	@ConfigItem(
		keyName = "defenceInfoBox",
		name = "Defence info box",
		description = "Show tracked Defence in the RuneLite info-box bar. This follows the shared Show full Defence level setting above.",
		position = 1,
		section = INFO_BOXES)
	default boolean defenceInfoBox() { return true; }

	@ConfigItem(
		keyName = "defenceInfoBoxValue",
		name = "Defence info-box value",
		description = "Choose the single Defence number shown inside the info box.",
		position = 2,
		section = INFO_BOXES)
	default DefenceInfoBoxValue defenceInfoBoxValue() { return DefenceInfoBoxValue.CURRENT; }

	@ConfigItem(
		keyName = "extraInfoInInfoBox",
		name = "Spec history on hover",
		description = "When hovering the Defence info box, show who used each tracked defence-draining special attack.",
		position = 3,
		section = INFO_BOXES)
	default boolean extraInfoInInfoBox() { return true; }

	@ConfigItem(
		keyName = "specWeaponInfoBoxes",
		name = "Weapon spec info boxes",
		description = "Show Better Party Defence's own weapon-icon counters using accepted encounter spec history and party sync. Turn off to hide the extra counters.",
		position = 4,
		section = INFO_BOXES)
	default boolean specWeaponInfoBoxes() { return true; }

	@ConfigItem(
		keyName = "magicDefenceInfoBox",
		name = "Magic info box",
		description = "Show tracked Magic-defence changes in a RuneLite info box. Requires Show magic defence to be enabled.",
		position = 5,
		section = INFO_BOXES)
	default boolean magicDefenceInfoBox() { return true; }

	@ConfigItem(
		keyName = "defenceInfoBoxUseThemeSkillIcon",
		name = "Use themed info-box icons",
		description = "Use Defence and Magic skill icons from the active RuneLite theme in Better Party Defence info boxes when available.",
		position = 6,
		section = INFO_BOXES)
	default boolean defenceInfoBoxUseThemeSkillIcon() { return false; }

	@ConfigItem(
		keyName = "defenceShowIcons",
		name = "Show skill icons",
		description = "Draw Defence and Magic skill icons in front of Defence readouts.",
		position = 1,
		section = TEXT)
	default boolean defenceShowIcons() { return true; }

	@ConfigItem(
		keyName = "defenceUseThemeSkillIcons",
		name = "Use themed tracker icons",
		description = "Use skill icons from the active RuneLite theme when available; otherwise use the standard icons.",
		position = 2,
		section = TEXT)
	default boolean defenceUseThemeSkillIcons() { return true; }

	@ConfigItem(
		keyName = "defenceFont",
		name = "Font",
		description = "Font used for Defence readouts. Choose Add custom font... to select a local font file.",
		position = 3,
		section = TEXT)
	default TrackerFont defenceFont() { return TrackerFont.RUNESCAPE; }

	@Range(min = 8, max = 48)
	@ConfigItem(
		keyName = "defenceFontSizePx",
		name = "Font size",
		description = "Font size for Defence readouts, in pixels.",
		position = 4,
		section = TEXT)
	default int defenceFontSize() { return 15; }

	@ConfigItem(
		keyName = "defenceBoldText",
		name = "Bold text",
		description = "Use bold text for Defence readouts.",
		position = 5,
		section = TEXT)
	default boolean defenceBoldText() { return false; }

	@ConfigItem(
		keyName = "customFontPath",
		name = "Custom font file",
		description = "Local font selected by Add custom font.",
		position = 6,
		section = TEXT,
		hidden = true)
	default String customFontPath() { return ""; }

	@ConfigItem(
		keyName = "defenceTextPlate",
		name = "Text background",
		description = "Draw a translucent background behind Defence readouts.",
		position = 6,
		section = TEXT)
	default boolean defenceTextPlate() { return false; }

	@Range(min = 0, max = 500)
	@ConfigItem(
		keyName = "defenceLowThreshold",
		name = "Low-Defence threshold",
		description = "At or below this amount of remaining drainable Defence, use the Low Defence colour.",
		position = 1,
		section = COLOURS)
	default int defenceLowThreshold() { return 10; }

	@ConfigItem(
		keyName = "defenceLowThresholdUnit",
		name = "Threshold type",
		description = "Treat the Low-Defence threshold as levels above the Defence floor or as a percent of total drainable Defence.",
		position = 2,
		section = COLOURS)
	default DefenceThresholdUnit defenceLowThresholdUnit() { return DefenceThresholdUnit.LEVELS; }

	@Alpha
	@ConfigItem(
		keyName = "defenceHighColor",
		name = "Normal Defence colour",
		description = "Colour used while Defence is above the Low-Defence threshold.",
		position = 3,
		section = COLOURS)
	default Color defenceHighColor() { return Color.WHITE; }

	@Alpha
	@ConfigItem(
		keyName = "defenceLowColor",
		name = "Low Defence colour",
		description = "Colour used when Defence reaches or falls below the Low-Defence threshold.",
		position = 4,
		section = COLOURS)
	default Color defenceLowColor() { return new Color(0xC0, 0xAB, 0x46); }

	@Alpha
	@ConfigItem(
		keyName = "defenceCappedColor",
		name = "Fully drained colour",
		description = "Colour used when the target reaches its minimum Defence floor and cannot be drained further.",
		position = 5,
		section = COLOURS)
	default Color defenceCappedColor() { return new Color(0x57, 0x95, 0x49); }

	@Alpha
	@ConfigItem(
		keyName = "defenceDrainColor",
		name = "Drain amount colour",
		description = "Colour used for the down arrow and drained amount or percentage.",
		position = 6,
		section = COLOURS)
	default Color defenceDrainColor() { return new Color(0xAD, 0x14, 0x59); }

	@ConfigItem(
		keyName = "magicDefence",
		name = "Show magic defence",
		description = "Track and display Magic-defence changes caused by supported special attacks.",
		position = 1,
		section = MAGIC)
	default boolean magicDefence() { return true; }

	@ConfigItem(
		keyName = "magicDefenceDisplay",
		name = "Magic value shown",
		description = "Choose whether the tracker shows Magic-defence bonus, Magic level, percent of the starting magic-defence roll, or bonus plus percent.",
		position = 2,
		section = MAGIC)
	default MagicDefenceDisplay magicDefenceDisplay() { return MagicDefenceDisplay.BONUS; }

	@ConfigItem(
		keyName = "magicDefenceSameRow",
		name = "Place beside Defence",
		description = "Show the Magic-defence readout beside Defence instead of on a second line.",
		position = 3,
		section = MAGIC)
	default boolean magicDefenceSameRow() { return true; }

	@Alpha
	@ConfigItem(
		keyName = "magicDefenceColor",
		name = "Magic defence colour",
		description = "Colour used for the Magic-defence readout.",
		position = 4,
		section = MAGIC)
	default Color magicDefenceColor() { return new Color(0x22, 0x5E, 0xA8); }
}
