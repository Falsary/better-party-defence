package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.betterpartydefence.DefenceTracker.SyncState;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.plugins.specialcounter.SpecialCounterUpdate;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/**
 * Two-client recovery regressions for a temporary NPC absence. The tests use the actual plugin
 * handlers and DefenceTracker; only the RuneLite client and party transport are mocked.
 */
public class EncounterRecoveryRegressionTest
{
	private static final int WORLD = 301;
	private static final int MOLE_INDEX = 81;
	private static final long REMOTE_MEMBER_ID = 7L;

	@Test
	public void encounterPresenceAnnouncesScopeChangesAndStaysSilentInIdleWorld()
	{
		Node node = new Node();
		node.tick();
		assertTrue(node.sentEncounterScopes.isEmpty());

		when(node.worldView.isInstance()).thenReturn(true);
		node.tick();
		node.tick();

		when(node.worldView.isInstance()).thenReturn(false);
		node.tick();
		node.tick();
		assertEquals(List.of(BpdDefenceSync.SCOPE_INSTANCE, BpdDefenceSync.SCOPE_WORLD),
			node.sentEncounterScopes);
	}

	@Test
	public void teleportAndReturnRecoversPeerState()
	{
		Node alice = new Node();
		Node bob = new Node();
		alice.addMole();
		bob.addMole();
		SyncState reduced = moleState(120);
		alice.apply(reduced);
		bob.apply(reduced);

		bob.removeMole();
		alice.sendSyncTo(bob, reduced, 1L);
		bob.tick();

		assertTrue("active peer evidence must keep the absent target remembered",
			bob.tracker.hasRememberedState(BossDefence.GIANT_MOLE));

		bob.addMole();
		bob.tick();
		assertEquals(120L, bob.tracker.state().getCurrent());
	}

	@Test
	public void absenceBeyondTwentySecondsRecoversWhilePeerRemainsActive()
	{
		Node alice = new Node();
		Node bob = new Node();
		alice.addMole();
		bob.addMole();
		SyncState reduced = moleState(120);
		alice.apply(reduced);
		bob.apply(reduced);

		bob.removeMole();
		alice.sendSyncTo(bob, reduced, 1L);
		bob.tick();

		assertTrue("a fresh peer sync must renew presence beyond the local absence timer",
			bob.tracker.hasRememberedState(BossDefence.GIANT_MOLE));

		bob.addMole();
		bob.tick();
		assertEquals(120L, bob.tracker.state().getCurrent());
	}

	@Test
	public void missingPeerMessagesDoNotInvalidateRememberedState()
	{
		Node bob = new Node();
		bob.addMole();
		bob.apply(moleState(120));
		bob.removeMole();

		bob.tick();

		assertTrue("missing snapshots are not authoritative evidence of encounter termination",
			bob.tracker.hasRememberedState(BossDefence.GIANT_MOLE));
	}

	@Test
	public void partyJoinDoesNotRestoreSpecsFromBeforeAnObservedSoloKill()
	{
		Node node = new Node();
		node.addMole();
		when(node.party.isInParty()).thenReturn(false);
		node.tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, MOLE_INDEX, 13, WORLD,
			"Alice", node.mole, System.currentTimeMillis() - 30_000);
		node.tick();
		SyncState stale = node.tracker.syncStateForBoss(BossDefence.GIANT_MOLE);
		when(node.mole.isDead()).thenReturn(true);
		node.tick();
		assertNull(node.tracker.state());

		when(node.party.isInParty()).thenReturn(true);
		node.plugin.onPartyChanged(new PartyChanged("test-party", 0L));
		node.addMole();
		node.receiveSync(stale, 1L, 0L);
		node.tick();
		assertNull(node.tracker.state());
	}

	@Test
	public void rejoinCombinesTwoIndependentLandedSpecsWhenBothSeeTheSameBoss()
	{
		Node holder = new Node();
		Node joiner = new Node();
		holder.addMole();
		joiner.addMole();
		holder.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 42);
		holder.tick();
		when(joiner.party.isInParty()).thenReturn(false);
		joiner.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 6);
		joiner.tick();
		assertEquals(140L, holder.tracker.state().getCurrent());
		assertEquals(140L, joiner.tracker.state().getCurrent());

		holder.setPeer(joiner);
		joiner.setPeer(holder);
		when(joiner.party.isInParty()).thenReturn(true);
		joiner.eventBus.post(new UserJoin(0L, joiner.localMember.getMemberId()));
		joiner.tick();
		holder.tick();
		joiner.tick();
		holder.tick();
		assertEquals(98L, holder.tracker.state().getCurrent());
		assertEquals(98L, joiner.tracker.state().getCurrent());
		assertEquals(2, joiner.tracker.specHistory().size());
		assertEquals(holder.tracker.specHistory(), joiner.tracker.specHistory());
		assertEquals(1, joiner.sentBossPresenceRequests);
		assertEquals(1, holder.sentDefenceSyncs);
		assertTrue(joiner.sentSpecs.isEmpty());
	}

	@Test
	public void returningToTheBossReoffersHistoryWhenTheJoinerSpeccedWhilePeerWasAway()
	{
		Node holder = new Node();
		Node joiner = new Node();
		holder.addMole();
		joiner.addMole();
		holder.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 42);
		holder.tick();
		holder.removeMole();
		holder.tick();
		when(joiner.party.isInParty()).thenReturn(false);
		joiner.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 6);
		joiner.tick();

		holder.setPeer(joiner);
		joiner.setPeer(holder);
		when(joiner.party.isInParty()).thenReturn(true);
		joiner.eventBus.post(new UserJoin(0L, joiner.localMember.getMemberId()));
		joiner.tick();
		holder.tick();
		joiner.tick();
		assertEquals(98L, joiner.tracker.state().getCurrent());
		assertEquals(140L, holder.tracker.state().getCurrent());

		holder.addMole();
		holder.tick();
		joiner.tick();
		holder.tick();
		assertEquals(98L, holder.tracker.state().getCurrent());
		assertEquals(joiner.tracker.specHistory(), holder.tracker.specHistory());
		assertEquals(1, holder.sentBossPresenceRequests);
		assertEquals(1, joiner.sentBossPresenceRequests);
	}

	@Test
	public void rejoinCombinesDisjointWarhammerMissesWithTheMatchedBossHit()
	{
		Node holder = new Node();
		Node joiner = new Node();
		holder.addMole();
		joiner.addMole();
		holder.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 13);
		holder.tick();
		when(joiner.party.isInParty()).thenReturn(false);
		joiner.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 0);
		joiner.tick();
		joiner.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 0);
		joiner.tick();
		assertEquals(200L, joiner.tracker.state().getCurrent());

		holder.setPeer(joiner);
		joiner.setPeer(holder);
		when(joiner.party.isInParty()).thenReturn(true);
		joiner.eventBus.post(new UserJoin(0L, joiner.localMember.getMemberId()));
		joiner.tick();
		holder.tick();
		joiner.tick();
		assertEquals(140L, holder.tracker.state().getCurrent());
		assertEquals(140L, joiner.tracker.state().getCurrent());
		assertEquals(3, joiner.tracker.specHistory().size());
		assertEquals(joiner.tracker.specHistory(), holder.tracker.specHistory());
		assertEquals(1, joiner.sentBossPresenceRequests);
		assertEquals(1, holder.sentDefenceSyncs);
	}

	@Test
	public void rejoiningWithNewerHistoryOffersItOnceWithoutASnapshotReply()
	{
		Node holder = new Node();
		Node joiner = new Node();
		holder.addMole();
		joiner.addMole();
		holder.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 1);
		holder.tick();
		joiner.apply(holder.tracker.syncStateForBoss(BossDefence.GIANT_MOLE));
		when(joiner.party.isInParty()).thenReturn(false);
		joiner.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 17);
		joiner.tick();
		assertEquals(140L, holder.tracker.state().getCurrent());
		assertEquals(98L, joiner.tracker.state().getCurrent());

		holder.setPeer(joiner);
		joiner.setPeer(holder);
		when(joiner.party.isInParty()).thenReturn(true);
		joiner.eventBus.post(new UserJoin(0L, joiner.localMember.getMemberId()));
		joiner.tick();
		holder.tick();
		joiner.tick();
		holder.tick();
		assertEquals(98L, holder.tracker.state().getCurrent());
		assertEquals(joiner.tracker.specHistory(), holder.tracker.specHistory());
		assertEquals(1, joiner.sentBossPresenceRequests);
		assertEquals(0, holder.sentDefenceSyncs + joiner.sentDefenceSyncs);
		assertTrue(joiner.sentSpecs.isEmpty());
	}

	@Test
	public void rejoinWithDifferentNewSpecsReconcilesBothHistoriesWithOneReply()
	{
		Node holder = new Node();
		Node joiner = new Node();
		holder.addMole();
		joiner.addMole();
		holder.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 1);
		holder.tick();
		joiner.apply(holder.tracker.syncStateForBoss(BossDefence.GIANT_MOLE));
		holder.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 12);
		holder.tick();
		when(joiner.party.isInParty()).thenReturn(false);
		joiner.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 17);
		joiner.tick();

		holder.setPeer(joiner);
		joiner.setPeer(holder);
		when(joiner.party.isInParty()).thenReturn(true);
		joiner.eventBus.post(new UserJoin(0L, joiner.localMember.getMemberId()));
		joiner.tick();
		holder.tick();
		joiner.tick();
		holder.tick();
		assertEquals(69L, holder.tracker.state().getCurrent());
		assertEquals(holder.tracker.state(), joiner.tracker.state());
		assertEquals(holder.tracker.specHistory(), joiner.tracker.specHistory());
		assertEquals(1, joiner.sentBossPresenceRequests);
		assertEquals(1, holder.sentDefenceSyncs);
		assertEquals(0, joiner.sentDefenceSyncs);
	}

	@Test
	public void moleSpecDuringBurrowSendsOnceAndDeathClearsTheEncounter()
	{
		Node node = new Node();
		node.addMole();
		node.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 17);
		node.removeMole();
		node.tick();
		assertEquals(1, node.sentSpecs.size());
		assertEquals(140L, node.tracker.state().getCurrent());

		node.addMole();
		node.tick();
		assertEquals(140L, node.tracker.state().getCurrent());
		assertEquals(1, node.sentSpecs.size());

		node.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 17);
		when(node.mole.isDead()).thenReturn(true);
		node.removeMole();
		node.tick();
		assertNull(node.tracker.state());
		assertEquals(1, node.sentEncounterResets);
		assertEquals(1, node.sentSpecs.size());
	}

	@Test
	public void partyRejoinCanRecoverActivePeerState()
	{
		Node alice = new Node();
		Node bob = new Node();
		alice.addMole();
		bob.addMole();
		SyncState reduced = moleState(120);
		alice.apply(reduced);

		bob.tracker.clearBossState(BossDefence.GIANT_MOLE, "test party rejoin");
		PartyChanged changed = mock(PartyChanged.class);
		when(changed.getPartyId()).thenReturn(2L);
		bob.plugin.onPartyChanged(changed);
		when(bob.party.getPartyId()).thenReturn(2L);

		bob.receiveSync(reduced, 1L, 2L);
		bob.tick();

		assertEquals(120L, bob.tracker.state().getCurrent());
	}

	@Test
	public void formerPartyMemberRetainsStateWithoutElapsedTimeCleanup()
	{
		Node bob = new Node();
		bob.addMole();
		bob.apply(moleState(120));
		when(bob.party.isInParty()).thenReturn(false);
		bob.removeMole();

		bob.tick();

		assertTrue("leaving PartyService is not evidence that the encounter ended",
			bob.tracker.hasRememberedState(BossDefence.GIANT_MOLE));
		bob.addMole();
		bob.tick();
		assertEquals(120L, bob.tracker.state().getCurrent());
	}

	@Test
	public void returningClientRequestsFullSnapshot()
	{
		Node alice = new Node();
		Node bob = new Node();
		alice.addMole();
		bob.addMole();
		alice.apply(moleState(120));
		alice.setPeer(bob);
		bob.setPeer(alice);

		alice.tick();
		assertEquals(0, alice.sentDefenceSyncs);
		bob.tick();
		bob.tick();

		assertEquals(120L, bob.tracker.state().getCurrent());
		assertEquals(1, bob.sentBossPresenceRequests);
		assertEquals(1, alice.sentDefenceSyncs);
	}

	@Test
	public void publicBossRebindsBeyondEightTilesWithoutNetworkTraffic()
	{
		for (String boss : List.of("Giant Mole", "Corporeal Beast", "Chaos Elemental"))
		{
			Node node = new Node();
			NPC original = node.addNpc(MOLE_INDEX, boss);
			node.tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, MOLE_INDEX, 1, WORLD, "Alice", original);
			node.tick();
			SyncState before = node.tracker.syncState();
			int requests = node.sentBossPresenceRequests;
			int specs = node.sentSpecs.size();

			NPC replacement = node.addNpc(MOLE_INDEX, boss);
			when(replacement.getWorldLocation()).thenReturn(new WorldPoint(3040, 3000, 0));
			node.tick();
			node.tick();

			assertTrue(node.tracker.hasBoundNpc());
			assertEquals(before, node.tracker.syncState());
			assertEquals(requests, node.sentBossPresenceRequests);
			assertEquals(specs, node.sentSpecs.size());
			assertEquals(0, node.sentDefenceSyncs);
		}
	}

	@Test
	public void publicBossCacheSurvivesUnrelatedIndexReuseAndClearsOnObservedKill()
	{
		Node node = new Node();
		node.addMole();
		node.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 1);
		node.tick();
		SyncState before = node.tracker.syncState();

		node.addNpc(MOLE_INDEX, "Goblin");
		node.tick();
		assertFalse(node.tracker.hasBoundNpc());
		assertTrue(node.tracker.states().isEmpty());
		assertTrue(node.tracker.hasRememberedState(BossDefence.GIANT_MOLE));

		int replacementIndex = MOLE_INDEX + 1;
		NPC replacement = node.addNpc(replacementIndex, "Giant Mole");
		when(replacement.getWorldLocation()).thenReturn(new WorldPoint(3040, 3000, 0));
		node.tick();
		assertTrue(node.tracker.hasBoundNpc());
		assertEquals(replacementIndex, node.tracker.state().getNpcIndex());
		assertEquals(before, node.tracker.syncState());

		when(replacement.isDead()).thenReturn(true);
		node.tick();
		assertFalse(node.tracker.hasRememberedState(BossDefence.GIANT_MOLE));
	}

	@Test
	public void publicBossCacheDoesNotAttachToPrivateCopyOrSurviveAWorldChange()
	{
		Node node = new Node();
		node.addMole();
		node.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 1);
		node.tick();
		SyncState before = node.tracker.syncState();

		when(node.worldView.isInstance()).thenReturn(true);
		node.addMole();
		node.tick();
		assertFalse(node.tracker.hasBoundNpc());
		assertNull(node.tracker.syncStateForBoss(BossDefence.GIANT_MOLE));
		node.apply(moleState(123));
		node.tick();
		assertEquals(123L, node.tracker.state().getCurrent());

		when(node.worldView.isInstance()).thenReturn(false);
		node.addMole();
		node.tick();
		assertEquals(before, node.tracker.syncState());

		when(node.client.getWorld()).thenReturn(WORLD + 1);
		node.tick();
		assertFalse(node.tracker.hasRememberedState(BossDefence.GIANT_MOLE));
	}

	@Test
	public void ambiguousPublicBossesDoNotInheritAStoredDrain()
	{
		Node node = new Node();
		node.addMole();
		node.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 1);
		node.tick();
		SyncState before = node.tracker.syncState();
		node.removeMole();
		node.addNpc(MOLE_INDEX + 1, "Giant Mole");
		node.addNpc(MOLE_INDEX + 2, "Giant Mole");

		node.tick();
		assertFalse(node.tracker.hasBoundNpc());
		assertTrue(node.tracker.states().isEmpty());
		assertEquals(before, node.tracker.syncState());
	}

	@Test
	public void corpCatchUpAddsOneReplyFromAnInstancedPeerAndKillClearsItsCache()
	{
		Node holder = new Node();
		Node returning = new Node();
		NPC corp = holder.addNpc(MOLE_INDEX, "Corporeal Beast");
		holder.tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, MOLE_INDEX, 1, WORLD, "Alice", corp);
		holder.tick();
		SyncState before = holder.tracker.syncState();
		holder.removeMole();
		when(holder.worldView.isInstance()).thenReturn(true);
		holder.tick();
		holder.setPeer(returning);
		returning.setPeer(holder);
		NPC visibleCorp = returning.addNpc(MOLE_INDEX, "Corporeal Beast");
		returning.tick();
		for (int i = 0; i < 12; i++)
		{
			returning.tick();
			holder.tick();
		}
		assertEquals(1, returning.sentBossPresenceRequests);
		assertEquals(1, holder.sentDefenceSyncs);
		assertEquals(before, returning.tracker.syncState());

		when(visibleCorp.isDead()).thenReturn(true);
		returning.tick();
		assertEquals(1, returning.sentEncounterResets);
		assertFalse(holder.tracker.hasRememberedState(BossDefence.CORPOREAL_BEAST));
	}

	@Test
	public void onlyOneInstancedPeerCanSendTheAdditionalReply()
	{
		Node first = new Node();
		Node second = new Node();
		when(second.localMember.getMemberId()).thenReturn(2L);
		when(first.party.getMembers()).thenReturn(List.of(first.localMember, second.localMember));
		when(second.party.getMembers()).thenReturn(List.of(first.localMember, second.localMember));
		for (Node holder : List.of(first, second))
		{
			holder.addMole();
			holder.apply(moleState(94));
			holder.removeMole();
			when(holder.worldView.isInstance()).thenReturn(true);
			holder.tick();
		}

		BpdBossPresence request = new BpdBossPresence(WORLD, BossDefence.GIANT_MOLE,
			BpdDefenceSync.SCOPE_WORLD, 0, 3000, 3000, 0, -1);
		request.setMemberId(REMOTE_MEMBER_ID);
		first.plugin.onBpdBossPresence(request);
		second.plugin.onBpdBossPresence(request);
		assertEquals(1, first.sentDefenceSyncs);
		assertEquals(0, second.sentDefenceSyncs);
	}

	@Test
	public void visibleBossWaitsForLocalPartyJoinAndRequestsOnce()
	{
		Node bob = new Node();
		bob.addMole();
		bob.apply(moleState(180));
		when(bob.party.getPartyId()).thenReturn(2L);
		when(bob.party.getLocalMember()).thenReturn(null);
		bob.plugin.onPartyChanged(new PartyChanged("test-party", 2L));
		bob.tick();
		assertEquals(0, bob.sentBossPresenceRequests);

		when(bob.party.getLocalMember()).thenReturn(bob.localMember);
		bob.eventBus.post(new UserJoin(2L, REMOTE_MEMBER_ID));
		bob.tick();
		assertEquals(0, bob.sentBossPresenceRequests);

		bob.eventBus.post(new UserJoin(2L, bob.localMember.getMemberId()));
		bob.tick();
		bob.tick();
		assertEquals(1, bob.sentBossPresenceRequests);
	}

	@Test
	public void unansweredBossRequestWaitsForBossToReturnToView()
	{
		Node bob = new Node();
		bob.addMole();
		bob.tick();
		assertEquals(1, bob.sentBossPresenceRequests);

		for (int i = 0; i < 100; i++)
		{
			bob.tick();
		}
		assertEquals(1, bob.sentBossPresenceRequests);

		bob.removeMole();
		bob.tick();
		bob.addMole();
		bob.tick();
		assertEquals(2, bob.sentBossPresenceRequests);
	}

	@Test
	public void eachLocalSpecSendsOneDeltaAndNoHistoryBroadcast()
	{
		Node alice = new Node();
		Node bob = new Node();
		alice.addMole();
		bob.addMole();
		int chaosIndex = MOLE_INDEX + 1;
		NPC chaos = alice.addNpc(chaosIndex, "Chaos Elemental");
		alice.tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, chaosIndex, 1, WORLD, "Alice", chaos);
		alice.tick();
		assertEquals(1, alice.sentSpecs.size());

		alice.setPeer(bob);
		alice.tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, MOLE_INDEX, 1, WORLD, "Alice", alice.mole);
		alice.tick();
		bob.tick();
		assertEquals(2, alice.sentSpecs.size());
		assertEquals(alice.tracker.state().getCurrent(), bob.tracker.state().getCurrent());
		assertEquals(alice.tracker.specHistory(), bob.tracker.specHistory());

		bob.receiveSpec(alice.sentSpecs.get(1));
		alice.tick();
		bob.tick();
		assertEquals(2, alice.sentSpecs.size());
		assertEquals(1, bob.tracker.specHistory().size());
		assertTrue(bob.sentSpecs.isEmpty());
		assertEquals(0, alice.sentDefenceSyncs + bob.sentDefenceSyncs);
	}

	@Test
	public void newerDeltaDetectsMissedPredecessorAndRequestsIt()
	{
		Node alice = new Node();
		Node bob = new Node();
		alice.addMole();
		bob.addMole();
		alice.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 1);
		alice.tick();
		alice.setPeer(bob);
		bob.setPeer(alice);
		alice.localSpec(SpecialWeapon.ARCLIGHT, 1);
		alice.tick();
		bob.tick();
		assertEquals(1, bob.sentBossPresenceRequests);
		bob.tick();
		assertEquals(alice.tracker.state().getCurrent(), bob.tracker.state().getCurrent());
		assertEquals(alice.tracker.specHistory(), bob.tracker.specHistory());
		assertEquals(1, alice.sentDefenceSyncs);
		assertEquals(1, bob.sentBossPresenceRequests);
	}

	@Test
	public void returningToRenderRequestsMissedSpecWithoutAnotherLiveEvent()
	{
		Node alice = new Node();
		Node bob = new Node();
		alice.addMole();
		bob.addMole();
		alice.setPeer(bob);
		bob.setPeer(alice);
		alice.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 1);
		alice.tick();
		bob.tick();

		bob.removeMole();
		bob.tick();
		alice.setPeer(null); // Bob misses both transports while away.
		alice.localSpec(SpecialWeapon.ARCLIGHT, 1);
		alice.tick();
		alice.setPeer(bob);
		assertEquals(0, alice.sentDefenceSyncs);

		bob.addMole();
		bob.tick();
		bob.tick();
		bob.tick();
		assertEquals(alice.tracker.specHistory(), bob.tracker.specHistory());
		assertEquals(alice.tracker.state().getCurrent(), bob.tracker.state().getCurrent());
		assertEquals(1, bob.sentBossPresenceRequests);
		assertEquals(1, alice.sentDefenceSyncs);
	}

	@Test
	public void delayedCompactSpecCannotRestoreAnEndedEncounter()
	{
		Node alice = new Node();
		Node bob = new Node();
		alice.addMole();
		bob.addMole();
		alice.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 1);
		alice.tick();
		bob.receiveSpec(alice.sentSpecs.get(0));
		bob.tick();

		BpdEncounterReset reset = new BpdEncounterReset(
			WORLD, BossDefence.GIANT_MOLE, BpdDefenceSync.SCOPE_WORLD, 0);
		reset.setMemberId(REMOTE_MEMBER_ID);
		bob.plugin.onBpdEncounterReset(reset);
		bob.addMole();
		bob.receiveSpec(alice.sentSpecs.get(0));
		bob.tick();
		assertFalse(bob.tracker.hasRememberedState(BossDefence.GIANT_MOLE));
	}

	@Test
	public void standardSpecAndCompactEventCountOnceInEitherOrder()
	{
		for (boolean standardFirst : new boolean[]{true, false})
		{
			Node alice = new Node();
			Node bob = new Node();
			alice.addMole();
			bob.addMole();
			alice.localSpec(SpecialWeapon.DRAGON_WARHAMMER, 1);
			alice.tick();
			if (standardFirst)
			{
				bob.standardSpec(SpecialWeapon.DRAGON_WARHAMMER, 1);
				bob.tick();
				bob.receiveSpec(alice.sentSpecs.get(0));
			}
			else
			{
				bob.receiveSpec(alice.sentSpecs.get(0));
				bob.tick();
				bob.standardSpec(SpecialWeapon.DRAGON_WARHAMMER, 1);
			}
			bob.tick();
			assertEquals(alice.tracker.specHistory(), bob.tracker.specHistory());
			assertEquals(alice.tracker.state().getCurrent(), bob.tracker.state().getCurrent());
		}
	}

	@Test
	public void identifiedRalosEventCorrectsStandardHitCount()
	{
		Node alice = new Node();
		Node bob = new Node();
		alice.addMole();
		bob.addMole();
		alice.localSpec(SpecialWeapon.TONALZTICS_OF_RALOS, 1);
		alice.tick();
		bob.standardSpec(SpecialWeapon.TONALZTICS_OF_RALOS, 2);
		bob.tick();
		bob.receiveSpec(alice.sentSpecs.get(0));
		bob.tick();
		assertEquals(alice.tracker.specHistory(), bob.tracker.specHistory());
		assertEquals(alice.tracker.state().getCurrent(), bob.tracker.state().getCurrent());
	}

	@Test
	public void clientsResolveSeparatedHitsLocallyDespiteOppositeArrivalOrder()
	{
		Node alice = new Node();
		Node bob = new Node();
		alice.addMole();
		bob.addMole();
		when(alice.remoteMember.getDisplayName()).thenReturn("Bob");
		long hitTime = System.currentTimeMillis();
		alice.tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, MOLE_INDEX, 1, WORLD, "Alice", alice.mole, hitTime - 30_000);
		bob.tracker.queueLocal(SpecialWeapon.ARCLIGHT, MOLE_INDEX, 1, WORLD, "Bob", bob.mole, hitTime);
		alice.tick();
		bob.tick();

		alice.receiveSpec(bob.sentSpecs.get(0));
		bob.receiveSpec(alice.sentSpecs.get(0));
		alice.tick();
		bob.tick();
		assertEquals(alice.tracker.specHistory(), bob.tracker.specHistory());
		assertEquals(alice.tracker.state().getCurrent(), bob.tracker.state().getCurrent());
		assertEquals(0, alice.sentDefenceSyncs + bob.sentDefenceSyncs);
	}

	@Test
	public void moleClientsAgreeOnLateArclightAcrossDifferentTickAnchors()
	{
		Node alice = new Node();
		Node bob = new Node();
		alice.addMole();
		bob.addMole();
		long start = 1_794_000_000_000L;
		SpecHistoryEntry first = new SpecHistoryEntry("Alice", SpecialWeapon.ARCLIGHT, 4, "first", "a", 0)
			.withObservedAtMillis(start);
		SpecHistoryEntry second = new SpecHistoryEntry("Alice", SpecialWeapon.ARCLIGHT, 11, "second", "a", 20)
			.withObservedAtMillis(start + 12_000);
		SpecHistoryEntry dwh = new SpecHistoryEntry("Bob", SpecialWeapon.DRAGON_WARHAMMER, 41, "dwh", "b", 0)
			.withObservedAtMillis(start + 30_000);
		SpecHistoryEntry late = new SpecHistoryEntry("Alice", SpecialWeapon.ARCLIGHT, 6, "late", "a", 113)
			.withObservedAtMillis(start + 68_000);
		SyncState aliceState = moleState(117, List.of(first, second,
			new SpecHistoryEntry("Bob", SpecialWeapon.DRAGON_WARHAMMER, 41), late));
		SyncState bobState = moleState(114, List.of(first, second, dwh,
			new SpecHistoryEntry("Alice", SpecialWeapon.ARCLIGHT, 6)));
		alice.apply(aliceState);
		bob.apply(bobState);

		alice.sendSyncTo(bob, aliceState, 4L);
		bob.sendSyncTo(alice, bobState, 4L);
		alice.tick();
		bob.tick();

		assertEquals(114L, alice.tracker.state().getCurrent());
		assertEquals(114L, bob.tracker.state().getCurrent());
		assertEquals(alice.tracker.specHistory(), bob.tracker.specHistory());
		assertTrue(alice.tracker.specHistory().contains(dwh));
		assertTrue(bob.tracker.specHistory().contains(late));
	}

	@Test
	public void standardMoleSpecCatchesUpOnReturnWithoutAnotherSnapshot()
	{
		Node alice = new Node();
		Node bob = new Node();
		alice.addMole();
		bob.addMole();
		SyncState initial = moleState(180);
		alice.apply(initial);
		bob.apply(initial);

		bob.removeMole();
		alice.tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, MOLE_INDEX, 1, WORLD, "Alice", alice.mole);
		alice.tick();
		SpecialCounterUpdate spec = new SpecialCounterUpdate(MOLE_INDEX, SpecialWeapon.DRAGON_WARHAMMER, 1, WORLD, 10);
		spec.setMemberId(REMOTE_MEMBER_ID);
		bob.plugin.onSpecialCounterUpdate(spec);
		bob.tick();
		assertTrue(bob.tracker.hasUnprocessedSpecsForBoss(BossDefence.GIANT_MOLE));

		bob.addMole();
		bob.tick();
		assertEquals(alice.tracker.state().getCurrent(), bob.tracker.state().getCurrent());
		assertEquals(2, bob.tracker.specHistory().size());
	}

	@Test
	public void clearedReturningClientCanRequestRecoveryWithoutRepeatedTraffic()
	{
		Node alice = new Node();
		Node bob = new Node();
		alice.addMole();
		bob.addMole();
		alice.apply(moleState(120));
		alice.setPeer(bob);
		bob.setPeer(alice);

		bob.tick();
		bob.tick();
		int requestsAfterRecovery = bob.sentBossPresenceRequests;
		bob.tick();

		assertEquals(120L, bob.tracker.state().getCurrent());
		assertTrue("the empty client must request peer recovery", requestsAfterRecovery > 0);
		assertEquals("a bound recovered state must not generate repeated requests",
			requestsAfterRecovery, bob.sentBossPresenceRequests);
	}

	@Test
	public void specsWhileAbsentAreRecoveredFromLatestPeerState()
	{
		Node alice = new Node();
		Node bob = new Node();
		alice.addMole();
		bob.addMole();
		SyncState initial = moleState(180);
		alice.apply(initial);
		bob.apply(initial);

		bob.removeMole();
		alice.tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, MOLE_INDEX, 1, WORLD,
			"Alice", alice.mole);
		alice.tracker.onGameTick();
		SyncState latest = alice.tracker.syncStateForBoss(BossDefence.GIANT_MOLE);

		bob.addMole();
		alice.sendSyncTo(bob, latest, 2L);
		bob.tick();

		assertEquals(latest.getCurrent(), bob.tracker.state().getCurrent());
		assertEquals(latest.getHistory().size(), bob.tracker.specHistory().size());
	}

	@Test
	public void delayedResetReplaysOnlySpecsLandedAfterIt()
	{
		Node node = new Node();
		node.addMole();
		long resetAt = System.currentTimeMillis() - 500;
		node.receiveSpec(moleSpec(timedWarhammer("before", resetAt - 1)));
		node.tick();
		node.receiveSpec(moleSpec(timedWarhammer("after", resetAt + 1)));
		node.tick();
		assertEquals(98L, node.tracker.state().getCurrent());

		node.plugin.onBpdEncounterReset(resetAt(resetAt));
		assertEquals(140L, node.tracker.state().getCurrent());
		assertEquals(1, node.tracker.specHistory().size());
		assertEquals("after", node.tracker.specHistory().get(0).getEventId());
	}

	@Test
	public void timestampedUpdatesApplyImmediatelyAfterResetAndSurviveLateResetDelivery()
	{
		Node specReceiver = new Node();
		Node snapshotReceiver = new Node();
		Node sender = new Node();
		specReceiver.addMole();
		snapshotReceiver.addMole();
		sender.addMole();
		long resetAt = System.currentTimeMillis() - 500;
		BpdEncounterReset reset = resetAt(resetAt);
		specReceiver.plugin.onBpdEncounterReset(reset);
		snapshotReceiver.plugin.onBpdEncounterReset(reset);

		SpecHistoryEntry stale = timedWarhammer("stale", resetAt - 1);
		specReceiver.receiveSpec(moleSpec(stale));
		specReceiver.receiveSpec(moleSpec(timedWarhammer("untimestamped", 0)));
		snapshotReceiver.sendSyncToSelf(moleState(140, List.of(stale)), 1L);
		specReceiver.tick();
		snapshotReceiver.tick();
		assertNull(specReceiver.tracker.state());
		assertNull(snapshotReceiver.tracker.state());

		SpecHistoryEntry fresh = timedWarhammer("fresh", resetAt + 1);
		specReceiver.receiveSpec(moleSpec(fresh));
		snapshotReceiver.sendSyncToSelf(moleState(140, List.of(fresh)), 2L);
		specReceiver.tick();
		snapshotReceiver.tick();
		assertEquals(140L, specReceiver.tracker.state().getCurrent());
		assertEquals(140L, snapshotReceiver.tracker.state().getCurrent());

		sender.receiveSpec(moleSpec(fresh));
		sender.plugin.onBpdEncounterReset(reset);
		sender.tick();
		assertEquals(140L, sender.tracker.state().getCurrent());
		sender.plugin.onBpdEncounterReset(resetAt(resetAt + 1));
		assertNull(sender.tracker.state());
	}

	private static BpdEncounterReset resetAt(long timestamp)
	{
		Gson gson = new Gson();
		var json = gson.toJsonTree(new BpdEncounterReset(
			WORLD, BossDefence.GIANT_MOLE, BpdDefenceSync.SCOPE_WORLD, 0)).getAsJsonObject();
		json.addProperty("sentAtMillis", timestamp);
		BpdEncounterReset reset = gson.fromJson(json, BpdEncounterReset.class);
		reset.setMemberId(REMOTE_MEMBER_ID);
		return reset;
	}

	private static SpecHistoryEntry timedWarhammer(String id, long timestamp)
	{
		return new SpecHistoryEntry("Alice", SpecialWeapon.DRAGON_WARHAMMER, null, 13, id, id, 0, timestamp);
	}

	private static BpdSpecEvent moleSpec(SpecHistoryEntry spec)
	{
		return new BpdSpecEvent(WORLD, BpdDefenceSync.SCOPE_WORLD, 0, BossDefence.GIANT_MOLE,
			MOLE_INDEX, BpdDefenceSync.packBossLocation(3000, 3000, 0), 100, spec, null);
	}

	@Test
	public void confirmedResetPreventsOldStateRestorationAfterRespawn()
	{
		Node bob = new Node();
		bob.addMole();
		SyncState old = moleState(120);
		bob.apply(old);

		BpdEncounterReset reset = new BpdEncounterReset(
			WORLD, BossDefence.GIANT_MOLE, BpdDefenceSync.SCOPE_WORLD, 0);
		reset.setMemberId(REMOTE_MEMBER_ID);
		bob.plugin.onBpdEncounterReset(reset);

		assertFalse(bob.tracker.hasRememberedState(BossDefence.GIANT_MOLE));

		bob.sendSyncToSelf(old, 1L);
		bob.tick();
		assertFalse("an untimestamped snapshot cannot restore an encounter after a confirmed reset",
			bob.tracker.hasRememberedState(BossDefence.GIANT_MOLE));

		bob.addMole();
		bob.tick();
		assertFalse(bob.tracker.hasRememberedState(BossDefence.GIANT_MOLE));
	}

	@Test
	public void emptyFullStateCannotOverwriteValidReducedState()
	{
		Node alice = new Node();
		Node bob = new Node();
		alice.addMole();
		bob.addMole();
		SyncState reduced = moleState(120);
		alice.apply(reduced);
		bob.apply(fullMoleState());

		bob.sendSyncTo(alice, fullMoleState(), 1L);

		assertEquals("a full/empty state must not erase a valid reduced state",
			120L, alice.tracker.state().getCurrent());
	}

	@Test
	public void oldPartyStateCannotEnterNewParty()
	{
		Node bob = new Node();
		bob.addMole();
		when(bob.party.getPartyId()).thenReturn(2L);

		SyncState old = moleState(120);
		aliceSendTo(bob, old, 1L, 1L);

		assertFalse(bob.tracker.hasRememberedState(BossDefence.GIANT_MOLE));
	}

	private static void aliceSendTo(Node target, SyncState state, long version, long partyId)
	{
		target.receiveSync(state, version, partyId);
	}

	private static SyncState moleState(long current)
	{
		SpecHistoryEntry event = new SpecHistoryEntry("Alice", SpecialWeapon.DRAGON_WARHAMMER, 1);
		return moleState(current, Collections.singletonList(event));
	}

	private static SyncState moleState(long current, List<SpecHistoryEntry> history)
	{
		return new SyncState("Giant Mole", BossDefence.GIANT_MOLE, current, 0, 200,
			200, 200, 100, 100, 0, 0, false, false, false, true, history);
	}

	private static SyncState fullMoleState()
	{
		return new SyncState("Giant Mole", BossDefence.GIANT_MOLE, 200, 0, 200,
			200, 200, 100, 100, 0, 0, false, false, false, false,
			Collections.emptyList());
	}

	private static final class Node
	{
		private final Map<Integer, NPC> npcsByIndex = new LinkedHashMap<>();
		private final Client client = mock(Client.class);
		private final WorldView worldView = mock(WorldView.class);
		@SuppressWarnings("unchecked")
		private final IndexedObjectSet<NPC> npcs = mock(IndexedObjectSet.class);
		@SuppressWarnings("unchecked")
		private final IndexedObjectSet<Player> players = mock(IndexedObjectSet.class);
		private final Player localPlayer = mock(Player.class);
		private final PartyService party = mock(PartyService.class);
		private final PartyMember localMember = mock(PartyMember.class);
		private final PartyMember remoteMember = mock(PartyMember.class);
		private final ClientThread clientThread = mock(ClientThread.class);
		private final BetterPartyDefenceConfig config = mock(BetterPartyDefenceConfig.class);
		private final LocalDefenceSpecDetector localDetector = mock(LocalDefenceSpecDetector.class);
		private final BetterPartyDefencePlugin plugin = new BetterPartyDefencePlugin();
		private final EventBus eventBus = new EventBus(error -> { throw new AssertionError(error); });
		private final DefenceTracker tracker;
		private NPC mole;
		private Node peer;
		private final List<Integer> sentEncounterScopes = new ArrayList<>();
		private int sentDefenceSyncs;
		private int sentEncounterResets;
		private final List<BpdSpecEvent> sentSpecs = new ArrayList<>();
		private int sentBossPresenceRequests;
		private int tick = 100;

		@SuppressWarnings("unchecked")
		private Node()
		{
			when(client.getWorld()).thenReturn(WORLD);
			when(client.getTickCount()).thenAnswer(invocation -> tick);
			when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
			when(client.getTopLevelWorldView()).thenReturn(worldView);
			when(client.getLocalPlayer()).thenReturn(localPlayer);
			when(client.getVarbitValue(anyInt())).thenReturn(1);
			doReturn(npcs).when(worldView).npcs();
			doReturn(players).when(worldView).players();
			when(worldView.isInstance()).thenReturn(false);
			when(npcs.byIndex(anyInt())).thenAnswer(
				invocation -> npcsByIndex.get(invocation.<Integer>getArgument(0)));
			when(npcs.iterator()).thenAnswer(
				invocation -> new ArrayList<>(npcsByIndex.values()).iterator());
			when(players.iterator()).thenReturn(Collections.emptyIterator());
			when(localPlayer.getInteracting()).thenReturn(null);
			when(localPlayer.getWorldLocation()).thenReturn(new WorldPoint(3000, 3000, 0));

			when(party.isInParty()).thenReturn(true);
			when(party.getPartyId()).thenReturn(0L);
			when(party.getLocalMember()).thenReturn(localMember);
			when(party.getMemberById(anyLong())).thenAnswer(invocation ->
				invocation.<Long>getArgument(0) == REMOTE_MEMBER_ID ? remoteMember : null);
			when(localMember.getMemberId()).thenReturn(1L);
			when(remoteMember.getDisplayName()).thenReturn("Alice");
			doAnswer(invocation -> {
				((Runnable) invocation.getArgument(0)).run();
				return null;
			}).when(clientThread).invoke(any(Runnable.class));
			when(config.defenceAlwaysShow()).thenReturn(false);
			tracker = new DefenceTracker(client, config);
			doAnswer(invocation -> {
				Object message = invocation.getArgument(0);
				if (message instanceof BpdDefenceSync)
				{
					sentDefenceSyncs++;
					if (peer != null)
					{
						Gson gson = new Gson();
						BpdDefenceSync received = gson.fromJson(gson.toJson(message), BpdDefenceSync.class);
						received.setMemberId(REMOTE_MEMBER_ID);
						peer.receivePacket(received);
					}
				}
				else if (message instanceof BpdSpecEvent)
				{
					sentSpecs.add((BpdSpecEvent) message);
					if (peer != null) { peer.receiveSpec((BpdSpecEvent) message); }
				}
				else if (message instanceof BpdEncounterPresence)
				{
					sentEncounterScopes.add(((BpdEncounterPresence) message).getScopeType());
				}
				else if (message instanceof BpdEncounterReset)
				{
					sentEncounterResets++;
					BpdEncounterReset reset = (BpdEncounterReset) message;
					if (peer != null)
					{
						reset.setMemberId(REMOTE_MEMBER_ID);
						peer.plugin.onBpdEncounterReset(reset);
					}
				}
				else if (message instanceof BpdBossPresence)
				{
					sentBossPresenceRequests++;
					if (peer != null)
					{
						Gson gson = new Gson();
						BpdBossPresence received = gson.fromJson(gson.toJson(message), BpdBossPresence.class);
						received.setMemberId(REMOTE_MEMBER_ID);
						peer.plugin.onBpdBossPresence(received);
					}
				}
				return null;
			}).when(party).send(any());

			setPluginField("client", client);
			setPluginField("config", config);
			setPluginField("defenceTracker", tracker);
			setPluginField("partyService", party);
			setPluginField("clientThread", clientThread);
			setPluginField("localSpecDetector", localDetector);
			eventBus.register(plugin);
		}

		private void localSpec(SpecialWeapon weapon, int hit)
		{
			tracker.queueLocal(weapon, MOLE_INDEX, hit, WORLD, "Alice", mole);
		}

		private void standardSpec(SpecialWeapon weapon, int hit)
		{
			SpecialCounterUpdate event = new SpecialCounterUpdate(MOLE_INDEX, weapon, hit, WORLD, 3);
			event.setMemberId(REMOTE_MEMBER_ID);
			plugin.onSpecialCounterUpdate(event);
		}

		private void receiveSpec(BpdSpecEvent packet)
		{
			Gson gson = new Gson();
			BpdSpecEvent received = gson.fromJson(gson.toJson(packet), BpdSpecEvent.class);
			received.setMemberId(REMOTE_MEMBER_ID);
			eventBus.post(received);
		}

		private void addMole()
		{
			mole = addNpc(MOLE_INDEX, "Giant Mole");
		}

		private NPC addNpc(int index, String name)
		{
			NPC npc = mock(NPC.class);
			when(npc.getName()).thenReturn(name);
			when(npc.getIndex()).thenReturn(index);
			when(npc.getId()).thenReturn(1);
			when(npc.getHealthRatio()).thenReturn(1);
			when(npc.getHealthScale()).thenReturn(1);
			when(npc.isDead()).thenReturn(false);
			when(npc.getWorldLocation()).thenReturn(new WorldPoint(3000, 3000, 0));
			when(npc.getLogicalHeight()).thenReturn(100);
			npcsByIndex.put(index, npc);
			return npc;
		}

		private void removeMole()
		{
			npcsByIndex.remove(MOLE_INDEX);
		}

		private void apply(SyncState state)
		{
			tracker.applySyncState(state, mole);
		}

		private void tick()
		{
			tick++;
			plugin.onGameTick(mock(GameTick.class));
		}

		private void sendSyncTo(Node target, SyncState state, long version)
		{
			target.receiveSync(state, version, 0L);
		}

		private void setPeer(Node peer)
		{
			this.peer = peer;
		}

		private void sendSyncToSelf(SyncState state, long version)
		{
			receiveSync(state, version, 0L);
		}

		private void receiveSync(SyncState state, long version, long partyId)
		{
			BpdDefenceSync packet = new BpdDefenceSync(
				WORLD, state, BpdDefenceSync.SCOPE_WORLD, 0, 3000, 3000, 0, 100, version);
			if (partyId != 0)
			{
				packet = BpdDefenceSyncValidationTest.withLegacyPartyId(packet, partyId);
			}
			packet.setMemberId(REMOTE_MEMBER_ID);
			plugin.onBpdDefenceSync(packet);
		}

		private void receivePacket(BpdDefenceSync packet)
		{
			plugin.onBpdDefenceSync(packet);
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
