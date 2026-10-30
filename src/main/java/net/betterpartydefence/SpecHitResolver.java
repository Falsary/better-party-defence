package net.betterpartydefence;

import java.util.List;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

/** Mirrors RuneLite SpecialCounterPlugin's delayed/instant hitsplat selection. */
final class SpecHitResolver
{
	private SpecHitResolver() {}

	static int resolve(SpecialWeapon weapon, List<Integer> hitsplats)
	{
		if (weapon == null || hitsplats == null || hitsplats.isEmpty())
		{
			throw new IllegalArgumentException("A weapon and observed hitsplat are required");
		}
		return hitsplats.get(weapon.getHitDelay(1) > 1 ? 0 : hitsplats.size() - 1);
	}
}
