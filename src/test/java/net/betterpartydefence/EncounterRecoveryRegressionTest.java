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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
import net.runelite.client.events.PartyChanged;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
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
	public void clearedReturningClientRecoversFromPeerBroadcast()
	{
		Node alice = new Node();
		Node bob = new Node();
		alice.addMole();
		bob.addMole();
		alice.apply(moleState(120));
		alice.setPeer(bob);
		bob.setPeer(alice);

		alice.tick();
		bob.tick();

		assertEquals("the returning client must recover from the peer's production broadcast",
			120L, bob.tracker.state().getCurrent());
		assertTrue("the recovery must use a real defence-sync message",
			alice.sentDefenceSyncs > 0);
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
		return new SyncState("Giant Mole", BossDefence.GIANT_MOLE, current, 0, 200,
			200, 200, 100, 100, 0, 0, false, false, false, true,
			Collections.singletonList(event));
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
		private final DefenceTracker tracker;
		private NPC mole;
		private Node peer;
		private int sentDefenceSyncs;
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

			when(config.syncWithOtherPartyDefenceUsers()).thenReturn(true);
			when(config.defenceAlwaysShow()).thenReturn(false);
			tracker = new DefenceTracker(client, config);
			doAnswer(invocation -> {
				Object message = invocation.getArgument(0);
				if (message instanceof BpdDefenceSync)
				{
					sentDefenceSyncs++;
					((BpdDefenceSync) message).setMemberId(REMOTE_MEMBER_ID);
					if (peer != null)
					{
						peer.receivePacket((BpdDefenceSync) message);
					}
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
		}

		private void addMole()
		{
			mole = mock(NPC.class);
			when(mole.getName()).thenReturn("Giant Mole");
			when(mole.getIndex()).thenReturn(MOLE_INDEX);
			when(mole.getId()).thenReturn(1);
			when(mole.getHealthRatio()).thenReturn(1);
			when(mole.getHealthScale()).thenReturn(1);
			when(mole.isDead()).thenReturn(false);
			when(mole.getWorldLocation()).thenReturn(new WorldPoint(3000, 3000, 0));
			when(mole.getLogicalHeight()).thenReturn(100);
			npcsByIndex.put(MOLE_INDEX, mole);
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
				WORLD, state, BpdDefenceSync.SCOPE_WORLD, 0, 3000, 3000, 0, 100, version)
				.withPartyId(partyId);
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
