package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
		assertFalse("the post-reset barrier must reject the old snapshot",
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
				else if (message instanceof BpdBossPresence)
				{
					sentBossPresenceRequests++;
					((BpdBossPresence) message).setMemberId(REMOTE_MEMBER_ID);
					if (peer != null)
					{
						peer.plugin.onBpdBossPresence((BpdBossPresence) message);
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
