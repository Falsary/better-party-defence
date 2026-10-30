package net.betterpartydefence;

import java.util.Arrays;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SpecHitResolverTest
{
	@Test public void delayedSpecIgnoresTrailingThrallAndVengeance()
	{
		assertEquals(38, SpecHitResolver.resolve(SpecialWeapon.EYE_OF_AYAK, Arrays.asList(38, 3, 12)));
		assertEquals(38, SpecHitResolver.resolve(SpecialWeapon.SEERCULL, Arrays.asList(38, 3, 12)));
		assertEquals(0, SpecHitResolver.resolve(SpecialWeapon.ACCURSED_SCEPTRE, Arrays.asList(0, 3)));
	}

	@Test public void instantMeleeIgnoresPrecedingThrallAndVengeance()
	{
		assertEquals(38, SpecHitResolver.resolve(SpecialWeapon.BANDOS_GODSWORD, Arrays.asList(3, 12, 38)));
		assertEquals(0, SpecHitResolver.resolve(SpecialWeapon.DRAGON_WARHAMMER, Arrays.asList(3, 0)));
	}
}
