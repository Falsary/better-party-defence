package net.betterpartydefence;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/** Ensures icon values use precisely the accepted BPD hit history. */
public class SpecWeaponSummaryTest
{
    private SpecHistoryEntry hit(String player, SpecialWeapon weapon, int amount)
    {
        return new SpecHistoryEntry(player, weapon, amount);
    }

    @Test
    public void percentageSpecsCountHitsWhileDamageSpecsSumTheirAmounts()
    {
        Map<SpecialWeapon, SpecWeaponSummary> result = SpecWeaponSummary.fromHistory(Arrays.asList(
            hit("A", SpecialWeapon.DRAGON_WARHAMMER, 45),
            hit("B", SpecialWeapon.DRAGON_WARHAMMER, 12),
            hit("A", SpecialWeapon.BANDOS_GODSWORD, 25),
            hit("B", SpecialWeapon.BANDOS_GODSWORD, 19),
            hit("A", SpecialWeapon.EYE_OF_AYAK, 18),
            hit("B", SpecialWeapon.TONALZTICS_OF_RALOS, 2)));
        assertEquals(2, result.get(SpecialWeapon.DRAGON_WARHAMMER).value);
        assertEquals(44, result.get(SpecialWeapon.BANDOS_GODSWORD).value);
        assertEquals(18, result.get(SpecialWeapon.EYE_OF_AYAK).value);
        assertEquals(2, result.get(SpecialWeapon.TONALZTICS_OF_RALOS).value);
    }

    @Test
    public void missesNeverInflateHitsOrDamage()
    {
        SpecWeaponSummary dwh = SpecWeaponSummary.fromHistory(Arrays.asList(
            hit("A", SpecialWeapon.DRAGON_WARHAMMER, 0),
            hit("B", SpecialWeapon.DRAGON_WARHAMMER, 12),
            hit("B", SpecialWeapon.DRAGON_WARHAMMER, 0)))
            .get(SpecialWeapon.DRAGON_WARHAMMER);
        assertEquals(1, dwh.value);
        assertEquals(2, dwh.missedAttempts);
        assertEquals(1, dwh.players.get("B").misses);
        assertTrue(dwh.tooltip().contains("Misses: 2"));
    }

    @Test
    public void currentHistoryAloneControlsVisibilityAndFreshEncounterResets()
    {
        assertTrue(SpecWeaponSummary.fromHistory(Collections.emptyList()).isEmpty());
        Map<SpecialWeapon, SpecWeaponSummary> first = SpecWeaponSummary.fromHistory(Arrays.asList(
            hit("A", SpecialWeapon.BANDOS_GODSWORD, 30)));
        Map<SpecialWeapon, SpecWeaponSummary> next = SpecWeaponSummary.fromHistory(Arrays.asList(
            hit("A", SpecialWeapon.BANDOS_GODSWORD, 4)));
        assertEquals(30, first.get(SpecialWeapon.BANDOS_GODSWORD).value);
        assertEquals(4, next.get(SpecialWeapon.BANDOS_GODSWORD).value);
    }
}
