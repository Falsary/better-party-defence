package net.betterpartydefence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.betterpartydefence.DefenceTracker.DefenceState;
import org.junit.Test;

/** TFA Magic Defence presentation is disabled, without changing normal Defence tracking. */
public class MagicDefencePresentationTest {
	private static DefenceState state(boolean tfaBoss, boolean drained) {
		return new DefenceState(12, "Example", 100, 0, 100,
				25000, 25000, 100, 100, 290, 290, drained, tfaBoss);
	}

	@Test
	public void tfaMagicIsHiddenBeforeAndAfterSpecs() {
		assertFalse(DefenceReadout.magicDefenceVisible(state(true, false)));
		assertFalse(DefenceReadout.magicDefenceVisible(state(true, true)));
	}

	@Test
	public void nonTfaMagicIsUnchanged() {
		assertTrue(DefenceReadout.magicDefenceVisible(state(false, false)));
		assertTrue(DefenceReadout.magicDefenceVisible(state(false, true)));
	}

	@Test
	public void nullTargetNeverDisplaysMagic() {
		assertFalse(DefenceReadout.magicDefenceVisible(null));
	}

	@Test
	public void everySupportedTfaBossIsExcluded() {
		for (BossDefence boss : BossDefence.values()) {
			if (boss.has(BossDefence.Flag.TFA)) {
				assertFalse(boss.name(), DefenceReadout.magicDefenceVisible(state(true, true)));
			}
		}
	}
}
