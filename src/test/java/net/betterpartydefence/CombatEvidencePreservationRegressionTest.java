package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.betterpartydefence.DefenceTracker.DefenceState;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.betterpartydefence.DefenceTracker.SyncState;
import net.betterpartydefence.DefenceTracker.SyncTarget;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.specialcounter.SpecialCounterUpdate;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/**
 * Production-path regressions for combat evidence retention and display selection.
 *
 * <p>These tests deliberately use the real plugin reception method and DefenceTracker rather than
 * a separate test reducer. They distinguish evidence that was never received, rejected, deferred,
 * applied, or applied but not selected for the active display.
 */
public class CombatEvidencePreservationRegressionTest
{
	private static final int WORLD = 301;
	private static final int CHAOS_INDEX = 41;
	private static final int CORP_INDEX = 42;
	private static final int ARCHIVE_GUARDIAN_INDEX = 44;
	private static final long ALICE_MEMBER_ID = 101L;
	private static final long BOB_MEMBER_ID = 102L;
	private static final long CHARLIE_MEMBER_ID = 103L;
	private static final long DAVE_MEMBER_ID = 104L;
	private static final long EVE_MEMBER_ID = 105L;
	private static final int ALICE_PLAYER_ID = 1001;
	private static final int BOB_PLAYER_ID = 1002;
	private static final int CHARLIE_PLAYER_ID = 1003;
	private static final int DAVE_PLAYER_ID = 1004;
	private static final int EVE_PLAYER_ID = 1005;

	@Test
	public void twoPlayersSameTickIdenticalDwhAttacksRemainDistinct() throws Exception
	{
		Harness h = new Harness();
		h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		h.addPartyMember(BOB_MEMBER_ID, "Bob", BOB_PLAYER_ID, false);
		h.rememberSenderScope(ALICE_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);
		h.rememberSenderScope(BOB_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);

		h.tick = 100;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.sendSpecial(BOB_MEMBER_ID, BOB_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		List<SpecHistoryEntry> history = h.historyFor(CHAOS_INDEX);
		assertEquals("two independent players must both be represented", 2, history.size());
		assertEquals("Alice", history.get(0).getPlayerName());
		assertEquals("Bob", history.get(1).getPlayerName());
		assertEquals(133L, h.stateFor(CHAOS_INDEX).getCurrent());
	}

	@Test
	public void threePlayersSameTickIdenticalDwhAttacksRemainDistinct() throws Exception
	{
		Harness h = new Harness();
		h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		h.addPartyMember(BOB_MEMBER_ID, "Bob", BOB_PLAYER_ID, false);
		h.addPartyMember(CHARLIE_MEMBER_ID, "Charlie", CHARLIE_PLAYER_ID, false);
		h.rememberSenderScope(ALICE_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);
		h.rememberSenderScope(BOB_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);
		h.rememberSenderScope(CHARLIE_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);

		h.tick = 100;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.sendSpecial(BOB_MEMBER_ID, BOB_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.sendSpecial(CHARLIE_MEMBER_ID, CHARLIE_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		assertEquals(3, h.historyFor(CHAOS_INDEX).size());
		assertEquals(94L, h.stateFor(CHAOS_INDEX).getCurrent());
	}

	@Test
	public void fivePlayersSameTickIdenticalDwhAttacksRemainDistinct() throws Exception
	{
		Harness h = new Harness();
		h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		h.addPartyMember(BOB_MEMBER_ID, "Bob", BOB_PLAYER_ID, false);
		h.addPartyMember(CHARLIE_MEMBER_ID, "Charlie", CHARLIE_PLAYER_ID, false);
		h.addPartyMember(DAVE_MEMBER_ID, "Dave", DAVE_PLAYER_ID, false);
		h.addPartyMember(EVE_MEMBER_ID, "Eve", EVE_PLAYER_ID, false);
		h.rememberSenderScope(ALICE_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);
		h.rememberSenderScope(BOB_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);
		h.rememberSenderScope(CHARLIE_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);
		h.rememberSenderScope(DAVE_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);
		h.rememberSenderScope(EVE_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);

		h.tick = 100;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.sendSpecial(BOB_MEMBER_ID, BOB_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.sendSpecial(CHARLIE_MEMBER_ID, CHARLIE_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.sendSpecial(DAVE_MEMBER_ID, DAVE_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.sendSpecial(EVE_MEMBER_ID, EVE_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		List<SpecHistoryEntry> history = h.historyFor(CHAOS_INDEX);
		assertEquals(5, history.size());
		assertEquals("Alice", history.get(0).getPlayerName());
		assertEquals("Bob", history.get(1).getPlayerName());
		assertEquals("Charlie", history.get(2).getPlayerName());
		assertEquals("Dave", history.get(3).getPlayerName());
		assertEquals("Eve", history.get(4).getPlayerName());
	}

	@Test
	public void differentArrivalOrdersRetainTheSameDwhEvidence() throws Exception
	{
		Harness first = new Harness();
		first.addNpc(CHAOS_INDEX, "Chaos Elemental");
		first.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		first.addPartyMember(BOB_MEMBER_ID, "Bob", BOB_PLAYER_ID, false);
		first.rememberSenderScope(ALICE_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);
		first.rememberSenderScope(BOB_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);
		first.tick = 100;
		first.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		first.sendSpecial(BOB_MEMBER_ID, BOB_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		first.tracker.onGameTick();

		Harness second = new Harness();
		second.addNpc(CHAOS_INDEX, "Chaos Elemental");
		second.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		second.addPartyMember(BOB_MEMBER_ID, "Bob", BOB_PLAYER_ID, false);
		second.rememberSenderScope(ALICE_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);
		second.rememberSenderScope(BOB_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);
		second.tick = 100;
		second.sendSpecial(BOB_MEMBER_ID, BOB_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		second.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		second.tracker.onGameTick();

		assertEquals(2, first.historyFor(CHAOS_INDEX).size());
		assertEquals(2, second.historyFor(CHAOS_INDEX).size());
		assertEquals(first.stateFor(CHAOS_INDEX).getCurrent(), second.stateFor(CHAOS_INDEX).getCurrent());
	}

	@Test
	public void retransmissionOfOneStandardObservationIsAppliedOnce() throws Exception
	{
		Harness h = new Harness();
		h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		h.rememberSenderScope(ALICE_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);

		h.tick = 100;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		assertEquals("the standard message has no event id, so its bounded retransmission key is used",
			1, h.historyFor(CHAOS_INDEX).size());
		assertEquals(189L, h.stateFor(CHAOS_INDEX).getCurrent());
	}

	@Test
	public void scopeMismatchWithUniqueTargetIsAcceptedWhenSenderIsNotRendered() throws Exception
	{
		Harness h = new Harness();
		h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		h.rememberSenderScope(ALICE_MEMBER_ID, BpdDefenceSync.SCOPE_INSTANCE, 123456);

		h.tick = 100;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		assertEquals("rendering the sender is not combat evidence", 1, h.historyFor(CHAOS_INDEX).size());
		assertEquals(189L, h.stateFor(CHAOS_INDEX).getCurrent());
	}

	@Test
	public void scopeMismatchWithoutIdentifiedTargetIsStillRejected() throws Exception
	{
		Harness h = new Harness();
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, true);
		h.rememberSenderScope(ALICE_MEMBER_ID, BpdDefenceSync.SCOPE_INSTANCE, 123456);

		h.tick = 100;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		assertNull(h.tracker.state());
		assertFalse(h.tracker.hasUnprocessedSpecs());
	}

	@Test
	public void scopeMismatchWithAmbiguousTargetIsStillRejected() throws Exception
	{
		Harness h = new Harness();
		h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		h.addNpc(CHAOS_INDEX + 1, "Chaos Elemental");
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, true);
		h.rememberSenderScope(ALICE_MEMBER_ID, BpdDefenceSync.SCOPE_INSTANCE, 123456);

		h.tick = 100;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		assertNull(h.tracker.state());
		assertFalse(h.tracker.hasUnprocessedSpecs());
	}

	@Test
	public void outOfRenderTargetRetainsMatchingScopeEvidenceUntilItAppears() throws Exception
	{
		Harness h = new Harness();
		h.addNpc(CORP_INDEX, "Corporeal Beast");
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		h.rememberSenderScope(ALICE_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);
		h.interacting = h.npc(CORP_INDEX);

		h.tick = 100;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		assertTrue("matching-scope evidence is retained while its target is unresolved",
			h.tracker.hasUnprocessedSpecs());
		assertNull(h.stateFor(CHAOS_INDEX));

		h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		h.tick = 101;
		h.tracker.onGameTick();

		assertFalse(h.tracker.hasUnprocessedSpecs());
		assertEquals(189L, h.stateFor(CHAOS_INDEX).getCurrent());
		assertEquals(1, h.historyFor(CHAOS_INDEX).size());
	}

	@Test
	public void teammateSpecOnBossAIsAppliedButBossBRemainsTheSelectedDisplayTarget() throws Exception
	{
		Harness h = new Harness();
		NPC bossA = h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		NPC bossB = h.addNpc(CORP_INDEX, "Corporeal Beast");
		h.interacting = bossB;
		h.localSpec(bossB, "LocalPlayer", SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();
		assertEquals(CORP_INDEX, h.tracker.state().getNpcIndex());

		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		h.rememberSenderScope(ALICE_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);
		h.tick = 101;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, bossA.getIndex(),
			SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		assertEquals("combat evidence was applied to Boss A", 189L, h.stateFor(CHAOS_INDEX).getCurrent());
		assertEquals(1, h.historyFor(CHAOS_INDEX).size());
		assertEquals("the existing interacting-target policy keeps Boss B selected",
			CORP_INDEX, h.tracker.state().getNpcIndex());

		h.interacting = bossA;
		h.tick = 102;
		h.tracker.onGameTick();

		assertEquals("clicking only changes the display selection", CHAOS_INDEX,
			h.tracker.state().getNpcIndex());
		assertEquals(189L, h.tracker.state().getCurrent());
		assertEquals(1, h.historyFor(CHAOS_INDEX).size());
	}

	@Test
	public void teammateSpecOnBossAWithExistingHistoryIsAppliedWithoutStealingSelection() throws Exception
	{
		Harness h = new Harness();
		NPC bossA = h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		NPC bossB = h.addNpc(CORP_INDEX, "Corporeal Beast");
		h.interacting = bossA;
		h.localSpec(bossA, "LocalPlayer", SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();
		h.interacting = bossB;
		h.localSpec(bossB, "LocalPlayer", SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		h.rememberSenderScope(ALICE_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);
		h.tick = 101;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, bossA.getIndex(),
			SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		assertEquals(2, h.historyFor(CHAOS_INDEX).size());
		assertEquals(133L, h.stateFor(CHAOS_INDEX).getCurrent());
		assertEquals(CORP_INDEX, h.tracker.state().getNpcIndex());
	}

	@Test
	public void dwhWithoutHeartbeatIsAcceptedForKnownBpdPeer() throws Exception
	{
		Harness h = new Harness();
		h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		h.markKnownBpd(ALICE_MEMBER_ID);

		h.tick = 100;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX,
			SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		assertEquals("missing presence metadata must not erase a valid DWH",
			1, h.historyFor(CHAOS_INDEX).size());
		assertEquals(189L, h.stateFor(CHAOS_INDEX).getCurrent());
	}

	@Test
	public void dwhWithStaleHeartbeatIsAcceptedForKnownBpdPeer() throws Exception
	{
		Harness h = new Harness();
		h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		h.rememberSenderScope(ALICE_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0);
		h.ageSenderHeartbeat(ALICE_MEMBER_ID, BpdDefenceSync.SCOPE_WORLD, 0, 7_000L);

		h.tick = 100;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX,
			SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		assertEquals("a stale heartbeat must not erase a valid DWH",
			1, h.historyFor(CHAOS_INDEX).size());
		assertEquals(189L, h.stateFor(CHAOS_INDEX).getCurrent());
	}

	@Test
	public void ralosWithoutHeartbeatIsAcceptedForKnownBpdPeer() throws Exception
	{
		Harness h = new Harness();
		h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		h.markKnownBpd(ALICE_MEMBER_ID);

		h.tick = 100;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX,
			SpecialWeapon.TONALZTICS_OF_RALOS, 2);
		h.tracker.onGameTick();

		assertEquals("missing presence metadata must not erase a valid Ralos hit",
			1, h.historyFor(CHAOS_INDEX).size());
		assertNotNull(h.stateFor(CHAOS_INDEX));
	}

	@Test
	public void staleHeartbeatRalosOnArchiveGuardianResolvesAfterTargetAppears() throws Exception
	{
		Harness h = new Harness();
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		h.rememberSenderScope(ALICE_MEMBER_ID, BpdDefenceSync.SCOPE_INSTANCE, 123456);
		h.ageSenderHeartbeat(ALICE_MEMBER_ID, BpdDefenceSync.SCOPE_INSTANCE, 123456, 7_000L);

		h.tick = 100;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, ARCHIVE_GUARDIAN_INDEX,
			SpecialWeapon.TONALZTICS_OF_RALOS, 2);
		h.tracker.onGameTick();

		assertTrue(h.tracker.hasUnprocessedSpecs());

		h.addNpc(ARCHIVE_GUARDIAN_INDEX, "Archive Guardian");
		h.tick = 101;
		h.tracker.onGameTick();

		assertFalse(h.tracker.hasUnprocessedSpecs());
		assertEquals(1, h.historyFor(ARCHIVE_GUARDIAN_INDEX).size());
		assertNotNull(h.stateFor(ARCHIVE_GUARDIAN_INDEX));
	}

	@Test
	public void sacOnlySenderWithoutPresenceMetadataRemainsSupported() throws Exception
	{
		Harness h = new Harness();
		h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);

		h.tick = 100;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX,
			SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		assertEquals(1, h.historyFor(CHAOS_INDEX).size());
		assertEquals(189L, h.stateFor(CHAOS_INDEX).getCurrent());
	}

	@Test
	public void olderBpdSenderWithIncompleteMetadataRemainsSupported() throws Exception
	{
		Harness h = new Harness();
		h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		h.markKnownBpd(ALICE_MEMBER_ID);

		h.tick = 100;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX,
			SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		assertEquals("a recognized BPD peer without the current heartbeat message is legacy-compatible",
			1, h.historyFor(CHAOS_INDEX).size());
	}

	@Test
	public void missingHeartbeatRetainsUnrenderedTargetForLaterAssociation() throws Exception
	{
		Harness h = new Harness();
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		h.markKnownBpd(ALICE_MEMBER_ID);

		h.tick = 100;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX,
			SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		assertTrue("the existing bounded deferred-event path must retain the observation",
			h.tracker.hasUnprocessedSpecs());
		assertNull(h.stateFor(CHAOS_INDEX));

		h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		h.tick = 101;
		h.tracker.onGameTick();

		assertFalse(h.tracker.hasUnprocessedSpecs());
		assertEquals(1, h.historyFor(CHAOS_INDEX).size());
		assertEquals(189L, h.stateFor(CHAOS_INDEX).getCurrent());
	}

	@Test
	public void missingHeartbeatDoesNotGuessBetweenAmbiguousTargets() throws Exception
	{
		Harness h = new Harness();
		h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		h.addNpc(CHAOS_INDEX + 2, "Chaos Elemental");
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		h.markKnownBpd(ALICE_MEMBER_ID);

		h.tick = 100;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX,
			SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		assertTrue(h.tracker.hasUnprocessedSpecs());
		assertNull(h.stateFor(CHAOS_INDEX));
		assertNull(h.stateFor(CHAOS_INDEX + 2));
	}

	@Test
	public void twoPlayersWithoutHeartbeatsRemainDistinctOnTheSameTick() throws Exception
	{
		Harness h = new Harness();
		h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		h.addPartyMember(BOB_MEMBER_ID, "Bob", BOB_PLAYER_ID, false);
		h.markKnownBpd(ALICE_MEMBER_ID);
		h.markKnownBpd(BOB_MEMBER_ID);

		h.tick = 100;
		h.sendSpecial(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX,
			SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.sendSpecial(BOB_MEMBER_ID, BOB_PLAYER_ID, CHAOS_INDEX,
			SpecialWeapon.DRAGON_WARHAMMER, 1);
		h.tracker.onGameTick();

		List<SpecHistoryEntry> history = h.historyFor(CHAOS_INDEX);
		assertEquals(2, history.size());
		assertEquals("Alice", history.get(0).getPlayerName());
		assertEquals("Bob", history.get(1).getPlayerName());
		assertEquals(133L, h.stateFor(CHAOS_INDEX).getCurrent());
	}

	@Test
	public void missingHeartbeatDoesNotBypassWorldValidation() throws Exception
	{
		Harness h = new Harness();
		h.addNpc(CHAOS_INDEX, "Chaos Elemental");
		h.addPartyMember(ALICE_MEMBER_ID, "Alice", ALICE_PLAYER_ID, false);
		h.markKnownBpd(ALICE_MEMBER_ID);

		h.tick = 100;
		h.sendSpecialInWorld(ALICE_MEMBER_ID, ALICE_PLAYER_ID, CHAOS_INDEX,
			SpecialWeapon.DRAGON_WARHAMMER, 1, WORLD + 1);
		h.tracker.onGameTick();

		assertNull(h.stateFor(CHAOS_INDEX));
		assertFalse(h.tracker.hasUnprocessedSpecs());
	}

	private static final class Harness
	{
		private final Map<Integer, NPC> npcsByIndex = new LinkedHashMap<>();
		private final Map<Integer, Player> playersById = new LinkedHashMap<>();
		private final Map<Long, PartyMember> membersById = new LinkedHashMap<>();
		private final Client client = mock(Client.class);
		private final WorldView worldView = mock(WorldView.class);
		@SuppressWarnings("unchecked")
		private final IndexedObjectSet<NPC> npcs = mock(IndexedObjectSet.class);
		@SuppressWarnings("unchecked")
		private final IndexedObjectSet<Player> players = mock(IndexedObjectSet.class);
		private final PartyService party = mock(PartyService.class);
		private final PartyMember localMember = mock(PartyMember.class);
		private final Player localPlayer = mock(Player.class);
		private final BetterPartyDefenceConfig config = mock(BetterPartyDefenceConfig.class);
		private final DefenceTracker tracker;
		private final BetterPartyDefencePlugin plugin = new BetterPartyDefencePlugin();
		private int tick = 100;
		private NPC interacting;

		@SuppressWarnings("unchecked")
		private Harness() throws Exception
		{
			when(client.getWorld()).thenReturn(WORLD);
			when(client.getTickCount()).thenAnswer(invocation -> tick);
			when(client.getTopLevelWorldView()).thenReturn(worldView);
			when(worldView.isInstance()).thenReturn(false);
			doReturn(npcs).when(worldView).npcs();
			doReturn(players).when(worldView).players();
			when(npcs.byIndex(anyInt())).thenAnswer(invocation -> npcsByIndex.get(invocation.getArgument(0)));
			when(npcs.iterator()).thenAnswer(invocation -> new ArrayList<>(npcsByIndex.values()).iterator());
			when(players.iterator()).thenAnswer(invocation -> new ArrayList<>(playersById.values()).iterator());

			when(localMember.getMemberId()).thenReturn(999L);
			when(localMember.getDisplayName()).thenReturn("LocalPlayer");
			when(party.isInParty()).thenReturn(true);
			when(party.getLocalMember()).thenReturn(localMember);
			when(party.getMemberById(anyLong())).thenAnswer(invocation -> membersById.get(invocation.getArgument(0)));

			when(localPlayer.getId()).thenReturn(9999);
			when(localPlayer.getName()).thenReturn("LocalPlayer");
			when(localPlayer.getInteracting()).thenAnswer(invocation -> interacting);
			when(localPlayer.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
			when(client.getLocalPlayer()).thenReturn(localPlayer);
			tracker = new DefenceTracker(client, config);

			ClientThread clientThread = mock(ClientThread.class);
			doAnswer(invocation -> {
				((Runnable) invocation.getArgument(0)).run();
				return null;
			}).when(clientThread).invoke(any(Runnable.class));

			setPluginField("client", client);
			setPluginField("config", config);
			setPluginField("defenceTracker", tracker);
			setPluginField("partyService", party);
			setPluginField("clientThread", clientThread);
		}

		private NPC addNpc(int index, String name)
		{
			NPC npc = mock(NPC.class);
			when(npc.getName()).thenReturn(name);
			when(npc.getIndex()).thenReturn(index);
			when(npc.getId()).thenReturn(0);
			when(npc.getHealthRatio()).thenReturn(1);
			when(npc.getHealthScale()).thenReturn(1);
			when(npc.isDead()).thenReturn(false);
			when(npc.getWorldLocation()).thenReturn(new WorldPoint(3200 + index, 3200, 0));
			npcsByIndex.put(index, npc);
			return npc;
		}

		private NPC npc(int index)
		{
			return npcsByIndex.get(index);
		}

		private void addPartyMember(long memberId, String name, int playerId, boolean rendered)
		{
			PartyMember member = mock(PartyMember.class);
			when(member.getMemberId()).thenReturn(memberId);
			when(member.getDisplayName()).thenReturn(name);
			membersById.put(memberId, member);
			if (rendered)
			{
				Player player = mock(Player.class);
				when(player.getId()).thenReturn(playerId);
				when(player.getName()).thenReturn(name);
				playersById.put(playerId, player);
			}
		}

		private void sendSpecial(long memberId, int playerId, int npcIndex,
			SpecialWeapon weapon, int hit)
		{
			sendSpecialInWorld(memberId, playerId, npcIndex, weapon, hit, WORLD);
		}

		private void sendSpecialInWorld(long memberId, int playerId, int npcIndex,
			SpecialWeapon weapon, int hit, int world)
		{
			assertNotNull(membersById.get(memberId));
			SpecialCounterUpdate packet = new SpecialCounterUpdate(npcIndex, weapon, hit, world, playerId);
			packet.setMemberId(memberId);
			plugin.onSpecialCounterUpdate(packet);
		}

		@SuppressWarnings("unchecked")
		private void markKnownBpd(long memberId) throws Exception
		{
			Field known = BetterPartyDefencePlugin.class.getDeclaredField("knownBpdPartyMembers");
			known.setAccessible(true);
			((java.util.Set<Long>) known.get(plugin)).add(memberId);
		}

		@SuppressWarnings("unchecked")
		private void ageSenderHeartbeat(long memberId, int scopeType, int scopeId, long ageMillis)
			throws Exception
		{
			Field field = BetterPartyDefencePlugin.class.getDeclaredField("senderEncounterPresence");
			field.setAccessible(true);
			Map<Long, Object> presence = (Map<Long, Object>) field.get(plugin);
			Object current = presence.get(memberId);
			assertNotNull("test requires a stored heartbeat", current);

			Constructor<?> constructor = current.getClass().getDeclaredConstructor(
				int.class, int.class, int.class, long.class, long.class);
			constructor.setAccessible(true);
			long oldTimestamp = System.currentTimeMillis() - ageMillis;
			presence.put(memberId, constructor.newInstance(
				WORLD, scopeType, scopeId, oldTimestamp, oldTimestamp));
		}

		private void rememberSenderScope(long memberId, int scopeType, int scopeId) throws Exception
		{
			Method method = BetterPartyDefencePlugin.class.getDeclaredMethod(
				"rememberSenderEncounterPresence",
				long.class, int.class, int.class, int.class, long.class);
			method.setAccessible(true);
			method.invoke(plugin, memberId, WORLD, scopeType, scopeId, System.currentTimeMillis());
		}

		private void localSpec(NPC npc, String playerName, SpecialWeapon weapon, int hit)
		{
			tracker.queueLocal(weapon, npc.getIndex(), hit, WORLD, playerName, npc);
		}

		private DefenceState stateFor(int npcIndex)
		{
			for (DefenceState state : tracker.states())
			{
				if (state.getNpcIndex() == npcIndex)
				{
					return state;
				}
			}
			return null;
		}

		private List<SpecHistoryEntry> historyFor(int npcIndex)
		{
			for (SyncTarget target : tracker.syncTargets())
			{
				if (target.getNpcIndex() == npcIndex && target.getState() != null)
				{
					SyncState state = target.getState();
					return state.getHistory() == null ? new ArrayList<>() : state.getHistory();
				}
			}
			return new ArrayList<>();
		}

		private void setPluginField(String name, Object value)
		{
			try
			{
				Field field = BetterPartyDefencePlugin.class.getDeclaredField(name);
				field.setAccessible(true);
				field.set(plugin, value);
			}
			catch (ReflectiveOperationException ex)
			{
				throw new AssertionError(ex);
			}
		}
	}
}
