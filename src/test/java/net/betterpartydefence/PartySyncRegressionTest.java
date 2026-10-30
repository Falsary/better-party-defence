package net.betterpartydefence;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.betterpartydefence.DefenceTracker.SyncState;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.IndexedObjectSet;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.PartyMember;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PartySyncRegressionTest
{
	private BetterPartyDefencePlugin plugin;
	private DefenceTracker tracker;
	private Client client;
	private NPC npc;
	private final SpecHistoryEntry a = new SpecHistoryEntry("Alice",SpecialWeapon.DRAGON_WARHAMMER,1);
	private final SpecHistoryEntry b = new SpecHistoryEntry("Bob",SpecialWeapon.BANDOS_GODSWORD,50);

	private void set(String name,Object value) throws Exception
	{
		Field field=BetterPartyDefencePlugin.class.getDeclaredField(name);
		field.setAccessible(true); field.set(plugin,value);
	}

	@Before @SuppressWarnings("unchecked") public void setup() throws Exception
	{
		client=mock(Client.class);
		WorldView view=mock(WorldView.class);
		IndexedObjectSet<? extends NPC> npcs=mock(IndexedObjectSet.class);
		npc=mock(NPC.class);
		when(client.getWorld()).thenReturn(301);
		when(client.getTopLevelWorldView()).thenReturn(view);
		doReturn(npcs).when(view).npcs();
		when(npc.getName()).thenReturn("Chaos Elemental");
		when(npc.getIndex()).thenReturn(81);
		when(npc.getHealthRatio()).thenReturn(1);
		when(npcs.byIndex(81)).thenReturn(npc);
		when(npcs.iterator()).thenAnswer(i -> Collections.singletonList(npc).iterator());
		BetterPartyDefenceConfig config=mock(BetterPartyDefenceConfig.class);
		when(config.syncWithOtherPartyDefenceUsers()).thenReturn(true);
		tracker=new DefenceTracker(client,config);
		plugin=new BetterPartyDefencePlugin();
		set("client",client); set("config",config); set("defenceTracker",tracker);
	}

	private SyncState state(long current, SpecHistoryEntry... rows)
	{
		return new SyncState("Chaos Elemental",BossDefence.CHAOS_ELEMENTAL,current,0,270,
			270,270,270,270,70,70,false,false,false,true,Arrays.asList(rows));
	}

	private boolean acceptable(SyncState incoming,long version) throws Exception
	{
		Method method=BetterPartyDefencePlugin.class.getDeclaredMethod("incomingVersionIsAcceptable",
			BpdDefenceSync.class,SyncState.class,NPC.class);
		method.setAccessible(true);
		return (boolean)method.invoke(plugin,new BpdDefenceSync(301,incoming,0,0,0,0,0,100,version),incoming,npc);
	}

	@Test public void hugePeerVersionCannotRollBackAcceptedSpecs() throws Exception
	{
		tracker.applySyncState(state(139,a,b),npc);
		assertFalse(acceptable(state(189,a),Long.MAX_VALUE));
		assertEquals(139,tracker.state().getCurrent());
	}

	@Test public void smallerPeerVersionWithTruePrefixExtensionCanCatchUp() throws Exception
	{
		tracker.applySyncState(state(189,a),npc);
		assertTrue(acceptable(state(139,a,b),1));
	}

	@Test public void conflictingOrdersDoNotOverwriteOrAppendGuessedEvents() throws Exception
	{
		tracker.applySyncState(state(139,a,b),npc);
		assertFalse(acceptable(state(154,b,a),Long.MAX_VALUE));
		assertEquals(Arrays.asList(a,b),tracker.specHistory());
		assertEquals(139,tracker.state().getCurrent());
	}

	@Test public void snapshotWaitsForQueuedLocalEvidence() throws Exception
	{
		tracker.queue(SpecialWeapon.BANDOS_GODSWORD,81,50,301,"Bob");
		assertFalse(acceptable(state(189,a),1));
		tracker.onGameTick();
		assertEquals("Bob",tracker.specHistory().get(0).getPlayerName());
	}

	@Test public void websocketCallbackQueuesAndPartyChangeDiscardsSnapshot() throws Exception
	{
		PartyService party=mock(PartyService.class);
		PartyMember sender=mock(PartyMember.class);
		when(party.isInParty()).thenReturn(true);
		when(party.getMemberById(7)).thenReturn(sender);
		ClientThread thread=mock(ClientThread.class);
		doAnswer(i -> { ((Runnable)i.getArgument(0)).run(); return null; }).when(thread).invoke(any(Runnable.class));
		set("partyService",party); set("clientThread",thread);
		BpdDefenceSync packet=new BpdDefenceSync(301,state(189,a),0,0,0,0,0,100,1);
		packet.setMemberId(7);
		plugin.onBpdDefenceSync(packet);
		assertNull(tracker.state());
		Field inbox=BetterPartyDefencePlugin.class.getDeclaredField("pendingSnapshots");
		inbox.setAccessible(true);
		assertEquals(1,((java.util.Deque<?>)inbox.get(plugin)).size());
		plugin.onPartyChanged(new PartyChanged(null,null));
		assertTrue(((java.util.Deque<?>)inbox.get(plugin)).isEmpty());
	}
	@Test public void legacyExtensionCannotEraseIdentifiedLocalHistory() throws Exception
	{
		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER,81,1,301,"Alice");
		tracker.onGameTick();
		SyncState before = tracker.syncState();
		assertFalse(acceptable(state(139,a,b),Long.MAX_VALUE));
		assertEquals(before,tracker.syncState());
	}

	@Test public void applyingSnapshotDoesNotErasePendingObservation() throws Exception
	{
		tracker.queueLocal(SpecialWeapon.BANDOS_GODSWORD,81,50,301,"Bob");
		tracker.applySyncState(state(189,a),npc);
		assertTrue(tracker.hasUnprocessedSpecs());
		tracker.onGameTick();
		assertEquals(2, tracker.specHistory().size());
		assertEquals("Bob", tracker.specHistory().get(1).getPlayerName());
	}

	@Test public void adoptedRowsHaveOneTargetScopedEchoCreditAndRefreshAddsNone() throws Exception
	{
		when(client.getTickCount()).thenReturn(100);
		Method remember = BetterPartyDefencePlugin.class.getDeclaredMethod("rememberSyncedSpecs",
			SyncState.class,SyncState.class,NPC.class);
		remember.setAccessible(true);
		remember.invoke(plugin,state(139,a,b),state(189,a),npc);
		Class<?> keyClass = Class.forName("net.betterpartydefence.BetterPartyDefencePlugin$SyncedSpecKey");
		java.lang.reflect.Constructor<?> constructor = keyClass.getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		Field credits = BetterPartyDefencePlugin.class.getDeclaredField("recentSyncedSpecs");
		credits.setAccessible(true);
		@SuppressWarnings("unchecked") SnapshotEchoGuard<Object> guard = (SnapshotEchoGuard<Object>)credits.get(plugin);
		Object bob = constructor.newInstance(301,81,"bob",SpecialWeapon.BANDOS_GODSWORD,50);
		Object otherBoss = constructor.newInstance(301,82,"bob",SpecialWeapon.BANDOS_GODSWORD,50);
		assertFalse(guard.consume(otherBoss,100));
		assertTrue(guard.consume(bob,100));
		assertFalse(guard.consume(bob,100));
		remember.invoke(plugin,state(139,a,b),state(139,a,b),npc);
		assertFalse(guard.consume(bob,100));
		assertFalse(guard.consume(constructor.newInstance(301,81,"alice",SpecialWeapon.DRAGON_WARHAMMER,1),100));
	}

	@Test public void snapshotInboxWaitsUntilLocalQueueSettles() throws Exception
	{
		PartyService party=mock(PartyService.class);
		when(party.isInParty()).thenReturn(true);
		set("partyService",party);
		Field inbox=BetterPartyDefencePlugin.class.getDeclaredField("pendingSnapshots");
		inbox.setAccessible(true);
		@SuppressWarnings("unchecked") java.util.Deque<BpdDefenceSync> pending =
			(java.util.Deque<BpdDefenceSync>)inbox.get(plugin);
		pending.add(new BpdDefenceSync(301,state(189,a),0,0,0,0,0,100,1));
		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER,81,1,301,"Alice");
		Method reconcile=BetterPartyDefencePlugin.class.getDeclaredMethod("reconcileQueuedSnapshots");
		reconcile.setAccessible(true);
		reconcile.invoke(plugin);
		assertEquals(1,pending.size());
		tracker.onGameTick();
		reconcile.invoke(plugin); // Sender no longer present: discard rather than adopt.
		assertTrue(pending.isEmpty());
		assertEquals(1,tracker.specHistory().size());
	}

	@Test public void delayedCallbackFromPreviousPartyEpochIsDiscarded() throws Exception
	{
		PartyService party=mock(PartyService.class);
		when(party.isInParty()).thenReturn(true);
		when(party.getMemberById(7)).thenReturn(mock(PartyMember.class));
		ClientThread thread=mock(ClientThread.class);
		java.util.List<Runnable> callbacks=new java.util.ArrayList<>();
		doAnswer(i -> { callbacks.add(i.getArgument(0)); return null; }).when(thread).invoke(any(Runnable.class));
		set("partyService",party); set("clientThread",thread);
		BpdDefenceSync packet=new BpdDefenceSync(301,state(189,a),0,0,0,0,0,100,1);
		packet.setMemberId(7);
		plugin.onBpdDefenceSync(packet);
		plugin.onPartyChanged(new PartyChanged(null,null));
		callbacks.get(0).run();
		Field inbox=BetterPartyDefencePlugin.class.getDeclaredField("pendingSnapshots");
		inbox.setAccessible(true);
		assertTrue(((java.util.Deque<?>)inbox.get(plugin)).isEmpty());
	}

	@Test public void observedRaidEntrySetsRelativeTickOrigin() throws Exception
	{
		Method update=BetterPartyDefencePlugin.class.getDeclaredMethod("updateSharedRaidTickAnchor",int.class);
		update.setAccessible(true);
		update.invoke(plugin,100);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(1);
		update.invoke(plugin,101);
		when(client.getTickCount()).thenReturn(105);
		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER,81,1,301,"Alice");
		tracker.onGameTick();
		assertTrue(tracker.specHistory().get(0).getTickAnchor().startsWith("raid-start:"));
		assertEquals(4,tracker.specHistory().get(0).getEncounterTick());
	}

	@Test public void startingInsideRaidDoesNotInventAStartTick() throws Exception
	{
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(1);
		Method update=BetterPartyDefencePlugin.class.getDeclaredMethod("updateSharedRaidTickAnchor",int.class);
		update.setAccessible(true);
		update.invoke(plugin,100);
		when(client.getTickCount()).thenReturn(105);
		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER,81,1,301,"Alice");
		tracker.onGameTick();
		SpecHistoryEntry entry=tracker.specHistory().get(0);
		assertEquals(entry.getEventId(),entry.getTickAnchor());
		assertEquals(0,entry.getEncounterTick());
	}

	@Test public void ordinaryInstanceEntryDoesNotInventARaidBoundary() throws Exception
	{
		Method update=BetterPartyDefencePlugin.class.getDeclaredMethod("updateSharedRaidTickAnchor",int.class);
		update.setAccessible(true);
		update.invoke(plugin,100);
		when(client.getTopLevelWorldView().isInstance()).thenReturn(true);
		update.invoke(plugin,101);
		when(client.getTickCount()).thenReturn(105);
		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER,81,1,301,"Alice");
		tracker.onGameTick();
		SpecHistoryEntry entry=tracker.specHistory().get(0);
		assertEquals(entry.getEventId(),entry.getTickAnchor());
	}

	@Test public void retransmittedSuppressedEchoCannotApplyAfterCreditIsConsumed() throws Exception
	{
		PartyService party=mock(PartyService.class);
		PartyMember sender=mock(PartyMember.class);
		when(party.isInParty()).thenReturn(true);
		when(party.getMemberById(7)).thenReturn(sender);
		when(sender.getDisplayName()).thenReturn("Bob");
		ClientThread thread=mock(ClientThread.class);
		doAnswer(i -> { ((Runnable)i.getArgument(0)).run(); return null; }).when(thread).invoke(any(Runnable.class));
		set("partyService",party); set("clientThread",thread);
		tracker.applySyncState(state(139,a,b),npc);
		when(client.getTickCount()).thenReturn(100);
		Method remember=BetterPartyDefencePlugin.class.getDeclaredMethod("rememberSyncedSpecs",
			SyncState.class,SyncState.class,NPC.class);
		remember.setAccessible(true);
		remember.invoke(plugin,state(139,a,b),state(189,a),npc);
		net.runelite.client.plugins.specialcounter.SpecialCounterUpdate packet =
			new net.runelite.client.plugins.specialcounter.SpecialCounterUpdate(81,SpecialWeapon.BANDOS_GODSWORD,50,301,2);
		packet.setMemberId(7);
		plugin.onSpecialCounterUpdate(packet);
		plugin.onSpecialCounterUpdate(packet);
		assertFalse(tracker.hasUnprocessedSpecs());
		assertEquals(2,tracker.specHistory().size());
		when(client.getTickCount()).thenReturn(103);
		plugin.onSpecialCounterUpdate(packet);
		tracker.onGameTick();
		assertEquals(3,tracker.specHistory().size());
	}
}
