package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

public class SpecialWeaponRulesTest {
	@Test
	public void supportedWeaponsUseOneClassification() {
		assertTrue(SpecialWeaponRules.isSupported(SpecialWeapon.DRAGON_WARHAMMER));
		assertTrue(SpecialWeaponRules.drainsDefence(SpecialWeapon.DRAGON_WARHAMMER));
		assertFalse(SpecialWeaponRules.drainsMagicDefence(SpecialWeapon.DRAGON_WARHAMMER));

		assertTrue(SpecialWeaponRules.isSupported(SpecialWeapon.SEERCULL));
		assertFalse(SpecialWeaponRules.drainsDefence(SpecialWeapon.SEERCULL));
		assertTrue(SpecialWeaponRules.drainsMagicDefence(SpecialWeapon.SEERCULL));

		assertFalse(SpecialWeaponRules.isSupported(SpecialWeapon.BULWARK));
		assertFalse(SpecialWeaponRules.drainsDefence(null));
	}

	@Test
	public void equipmentIdIndexContainsEverySupportedVariantOnly() {
		for (SpecialWeapon weapon : SpecialWeapon.values()) {
			for (int itemId : weapon.getItemID()) {
				SpecialWeapon match = SpecialWeaponRules.byItemId(itemId);
				if (SpecialWeaponRules.isSupported(weapon)) {
					assertSame(weapon, match);
				} else {
					assertNull(match);
				}
			}
		}
		assertSame(SpecialWeapon.DRAGON_WARHAMMER,
				SpecialWeaponRules.byItemId(ItemID.BH_DRAGON_WARHAMMER_CORRUPTED));
	}

	@Test
	public void reportedValuesPreserveDamageAndCountWeapons() {
		assertEquals(25, SpecialWeaponRules.reportedValue(SpecialWeapon.BANDOS_GODSWORD, 25));
		assertEquals(2, SpecialWeaponRules.reportedValue(SpecialWeapon.TONALZTICS_OF_RALOS, 2));
		assertEquals(1, SpecialWeaponRules.reportedValue(SpecialWeapon.DRAGON_WARHAMMER, 42));
		assertEquals(0, SpecialWeaponRules.reportedValue(SpecialWeapon.BANDOS_GODSWORD, -1));
	}
}
