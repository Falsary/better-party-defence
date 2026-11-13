package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import net.betterpartydefence.DefenceTracker.SyncState;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/**
 * Small two-client characterization harness. It deliberately uses the production tracker and
 * {@link BpdDefenceSync} wire conversion rather than an idealized reducer.
 *
 * <p>Tests named "remain" or "cannot" pin current synchronization limitations. They are not
 * assertions that the limitation is desirable; a Stage 3B fix should replace those expectations
 * after the production behavior changes.
 */
public class PartySynchronizationCharacterizationTest
{
	private static final int WORLD = 301;
	private static final int NPC_INDEX = 1;

	@Test
	public void differentTickMixedObservationsDivergeBeforeSnapshotExchange()
	{
		ClientNode alice = new ClientNode();
		ClientNode bob = new ClientNode();

		// Combat order was DWH then BGS. Alice processes her local DWH first and Bob's
		// standard observation second; Bob processes the reverse network arrival order.
		alice.local(100, SpecialWeapon.DRAGON_WARHAMMER, 1, "Alice");
		alice.standard(101, SpecialWeapon.BANDOS_GODSWORD, 20, "Bob");
		bob.local(101, SpecialWeapon.BANDOS_GODSWORD, 20, "Bob");
		bob.standard(102, SpecialWeapon.DRAGON_WARHAMMER, 1, "Alice");

		// Current defect: the local row has an ID, but SpecialCounterUpdate does not. The
		// incomplete pair bypasses deterministic replay and follows arrival order.
		assertEquals(177L, alice.current());
		assertEquals(189L, bob.current());
		assertNotEquals("different-tick clients remain arrival dependent", alice.current(), bob.current());
		assertFalse(EncounterTimeline.complete(alice.tracker.specHistory()));
		assertFalse(EncounterTimeline.complete(bob.tracker.specHistory()));
	}

	@Test
	public void sameTickMixedObservationsDivergeBeforeSnapshotExchange()
	{
		ClientNode alice = new ClientNode();
		ClientNode bob = new ClientNode();

		alice.local(100, SpecialWeapon.DRAGON_WARHAMMER, 1, "Alice");
		alice.standard(100, SpecialWeapon.BANDOS_GODSWORD, 20, "Bob");
		bob.local(100, SpecialWeapon.BANDOS_GODSWORD, 20, "Bob");
		bob.standard(100, SpecialWeapon.DRAGON_WARHAMMER, 1, "Alice");

		// Without identity on the standard row the tracker cannot prove the attacks are
		// unordered, so the clients invent conflicting chronology from arrival order.
		assertEquals(177L, alice.current());
		assertEquals(189L, bob.current());
		assertNotEquals("same-tick clients invent conflicting chronology", alice.current(), bob.current());

		SyncState aliceBeforeExchange = alice.snapshot();
		SyncState bobBeforeExchange = bob.snapshot();
		assertTrue(alice.merge(bobBeforeExchange));
		assertTrue(bob.merge(aliceBeforeExchange));
		assertEquals(alice.current(), bob.current());
		assertEquals(alice.tracker.specHistory(), bob.tracker.specHistory());
	}

	@Test
	public void fullSnapshotExchangeRepairsMixedObservationDivergence()
	{
		ClientNode alice = new ClientNode();
		ClientNode bob = new ClientNode();

		alice.local(100, SpecialWeapon.DRAGON_WARHAMMER, 1, "Alice");
		alice.standard(101, SpecialWeapon.BANDOS_GODSWORD, 20, "Bob");
		bob.local(101, SpecialWeapon.BANDOS_GODSWORD, 20, "Bob");
		bob.standard(102, SpecialWeapon.DRAGON_WARHAMMER, 1, "Alice");
		assertNotEquals(alice.current(), bob.current());

		SyncState aliceBeforeExchange = alice.snapshot();
		SyncState bobBeforeExchange = bob.snapshot();
		assertTrue(alice.merge(bobBeforeExchange));
		assertTrue(bob.merge(aliceBeforeExchange));

		assertEquals(alice.current(), bob.current());
		assertEquals(alice.tracker.specHistory(), bob.tracker.specHistory());
		assertTrue(EncounterTimeline.complete(alice.tracker.specHistory()));
	}

	@Test
	public void identifiedDifferentTickEventsConvergeAfterWireExchange()
	{
		ClientNode alice = new ClientNode();
		ClientNode bob = new ClientNode();
		ClientNode seed = new ClientNode();
		seed.local(100, SpecialWeapon.DRAGON_WARHAMMER, 1, "Seed");

		alice.adopt(seed.snapshot());
		bob.adopt(seed.snapshot());
		alice.useSharedClock("serialized-test-clock", 1000);
		bob.useSharedClock("serialized-test-clock", 1000);

		alice.local(1001, SpecialWeapon.BANDOS_GODSWORD, 20, "Alice");
		bob.local(1002, SpecialWeapon.DRAGON_WARHAMMER, 1, "Bob");

		SyncState aliceBeforeExchange = alice.snapshot();
		SyncState bobBeforeExchange = bob.snapshot();

		assertTrue(alice.merge(bobBeforeExchange));
		assertTrue(bob.merge(aliceBeforeExchange));
		assertEquals(alice.current(), bob.current());
		assertEquals(alice.tracker.specHistory(), bob.tracker.specHistory());
		assertTrue(EncounterTimeline.complete(alice.tracker.specHistory()));
	}

	@Test
	public void identifiedSameTickEventsConvergeWithoutInventingAnOrder()
	{
		ClientNode alice = new ClientNode();
		ClientNode bob = new ClientNode();
		ClientNode seed = new ClientNode();
		seed.local(100, SpecialWeapon.DRAGON_WARHAMMER, 1, "Seed");

		alice.adopt(seed.snapshot());
		bob.adopt(seed.snapshot());
		alice.useSharedClock("same-tick-clock", 2000);
		bob.useSharedClock("same-tick-clock", 2000);

		alice.local(2001, SpecialWeapon.BANDOS_GODSWORD, 20, "Alice");
		bob.local(2001, SpecialWeapon.DRAGON_WARHAMMER, 1, "Bob");

		SyncState aliceBeforeExchange = alice.snapshot();
		SyncState bobBeforeExchange = bob.snapshot();

		assertTrue(alice.merge(bobBeforeExchange));
		assertTrue(bob.merge(aliceBeforeExchange));
		assertEquals(alice.current(), bob.current());
		assertEquals(alice.tracker.specHistory(), bob.tracker.specHistory());
	}

	@Test
	public void independentIdentifiedEventsWithoutSharedEvidenceCannotMerge()
	{
		ClientNode alice = new ClientNode();
		ClientNode bob = new ClientNode();

		// The IDs prove these are distinct events, but there is no shared row proving that
		// the two snapshots describe the same stat epoch.
		alice.local(100, SpecialWeapon.DRAGON_WARHAMMER, 1, "Alice");
		bob.local(200, SpecialWeapon.BANDOS_GODSWORD, 20, "Bob");

		SyncState aliceOnly = alice.snapshot();
		SyncState bobOnly = bob.snapshot();
		assertFalse(alice.merge(bobOnly));
		assertFalse(bob.merge(aliceOnly));
		assertEquals(1, alice.tracker.specHistory().size());
		assertEquals(1, bob.tracker.specHistory().size());
		assertNotEquals("partial observations remain independent", alice.current(), bob.current());
	}

	@Test
	public void repeatedSnapshotExchangeIsIdempotent()
	{
		ClientNode alice = new ClientNode();
		ClientNode bob = new ClientNode();
		ClientNode seed = new ClientNode();
		seed.local(100, SpecialWeapon.DRAGON_WARHAMMER, 1, "Seed");

		alice.adopt(seed.snapshot());
		bob.adopt(seed.snapshot());
		bob.local(300, SpecialWeapon.BANDOS_GODSWORD, 20, "Bob");
		SyncState bobSnapshot = bob.snapshot();

		assertTrue(alice.merge(bobSnapshot));
		long defendedOnce = alice.current();
		int historySize = alice.tracker.specHistory().size();

		assertFalse("the same identified snapshot must not merge twice", alice.merge(bobSnapshot));
		assertEquals(defendedOnce, alice.current());
		assertEquals(historySize, alice.tracker.specHistory().size());
	}

	@Test
	public void lateJoinerReconstructsFromSerializedSnapshot()
	{
		ClientNode source = new ClientNode();
		source.local(100, SpecialWeapon.DRAGON_WARHAMMER, 1, "Alice");
		source.local(101, SpecialWeapon.BANDOS_GODSWORD, 20, "Bob");

		BpdDefenceSync wire = source.wire(4L);
		com.google.gson.Gson gson = new com.google.gson.Gson();
		SyncState received = gson.fromJson(gson.toJson(wire), BpdDefenceSync.class).toSyncState();

		ClientNode lateJoiner = new ClientNode();
		assertTrue("an initial snapshot is acceptable without a prior local epoch",
			lateJoiner.snapshotAcceptable(wire, received));
		lateJoiner.acceptSnapshot(wire, received);

		assertEquals(source.current(), lateJoiner.current());
		assertEquals(source.tracker.specHistory(), lateJoiner.tracker.specHistory());
		assertEquals(source.snapshot(), lateJoiner.snapshot());
	}

	@Test
	public void snapshotWithIdenticalHistoryButTamperedDefenceIsRejected()
	{
		ClientNode node = new ClientNode();
		node.local(100, SpecialWeapon.DRAGON_WARHAMMER, 1, "Alice");
		SyncState honest = node.snapshot();
		SyncState tampered = new SyncState(
			honest.getBossName(), honest.getBossType(), honest.getCurrent() - 1,
			honest.getMin(), honest.getBase(), honest.getAttackLevel(), honest.getStrengthLevel(),
			honest.getMagicLevel(), honest.getMagicBaseLevel(), honest.getMagicDef(),
			honest.getMagicBaseDef(), honest.isMagicUsesDefence(), honest.isDemon(),
			honest.isAccursedApplied(), honest.isDrained(), honest.getHistory());
		BpdDefenceSync wire = new BpdDefenceSync(
			WORLD, tampered, BpdDefenceSync.SCOPE_WORLD, 0, 0, 0, 0, 100, 2L);

		assertFalse("an absolute value cannot override identical accepted evidence",
			node.snapshotAcceptable(wire, tampered));
		assertEquals(honest, node.snapshot());
	}

	@Test
	public void resetTimestampIsBoundToTheExactEncounterScope()
	{
		ClientNode node = new ClientNode();
		long beforeReset = System.currentTimeMillis() - 1;
		node.applyReset(BossDefence.CORPOREAL_BEAST, BpdDefenceSync.SCOPE_WORLD, 0);

		assertTrue("a delayed packet from the reset scope must be blocked",
			node.resetBlocked(BossDefence.CORPOREAL_BEAST, BpdDefenceSync.SCOPE_WORLD, 0, beforeReset));
		assertFalse("the barrier must not suppress another concrete scope",
			node.resetBlocked(BossDefence.CORPOREAL_BEAST, BpdDefenceSync.SCOPE_INSTANCE, 77, beforeReset));
	}

	@Test
	public void partySwitchRejectsOldNonzeroPartyIdButRetainsLegacyZero()
	{
		ClientNode node = new ClientNode();
		PartyService party = mock(PartyService.class);
		when(party.isInParty()).thenReturn(true);
		when(party.getPartyId()).thenReturn(2L);
		node.useParty(party, 2L);

		assertFalse(node.packetBelongsToCurrentParty(1L));
		assertTrue(node.packetBelongsToCurrentParty(2L));
		assertTrue("legacy senders intentionally retain the compatibility path",
			node.packetBelongsToCurrentParty(0L));
	}

	private static final class ClientNode
	{
		private final Client client;
		private final NPC npc;
		private final DefenceTracker tracker;
		private final BetterPartyDefencePlugin plugin;
		private int tick;

		private ClientNode()
		{
			client = mock(Client.class);
			WorldView worldView = mock(WorldView.class);
			@SuppressWarnings("unchecked")
			IndexedObjectSet<? extends NPC> npcs = mock(IndexedObjectSet.class);
			npc = mock(NPC.class);

			when(client.getWorld()).thenReturn(WORLD);
			when(client.getTickCount()).thenAnswer(invocation -> tick);
			when(client.getTopLevelWorldView()).thenReturn(worldView);
			doReturn(npcs).when(worldView).npcs();
			when(npcs.byIndex(NPC_INDEX)).thenReturn(npc);
			when(npcs.iterator()).thenAnswer(invocation -> Collections.singletonList(npc).iterator());
			when(npc.getName()).thenReturn("Corporeal Beast");
			when(npc.getIndex()).thenReturn(NPC_INDEX);
			when(npc.getId()).thenReturn(-1);
			when(npc.getHealthRatio()).thenReturn(1);
			when(npc.getHealthScale()).thenReturn(1);

			tracker = new DefenceTracker(client, mock(BetterPartyDefenceConfig.class));
			plugin = new BetterPartyDefencePlugin();
			setPluginField("client", client);
			setPluginField("defenceTracker", tracker);
		}

		private void local(int atTick, SpecialWeapon weapon, int hit, String player)
		{
			tick = atTick;
			tracker.queueLocal(weapon, NPC_INDEX, hit, WORLD, player, npc);
			tracker.onGameTick();
		}

		private void standard(int atTick, SpecialWeapon weapon, int hit, String player)
		{
			tick = atTick;
			tracker.queue(weapon, NPC_INDEX, hit, WORLD, player, npc);
			tracker.onGameTick();
		}

		private void useSharedClock(String anchor, int atTick)
		{
			tick = atTick;
			tracker.setSharedTickAnchor(anchor, atTick);
		}

		private long current()
		{
			return tracker.state().getCurrent();
		}

		private SyncState snapshot()
		{
			return tracker.syncState();
		}

		private BpdDefenceSync wire(long version)
		{
			return new BpdDefenceSync(WORLD, snapshot(), BpdDefenceSync.SCOPE_WORLD, 0,
				0, 0, 0, 100, version);
		}

		private void adopt(SyncState state)
		{
			tracker.applySyncState(state, npc);
		}

		private boolean merge(SyncState state)
		{
			return tracker.mergeEventSyncState(state, npc);
		}

		private boolean snapshotAcceptable(BpdDefenceSync wire, SyncState state)
		{
			try
			{
				Method method = BetterPartyDefencePlugin.class.getDeclaredMethod(
					"incomingVersionIsAcceptable", BpdDefenceSync.class, SyncState.class, NPC.class);
				method.setAccessible(true);
				return (boolean) method.invoke(plugin, wire, state, npc);
			}
			catch (ReflectiveOperationException ex)
			{
				throw new AssertionError(ex);
			}
		}

		private void acceptSnapshot(BpdDefenceSync wire, SyncState state)
		{
			try
			{
				Method method = BetterPartyDefencePlugin.class.getDeclaredMethod(
					"applyAcceptedSync", BpdDefenceSync.class, SyncState.class, NPC.class);
				method.setAccessible(true);
				method.invoke(plugin, wire, state, npc);
			}
			catch (ReflectiveOperationException ex)
			{
				throw new AssertionError(ex);
			}
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

		private void applyReset(BossDefence boss, int scopeType, int scopeId)
		{
			try
			{
				Class<?> scopeClass = Class.forName(
					"net.betterpartydefence.BetterPartyDefencePlugin$SyncScope");
				Constructor<?> constructor = scopeClass.getDeclaredConstructor(int.class, int.class);
				constructor.setAccessible(true);
				Object scope = constructor.newInstance(scopeType, scopeId);
				Method method = BetterPartyDefencePlugin.class.getDeclaredMethod(
					"applyEncounterReset", BossDefence.class, scopeClass, String.class, boolean.class);
				method.setAccessible(true);
				method.invoke(plugin, boss, scope, "characterization reset", true);
			}
			catch (ReflectiveOperationException ex)
			{
				throw new AssertionError(ex);
			}
		}

		private boolean resetBlocked(BossDefence boss, int scopeType, int scopeId, long observedAt)
		{
			try
			{
				Method method = BetterPartyDefencePlugin.class.getDeclaredMethod(
					"observationPredatesReset", BossDefence.class, int.class, int.class, long.class);
				method.setAccessible(true);
				return (boolean) method.invoke(plugin, boss, scopeType, scopeId, observedAt);
			}
			catch (ReflectiveOperationException ex)
			{
				throw new AssertionError(ex);
			}
		}

		private void useParty(PartyService party, long currentPartyId)
		{
			setPluginField("partyService", party);
			setPluginField("currentPartyId", currentPartyId);
		}

		private boolean packetBelongsToCurrentParty(long packetPartyId)
		{
			try
			{
				Method method = BetterPartyDefencePlugin.class.getDeclaredMethod(
					"partyMessageBelongsToCurrentParty", long.class);
				method.setAccessible(true);
				return (boolean) method.invoke(plugin, packetPartyId);
			}
			catch (ReflectiveOperationException ex)
			{
				throw new AssertionError(ex);
			}
		}
	}
}
