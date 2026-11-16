package net.betterpartydefence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

/**
 * Aggregates accepted history into per-weapon and per-player totals.
 */
final class SpecWeaponSummary
{
	final SpecialWeapon weapon;
	int value;
	int missedAttempts;
	final Map<String, PlayerSummary> players = new LinkedHashMap<>();

	private SpecWeaponSummary(SpecialWeapon weapon)
	{
		this.weapon = weapon;
	}

	static Map<SpecialWeapon, SpecWeaponSummary> fromHistory(List<SpecHistoryEntry> history)
	{
		Map<SpecialWeapon, SpecWeaponSummary> result = new LinkedHashMap<>();
		if (history == null)
		{
			return result;
		}
		for (SpecHistoryEntry entry : history)
		{
			if (entry == null || !SpecialWeaponRules.isSupported(entry.getWeapon()))
			{
				continue;
			}
			SpecialWeapon weapon = entry.getWeapon();
			SpecWeaponSummary summary = result.computeIfAbsent(weapon, SpecWeaponSummary::new);
			String player = entry.getPlayerName();
			if (player == null || player.trim().isEmpty())
			{
				player = "Unknown";
			}
			PlayerSummary own = summary.players.computeIfAbsent(player, ignored -> new PlayerSummary());
			int hit = entry.getHit();
			if (hit <= 0)
			{
				summary.missedAttempts++;
				own.misses++;
			}
			else
			{
				int delta = SpecialWeaponRules.reportedValue(weapon, hit);
				summary.value += delta;
				own.value += delta;
			}
		}
		return result;
	}

	String tooltip()
	{
		String unit = weapon.isDamage() ? "reported amount" : "successful hits";
		StringBuilder out = new StringBuilder(weapon.getName()).append("<br>")
			.append(unit).append(": ").append(value);
		if (missedAttempts > 0)
		{
			out.append("<br><col=ff4040>Misses: ").append(missedAttempts).append("</col>");
		}
		for (Map.Entry<String, PlayerSummary> entry : players.entrySet())
		{
			PlayerSummary player = entry.getValue();
			out.append("<br>").append(entry.getKey()).append(": ").append(player.value);
			if (player.misses > 0)
			{
				out.append(" <col=ff4040>(").append(player.misses).append(" misses)</col>");
			}
		}
		return out.toString();
	}

	static final class PlayerSummary
	{
		int value;
		int misses;
	}
}
