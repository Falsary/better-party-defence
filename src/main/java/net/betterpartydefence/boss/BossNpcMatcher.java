package net.betterpartydefence.boss;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.betterpartydefence.BossDefence;
import net.runelite.api.NPC;
import net.runelite.client.util.Text;

/**
 * Maps RuneLite NPC actors to their stable boss-stat definition.
 */
public final class BossNpcMatcher {
	private static final int DAGONHAI_MONK_FIRST_ACTOR = 16645;
	private static final int DAGONHAI_MONK_SECOND_ACTOR = 16646;

	private static final Map<BossDefence, String> NORMALIZED_NAMES = buildNormalizedNames();

	private BossNpcMatcher() {
	}

	public static BossDefence matchingNpc(NPC npc) {
		if (npc == null) {
			return null;
		}
		if (OcellusActors.isAttackablePhaseTwo(npc)) {
			return BossDefence.TFA_OCELLUS_P2;
		}
		if (npc.getName() == null) {
			return null;
		}

		String name = normalize(npc.getName());
		if ("Dagon'hai Monk".equalsIgnoreCase(name)) {
			if (npc.getId() == DAGONHAI_MONK_FIRST_ACTOR) {
				return BossDefence.TFA_DAGONHAI_MONK_16645;
			}
			if (npc.getId() == DAGONHAI_MONK_SECOND_ACTOR) {
				return BossDefence.TFA_DAGONHAI_MONK_16646;
			}
			return null;
		}
		if (OcellusActors.isBladeName(name)) {
			return null;
		}
		if (OcellusActors.isOcellusName(name)) {
			return OcellusActors.matchingOcellus(npc);
		}
		return matchingNpcName(name);
	}

	public static BossDefence matchingNpcName(String name) {
		if (name == null || name.trim().isEmpty()) {
			return null;
		}

		String normalized = normalize(name);
		if (OcellusActors.isBladeName(normalized)) {
			return null;
		}

		BossDefence prefixMatch = null;
		for (Map.Entry<BossDefence, String> entry : NORMALIZED_NAMES.entrySet()) {
			BossDefence boss = entry.getKey();
			if (boss == BossDefence.TFA_DREADNOTH && isDreadnothJellied(normalized)) {
				return boss;
			}

			String candidate = entry.getValue();
			if (candidate.equalsIgnoreCase(normalized)) {
				return boss.trackingType();
			}
			if (prefixMatch == null
				&& normalized.regionMatches(true, 0, candidate, 0, candidate.length())
				&& normalized.length() > candidate.length()
				&& normalized.charAt(candidate.length()) == ' ') {
				prefixMatch = boss;
			}
		}
		return prefixMatch == null ? null : prefixMatch.trackingType();
	}

	public static boolean isAttackableOcellusPhaseTwo(NPC npc) {
		return OcellusActors.isAttackablePhaseTwo(npc);
	}

	public static boolean isLiveNpc(NPC npc) {
		return npc != null && !npc.isDead()
			&& (npc.getHealthRatio() != 0 || isAttackableOcellusPhaseTwo(npc));
	}

	private static boolean isDreadnothJellied(String name) {
		String normalized = name.toLowerCase(Locale.ROOT);
		return normalized.contains("dreadnoth") && normalized.contains("jellied");
	}

	private static String normalize(String name) {
		return Text.removeTags(name).trim();
	}

	private static Map<BossDefence, String> buildNormalizedNames() {
		Map<BossDefence, String> names = new EnumMap<>(BossDefence.class);
		for (BossDefence boss : BossDefence.values()) {
			names.put(boss, normalize(boss.getNpcName()));
		}
		return names;
	}
}
