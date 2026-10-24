package net.betterpartydefence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

/**
 * Projection of BPD's authoritative encounter history into SAC-style icon counters.
 * This class NEVER listens to RuneLite's separate UI/counter state. It is a pure view
 * of the exact accepted, synced specs used to calculate our own Defence readouts.
 */
final class SpecWeaponSummary
{
    final SpecialWeapon weapon;
    int value;
    int missedAttempts;
    final Map<String, PlayerSummary> players = new LinkedHashMap<>();

    static final class PlayerSummary
    {
        int value;
        int misses;
    }

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
            if (entry == null || !DefenceTracker.isSupportedWeapon(entry.getWeapon()))
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
                // Match Special Attack Counter's semantics: successful-use count for
                // percentage specials, actual reported amount for damage-based specs.
                // In particular Ralos is already resolved to 0-2 successful projectiles
                // by BPD's existing detector; never reinterpret 1/2 as raw damage.
                int delta = weapon.isDamage() ? hit : 1;
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
        for (Map.Entry<String, PlayerSummary> e : players.entrySet())
        {
            PlayerSummary p = e.getValue();
            out.append("<br>").append(e.getKey()).append(": ").append(p.value);
            if (p.misses > 0)
            {
                out.append(" <col=ff4040>(").append(p.misses).append(" misses)</col>");
            }
        }
        return out.toString();
    }
}
