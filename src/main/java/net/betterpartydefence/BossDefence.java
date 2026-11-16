package net.betterpartydefence;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import net.betterpartydefence.boss.BossNpcMatcher;
import net.runelite.api.NPC;

// Static boss stats and lifecycle metadata.
// Columns: display name, Defence, Magic, Attack, Strength, magic Defence bonus, floor, flags.
// Levels and floors follow osrs-dps-calc.
@Getter
public enum BossDefence {
	TFA_ARCHIVE_GUARDIAN("Archive Guardian", 260, 250, 250, 240, 130, 156, Flag.TFA),
	TFA_EZEKIEL("Ezekiel", 130, 200, 250, 150, 200, 78, Flag.TFA),
	TFA_BORK("Bork", 300, 90, 200, 200, 300, 180, Flag.TFA),
	TFA_SMORKS("The Smorks", 220, 185, 1, 1, 400, 0, Flag.TFA),
	TFA_MOIA("Moia", 195, 350, 1, 1, 30, 117, Flag.TFA),
	TFA_DREADNOTH("Dreadnoth the Jellied", 125, 220, 280, 285, 20, 0, Flag.TFA, Flag.DEMON),
	TFA_XARTH("Xarth", 170, 350, 250, 225, 205, 102, Flag.TFA, Flag.DEMON),
	TFA_DAGONHAI_ELDER("Dagon'hai Elder", 100, 290, 1, 1, 100, 0, Flag.TFA),
	// Same display name; separate NPC IDs and defence pools.
	TFA_DAGONHAI_MONK_16645("Dagon'hai Monk (16645)", 100, 290, 1, 1, 100, 0, Flag.TFA),
	TFA_DAGONHAI_MONK_16646("Dagon'hai Monk (16646)", 100, 290, 1, 1, 100, 0, Flag.TFA),


	// The Blade during portals is the attackable P2 actor; the prop never owns defence state.
	TFA_OCELLUS_P1("Ocellus (Phase 1)", 215, 350, 1, 1, 100, 129, Flag.TFA, Flag.DEMON),
	TFA_OCELLUS_P2("The Blade", 250, 220, 1, 1, 100, 0, Flag.TFA),
	TFA_OCELLUS_P3("Ocellus (Phase 3)", 250, 300, 325, 250, 200, 150, Flag.TFA, Flag.DEMON),

	// Chambers of Xeric
	TEKTON("Tekton", 205, 205, 390, 390, 0, 0, Flag.COX_SCALED, Flag.COX_MAGIC_SCALED, Flag.TEKTON_CM),
	TEKTON_ENRAGED("Tekton (enraged)", 205, 205, 390, 390, 0, 0, Flag.COX_SCALED, Flag.COX_MAGIC_SCALED, Flag.TEKTON_CM),
	ICE_DEMON("Ice Demon", 160, 390, 1, 1, 40, 0, Flag.COX_SCALED, Flag.MAGIC_USES_DEFENCE),
	LIZARDMAN_SHAMAN("Lizardman shaman", 140, 130, 120, 120, 50, 0, Flag.COX_SCALED),
	ABYSSAL_PORTAL("Abyssal portal", 176, 176, 1, 1, 60, 0, Flag.COX_SCALED, Flag.COX_MAGIC_SCALED),
	DEATHLY_MAGE("Deathly mage", 155, 210, 1, 1, 0, 0, Flag.COX_SCALED),
	DEATHLY_RANGER("Deathly ranger", 155, 155, 1, 1, 0, 0, Flag.COX_SCALED, Flag.COX_MAGIC_SCALED),
	VASA("Vasa Nistirio", 175, 230, 1, 1, 400, 0, Flag.COX_SCALED),
	SKELETAL_MYSTIC("Skeletal Mystic", 187, 140, 140, 140, 140, 0, Flag.COX_SCALED),
	GREAT_OLM("Great Olm", 150, 250, 250, 250, 200, 0, Flag.COX_SCALED),
	GREAT_OLM_LEFT_CLAW("Great Olm (Left claw)", 175, 175, 250, 250, 200, 0, Flag.COX_SCALED, Flag.COX_MAGIC_SCALED),
	GREAT_OLM_RIGHT_CLAW("Great Olm (Right claw)", 175, 87, 250, 250, 50, 0, Flag.COX_SCALED, Flag.COX_MAGIC_SCALED),
	
	// Tombs of Amascut
	BA_BA("Ba-Ba", 80, 100, 150, 160, 280, 60),
	KEPHRI("Kephri", 80, 125, 0, 0, 200, 60),
	AKKHA("Akkha", 80, 100, 100, 140, 10, 70),
	AKKHAS_SHADOW("Akkha's Shadow", 30, 100, 100, 140, 10, 0),
	ZEBAK("Zebak", 70, 100, 250, 140, 200, 50),
	OBELISK("<col=00ffff>Obelisk</col>", 100, 100, 200, 150, 50, 60),
	ELIDINIS_WARDEN("Elidinis' Warden", 150, 150, 150, 150, 20, 120),
	TUMEKENS_WARDEN("Tumeken's Warden", 150, 150, 150, 150, 20, 120),

	// Theatre of Blood
	THE_MAIDEN_OF_SUGADINTI("The Maiden of Sugadinti", 200, 350, 350, 350, 0, 0),
	PESTILENT_BLOAT("Pestilent Bloat", 100, 150, 250, 340, 600, 0),
	NYLOCAS_VASILIAS("Nylocas Vasilias", 50, 50, 400, 350, 0, 0),
	SOTETSEG("Sotetseg", 200, 250, 250, 250, 30, 100),
	XARPUS("Xarpus", 250, 220, 1, 1, 0, 0),

	// Slayer Bosses
	ABYSSAL_SIRE("Abyssal Sire", 250, 200, 180, 136, 20, 0, Flag.DEMON),
	ALCHEMICAL_HYDRA("Alchemical Hydra", 100, 260, 100, 100, 150, 0),
	ARAXXOR("Araxxor", 135, 190, 320, 320, 237, 90),
	CERBERUS("Cerberus", 100, 220, 220, 220, 65, 0),

	// Wilderness
	ARTIO("Artio", 150, 90, 250, 270, 0, 0),
	CALLISTO("Callisto", 225, 140, 350, 300, 0, 0),
	CALVARION("Calvar'ion", 225, 178, 250, 250, 198, 0),
	VETION("Vet'ion", 395, 300, 430, 430, 250, 0),
	SPINDEL("Spindel", 225, 235, 200, 130, 205, 0),
	VENENATIS("Venenatis", 321, 300, 300, 200, 300, 0),
	SCORPIA("Scorpia", 180, 1, 250, 150, 44, 0),
	KING_BLACK_DRAGON("King Black Dragon", 240, 240, 240, 240, 80, 0),
	CHAOS_ELEMENTAL("Chaos Elemental", 270, 270, 270, 270, 70, 0),

	// Godwars
	GENERAL_GRAARDOR("General Graardor", 250, 80, 280, 350, 298, 0),
	COMMANDER_ZILYANA("Commander Zilyana", 300, 300, 280, 196, 100, 0),
	KREE_ARRA("Kree'arra", 260, 200, 300, 200, 200, 0),
	KRIL_TSUTSAROTH("K'ril Tsutsaroth", 270, 200, 340, 300, 80, 0, Flag.DEMON),
	NEX("Nex", 260, 230, 315, 200, 300, 250),

	// Corp
	CORPOREAL_BEAST("Corporeal Beast", 310, 350, 320, 320, 150, 0),

	// Non-raid bosses
	GIANT_MOLE("Giant Mole", 200, 200, 200, 200, 80, 0),
	KALPHITE_QUEEN("Kalphite Queen", 300, 150, 300, 300, 100, 0),
	DAGANNOTH_PRIME("Dagannoth Prime", 255, 255, 255, 255, 255, 0),
	DAGANNOTH_REX("Dagannoth Rex", 255, 0, 255, 255, 10, 0),
	DAGANNOTH_SUPREME("Dagannoth Supreme", 128, 255, 255, 255, 255, 0),
	PHANTOM_MUSPAH("Phantom Muspah", 200, 150, 280, 280, 437, 0),
	THE_NIGHTMARE("The Nightmare", 150, 150, 150, 150, 600, 120),
	PHOSANIS_NIGHTMARE("Phosani's Nightmare", 150, 150, 150, 150, 600, 120),
	SARACHNIS("Sarachnis", 150, 150, 200, 240, 150, 0),
	SKOTIZO("Skotizo", 200, 280, 240, 250, 80, 0),
	THE_HUEYCOATL("The Hueycoatl", 125, 50, 150, 50, 200, 120),
	VORKATH("Vorkath", 214, 150, 560, 308, 240, 0),
	YAMA("Yama", 225, 250, 320, 350, 0, 145, Flag.DEMON),
	// For zulrah, magic defence changes by form.
	ZULRAH("Zulrah", 300, 300, 1, 1, 0, 0),
	// Fight Caves
	TZKAL_ZUK("TzKal-Zuk", 260, 150, 350, 600, 350, 0),
	TZTOK_JAD("TzTok-Jad", 480, 480, 640, 960, 0, 0),

	// No defence level; only magic defence can drain.
	VARDORVIS("Vardorvis", 0, 215, 280, 0, 580, 0);

	// Behavioural flags.
	public enum Flag {
		// Magic defence uses defence level.
		MAGIC_USES_DEFENCE,
		// CoX scales magic using the defensive party-size factor.
		COX_MAGIC_SCALED,
		// Applies CoX party-size and challenge-mode scaling.
		COX_SCALED,
		// Tekton uses the smaller challenge-mode defence bump.
		TEKTON_CM,

		// The Fractured Archive boss.
		TFA,
		// Demon; arclight family drains harder.
		DEMON
	}

	private final String npcName;
	private final int baseDef;
	private final int baseMagic;
	private final int baseAtk;
	private final int baseStr;
	private final int baseMagicDef;
	private final int minDef;
	@Getter(AccessLevel.NONE)
	private final Set<Flag> flags;

	BossDefence(String npcName, int baseDef, int baseMagic, int baseAtk, int baseStr, int baseMagicDef, int minDef,
			Flag... flags) {
		if (baseDef < 0 || baseMagic < 0 || baseAtk < 0 || baseStr < 0 || baseMagicDef < 0
				|| minDef < 0 || minDef > baseDef) {
			throw new IllegalArgumentException("Invalid defence values for " + name());
		}
		this.npcName = Objects.requireNonNull(npcName, "npcName");
		this.baseDef = baseDef;
		this.baseMagic = baseMagic;
		this.baseAtk = baseAtk;
		this.baseStr = baseStr;
		this.baseMagicDef = baseMagicDef;
		this.minDef = minDef;
		this.flags = EnumSet.noneOf(Flag.class);
		if (flags != null) {
			this.flags.addAll(Arrays.asList(flags));
		}
	}

	public boolean has(Flag flag) {
		return flags.contains(flag);
	}

	// Tekton's enraged form uses the same tracked encounter. This makes sure that TEKTON_ENRAGED is treated as TEKTON within the encounter.
	public BossDefence trackingType() {
		return this == TEKTON_ENRAGED ? TEKTON : this;
	}

	public static BossDefence matchingNpc(NPC npc) {
		return BossNpcMatcher.matchingNpc(npc);
	}

	static boolean isAttackableOcellusPhaseTwo(NPC npc) {
		return BossNpcMatcher.isAttackableOcellusPhaseTwo(npc);
	}

	static boolean isLiveNpc(NPC npc) {
		return BossNpcMatcher.isLiveNpc(npc);
	}

	public static BossDefence matchingNpcName(String name) {
		return BossNpcMatcher.matchingNpcName(name);
	}

}
