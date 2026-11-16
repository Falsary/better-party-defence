package net.betterpartydefence;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DefenceMathTest
{
	@Test
	public void magicRollMatchesDocumentedFormula()
	{
		assertEquals((9L + 215L) * (100L + 64L), DefenceMath.magicRoll(215, 100));
		assertEquals((9L + 250L) * (0L + 64L), DefenceMath.magicRoll(250, 0));
	}

	@Test
	public void bandosDamageStopsWhenDefenceStopsAtZero()
	{
		DefenceMath.BandosResult result = DefenceMath.applyBandos(310, 0, 200, 180, 200, 350);

		assertEquals(0, result.getDefence());
		assertEquals(160, result.getStrength());
		assertEquals(180, result.getAttack());
		assertEquals(200, result.getMagic());
	}

	@Test
	public void bandosDamageSpillsThroughEveryDrainableStat()
	{
		DefenceMath.BandosResult result = DefenceMath.applyBandos(310, 0, 200, 180, 200, 900);

		assertEquals(0, result.getDefence());
		assertEquals(0, result.getStrength());
		assertEquals(0, result.getAttack());
		assertEquals(0, result.getMagic());
	}

	@Test
	public void bandosDamageDoesNotSpillAboveZeroDefence()
	{
		DefenceMath.BandosResult result = DefenceMath.applyBandos(310, 0, 200, 180, 200, 50);

		assertEquals(260, result.getDefence());
		assertEquals(200, result.getStrength());
		assertEquals(180, result.getAttack());
		assertEquals(200, result.getMagic());
	}
}
