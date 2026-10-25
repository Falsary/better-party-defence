package net.betterpartydefence;

import net.betterpartydefence.DefenceTracker.DefenceState;

/**
 * Hide Magic Defence readouts for The Fractured Archive until its boss-specific
 * Magic drain mechanics are verified. This policy applies to active, previous,
 * and party-synced target displays. It does not mutate combat stats: internal
 * Magic state is still needed for Ralos and other Defence-drain calculations.
 * Every supported non-TFA boss keeps its usual Magic Defence presentation.
 */
final class MagicDefenceVisibility
{
    private MagicDefenceVisibility()
    {
    }

    static boolean isVisible(DefenceState state)
    {
        return state != null && !state.isTfaBoss();
    }
}
