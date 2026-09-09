package net.betterpartydefence;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import net.betterpartydefence.DefenceTracker.DefenceState;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/**
 * Status-bar (info-box) display of the monster's live defence. The box itself shows the
 * configured single value; its optional tooltip is reserved for per-encounter spec history.
 */
public class DefenceInfoBox extends InfoBox
{
	private final DefenceTracker tracker;
	private final BetterPartyDefenceConfig config;
	/** The last tooltip pushed, so a per-frame render only rebuilds it on a change. */
	private String tooltip = "";

	public DefenceInfoBox(BufferedImage image, Plugin plugin, DefenceTracker tracker, BetterPartyDefenceConfig config)
	{
		super(image, plugin);
		this.tracker = tracker;
		this.config = config;
		setTooltip("Better Party Defence");
	}

	@Override
	public String getText()
	{
		DefenceState state = tracker.state();
		if (state == null)
		{
			return "";
		}
		updateTooltip();
		boolean full = config.defenceShowFullLevel();
		long current = DefenceReadout.shownDefence(state, full);
		long base = DefenceReadout.shownBaseDefence(state, full);
		switch (config.defenceInfoBoxValue())
		{
			case PERCENT:
				return DefenceReadout.percentRemaining(current, base) + "%";
			case DRAINED:
				return Long.toString(Math.max(0, base - current));
			case CURRENT:
			default:
				return Long.toString(current);
		}
	}

	/** Tooltip intentionally contains only spec history; current Defence/Magic data is not repeated here. */
	private void updateTooltip()
	{
		String text;
		if (!config.extraInfoInInfoBox())
		{
			text = "Better Party Defence";
		}
		else
		{
			List<SpecHistoryEntry> history = tracker.specHistory();
			if (history.isEmpty())
			{
				text = "No specs yet";
			}
			else
			{
				StringBuilder tip = new StringBuilder("Spec history:");
				for (SpecHistoryEntry entry : history)
				{
					tip.append("<br>")
						.append(entry.getPlayerName())
						.append(": ")
						.append(shortWeaponName(entry.getWeapon()))
						.append(' ')
						.append(entry.getHit());
				}
				text = tip.toString();
			}
		}

		if (!text.equals(tooltip))
		{
			tooltip = text;
			setTooltip(text);
		}
	}

	private static String shortWeaponName(SpecialWeapon weapon)
	{
		if (weapon == null)
		{
			return "Spec";
		}

		switch (weapon)
		{
			case DRAGON_WARHAMMER:
				return "DWH";
			case BANDOS_GODSWORD:
				return "BGS";
			case ELDER_MAUL:
				return "Elder maul";
			case TONALZTICS_OF_RALOS:
				return "Ralos";
			case BARRELCHEST_ANCHOR:
				return "Anchor";
			case BONE_DAGGER:
				return "Bone dagger";
			case DORGESHUUN_CROSSBOW:
				return "Dorgeshuun cbow";
			case ACCURSED_SCEPTRE:
				return "Accursed";
			case EYE_OF_AYAK:
				return "Eye of ayak";
			case ARCLIGHT:
				return "Arclight";
			case DARKLIGHT:
				return "Darklight";
			case EMBERLIGHT:
				return "Emberlight";
			case SEERCULL:
				return "Seercull";
			default:
				return weapon.name();
		}
	}

	@Override
	public Color getTextColor()
	{
		DefenceState state = tracker.state();
		return state == null ? Color.WHITE : DefenceReadout.defenceColor(state, config);
	}
}
