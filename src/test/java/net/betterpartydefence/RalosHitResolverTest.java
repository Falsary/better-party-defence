package net.betterpartydefence;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Tests the 2026-10-20 upstream-verified delayed-hit ordering rule. */
public class RalosHitResolverTest
{
    @Test
    public void realRalosLowHitsCountDespiteHighOrLowThrall()
    {
        assertEquals(2, RalosHitResolver.countConfirmedHits(Arrays.asList(1, 2, 3)));
        assertEquals(1, RalosHitResolver.countConfirmedHits(Arrays.asList(0, 2, 3)));
        assertEquals(1, RalosHitResolver.countConfirmedHits(Arrays.asList(2, 0, 3)));
        assertEquals(0, RalosHitResolver.countConfirmedHits(Arrays.asList(0, 0, 3)));
        assertEquals(0, RalosHitResolver.countConfirmedHits(Arrays.asList(0, 0, 2)));
        assertEquals(2, RalosHitResolver.countConfirmedHits(Arrays.asList(2, 2, 2)));
    }

    @Test
    public void extraDamageAfterRalosDoesNotDisplaceHits()
    {
        assertEquals(1, RalosHitResolver.countConfirmedHits(
            Arrays.asList(0, 1, 3, 44, 88)));
        assertEquals(2, RalosHitResolver.countConfirmedHits(
            Arrays.asList(1, 1, 2, 0, 10)));
    }

    @Test
    public void missingHitsplatsStayUnresolved()
    {
        assertEquals(-1, RalosHitResolver.countConfirmedHits(null));
        assertEquals(-1, RalosHitResolver.countConfirmedHits(Collections.emptyList()));
        assertEquals(-1, RalosHitResolver.countConfirmedHits(Collections.singletonList(3)));
        assertEquals(-1, RalosHitResolver.countConfirmedHits(Arrays.asList(-1, 3)));
    }

    @Test
    public void exhaustiveTwoRalosHitsAndSameTickThrall()
    {
        for (int first = 0; first <= 75; first++)
        {
            for (int second = 0; second <= 75; second++)
            {
                for (int thrall = 0; thrall <= 3; thrall++)
                {
                    List<Integer> hits = Arrays.asList(first, second, thrall);
                    int expected = (first > 0 ? 1 : 0) + (second > 0 ? 1 : 0);
                    assertEquals("Ralos=" + first + "," + second + "; thrall=" + thrall,
                        expected, RalosHitResolver.countConfirmedHits(hits));
                }
            }
        }
    }
}
