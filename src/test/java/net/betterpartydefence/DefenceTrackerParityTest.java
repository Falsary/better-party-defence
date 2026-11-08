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
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
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
		return fakeNpc(name, index, id, null);
	}

	private NPC fakeNpc(String name, int index, int id, WorldPoint location)
	{
		NPC npc = mock(NPC.class);
		when(npc.getName()).thenReturn(name);
		when(npc.getIndex()).thenReturn(index);
		when(npc.getId()).thenReturn(id);
		when(npc.getWorldLocation()).thenReturn(location);
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

	private void restoreNpc(NPC npc)
	{
		when(npcs.byIndex(npc.getIndex())).thenReturn(npc);
		if (!sceneNpcs.contains(npc))
		{
			sceneNpcs.add(npc);
		}
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
	public void infoBoxWeaponClassificationSeparatesDefenceAndMagic()
	{
		assertTrue(DefenceTracker.drainsDefence(SpecialWeapon.DRAGON_WARHAMMER));
		assertTrue(DefenceTracker.drainsDefence(SpecialWeapon.TONALZTICS_OF_RALOS));
		assertFalse(DefenceTracker.drainsMagicDefence(SpecialWeapon.DRAGON_WARHAMMER));
		assertFalse(DefenceTracker.drainsMagicDefence(SpecialWeapon.TONALZTICS_OF_RALOS));

		assertFalse(DefenceTracker.drainsDefence(SpecialWeapon.SEERCULL));
		assertFalse(DefenceTracker.drainsDefence(SpecialWeapon.EYE_OF_AYAK));
		assertTrue(DefenceTracker.drainsMagicDefence(SpecialWeapon.SEERCULL));
		assertTrue(DefenceTracker.drainsMagicDefence(SpecialWeapon.EYE_OF_AYAK));

		assertTrue(DefenceTracker.drainsDefence(SpecialWeapon.ACCURSED_SCEPTRE));
		assertTrue(DefenceTracker.drainsMagicDefence(SpecialWeapon.ACCURSED_SCEPTRE));
	}

	@Test
	public void tfaMagicIsHiddenWhileInternalMagicAndNormalDefenceRemainIntact()
	{
		fakeNpc("Archive Guardian", 71);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 71, 1, WORLD);
		tracker.onGameTick();

		DefenceTracker.DefenceState state = tracker.state();
		assertTrue(state.isTfaBoss());
		assertFalse(MagicDefenceVisibility.isVisible(state));
		assertEquals(182, state.getCurrent());
		// Preserve Magic for Ralos calculations and complete party sync.
		assertEquals(250, state.getMagicLevel());
		assertEquals(130, state.getMagicDef());
	}

	@Test
	public void dagonhaiMonkRetainsUncappedNormalDefenceAndHidesMagic()
	{
		fakeNpc("Dagon'hai Monk", 73, 16645);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 73, 1, WORLD);
		tracker.onGameTick();

		DefenceTracker.DefenceState state = tracker.state();
		assertTrue(state.isTfaBoss());
		assertFalse(MagicDefenceVisibility.isVisible(state));
		assertEquals(0, state.getMin());
		assertTrue(state.getCurrent() < state.getBase());
		assertEquals(290, state.getMagicLevel());
	}

	@Test
	public void magicOutsideTfaStillDisplays()
	{
		fakeNpc("Corporeal Beast", 75);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.EYE_OF_AYAK, 75, 30, WORLD);
		tracker.onGameTick();

		DefenceTracker.DefenceState state = tracker.state();
		assertFalse(state.isTfaBoss());
		assertTrue(MagicDefenceVisibility.isVisible(state));
		assertEquals(120, state.getMagicDef());
	}

	@Test
	public void magicOnlySpecDoesNotCreateDefenceHistory()
	{
		fakeNpc("Corporeal Beast", 7);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.EYE_OF_AYAK, 7, 30, WORLD);
		tracker.onGameTick();

		assertFalse(tracker.hasDefenceSpecHistory());
		assertTrue(tracker.hasMagicDefenceSpecHistory());
	}

	@Test
	public void accursedCreatesBothDefenceAndMagicHistory()
	{
		fakeNpc("Corporeal Beast", 7);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.ACCURSED_SCEPTRE, 7, 1, WORLD);
		tracker.onGameTick();

		assertTrue(tracker.hasDefenceSpecHistory());
		assertTrue(tracker.hasMagicDefenceSpecHistory());
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
		NPC npc = fakeNpc("Chaos Elemental", 12, 5001, new WorldPoint(3100, 3100, 0));
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 12, 1, WORLD, "Alice", npc);
		removeNpc(12);
		tracker.onGameTick();
		assertNull(tracker.state());

		restoreNpc(npc);
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
	public void kephriPhaseActorReplacementAtSameIndexKeepsEncounter()
	{
		fakeNpc("Kephri", 24, NpcID.TOA_KEPHRI_BOSS_SHIELDED);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 24, 1, WORLD);
		tracker.onGameTick();
		assertEquals(60, tracker.state().getCurrent());

		removeNpc(24);
		fakeNpc("Kephri", 24, NpcID.TOA_KEPHRI_BOSS_WEAK);
		tracker.onGameTick();

		assertEquals(24, tracker.state().getNpcIndex());
		assertEquals(60, tracker.state().getCurrent());
	}

	@Test
	public void immediateSameIndexRespawnDoesNotInheritState()
	{
		NPC original = fakeNpc("Chaos Elemental", 107, -1, new WorldPoint(3020, 3020, 0));
		DefenceTracker tracker = makeTracker();
		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, 107, 1, WORLD, "Alice", original);
		tracker.onGameTick();
		assertEquals(189, tracker.state().getCurrent());

		when(original.isDead()).thenReturn(true);
		removeNpc(107);
		fakeNpc("Chaos Elemental", 107, -1, new WorldPoint(3020, 3020, 0));
		tracker.onGameTick();

		assertNull(tracker.state());
		assertTrue(tracker.states().isEmpty());
	}

	@Test
	public void interactingWithAnotherShamanDoesNotRebindMissingTarget()
	{
		when(client.getTickCount()).thenReturn(100);
		NPC first = fakeNpc("Lizardman shaman", 110, -1, new WorldPoint(3000, 3000, 0));
		DefenceTracker tracker = makeTracker();
		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, 110, 1, WORLD, "Alice", first);
		tracker.onGameTick();
		assertEquals(98, tracker.state().getCurrent());

		removeNpc(110);
		NPC second = fakeNpc("Lizardman shaman", 111, -1, new WorldPoint(3020, 3020, 0));
		Player player = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getInteracting()).thenReturn(second);
		when(client.getTickCount()).thenReturn(101);
		tracker.onGameTick();

		assertEquals(110, tracker.state().getNpcIndex());
		assertEquals(98, tracker.state().getCurrent());
		assertTrue(tracker.states().stream().noneMatch(state -> state.getNpcIndex() == 111));
	}

	@Test
	public void newSpecOnAnotherShamanDoesNotRestoreMissingTarget()
	{
		when(client.getTickCount()).thenReturn(100);
		NPC first = fakeNpc("Lizardman shaman", 112, -1, new WorldPoint(3030, 3030, 0));
		DefenceTracker tracker = makeTracker();
		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, 112, 1, WORLD, "Alice", first);
		tracker.onGameTick();
		assertEquals(98, tracker.state().getCurrent());

		removeNpc(112);
		NPC second = fakeNpc("Lizardman shaman", 113, -1, new WorldPoint(3050, 3050, 0));
		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, 113, 1, WORLD, "Bob", second);
		when(client.getTickCount()).thenReturn(101);
		tracker.onGameTick();

		assertEquals(2, tracker.states().size());
		assertEquals(98, tracker.states().stream()
			.filter(state -> state.getNpcIndex() == 112).findFirst().orElseThrow(AssertionError::new).getCurrent());
		assertEquals(98, tracker.states().stream()
			.filter(state -> state.getNpcIndex() == 113).findFirst().orElseThrow(AssertionError::new).getCurrent());
	}

	@Test
	public void unidentifiedLegacyObservationDoesNotUseAnotherNpcIndex()
	{
		when(client.getTickCount()).thenReturn(100);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 120, 1, WORLD, "Alice");
		tracker.onGameTick();
		assertTrue(tracker.hasUnprocessedSpecs());

		fakeNpc("Lizardman shaman", 126, -1, new WorldPoint(3070, 3070, 0));
		when(client.getTickCount()).thenReturn(101);
		tracker.onGameTick();

		assertNull(tracker.state());
		assertTrue(tracker.hasUnprocessedSpecs());
	}

	@Test
	public void outOfRenderLegacyObservationRecoversWhenIndexedBossBecomesUnique()
	{
		when(client.getTickCount()).thenReturn(100);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 121, 1, WORLD, "Alice");
		tracker.onGameTick();
		assertNull(tracker.state());
		assertTrue(tracker.hasUnprocessedSpecs());

		fakeNpc("Lizardman shaman", 121, -1, new WorldPoint(3071, 3071, 0));
		when(client.getTickCount()).thenReturn(101);
		tracker.onGameTick();

		assertEquals(121, tracker.state().getNpcIndex());
		assertEquals(98, tracker.state().getCurrent());
		assertFalse(tracker.hasUnprocessedSpecs());
	}

	@Test
	public void outOfRenderLegacyObservationStaysAmbiguousWithTwoCandidates()
	{
		when(client.getTickCount()).thenReturn(100);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 124, 1, WORLD, "Alice");
		tracker.onGameTick();
		assertTrue(tracker.hasUnprocessedSpecs());

		fakeNpc("Lizardman shaman", 124, -1, new WorldPoint(3073, 3073, 0));
		fakeNpc("Lizardman shaman", 125, -1, new WorldPoint(3074, 3074, 0));
		when(client.getTickCount()).thenReturn(101);
		tracker.onGameTick();

		assertNull(tracker.state());
		assertTrue(tracker.hasUnprocessedSpecs());
	}

	@Test
	public void sotetsegTrackerSurvivesMazeButRestoresDefence()
	{
		when(client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_TYPE)).thenReturn(1);
		fakeNpc("Sotetseg", 30, NpcID.TOB_SOTETSEG_COMBAT);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 30, 1, WORLD, "Before maze");
		tracker.onGameTick();
		assertEquals(140, tracker.state().getCurrent());
		assertEquals(1, tracker.specHistoryForCurrentTarget(30).size());

		when(client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_TYPE)).thenReturn(2);
		removeNpc(30);
		fakeNpc("Sotetseg", 31, NpcID.TOB_SOTETSEG_NONCOMBAT);
		tracker.onGameTick();
		assertEquals(31, tracker.state().getNpcIndex());
		assertEquals(200, tracker.state().getCurrent());
		assertTrue(tracker.specHistoryForCurrentTarget(31).isEmpty());
		assertTrue(tracker.specHistoryForCurrentTarget(30).isEmpty());

		when(client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_TYPE)).thenReturn(1);
		removeNpc(31);
		fakeNpc("Sotetseg", 32, NpcID.TOB_SOTETSEG_COMBAT);
		tracker.onGameTick();
		assertEquals(32, tracker.state().getNpcIndex());
		assertEquals(200, tracker.state().getCurrent());
		assertTrue(tracker.specHistoryForCurrentTarget(32).isEmpty());

		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 32, 1, WORLD, "After maze");
		tracker.onGameTick();
		assertEquals(140, tracker.state().getCurrent());
		assertEquals(1, tracker.specHistoryForCurrentTarget(32).size());
		assertEquals("After maze", tracker.specHistoryForCurrentTarget(32).get(0).getPlayerName());

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
	@Test
	public void leavingRenderDistancePreservesDefenceAndHistory()
	{
		WorldPoint location = new WorldPoint(3110, 3110, 0);
		fakeNpc("Chaos Elemental", 40, 5002, location);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 40, 1, WORLD, "Heisty");
		tracker.onGameTick();
		assertEquals(189, tracker.state().getCurrent());
		assertEquals(1, tracker.specHistory().size());

		removeNpc(40);
		tracker.onGameTick();
		assertEquals(189, tracker.state().getCurrent());
		assertEquals(1, tracker.specHistory().size());

		fakeNpc("Chaos Elemental", 41, 5002, location);
		tracker.onGameTick();
		assertEquals(41, tracker.state().getNpcIndex());
		assertEquals(189, tracker.state().getCurrent());
		assertEquals("Heisty", tracker.specHistory().get(0).getPlayerName());
	}

	@Test
	public void resetClearsSpecHistory()
	{
		fakeNpc("Chaos Elemental", 42);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.BANDOS_GODSWORD, 42, 49, WORLD, "Other Party Member");
		tracker.onGameTick();
		assertEquals(1, tracker.specHistory().size());

		tracker.reset("test");
		assertTrue(tracker.specHistory().isEmpty());
	}

	@Test
	public void currentTargetHistoryNeverIncludesPreviousTargetHistory()
	{
		fakeNpc("Chaos Elemental", 43);
		fakeNpc("Corporeal Beast", 44);
		DefenceTracker tracker = makeTracker();

		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 43, 1, WORLD, "Previous target");
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 43, 1, WORLD, "Previous target");
		tracker.onGameTick();
		assertEquals(2, tracker.specHistoryForCurrentTarget(43).size());

		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 44, 1, WORLD, "Current target");
		tracker.onGameTick();
		assertEquals(44, tracker.state().getNpcIndex());
		assertEquals(1, tracker.specHistoryForCurrentTarget(44).size());
		assertEquals("Current target", tracker.specHistoryForCurrentTarget(44).get(0).getPlayerName());
		assertTrue(tracker.specHistoryForCurrentTarget(43).isEmpty());
	}

	@Test
	public void simultaneousOlmTargetsKeepIndependentMemory()
	{
		fakeNpc("Great Olm (Right claw)", 50);
		fakeNpc("Great Olm (Left claw)", 51);
		DefenceTracker tracker = makeTracker();

		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 50, 1, WORLD, "Melee");
		tracker.onGameTick();
		long rightAfterDwh = tracker.state().getCurrent();

		tracker.queue(SpecialWeapon.EYE_OF_AYAK, 51, 20, WORLD, "Mage");
		tracker.onGameTick();

		assertEquals(2, tracker.states().size());
		DefenceTracker.DefenceState right = tracker.states().stream()
			.filter(state -> state.getNpcIndex() == 50).findFirst().orElse(null);
		DefenceTracker.DefenceState left = tracker.states().stream()
			.filter(state -> state.getNpcIndex() == 51).findFirst().orElse(null);
		assertTrue(right != null && right.isDrained());
		assertTrue(left != null && left.isDrained());
		assertEquals(rightAfterDwh, right.getCurrent());
		assertEquals(2, tracker.syncTargets().size());
	}

	@Test public void snapshotMustNotDeleteQueuedSpecOnAnotherTarget()
	{
		NPC first = fakeNpc("Chaos Elemental", 81);
		fakeNpc("Corporeal Beast", 82);
		DefenceTracker source = makeTracker();
		source.queue(SpecialWeapon.DRAGON_WARHAMMER,81,1,WORLD,"Alice");
		source.onGameTick();
		DefenceTracker receiver = makeTracker();
		receiver.queue(SpecialWeapon.BANDOS_GODSWORD,82,50,WORLD,"Bob");
		receiver.applySyncState(source.syncState(),first);
		receiver.onGameTick();
		assertEquals(2,receiver.syncTargets().size());
		assertEquals("Bob",receiver.specHistory().get(0).getPlayerName());
	}

	@Test
	public void exactLocalObservationsKeepTwoSameTypeTargetsSeparate()
	{
		NPC first = fakeNpc("Chaos Elemental", 90);
		NPC second = fakeNpc("Chaos Elemental", 91);
		DefenceTracker tracker = makeTracker();

		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, 90, 1, WORLD, "Alice", first);
		tracker.onGameTick();
		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, 91, 1, WORLD, "Bob", second);
		tracker.onGameTick();

		assertEquals(2, tracker.states().size());
		assertEquals(189, tracker.states().stream()
			.filter(state -> state.getNpcIndex() == 90).findFirst().orElseThrow(AssertionError::new).getCurrent());
		assertEquals(189, tracker.states().stream()
			.filter(state -> state.getNpcIndex() == 91).findFirst().orElseThrow(AssertionError::new).getCurrent());
		assertFalse(tracker.hasUnprocessedSpecs());
	}

	@Test
	public void unresolvedTargetsRetainIndependentHeldQueues()
	{
		NPC first = fakeNpc("Lizardman shaman", 92, 5003, new WorldPoint(3120, 3120, 0));
		NPC second = fakeNpc("Corporeal Beast", 93, 5004, new WorldPoint(3130, 3130, 0));
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 92, 1, WORLD, "Alice", first);
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 93, 1, WORLD, "Bob", second);
		removeNpc(92);
		removeNpc(93);
		tracker.onGameTick();

		restoreNpc(first);
		tracker.onGameTick();
		assertEquals(92, tracker.state().getNpcIndex());
		assertTrue(tracker.hasUnprocessedSpecs());

		restoreNpc(second);
		tracker.onGameTick();
		assertEquals(2, tracker.states().size());
		assertFalse(tracker.hasUnprocessedSpecs());
	}

	@Test
	public void ambiguousSameTypeTargetsAreNeverGuessed()
	{
		fakeNpc("Chaos Elemental", 94);
		fakeNpc("Chaos Elemental", 95);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 94, 1, WORLD, "Alice");
		tracker.onGameTick();

		assertNull(tracker.state());
		assertTrue(tracker.hasUnprocessedSpecs());

		// Once two equally plausible actors existed, a later disappearance is not proof that
		// the remaining actor received the observation.
		removeNpc(95);
		tracker.onGameTick();
		assertNull(tracker.state());
		assertTrue(tracker.hasUnprocessedSpecs());
	}

	@Test
	public void respawnedNpcDoesNotInheritStateFromReusedIndex()
	{
		when(client.getTickCount()).thenReturn(100);
		WorldPoint spawn = new WorldPoint(3000, 3000, 0);
		NPC original = fakeNpc("Chaos Elemental", 96, -1, spawn);
		DefenceTracker tracker = makeTracker();
		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, 96, 1, WORLD, "Alice", original);
		tracker.onGameTick();
		assertEquals(189, tracker.state().getCurrent());

		when(original.isDead()).thenReturn(true);
		removeNpc(96);
		when(client.getTickCount()).thenReturn(101);
		tracker.onGameTick();
		assertNull(tracker.state());
		assertTrue(tracker.states().isEmpty());

		fakeNpc("Chaos Elemental", 96, -1, spawn);
		when(client.getTickCount()).thenReturn(102);
		tracker.onGameTick();
		assertNull(tracker.state());
		assertTrue(tracker.states().isEmpty());
	}

	@Test
	public void respawnedNpcWithDifferentIndexDoesNotInheritDrainedState()
	{
		when(client.getTickCount()).thenReturn(100);
		NPC original = fakeNpc("Chaos Elemental", 105, -1, new WorldPoint(3010, 3010, 0));
		DefenceTracker tracker = makeTracker();
		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, 105, 1, WORLD, "Alice", original);
		tracker.onGameTick();
		assertEquals(189, tracker.state().getCurrent());

		when(original.isDead()).thenReturn(true);
		removeNpc(105);
		fakeNpc("Chaos Elemental", 106, -1, new WorldPoint(3040, 3040, 0));
		when(client.getTickCount()).thenReturn(101);
		tracker.onGameTick();

		assertNull(tracker.state());
		assertTrue(tracker.states().isEmpty());
	}

	@Test
	public void heldExactObservationCannotAttachToReusedIndex()
	{
		when(client.getTickCount()).thenReturn(100);
		WorldPoint spawn = new WorldPoint(3004, 3004, 0);
		NPC original = fakeNpc("Chaos Elemental", 97, -1, spawn);
		DefenceTracker tracker = makeTracker();
		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, 97, 1, WORLD, "Alice", original);
		removeNpc(97);
		tracker.onGameTick();
		assertNull(tracker.state());
		assertTrue(tracker.hasUnprocessedSpecs());

		fakeNpc("Chaos Elemental", 97, -1, spawn);
		when(client.getTickCount()).thenReturn(101);
		tracker.onGameTick();
		assertNull(tracker.state());
		assertFalse(tracker.hasUnprocessedSpecs());
	}

	@Test
	public void encounterResetInvalidatesHeldObservations()
	{
		when(client.getTickCount()).thenReturn(100);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 98, 1, WORLD, "Alice");
		tracker.onGameTick();
		assertTrue(tracker.hasUnprocessedSpecs());

		tracker.reset("encounter ended");
		fakeNpc("Chaos Elemental", 98);
		when(client.getTickCount()).thenReturn(101);
		tracker.onGameTick();
		assertNull(tracker.state());
		assertFalse(tracker.hasUnprocessedSpecs());
	}

	@Test
	public void bossClearInvalidatesPendingObservationForSameTarget()
	{
		NPC target = fakeNpc("Chaos Elemental", 102);
		DefenceTracker tracker = makeTracker();

		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, 102, 1, WORLD, "Alice", target);
		tracker.clearBossState(BossDefence.CHAOS_ELEMENTAL, "encounter reset");
		tracker.onGameTick();

		assertNull(tracker.state());
		assertFalse(tracker.hasUnprocessedSpecs());
	}

	@Test
	public void bossClearInvalidatesPendingLegacyObservationForSameIndex()
	{
		fakeNpc("Chaos Elemental", 103);
		DefenceTracker tracker = makeTracker();

		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 103, 1, WORLD, "Alice");
		tracker.onGameTick();
		assertTrue(tracker.state() != null);

		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 103, 1, WORLD, "Bob");
		tracker.clearBossState(BossDefence.CHAOS_ELEMENTAL, "encounter reset");
		tracker.onGameTick();

		assertNull(tracker.state());
		assertFalse(tracker.hasUnprocessedSpecs());
	}

	@Test
	public void heldObservationExpiresAfterBoundedTtl()
	{
		when(client.getTickCount()).thenReturn(100);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 104, 1, WORLD, "Alice");
		tracker.onGameTick();
		assertTrue(tracker.hasUnprocessedSpecs());

		when(client.getTickCount()).thenReturn(301);
		tracker.onGameTick();

		assertNull(tracker.state());
		assertFalse(tracker.hasUnprocessedSpecs());
	}

	@Test
	public void outOfRenderPartyObservationRecoversWhenTheSingleTargetAppears()
	{
		when(client.getTickCount()).thenReturn(100);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 100, 1, WORLD, "Alice");
		tracker.onGameTick();

		for (int tick = 101; tick <= 110; tick++)
		{
			when(client.getTickCount()).thenReturn(tick);
			tracker.onGameTick();
		}
		assertTrue(tracker.hasUnprocessedSpecs());
		assertNull(tracker.state());

		fakeNpc("Chaos Elemental", 100);
		when(client.getTickCount()).thenReturn(111);
		tracker.onGameTick();
		assertEquals(100, tracker.state().getNpcIndex());
		assertEquals(189, tracker.state().getCurrent());
		assertFalse(tracker.hasUnprocessedSpecs());

		when(client.getTickCount()).thenReturn(301);
		tracker.onGameTick();
		assertFalse(tracker.hasUnprocessedSpecs());
	}

	@Test
	public void singleUniqueTargetStillAppliesToLegacyObservation()
	{
		fakeNpc("Chaos Elemental", 101);
		DefenceTracker tracker = makeTracker();
		tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, 101, 1, WORLD, "Alice");
		tracker.onGameTick();

		assertEquals(101, tracker.state().getNpcIndex());
		assertEquals(189, tracker.state().getCurrent());
	}

}
