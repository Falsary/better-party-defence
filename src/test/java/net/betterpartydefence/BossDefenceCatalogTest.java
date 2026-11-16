package net.betterpartydefence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class BossDefenceCatalogTest
{
	@Test
	public void everyBossHasValidImmutableStatData()
	{
		for (BossDefence boss : BossDefence.values())
		{
			assertNotNull(boss.name(), boss.getNpcName());
			assertTrue(boss.name(), boss.getBaseDef() >= 0);
			assertTrue(boss.name(), boss.getBaseMagic() >= 0);
			assertTrue(boss.name(), boss.getBaseAtk() >= 0);
			assertTrue(boss.name(), boss.getBaseStr() >= 0);
			assertTrue(boss.name(), boss.getBaseMagicDef() >= 0);
			assertTrue(boss.name(), boss.getMinDef() >= 0);
			assertTrue(boss.name(), boss.getMinDef() <= boss.getBaseDef());
		}
	}

	@Test
	public void mutableFlagCollectionIsNotExposed()
	{
		boolean hasFlagGetter = Arrays.stream(BossDefence.class.getMethods())
			.anyMatch(method -> method.getName().equals("getFlags"));

		assertFalse(hasFlagGetter);
	}
}
