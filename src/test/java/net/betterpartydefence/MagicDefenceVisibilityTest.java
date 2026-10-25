package net.betterpartydefence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.betterpartydefence.DefenceTracker.DefenceState;
import org.junit.Test;

/** TFA Magic Defence presentation is disabled, without changing normal Defence tracking. */
public class MagicDefenceVisibilityTest
{
    private static DefenceState state(boolean tfaBoss, boolean drained)
    {
        return new DefenceState(12, "Example", 100, 0, 100,
            25000, 25000, 100, 100, 290, 290, drained, tfaBoss);
    }

    @Test
    public void tfaMagicIsHiddenBeforeAndAfterSpecs()
    {
        assertFalse(MagicDefenceVisibility.isVisible(state(true, false)));
        assertFalse(MagicDefenceVisibility.isVisible(state(true, true)));
    }

    @Test
    public void nonTfaMagicIsUnchanged()
    {
        assertTrue(MagicDefenceVisibility.isVisible(state(false, false)));
        assertTrue(MagicDefenceVisibility.isVisible(state(false, true)));
    }

    @Test
    public void nullTargetNeverDisplaysMagic()
    {
        assertFalse(MagicDefenceVisibility.isVisible(null));
    }

    @Test
    public void everySupportedTfaBossIsExcluded()
    {
        for (BossDefence boss : BossDefence.values())
        {
            if (boss.has(BossDefence.Flag.TFA))
            {
                assertFalse(boss.name(), MagicDefenceVisibility.isVisible(state(true, true)));
            }
        }
    }
}
