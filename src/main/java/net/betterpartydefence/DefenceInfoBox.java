package net.betterpartydefence;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
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
	public enum Stat
	{
		DEFENCE,
		MAGIC_DEFENCE
	}

	private final DefenceTracker tracker;
	private final BetterPartyDefenceConfig config;
	private final Stat stat;
	/** The last tooltip pushed, so a per-frame render only rebuilds it on a change. */
	private String tooltip = "";

	public DefenceInfoBox(BufferedImage image, Plugin plugin, DefenceTracker tracker, BetterPartyDefenceConfig config)
	{
		this(image, plugin, tracker, config, Stat.DEFENCE);
	}

	public DefenceInfoBox(
		BufferedImage image,
		Plugin plugin,
		DefenceTracker tracker,
		BetterPartyDefenceConfig config,
		Stat stat)
	{
		super(image, plugin);
		this.tracker = tracker;
		this.config = config;
		this.stat = stat;
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
		updateTooltip(state);
		if (stat == Stat.MAGIC_DEFENCE)
		{
			return magicText(state);
		}

		// Intentionally share the exact same full-vs-drainable toggle as the NPC readout.
		boolean full = config.defenceShowFullLevel();
		long current = DefenceReadout.shownDefence(state, full);
		long base = DefenceReadout.shownBaseDefence(state, full);
		return infoBoxValue(current, base);
	}

	private String magicText(DefenceState state)
	{
		long rollPercent = DefenceReadout.percentRemaining(state.getMagicRoll(), state.getMagicBaseRoll());
		switch (config.magicDefenceDisplay())
		{
			case LEVEL:
				return infoBoxValue(state.getMagicLevel(), state.getMagicBaseLevel());
			case PERCENT:
				return rollPercent + "%";
			case BOTH:
				return state.getMagicDef() + " " + rollPercent + "%";
			case BONUS:
			default:
				return infoBoxValue(state.getMagicDef(), state.getMagicBaseDef());
		}
	}

	private String infoBoxValue(long current, long base)
	{
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
	private void updateTooltip(DefenceState state)
	{
		String text;
		if (!config.extraInfoInInfoBox())
		{
			text = "Better Party Defence";
		}
		else
		{
			// Read history for the exact NPC state rendered above. If selection changes between
			// reads, fail closed to an empty tooltip rather than showing a previous target's count.
			List<SpecHistoryEntry> history = relevantHistory(
				tracker.specHistoryForCurrentTarget(state.getNpcIndex()));
			if (history.isEmpty())
			{
				text = "No specs yet";
			}
			else
			{
				StringBuilder tip = new StringBuilder("Spec history:");
				for (SpecSummary summary : aggregateHistory(history))
				{
					tip.append("<br>");
					if (summary.miss)
					{
						tip.append("<col=ff0000>");
					}
					tip.append(summary.playerName)
						.append(": ")
						.append(shortWeaponName(summary.weapon));
					if (summary.miss)
					{
						tip.append(" miss: ")
							.append(summary.value)
							.append("</col>");
					}
					else
					{
						tip.append(' ').append(summary.value);
					}
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

	private List<SpecHistoryEntry> relevantHistory(List<SpecHistoryEntry> history)
	{
		List<SpecHistoryEntry> relevant = new ArrayList<>();
		for (SpecHistoryEntry entry : history)
		{
			if (entry == null)
			{
				continue;
			}
			boolean include = stat == Stat.MAGIC_DEFENCE
				? DefenceTracker.drainsMagicDefence(entry.getWeapon())
				: DefenceTracker.drainsDefence(entry.getWeapon());
			if (include)
			{
				relevant.add(entry);
			}
		}
		return relevant;
	}

	/**
	 * Aggregate the hover the way the specs actually work. Percentage/fixed-effect specs are
	 * useful as a number of uses (DWH 1, 2, 3...), while damage/effect-magnitude specs such as
	 * BGS are useful as the total amount landed. Misses are always counted separately from
	 * successful specs, so they never inflate a use count or damage total. Preserve first-seen
	 * order for a stable tooltip.
	 */
	private static List<SpecSummary> aggregateHistory(List<SpecHistoryEntry> history)
	{
		List<SpecSummary> summaries = new ArrayList<>();
		for (SpecHistoryEntry entry : history)
		{
			if (entry == null)
			{
				continue;
			}
			SpecialWeapon weapon = entry.getWeapon();
			String player = entry.getPlayerName();
			if (player == null || player.trim().isEmpty())
			{
				player = "Unknown";
			}
			boolean miss = entry.getHit() <= 0;
			SpecSummary existing = null;
			for (SpecSummary summary : summaries)
			{
				if (summary.weapon == weapon && summary.miss == miss && summary.playerName.equals(player))
				{
					existing = summary;
					break;
				}
			}

			// A miss is always counted as one attempt in its own summary. It must never
			// increment a successful-use counter or contribute zero damage to a hit total.
			int amount = miss ? 1 : (historyUsesCount(weapon) ? 1 : Math.max(0, entry.getHit()));
			if (existing == null)
			{
				summaries.add(new SpecSummary(player, weapon, amount, miss));
			}
			else
			{
				existing.value += amount;
			}
		}
		return summaries;
	}

	private static boolean historyUsesCount(SpecialWeapon weapon)
	{
		if (weapon == null)
		{
			return true;
		}
		switch (weapon)
		{
			case DRAGON_WARHAMMER:
			case ELDER_MAUL:
			case ARCLIGHT:
			case DARKLIGHT:
			case EMBERLIGHT:
			case ACCURSED_SCEPTRE:
				return true;
			default:
				return false;
		}
	}

	private static final class SpecSummary
	{
		private final String playerName;
		private final SpecialWeapon weapon;
		private final boolean miss;
		private int value;

		private SpecSummary(String playerName, SpecialWeapon weapon, int value, boolean miss)
		{
			this.playerName = playerName;
			this.weapon = weapon;
			this.value = value;
			this.miss = miss;
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
		if (stat == Stat.MAGIC_DEFENCE)
		{
			return config.magicDefenceColor();
		}
		DefenceState state = tracker.state();
		return state == null ? Color.WHITE : DefenceReadout.defenceColor(state, config);
	}
}
