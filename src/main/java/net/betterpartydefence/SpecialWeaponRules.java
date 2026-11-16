package net.betterpartydefence;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

// Shared policy for the special attacks that Better Party Defence accepts.
final class SpecialWeaponRules {
	private static final Set<SpecialWeapon> SUPPORTED = Collections.unmodifiableSet(EnumSet.of(
			SpecialWeapon.DRAGON_WARHAMMER,
			SpecialWeapon.ELDER_MAUL,
			SpecialWeapon.BANDOS_GODSWORD,
			SpecialWeapon.ARCLIGHT,
			SpecialWeapon.DARKLIGHT,
			SpecialWeapon.EMBERLIGHT,
			SpecialWeapon.BARRELCHEST_ANCHOR,
			SpecialWeapon.BONE_DAGGER,
			SpecialWeapon.DORGESHUUN_CROSSBOW,
			SpecialWeapon.ACCURSED_SCEPTRE,
			SpecialWeapon.TONALZTICS_OF_RALOS,
			SpecialWeapon.SEERCULL,
			SpecialWeapon.EYE_OF_AYAK));

	private static final Map<Integer, SpecialWeapon> WEAPONS_BY_ITEM = buildWeaponIndex();

	private SpecialWeaponRules() {
	}

	static boolean isSupported(SpecialWeapon weapon) {
		return weapon != null && SUPPORTED.contains(weapon);
	}

	static boolean drainsDefence(SpecialWeapon weapon) {
		if (!isSupported(weapon)) {
			return false;
		}
		return weapon != SpecialWeapon.SEERCULL && weapon != SpecialWeapon.EYE_OF_AYAK;
	}

	static boolean drainsMagicDefence(SpecialWeapon weapon) {
		return weapon == SpecialWeapon.ACCURSED_SCEPTRE
				|| weapon == SpecialWeapon.SEERCULL
				|| weapon == SpecialWeapon.EYE_OF_AYAK;
	}

	static SpecialWeapon byItemId(int itemId) {
		return WEAPONS_BY_ITEM.get(itemId);
	}

	static int correlationHit(SpecialWeapon weapon, int hit) {
		return weapon == SpecialWeapon.TONALZTICS_OF_RALOS ? 0 : hit;
	}

	static int reportedValue(SpecialWeapon weapon, int hit) {
		return weapon != null && weapon.isDamage() ? Math.max(0, hit) : 1;
	}

	static String shortName(SpecialWeapon weapon) {
		if (weapon == null) {
			return "Spec";
		}
		switch (weapon) {
			case DRAGON_WARHAMMER:
				return "DWH";
			case BANDOS_GODSWORD:
				return "BGS";
			case ELDER_MAUL:
				return "Elder maul";
			case TONALZTICS_OF_RALOS:
				return "Ralos";
			case BARRELCHEST_ANCHOR:
				return "Anchor";
			case BONE_DAGGER:
				return "Bone dagger";
			case DORGESHUUN_CROSSBOW:
				return "Dorgeshuun cbow";
			case ACCURSED_SCEPTRE:
				return "Accursed";
			case EYE_OF_AYAK:
				return "Eye of ayak";
			case SEERCULL:
				return "Seercull";
			default:
				return weapon.name();
		}
	}

	private static Map<Integer, SpecialWeapon> buildWeaponIndex() {
		Map<Integer, SpecialWeapon> weapons = new HashMap<>();
		for (SpecialWeapon weapon : SpecialWeapon.values()) {
			if (!isSupported(weapon)) {
				continue;
			}
			for (int itemId : weapon.getItemID()) {
				weapons.putIfAbsent(itemId, weapon);
			}
		}
		return Collections.unmodifiableMap(weapons);
	}
}
