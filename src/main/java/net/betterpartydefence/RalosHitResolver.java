package net.betterpartydefence;

import java.util.List;

/**
 * Resolve the two delayed Tonalztics of Ralos glaive hits from game-client
 * hitsplat arrival order. Both normal damage and misses (0) count as
 * hitsplats. Later same-tick thrall/Vengeance/instant-damage hitsplats
 * cannot overwrite the pair, regardless of whether thrall models render.
 *
 * Evidence: RuneLite SpecialCounterPlugin, upstream PR #20377 merged Oct 20,
 * 2026. Arrival order: [delayed hits, thrall, veng, instant hits].
 *
 * This is NOT general-purpose attribution of multiple distinct delayed
 * projectiles arriving on the same tick; no attacker ID exists on hitsplats.
 */
final class RalosHitResolver
{
    static final int UNKNOWN = -1;

    private RalosHitResolver() {}

    /** Return 0..2 successful glaives, or UNKNOWN if the pair is incomplete. */
    static int countConfirmedHits(List<Integer> orderedHitsplats)
    {
        if (orderedHitsplats == null || orderedHitsplats.size() < 2)
        {
            return UNKNOWN;
        }
        Integer first = orderedHitsplats.get(0);
        Integer second = orderedHitsplats.get(1);
        if (first == null || second == null || first < 0 || second < 0)
        {
            return UNKNOWN;
        }
        return (first > 0 ? 1 : 0) + (second > 0 ? 1 : 0);
    }
}
