package net.betterpartydefence;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/** Verifies RuneLite-compatible hitsplat selection for supported special attacks. */
public class HitResolutionTest {
	@Test
	public void delayedSpecIgnoresTrailingThrallAndVengeance() {
		assertEquals(38, LocalDefenceSpecDetector.resolveObservedHit(
				SpecialWeapon.EYE_OF_AYAK, Arrays.asList(38, 3, 12)));
		assertEquals(38, LocalDefenceSpecDetector.resolveObservedHit(
				SpecialWeapon.SEERCULL, Arrays.asList(38, 3, 12)));
		assertEquals(0, LocalDefenceSpecDetector.resolveObservedHit(
				SpecialWeapon.ACCURSED_SCEPTRE, Arrays.asList(0, 3)));
	}

	@Test
	public void instantMeleeIgnoresPrecedingThrallAndVengeance() {
		assertEquals(38, LocalDefenceSpecDetector.resolveObservedHit(
				SpecialWeapon.BANDOS_GODSWORD, Arrays.asList(3, 12, 38)));
		assertEquals(0, LocalDefenceSpecDetector.resolveObservedHit(
				SpecialWeapon.DRAGON_WARHAMMER, Arrays.asList(3, 0)));
	}

	@Test
	public void realRalosHitsAreTakenFromTheFirstTwoSlots() {
		assertEquals(2, LocalDefenceSpecDetector.countConfirmedRalosHits(Arrays.asList(1, 2, 3)));
		assertEquals(1, LocalDefenceSpecDetector.countConfirmedRalosHits(Arrays.asList(0, 2, 3)));
		assertEquals(1, LocalDefenceSpecDetector.countConfirmedRalosHits(Arrays.asList(2, 0, 3)));
		assertEquals(0, LocalDefenceSpecDetector.countConfirmedRalosHits(Arrays.asList(0, 0, 3)));
		assertEquals(0, LocalDefenceSpecDetector.countConfirmedRalosHits(Arrays.asList(0, 0, 2)));
		assertEquals(2, LocalDefenceSpecDetector.countConfirmedRalosHits(Arrays.asList(2, 2, 2)));
		assertEquals(1, LocalDefenceSpecDetector.countConfirmedRalosHits(
				Arrays.asList(0, 1, 3, 44, 88)));
		assertEquals(2, LocalDefenceSpecDetector.countConfirmedRalosHits(
				Arrays.asList(1, 1, 2, 0, 10)));
	}

	@Test
	public void incompleteRalosPairStaysUnresolved() {
		assertEquals(-1, LocalDefenceSpecDetector.countConfirmedRalosHits(null));
		assertEquals(-1, LocalDefenceSpecDetector.countConfirmedRalosHits(Collections.emptyList()));
		assertEquals(-1, LocalDefenceSpecDetector.countConfirmedRalosHits(Collections.singletonList(3)));
		assertEquals(-1, LocalDefenceSpecDetector.countConfirmedRalosHits(Arrays.asList(-1, 3)));
	}

	@Test
	public void exhaustiveRalosRollsIgnoreSameTickThrall() {
		for (int first = 0; first <= 75; first++) {
			for (int second = 0; second <= 75; second++) {
				for (int thrall = 0; thrall <= 3; thrall++) {
					List<Integer> hits = Arrays.asList(first, second, thrall);
					int expected = (first > 0 ? 1 : 0) + (second > 0 ? 1 : 0);
					assertEquals("Ralos=" + first + "," + second + "; thrall=" + thrall,
							expected, LocalDefenceSpecDetector.countConfirmedRalosHits(hits));
				}
			}
		}
	}
}
