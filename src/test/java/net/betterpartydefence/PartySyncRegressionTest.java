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
		when(party.getMemberById(0)).thenReturn(mock(PartyMember.class));
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
		when(party.getMemberById(0)).thenReturn(null);
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

	@Test public void enteringRaidDoesNotStartSharedClock() throws Exception
	{
		Method update=BetterPartyDefencePlugin.class.getDeclaredMethod("updateSharedRaidTickAnchor",int.class);
		update.setAccessible(true);
		update.invoke(plugin,100);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(1);
		update.invoke(plugin,101);
		when(client.getTickCount()).thenReturn(105);
		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER,81,1,301,"Alice");
		tracker.onGameTick();
		assertEquals(tracker.specHistory().get(0).getEventId(),tracker.specHistory().get(0).getTickAnchor());
		assertEquals(0,tracker.specHistory().get(0).getEncounterTick());
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

	private int fingerprint() throws Exception
	{
		Method method=BetterPartyDefencePlugin.class.getDeclaredMethod("instanceFingerprint",WorldView.class);
		method.setAccessible(true);
		return (int)method.invoke(null,client.getTopLevelWorldView());
	}

	private boolean scopeMatches(int type,int id) throws Exception
	{
		Method method=BetterPartyDefencePlugin.class.getDeclaredMethod("incomingScopeMatches",
			BpdDefenceSync.class,BossDefence.class,NPC.class);
		method.setAccessible(true);
		return (boolean)method.invoke(plugin,new BpdDefenceSync(301,state(189,a),type,id,0,0,0,100,1),
			BossDefence.TEKTON,null);
	}

	@Test public void peerInstanceFallbackAcceptedWhileOurRaidControllerRemains() throws Exception
	{
		when(client.getVarpValue(net.runelite.api.gameval.VarPlayerID.RAIDS_PARTY_GROUPHOLDER)).thenReturn(77);
		when(client.getTopLevelWorldView().isInstance()).thenReturn(true);
		assertTrue(scopeMatches(BpdDefenceSync.SCOPE_INSTANCE,fingerprint()));
		assertTrue(scopeMatches(BpdDefenceSync.SCOPE_COX,77));
	}

	@Test public void fallbackStillRejectsMismatchedInstanceAndController() throws Exception
	{
		when(client.getVarpValue(net.runelite.api.gameval.VarPlayerID.RAIDS_PARTY_GROUPHOLDER)).thenReturn(77);
		when(client.getTopLevelWorldView().isInstance()).thenReturn(true);
		assertFalse(scopeMatches(BpdDefenceSync.SCOPE_INSTANCE,fingerprint()+1));
		assertFalse(scopeMatches(BpdDefenceSync.SCOPE_COX,78));
		when(client.getTopLevelWorldView().isInstance()).thenReturn(false);
		assertFalse(scopeMatches(BpdDefenceSync.SCOPE_INSTANCE,fingerprint()));
	}

	@Test public void standardSpecAlsoAcceptsVerifiedInstanceFallback() throws Exception
	{
		when(client.getVarpValue(net.runelite.api.gameval.VarPlayerID.RAIDS_PARTY_GROUPHOLDER)).thenReturn(77);
		when(client.getTopLevelWorldView().isInstance()).thenReturn(true);
		Method remember=BetterPartyDefencePlugin.class.getDeclaredMethod("rememberSenderEncounterPresence",
			long.class,int.class,int.class,int.class,long.class);
		remember.setAccessible(true);
		Method match=BetterPartyDefencePlugin.class.getDeclaredMethod("standardPartySpecScopeMatches",long.class,int.class);
		match.setAccessible(true);
		remember.invoke(plugin,7L,301,BpdDefenceSync.SCOPE_INSTANCE,fingerprint(),System.currentTimeMillis());
		assertEquals(true,match.invoke(plugin,7L,301));
		assertEquals(false,match.invoke(plugin,7L,302));
		remember.invoke(plugin,7L,301,BpdDefenceSync.SCOPE_INSTANCE,fingerprint()+1,System.currentTimeMillis());
		assertEquals(false,match.invoke(plugin,7L,301));
	}

	@Test @SuppressWarnings("unchecked") public void pendingHitOnOtherBossDoesNotBlockCatchup() throws Exception
	{
		NPC other=mock(NPC.class);
		when(other.getName()).thenReturn("Corporeal Beast");
		when(other.getIndex()).thenReturn(82);
		when(other.getHealthRatio()).thenReturn(1);
		IndexedObjectSet<? extends NPC> npcs=client.getTopLevelWorldView().npcs();
		when(npcs.byIndex(82)).thenReturn(other);
		tracker.applySyncState(state(189,a),npc);
		tracker.queueLocal(SpecialWeapon.BANDOS_GODSWORD,82,20,301,"Bob");
		assertTrue(acceptable(state(139,a,b),1));
		assertTrue(tracker.hasUnprocessedSpecsForBoss(BossDefence.CORPOREAL_BEAST));
		assertFalse(tracker.hasUnprocessedSpecsForBoss(BossDefence.CHAOS_ELEMENTAL));
		tracker.applySyncState(state(139,a,b),npc);
		tracker.onGameTick();
		assertEquals("Bob",tracker.specHistory().get(0).getPlayerName());
		assertEquals(2,tracker.syncTargets().size());
	}

	@Test public void unresolvedActorStillDefersSnapshot() throws Exception
	{
		tracker.queueLocal(SpecialWeapon.BANDOS_GODSWORD,99,20,301,"Bob");
		assertFalse(acceptable(state(189,a),1));
	}

	private void raidChat(String text, net.runelite.api.ChatMessageType type)
	{
		net.runelite.api.events.ChatMessage event=mock(net.runelite.api.events.ChatMessage.class);
		when(event.getType()).thenReturn(type);
		when(event.getMessage()).thenReturn(text);
		plugin.onChatMessage(event);
	}

	private void localClockSpec(int tick)
	{
		when(client.getTickCount()).thenReturn(tick);
		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER,81,1,301,"Alice");
		tracker.onGameTick();
	}

	@Test public void actualRaidBegunNotificationSetsTickZero()
	{
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(1);
		when(client.getTickCount()).thenReturn(101);
		raidChat("<col=ff0000>The raid has begun!</col>",net.runelite.api.ChatMessageType.FRIENDSCHATNOTIFICATION);
		localClockSpec(105);
		assertTrue(tracker.specHistory().get(0).getTickAnchor().startsWith("raid-begun:v2:cox:"));
		assertEquals(4,tracker.specHistory().get(0).getEncounterTick());
	}

	@Test public void duplicateStartNotificationDoesNotMoveTickZero()
	{
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(1);
		when(client.getTickCount()).thenReturn(101);
		raidChat("The raid has begun!",net.runelite.api.ChatMessageType.FRIENDSCHATNOTIFICATION);
		when(client.getTickCount()).thenReturn(103);
		raidChat("The raid has begun!",net.runelite.api.ChatMessageType.FRIENDSCHATNOTIFICATION);
		localClockSpec(105);
		assertEquals(4,tracker.specHistory().get(0).getEncounterTick());
	}

	@Test public void publicChatCannotCreateRaidClock()
	{
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(1);
		raidChat("The raid has begun!",net.runelite.api.ChatMessageType.PUBLICCHAT);
		localClockSpec(105);
		assertEquals(tracker.specHistory().get(0).getEventId(),tracker.specHistory().get(0).getTickAnchor());
	}

	@Test public void notificationOutsideRaidCannotCreateRaidClock()
	{
		raidChat("The raid has begun!",net.runelite.api.ChatMessageType.FRIENDSCHATNOTIFICATION);
		localClockSpec(105);
		assertEquals(tracker.specHistory().get(0).getEventId(),tracker.specHistory().get(0).getTickAnchor());
	}

	@Test public void raidCompletionClearsClock()
	{
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(1);
		when(client.getTickCount()).thenReturn(101);
		raidChat("The raid has begun!",net.runelite.api.ChatMessageType.FRIENDSCHATNOTIFICATION);
		raidChat("Congratulations - your raid is complete!",net.runelite.api.ChatMessageType.FRIENDSCHATNOTIFICATION);
		localClockSpec(105);
		assertEquals(tracker.specHistory().get(0).getEventId(),tracker.specHistory().get(0).getTickAnchor());
	}

	@Test public void leavingRaidClearsClockBeforeNextRaidEntry() throws Exception
	{
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(1);
		when(client.getTickCount()).thenReturn(101);
		raidChat("The raid has begun!",net.runelite.api.ChatMessageType.FRIENDSCHATNOTIFICATION);
		Method update=BetterPartyDefencePlugin.class.getDeclaredMethod("updateSharedRaidTickAnchor",int.class);
		update.setAccessible(true);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(0);
		update.invoke(plugin,102);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(1);
		update.invoke(plugin,103);
		localClockSpec(105);
		assertEquals(tracker.specHistory().get(0).getEventId(),tracker.specHistory().get(0).getTickAnchor());
	}

	@Test public void tobAndToaPartyPresenceDoNotManufactureStart() throws Exception
	{
		when(client.getTopLevelWorldView().isInstance()).thenReturn(true);
		Method update=BetterPartyDefencePlugin.class.getDeclaredMethod("updateSharedRaidTickAnchor",int.class);
		update.setAccessible(true);
		update.invoke(plugin,100);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.TOB_CLIENT_PARTYSTATUS)).thenReturn(1);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.TOA_CLIENT_PARTYSTATUS)).thenReturn(1);
		when(client.getVarpValue(net.runelite.api.gameval.VarPlayerID.TOB_MYCONTROLLER)).thenReturn(77);
		update.invoke(plugin,101);
		localClockSpec(105);
		assertEquals(tracker.specHistory().get(0).getEventId(),tracker.specHistory().get(0).getTickAnchor());
	}

	@Test public void staleHeartbeatCannotOverwriteNewerPresence() throws Exception
	{
		when(client.getTopLevelWorldView().isInstance()).thenReturn(true);

		Method remember = BetterPartyDefencePlugin.class.getDeclaredMethod("rememberSenderEncounterPresence",
			long.class, int.class, int.class, int.class, long.class);
		remember.setAccessible(true);
		Method match = BetterPartyDefencePlugin.class.getDeclaredMethod("standardPartySpecScopeMatches",
			long.class, int.class);
		match.setAccessible(true);

		int newPartyScope = fingerprint();
		long now = System.currentTimeMillis();

		remember.invoke(plugin, 7L, 301, BpdDefenceSync.SCOPE_INSTANCE, newPartyScope, now);
		assertEquals(true, match.invoke(plugin, 7L, 301));

		remember.invoke(plugin, 7L, 301, BpdDefenceSync.SCOPE_INSTANCE, newPartyScope + 1, now - 1000L);

		assertEquals("stale previous-party heartbeat must not poison the new-party scope",
			true, match.invoke(plugin, 7L, 301));
	}

	@Test public void presenceCallbackFromPreviousPartyIsDiscarded() throws Exception
	{
		PartyService party = mock(PartyService.class);
		PartyMember sender = mock(PartyMember.class);
		when(party.isInParty()).thenReturn(true);
		when(party.getMemberById(7L)).thenReturn(sender);
		when(sender.getDisplayName()).thenReturn("Alice");

		ClientThread thread = mock(ClientThread.class);
		final java.util.List<Runnable> queued = new java.util.ArrayList<>();
		doAnswer(i -> { queued.add(i.getArgument(0)); return null; })
			.when(thread).invoke(any(Runnable.class));
		set("partyService", party);
		set("clientThread", thread);
		when(client.getTopLevelWorldView().isInstance()).thenReturn(true);

		BpdEncounterPresence old = new BpdEncounterPresence(301,
			BpdDefenceSync.SCOPE_INSTANCE, fingerprint() + 1);
		old.setMemberId(7L);
		plugin.onBpdEncounterPresence(old);
		assertEquals(1, queued.size());

		plugin.onPartyChanged(new PartyChanged(null, null));
		for (Runnable runnable : queued)
		{
			runnable.run();
		}

		Method match = BetterPartyDefencePlugin.class.getDeclaredMethod("standardPartySpecScopeMatches",
			long.class, int.class);
		match.setAccessible(true);
		assertEquals("presence callback from the previous party must not populate the new session",
			true, match.invoke(plugin, 7L, 301));
	}

	@Test public void oldPartyPresenceCannotPopulateNewPartyScope() throws Exception
	{
		PartyService party = mock(PartyService.class);
		PartyMember sender = mock(PartyMember.class);
		when(party.isInParty()).thenReturn(true);
		when(party.getMemberById(7L)).thenReturn(sender);
		when(party.getPartyId()).thenReturn(2L);
		when(sender.getDisplayName()).thenReturn("Alice");

		ClientThread thread = mock(ClientThread.class);
		doAnswer(i -> { ((Runnable)i.getArgument(0)).run(); return null; })
			.when(thread).invoke(any(Runnable.class));
		set("partyService", party);
		set("clientThread", thread);
		when(client.getTopLevelWorldView().isInstance()).thenReturn(true);

		BpdEncounterPresence old = new BpdEncounterPresence(301,
			BpdDefenceSync.SCOPE_INSTANCE, fingerprint() + 1).withPartyId(1L);
		old.setMemberId(7L);
		plugin.onBpdEncounterPresence(old);

		Method match = BetterPartyDefencePlugin.class.getDeclaredMethod("standardPartySpecScopeMatches",
			long.class, int.class);
		match.setAccessible(true);
		assertEquals("old-party presence must be rejected by current party identity",
			true, match.invoke(plugin, 7L, 301));
	}

	@Test public void currentPartyPresenceIsAccepted() throws Exception
	{
		PartyService party = mock(PartyService.class);
		PartyMember sender = mock(PartyMember.class);
		when(party.isInParty()).thenReturn(true);
		when(party.getMemberById(7L)).thenReturn(sender);
		when(party.getPartyId()).thenReturn(2L);
		when(sender.getDisplayName()).thenReturn("Alice");

		ClientThread thread = mock(ClientThread.class);
		doAnswer(i -> { ((Runnable)i.getArgument(0)).run(); return null; })
			.when(thread).invoke(any(Runnable.class));
		set("partyService", party);
		set("clientThread", thread);
		when(client.getTopLevelWorldView().isInstance()).thenReturn(true);

		BpdEncounterPresence current = new BpdEncounterPresence(301,
			BpdDefenceSync.SCOPE_INSTANCE, fingerprint()).withPartyId(2L);
		current.setMemberId(7L);
		plugin.onBpdEncounterPresence(current);

		Field presence = BetterPartyDefencePlugin.class.getDeclaredField("senderEncounterPresence");
		presence.setAccessible(true);
		java.util.Map<?, ?> map = (java.util.Map<?, ?>)presence.get(plugin);
		assertTrue("current-party heartbeat must be stored", map.containsKey(7L));
	}

	@Test public void oldPartySnapshotCannotEnterPendingQueue() throws Exception
	{
		PartyService party = mock(PartyService.class);
		PartyMember sender = mock(PartyMember.class);
		when(party.isInParty()).thenReturn(true);
		when(party.getMemberById(7L)).thenReturn(sender);
		when(party.getPartyId()).thenReturn(2L);

		ClientThread thread = mock(ClientThread.class);
		doAnswer(i -> { ((Runnable)i.getArgument(0)).run(); return null; })
			.when(thread).invoke(any(Runnable.class));
		set("partyService", party);
		set("clientThread", thread);
		when(client.getTopLevelWorldView().isInstance()).thenReturn(true);

		BpdDefenceSync old = new BpdDefenceSync(301, state(189, a),
			BpdDefenceSync.SCOPE_INSTANCE, fingerprint(), 0, 0, 0, 100, 1).withPartyId(1L);
		old.setMemberId(7L);
		plugin.onBpdDefenceSync(old);

		Field inbox = BetterPartyDefencePlugin.class.getDeclaredField("pendingSnapshots");
		inbox.setAccessible(true);
		assertTrue("old-party snapshot must be rejected before it reaches the queue",
			((java.util.Deque<?>)inbox.get(plugin)).isEmpty());
	}

	@Test public void briefPartyRejoinPreservesEncounterState() throws Exception
	{
		tracker.applySyncState(state(189, a), npc);
		plugin.onPartyChanged(new PartyChanged(null, null));

		assertEquals(189, tracker.state().getCurrent());
		assertEquals(1, tracker.specHistory().size());
	}

	@Test public void partyChangeClearsOldLogicalVersions() throws Exception
	{
		Field currentParty = BetterPartyDefencePlugin.class.getDeclaredField("currentPartyId");
		currentParty.setAccessible(true);
		currentParty.set(plugin, 1L);

		Field versionsField = BetterPartyDefencePlugin.class.getDeclaredField("syncStateVersions");
		versionsField.setAccessible(true);
		java.util.Map<BossDefence, Object> versions =
			(java.util.Map<BossDefence, Object>)versionsField.get(plugin);

		Class<?> versionClass = Class.forName("net.betterpartydefence.BetterPartyDefencePlugin$VersionedSyncState");
		java.lang.reflect.Constructor<?> ctor = versionClass.getDeclaredConstructor(long.class, int.class);
		ctor.setAccessible(true);
		versions.put(BossDefence.CHAOS_ELEMENTAL, ctor.newInstance(999L, 123));

		plugin.onPartyChanged(new PartyChanged(null, null));

		assertTrue("party change must not retain old logical versions", versions.isEmpty());
	}
}
