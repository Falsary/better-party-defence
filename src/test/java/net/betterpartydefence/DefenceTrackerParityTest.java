package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Before;
import org.junit.Test;

/** Regression tests for the defence math retained from the original OSParty-derived tracker. */
public class DefenceTrackerParityTest
{
	private static final int WORLD = 1;

	private Client client;
	private BetterPartyDefenceConfig config;
	private WorldView worldView;
	private IndexedObjectSet<? extends NPC> npcs;
	private List<NPC> sceneNpcs;

	@SuppressWarnings("unchecked")
	@Before
	public void setUp()
	{
		client = mock(Client.class);
		config = mock(BetterPartyDefenceConfig.class);
		worldView = mock(WorldView.class);
		npcs = mock(IndexedObjectSet.class);
		sceneNpcs = new ArrayList<>();

		when(client.getWorld()).thenReturn(WORLD);
		when(client.getTopLevelWorldView()).thenReturn(worldView);
		org.mockito.Mockito.doReturn(npcs).when(worldView).npcs();
		when(npcs.iterator()).thenAnswer(invocation -> sceneNpcs.iterator());
		when(client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(0);
		when(client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE_SCALED)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.RAIDS_CHALLENGE_MODE)).thenReturn(0);
		when(config.defenceAlwaysShow()).thenReturn(false);
	}

	private NPC fakeNpc(String name, int index)
	{
		return fakeNpc(name, index, -1);
	}

	private NPC fakeNpc(String name, int index, int id)
	{
		NPC npc = mock(NPC.class);
		when(npc.getName()).thenReturn(name);
		when(npc.getIndex()).thenReturn(index);
		when(npc.getId()).thenReturn(id);
		when(npc.isDead()).thenReturn(false);
		when(npc.getHealthRatio()).thenReturn(1);
		when(npcs.byIndex(index)).thenReturn(npc);
		sceneNpcs.add(npc);
		return npc;
	}

	private void removeNpc(int index)
	{
		sceneNpcs.removeIf(npc -> npc.getIndex() == index);
		when(npcs.byIndex(index)).thenReturn(null);
	}

	private DefenceTracker makeTracker()
	{
		return new DefenceTracker(client, config);
	}

	private long field(Object object, String name) throws Exception
	{
		Field field = object.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return (long) field.get(object);
	}

	@Test
	public void bossNameMatchingHandlesTagsAndPhaseSuffixes()
	{
		assertEquals(BossDefence.CORE, BossDefence.matchingNpcName("Core"));
		assertEquals(BossDefence.OBELISK, BossDefence.matchingNpcName("<col=00ffff>Obelisk</col>"));
		assertEquals(BossDefence.VETION, BossDefence.matchingNpcName("Vet'ion Reborn"));
	}

	@Test
	public void supportedWeaponFilterIsDefenceOnly()
	{
		assertTrue(DefenceTracker.isSupportedWeapon(SpecialWeapon.DRAGON_WARHAMMER));
		assertTrue(DefenceTracker.isSupportedWeapon(SpecialWeapon.EYE_OF_AYAK));
		assertFalse(DefenceTracker.isSupportedWeapon(SpecialWeapon.BULWARK));
		assertFalse(DefenceTracker.isSupportedWeapon(null));
	}

	@Test
	public void dwhSequentialUsesCurrentDefence()
	{
		fakeNpc("Chaos Elemental", 3);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 3, 1, WORLD);
		tracker.onGameTick();
		assertEquals(189, tracker.state().getCurrent());
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 3, 1, WORLD);
		tracker.onGameTick();
		assertEquals(133, tracker.state().getCurrent());
	}

	@Test
	public void elderMaulUsesCurrentDefence()
	{
		fakeNpc("Chaos Elemental", 3);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.ELDER_MAUL, 3, 1, WORLD);
		tracker.onGameTick();
		assertEquals(176, tracker.state().getCurrent());
	}

	@Test
	public void elderMaulIsAppliedBeforeOtherSameTickDrains()
	{
		fakeNpc("Chaos Elemental", 3);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 3, 1, WORLD);
		tracker.queue(SpecialWeapon.ELDER_MAUL, 3, 1, WORLD);
		tracker.onGameTick();
		assertEquals(124, tracker.state().getCurrent());
	}

	@Test
	public void condemnAppliesOnce() throws Exception
	{
		fakeNpc("Corporeal Beast", 7);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 7, 1, WORLD);
		tracker.onGameTick();
		assertEquals(217, tracker.state().getCurrent());
		tracker.queue(SpecialWeapon.ACCURSED_SCEPTRE, 7, 1, WORLD);
		tracker.onGameTick();
		assertEquals(184, tracker.state().getCurrent());
		assertEquals(297, field(tracker, "magicLevel"));
		tracker.queue(SpecialWeapon.ACCURSED_SCEPTRE, 7, 1, WORLD);
		tracker.onGameTick();
		assertEquals(184, tracker.state().getCurrent());
	}

	@Test
	public void seercullDrainsMagicLevel() throws Exception
	{
		fakeNpc("Corporeal Beast", 7);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.SEERCULL, 7, 12, WORLD);
		tracker.onGameTick();
		assertEquals(338, field(tracker, "magicLevel"));
	}

	@Test
	public void eyeOfAyakDrainsMagicDefenceBonus() throws Exception
	{
		fakeNpc("Corporeal Beast", 7);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.EYE_OF_AYAK, 7, 30, WORLD);
		tracker.onGameTick();
		assertEquals(120, field(tracker, "magicDefBonus"));
		assertEquals(310, tracker.state().getCurrent());
	}

	@Test
	public void anchorDrainsDamageOverTen()
	{
		fakeNpc("K'ril Tsutsaroth", 4);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.BARRELCHEST_ANCHOR, 4, 200, WORLD);
		tracker.onGameTick();
		assertEquals(250, tracker.state().getCurrent());
	}

	@Test
	public void boneDaggerOnlyDrainsAtOrAboveBase()
	{
		fakeNpc("K'ril Tsutsaroth", 4);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.BONE_DAGGER, 4, 5, WORLD);
		tracker.onGameTick();
		assertEquals(265, tracker.state().getCurrent());
		tracker.queue(SpecialWeapon.BONE_DAGGER, 4, 5, WORLD);
		tracker.onGameTick();
		assertEquals(265, tracker.state().getCurrent());
	}

	@Test
	public void arclightDemonAndNonDemon()
	{
		fakeNpc("Abyssal Sire", 6);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.ARCLIGHT, 6, 1, WORLD);
		tracker.onGameTick();
		assertEquals(224, tracker.state().getCurrent());

		fakeNpc("Chaos Elemental", 3);
		DefenceTracker nonDemon = makeTracker();
		nonDemon.queue(SpecialWeapon.ARCLIGHT, 3, 1, WORLD);
		nonDemon.onGameTick();
		assertEquals(256, nonDemon.state().getCurrent());
	}

	@Test
	public void emberlightDemonAndNonDemon()
	{
		fakeNpc("Abyssal Sire", 6);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.EMBERLIGHT, 6, 1, WORLD);
		tracker.onGameTick();
		assertEquals(212, tracker.state().getCurrent());

		fakeNpc("Chaos Elemental", 3);
		DefenceTracker nonDemon = makeTracker();
		nonDemon.queue(SpecialWeapon.EMBERLIGHT, 3, 1, WORLD);
		nonDemon.onGameTick();
		assertEquals(256, nonDemon.state().getCurrent());
	}

	@Test
	public void ralosDrainsByMagicLevelPerConnectedGlaive() throws Exception
	{
		fakeNpc("Corporeal Beast", 7);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.TONALZTICS_OF_RALOS, 7, 2, WORLD);
		tracker.onGameTick();
		assertEquals(224, tracker.state().getCurrent());
		assertEquals(350, field(tracker, "magicLevel"));
	}

	@Test
	public void bandosCorpDrainIsDoubled()
	{
		fakeNpc("Corporeal Beast", 7);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.BANDOS_GODSWORD, 7, 100, WORLD);
		tracker.onGameTick();
		assertEquals(110, tracker.state().getCurrent());
	}

	@Test
	public void defenceFloorIsRespected()
	{
		fakeNpc("Nex", 9);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.BANDOS_GODSWORD, 9, 500, WORLD);
		tracker.onGameTick();
		assertEquals(250, tracker.state().getCurrent());
		assertEquals(250, tracker.state().getMin());
	}

	@Test
	public void foreignWorldDrainIsSkipped()
	{
		fakeNpc("Chaos Elemental", 3);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 3, 1, 9999);
		tracker.onGameTick();
		assertNull(tracker.state());
	}

	@Test
	public void heldDrainReplaysWhenNpcAppearsOnLaterTick()
	{
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 12, 1, WORLD);
		tracker.onGameTick();
		assertNull(tracker.state());

		fakeNpc("Chaos Elemental", 12);
		tracker.onGameTick();
		assertEquals(189, tracker.state().getCurrent());
	}

	@Test
	public void tektonDwhMissStillDrainsFivePercent()
	{
		fakeNpc("Tekton", 8, NpcID.RAIDS_TEKTON_FIGHTING_STANDARD);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 8, 0, WORLD);
		tracker.onGameTick();
		assertEquals(195, tracker.state().getCurrent());
	}

	@Test
	public void tektonBgsMissUsesRuneLiteTenDamageNormalization()
	{
		fakeNpc("Tekton", 8, NpcID.RAIDS_TEKTON_FIGHTING_STANDARD);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.BANDOS_GODSWORD, 8, 0, WORLD);
		tracker.onGameTick();
		assertEquals(195, tracker.state().getCurrent());
	}

	@Test
	public void stateNullBeforeAnyDrain()
	{
		fakeNpc("Chaos Elemental", 3);
		DefenceTracker tracker = makeTracker();
		assertNull(tracker.state());
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 3, 1, WORLD);
		tracker.onGameTick();
		assertEquals(189, tracker.state().getCurrent());
	}

	@Test
	public void resetClearsEncounterState()
	{
		fakeNpc("Chaos Elemental", 3);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 3, 1, WORLD);
		tracker.onGameTick();
		assertTrue(tracker.state() != null);
		tracker.reset("test");
		assertNull(tracker.state());
	}

	@Test
	public void kephriKeepsDrainAcrossShieldActorsAndResetsOnFinalPhase()
	{
		fakeNpc("Kephri", 20, NpcID.TOA_KEPHRI_BOSS_SHIELDED);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 20, 1, WORLD);
		tracker.onGameTick();
		assertEquals(60, tracker.state().getCurrent());

		removeNpc(20);
		fakeNpc("Kephri", 21, NpcID.TOA_KEPHRI_BOSS_WEAK);
		tracker.onGameTick();
		assertEquals(21, tracker.state().getNpcIndex());
		assertEquals(60, tracker.state().getCurrent());

		removeNpc(21);
		fakeNpc("Kephri", 22, NpcID.TOA_KEPHRI_BOSS_SHIELDED);
		tracker.onGameTick();
		assertEquals(22, tracker.state().getNpcIndex());
		assertEquals(60, tracker.state().getCurrent());

		removeNpc(22);
		fakeNpc("Kephri", 23, NpcID.TOA_KEPHRI_BOSS_ENRAGE);
		tracker.onGameTick();
		assertEquals(23, tracker.state().getNpcIndex());
		assertEquals(80, tracker.state().getCurrent());
	}

	@Test
	public void sotetsegTrackerSurvivesMazeButRestoresDefence()
	{
		when(client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_TYPE)).thenReturn(1);
		fakeNpc("Sotetseg", 30, NpcID.TOB_SOTETSEG_COMBAT);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 30, 1, WORLD);
		tracker.onGameTick();
		assertEquals(140, tracker.state().getCurrent());

		when(client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_TYPE)).thenReturn(2);
		removeNpc(30);
		fakeNpc("Sotetseg", 31, NpcID.TOB_SOTETSEG_NONCOMBAT);
		tracker.onGameTick();
		assertEquals(31, tracker.state().getNpcIndex());
		assertEquals(200, tracker.state().getCurrent());

		when(client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_TYPE)).thenReturn(1);
		removeNpc(31);
		fakeNpc("Sotetseg", 32, NpcID.TOB_SOTETSEG_COMBAT);
		tracker.onGameTick();
		assertEquals(32, tracker.state().getNpcIndex());
		assertEquals(200, tracker.state().getCurrent());

		when(client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_TYPE)).thenReturn(0);
		tracker.onGameTick();
		assertNull(tracker.state());
	}

	@Test
	public void coxPartyScaling()
	{
		when(client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE_SCALED)).thenReturn(5);
		fakeNpc("Skeletal Mystic", 5);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 5, 1, WORLD);
		tracker.onGameTick();
		assertEquals(194, tracker.state().getBase());
		assertEquals(136, tracker.state().getCurrent());
	}

	@Test
	public void coxChallengeModeTektonScaling()
	{
		when(client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE_SCALED)).thenReturn(5);
		when(client.getVarbitValue(VarbitID.RAIDS_CHALLENGE_MODE)).thenReturn(1);
		fakeNpc("Tekton", 8);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 8, 1, WORLD);
		tracker.onGameTick();
		assertEquals(287, tracker.state().getBase());
		assertEquals(201, tracker.state().getCurrent());
	}
}
