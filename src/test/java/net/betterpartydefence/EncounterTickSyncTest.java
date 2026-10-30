package net.betterpartydefence;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.util.*;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.betterpartydefence.DefenceTracker.SyncState;
import net.runelite.api.*;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Before;
import org.junit.Test;

public class EncounterTickSyncTest
{
    private Client client;
    private NPC npc;
    private DefenceTracker tracker;

    @Before @SuppressWarnings("unchecked") public void setup()
    {
        client = mock(Client.class);
        WorldView world = mock(WorldView.class);
        IndexedObjectSet<? extends NPC> npcs = mock(IndexedObjectSet.class);
        npc = mock(NPC.class);
        when(client.getWorld()).thenReturn(301);
        when(client.getTopLevelWorldView()).thenReturn(world);
        doReturn(npcs).when(world).npcs();
        when(npcs.byIndex(1)).thenReturn(npc);
        when(npcs.iterator()).thenAnswer(i -> Collections.singletonList(npc).iterator());
        when(npc.getName()).thenReturn("Corporeal Beast");
        when(npc.getIndex()).thenReturn(1);
        when(npc.getId()).thenReturn(-1);
        when(npc.getHealthRatio()).thenReturn(1);
        tracker = new DefenceTracker(client, mock(BetterPartyDefenceConfig.class));
    }

    private void local(int tick, SpecialWeapon weapon, int hit)
    {
        when(client.getTickCount()).thenReturn(tick);
        tracker.queueLocal(weapon, 1, hit, 301, "Alice");
        tracker.onGameTick();
    }

    private SyncState state(List<SpecHistoryEntry> events)
    {
        SyncState s = tracker.syncState();
        return new SyncState(s.getBossName(), s.getBossType(), s.getCurrent(), s.getMin(), s.getBase(),
            s.getAttackLevel(), s.getStrengthLevel(), s.getMagicLevel(), s.getMagicBaseLevel(),
            s.getMagicDef(), s.getMagicBaseDef(), s.isMagicUsesDefence(), s.isDemon(),
            s.isAccursedApplied(), s.isDrained(), events);
    }

    @Test public void encounterStartsAtObservedSpecNotLoginOrProcessingTick()
    {
        when(client.getTickCount()).thenReturn(5000);
        tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, 1, 1, 301, "Alice");
        when(client.getTickCount()).thenReturn(5003);
        tracker.onGameTick();
        local(5007, SpecialWeapon.DRAGON_WARHAMMER, 1);
        List<SpecHistoryEntry> h = tracker.specHistory();
        assertEquals(0, h.get(0).getEncounterTick());
        assertEquals(7, h.get(1).getEncounterTick());
        assertEquals(h.get(0).getEventId(), h.get(1).getTickAnchor());
        assertNotEquals(h.get(0).getEventId(), h.get(1).getEventId());
    }

    @Test public void ambiguousSameTickUsesSmallestDrainInEitherArrivalOrder()
    {
        local(100, SpecialWeapon.DRAGON_WARHAMMER, 1);
        local(100, SpecialWeapon.BANDOS_GODSWORD, 20);
        // Corp starts at 310. BGS first: (310 - 40) - floor(270 * .3) = 189.
        assertEquals(189, tracker.state().getCurrent());
        tracker.reset();
        local(200, SpecialWeapon.BANDOS_GODSWORD, 20);
        local(200, SpecialWeapon.DRAGON_WARHAMMER, 1);
        assertEquals(189, tracker.state().getCurrent());
    }

    @Test public void differentTicksPreserveActualOrder()
    {
        local(100, SpecialWeapon.DRAGON_WARHAMMER, 1);
        local(101, SpecialWeapon.BANDOS_GODSWORD, 20);
        assertEquals(177, tracker.state().getCurrent());
    }

    @Test public void recoveryIsIdempotentAndPreservesDistinctIdenticalSpecs()
    {
        local(100, SpecialWeapon.DRAGON_WARHAMMER, 1);
        SpecHistoryEntry root = tracker.specHistory().get(0);
        SpecHistoryEntry next = new SpecHistoryEntry("Alice", SpecialWeapon.DRAGON_WARHAMMER, 1,
            "next", root.getEventId(), 4);
        SyncState incoming = state(Arrays.asList(root, next));
        assertTrue(tracker.mergeEventSyncState(incoming, npc));
        assertEquals(152, tracker.state().getCurrent());
        assertFalse(tracker.mergeEventSyncState(incoming, npc));
        assertEquals(2, tracker.specHistory().size());
    }

    @Test public void receiptDoesNotAnchorAnUnrelatedClientClock()
    {
        local(10000, SpecialWeapon.DRAGON_WARHAMMER, 1);
        SpecHistoryEntry root = tracker.specHistory().get(0);
        SpecHistoryEntry peer = new SpecHistoryEntry("Bob", SpecialWeapon.BANDOS_GODSWORD, 20,
            "peer", "peer", 0);
        assertTrue(tracker.mergeEventSyncState(state(Arrays.asList(root, peer)), npc));
        assertEquals(189, tracker.state().getCurrent());
        assertFalse(EncounterTimeline.before(root, peer));
        assertFalse(EncounterTimeline.before(peer, root));
    }

    @Test public void standardEchoCanAcquireUniqueIdentityWithoutDoubleDrain()
    {
        local(100, SpecialWeapon.DRAGON_WARHAMMER, 1);
        SpecHistoryEntry root = tracker.specHistory().get(0);
        tracker.queue(SpecialWeapon.BANDOS_GODSWORD, 1, 20, 301, "Bob");
        tracker.onGameTick();
        SpecHistoryEntry peer = new SpecHistoryEntry("Bob", SpecialWeapon.BANDOS_GODSWORD, 20,
            "peer", "peer", 0);
        assertTrue(tracker.mergeEventSyncState(state(Arrays.asList(root, peer)), npc));
        assertEquals(2, tracker.specHistory().size());
        assertEquals(189, tracker.state().getCurrent());
    }

    @Test public void malformedConflictingOrUnanchoredEventsAreNotReplayed()
    {
        local(100, SpecialWeapon.DRAGON_WARHAMMER, 1);
        SpecHistoryEntry root = tracker.specHistory().get(0);
        SpecHistoryEntry bad = new SpecHistoryEntry("Alice", root.getWeapon(), 2,
            root.getEventId(), root.getTickAnchor(), 0);
        assertNull(EncounterTimeline.union(Arrays.asList(root), Arrays.asList(bad)));
        SpecHistoryEntry orphan = new SpecHistoryEntry("Bob", root.getWeapon(), 1, "b", null, 5);
        assertFalse(EncounterTimeline.complete(Arrays.asList(root, orphan)));
        assertEquals(217, tracker.state().getCurrent());
    }

    @Test public void versionedWireRoundTripAndFutureExtensionFallback() throws Exception
    {
        local(100, SpecialWeapon.DRAGON_WARHAMMER, 1);
        BpdDefenceSync wire = new BpdDefenceSync(301, tracker.syncState(), 0, 0, 1, 1, 0, 100, 9);
        assertEquals(1, wire.getProtocolVersion());
        assertEquals(tracker.specHistory(), wire.toSyncState().getHistory());
        Field version = BpdDefenceSync.class.getDeclaredField("eventVersion");
        version.setAccessible(true);
        version.set(wire, 999);
        assertNull(wire.toSyncState().getHistory().get(0).getEventId());
        assertEquals(tracker.state().getCurrent(), wire.toSyncState().getCurrent());
    }

    @Test public void lateJoinPreservesOriginTicksAndCreatesSeparateLocalAnchor()
    {
        local(100, SpecialWeapon.DRAGON_WARHAMMER, 1);
        SyncState snapshot = tracker.syncState();
        tracker.reset();
        tracker.applySyncState(snapshot, npc);
        local(40000, SpecialWeapon.BANDOS_GODSWORD, 20);
        assertEquals(2, tracker.specHistory().size());
        assertTrue(tracker.specHistory().contains(snapshot.getHistory().get(0)));
        assertEquals(189, tracker.state().getCurrent());
        for (SpecHistoryEntry e : tracker.specHistory()) { assertEquals(0, e.getEncounterTick()); }
    }

    @Test public void resetCannotReusePreviousOrigin()
    {
        local(100, SpecialWeapon.DRAGON_WARHAMMER, 1);
        String old = tracker.specHistory().get(0).getTickAnchor();
        tracker.reset();
        local(101, SpecialWeapon.DRAGON_WARHAMMER, 1);
        assertNotEquals(old, tracker.specHistory().get(0).getTickAnchor());
        assertEquals(0, tracker.specHistory().get(0).getEncounterTick());
    }

    @Test public void replayBudgetExhaustionReturnsSafeBaseline()
    {
        List<SpecHistoryEntry> events = new ArrayList<>();
        for (int i = 0; i < 10; i++)
        {
            events.add(new SpecHistoryEntry("P" + i, SpecialWeapon.BANDOS_GODSWORD, 1,
                "e" + i, "e" + i, 0));
        }
        Integer result = EncounterTimeline.replay(events, 100, new EncounterTimeline.Replay<Integer>()
        {
            public Integer apply(Integer value, SpecHistoryEntry e) { return value - 1; }
            public Integer conservative(Integer a, Integer b) { return Math.max(a, b); }
        });
        assertEquals(Integer.valueOf(100), result);
    }

    @Test public void repeatedLegacyPayloadCannotBeAssignedArbitraryIds()
    {
        SpecHistoryEntry legacy = new SpecHistoryEntry("Bob", SpecialWeapon.BANDOS_GODSWORD, 20);
        SpecHistoryEntry a = new SpecHistoryEntry("Bob", legacy.getWeapon(), 20, "a", "a", 0);
        SpecHistoryEntry b = new SpecHistoryEntry("Bob", legacy.getWeapon(), 20, "b", "a", 5);
        assertNull(EncounterTimeline.union(Arrays.asList(legacy, legacy), Arrays.asList(a, b)));
    }

    @Test public void phaseResetStartsNewStatEpoch()
    {
        when(npc.getName()).thenReturn("Sotetseg");
        when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.TOB_CLIENT_WAVEPROGRESS_TYPE)).thenReturn(1);
        local(100, SpecialWeapon.DRAGON_WARHAMMER, 1);
        String original = tracker.specHistory().get(0).getTickAnchor();
        when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.TOB_CLIENT_WAVEPROGRESS_TYPE)).thenReturn(2);
        tracker.onGameTick();
        assertTrue(tracker.specHistory().isEmpty());
        local(110, SpecialWeapon.DRAGON_WARHAMMER, 1);
        assertNotEquals(original, tracker.specHistory().get(0).getTickAnchor());
        assertEquals(0, tracker.specHistory().get(0).getEncounterTick());
    }

    @Test public void lowerSnapshotVersionStillRecoversUniqueEvent() throws Exception
    {
        local(100, SpecialWeapon.DRAGON_WARHAMMER, 1);
        SpecHistoryEntry root = tracker.specHistory().get(0);
        BetterPartyDefencePlugin plugin = new BetterPartyDefencePlugin();
        Field trackerField = BetterPartyDefencePlugin.class.getDeclaredField("defenceTracker");
        trackerField.setAccessible(true);
        trackerField.set(plugin, tracker);
        Field clientField = BetterPartyDefencePlugin.class.getDeclaredField("client");
        clientField.setAccessible(true);
        clientField.set(plugin, client);
        java.lang.reflect.Method accept = BetterPartyDefencePlugin.class.getDeclaredMethod(
            "incomingVersionIsAcceptable", BpdDefenceSync.class, SyncState.class, NPC.class);
        accept.setAccessible(true);
        SpecHistoryEntry later = new SpecHistoryEntry("Alice", SpecialWeapon.BANDOS_GODSWORD, 20,
            "later", root.getEventId(), 5);
        SyncState incoming = state(Arrays.asList(root, later));
        BpdDefenceSync wire = new BpdDefenceSync(301, incoming, 0, 0, 1, 1, 0, 100, 1);
        // Establish a strictly greater local logical version.
        java.lang.reflect.Method observe = BetterPartyDefencePlugin.class.getDeclaredMethod(
            "observeLocalStateVersion", SyncState.class);
        observe.setAccessible(true);
        observe.invoke(plugin, tracker.syncState());
        local(102, SpecialWeapon.DRAGON_WARHAMMER, 1);
        observe.invoke(plugin, tracker.syncState());
        assertEquals(false, accept.invoke(plugin, wire, incoming, npc)); // merged, not snapshot overwrite
        assertEquals(3, tracker.specHistory().size());
        assertTrue(tracker.specHistory().contains(later));
        assertEquals(112, tracker.state().getCurrent());
    }

    @Test public void newerSnapshotCannotEraseLocalIdentifiedSpec() throws Exception
    {
        local(100, SpecialWeapon.DRAGON_WARHAMMER, 1);
        SyncState earlier = tracker.syncState();
        local(105, SpecialWeapon.DRAGON_WARHAMMER, 1);
        BetterPartyDefencePlugin plugin = new BetterPartyDefencePlugin();
        Field f = BetterPartyDefencePlugin.class.getDeclaredField("defenceTracker");
        f.setAccessible(true);
        f.set(plugin, tracker);
        java.lang.reflect.Method accept = BetterPartyDefencePlugin.class.getDeclaredMethod(
            "incomingVersionIsAcceptable", BpdDefenceSync.class, SyncState.class, NPC.class);
        accept.setAccessible(true);
        BpdDefenceSync wire = new BpdDefenceSync(301, earlier, 0, 0, 1, 1, 0, 100, 999);
        assertEquals(false, accept.invoke(plugin, wire, earlier, npc));
        assertEquals(2, tracker.specHistory().size());
        assertEquals(152, tracker.state().getCurrent());
    }

    @Test public void twoIndependentClocksConvergeThroughStandardEchoIdentityPromotion()
    {
        local(9000, SpecialWeapon.DRAGON_WARHAMMER, 1);
        DefenceTracker peer = new DefenceTracker(client, mock(BetterPartyDefenceConfig.class));
        when(client.getTickCount()).thenReturn(50);
        peer.queueLocal(SpecialWeapon.BANDOS_GODSWORD, 1, 20, 301, "Bob");
        peer.onGameTick();
        tracker.queue(SpecialWeapon.BANDOS_GODSWORD, 1, 20, 301, "Bob");
        tracker.onGameTick();
        peer.queue(SpecialWeapon.DRAGON_WARHAMMER, 1, 1, 301, "Alice");
        peer.onGameTick();
        SyncState alice = tracker.syncState();
        SyncState bob = peer.syncState();
        assertTrue(tracker.mergeEventSyncState(bob, npc));
        assertTrue(peer.mergeEventSyncState(alice, npc));
        assertEquals(189, tracker.state().getCurrent());
        assertEquals(tracker.syncState(), peer.syncState());
        assertTrue(EncounterTimeline.complete(tracker.specHistory()));
    }

    @Test public void sharedRaidStartAlignsDifferentLocalCountersAndOrdersSpecs()
    {
        String sharedAnchor = "raid-start:1:731";
        tracker.setSharedTickAnchor(sharedAnchor, 5000);
        local(5007, SpecialWeapon.DRAGON_WARHAMMER, 1);

        DefenceTracker peer = new DefenceTracker(client, mock(BetterPartyDefenceConfig.class));
        peer.setSharedTickAnchor(sharedAnchor, 120);
        when(client.getTickCount()).thenReturn(126);
        peer.queueLocal(SpecialWeapon.BANDOS_GODSWORD, 1, 20, 301, "Bob");
        peer.onGameTick();

        assertEquals(7, tracker.specHistory().get(0).getEncounterTick());
        assertEquals(6, peer.specHistory().get(0).getEncounterTick());
        assertEquals(sharedAnchor, tracker.specHistory().get(0).getTickAnchor());
        assertEquals(sharedAnchor, peer.specHistory().get(0).getTickAnchor());
        // Standard spec messages establish the shared target epoch; the raid clock alone
        // must not authorize merging histories from different boss attempts.
        tracker.queue(SpecialWeapon.BANDOS_GODSWORD, 1, 20, 301, "Bob");
        tracker.onGameTick();
        peer.queue(SpecialWeapon.DRAGON_WARHAMMER, 1, 1, 301, "Alice");
        peer.onGameTick();
        assertTrue(tracker.mergeEventSyncState(peer.syncState(), npc));
        assertTrue(peer.mergeEventSyncState(tracker.syncState(), npc));
        assertEquals(189, tracker.state().getCurrent());
        assertEquals(tracker.syncState(), peer.syncState());
    }

    @Test public void sharedRaidClockAloneCannotMergeSeparateBossAttempts()
    {
        String sharedAnchor = "raid-start:1:731";
        tracker.setSharedTickAnchor(sharedAnchor, 5000);
        local(5007, SpecialWeapon.DRAGON_WARHAMMER, 1);
        SyncState original = tracker.syncState();

        DefenceTracker peer = new DefenceTracker(client, mock(BetterPartyDefenceConfig.class));
        peer.setSharedTickAnchor(sharedAnchor, 120);
        when(client.getTickCount()).thenReturn(126);
        peer.queueLocal(SpecialWeapon.BANDOS_GODSWORD, 1, 20, 301, "Bob");
        peer.onGameTick();

        assertFalse(tracker.mergeEventSyncState(peer.syncState(), npc));
        assertEquals(original, tracker.syncState());
        assertEquals(217, tracker.state().getCurrent());
    }

    @Test public void specOnBoundaryTickUsesAnchorDiscoveredAtGameTick()
    {
        when(client.getTickCount()).thenReturn(5000);
        tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, 1, 1, 301, "Alice");
        tracker.setSharedTickAnchor("raid-start:3:731", 5000);
        tracker.onGameTick();
        assertEquals("raid-start:3:731", tracker.specHistory().get(0).getTickAnchor());
        assertEquals(0, tracker.specHistory().get(0).getEncounterTick());
    }

    @Test public void timerBoundaryTickUsesRaidElapsedBase()
    {
        when(client.getTickCount()).thenReturn(5000);
        tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, 1, 1, 301, "Alice");
        tracker.setSharedTickAnchor("raid-time:v1:tfa:c77", 5000, 50);
        tracker.onGameTick();
        assertEquals("raid-time:v1:tfa:c77", tracker.specHistory().get(0).getTickAnchor());
        assertEquals(50L, tracker.specHistory().get(0).getEncounterTick());
    }

    @Test public void raidTimeAnchorAlignsDifferentLoginCounters()
    {
        String anchor = "raid-time:v1:toa:i731";
        tracker.setSharedTickAnchor(anchor, 5000, 100);
        local(5007, SpecialWeapon.DRAGON_WARHAMMER, 1);

        DefenceTracker peer = new DefenceTracker(client, mock(BetterPartyDefenceConfig.class));
        peer.setSharedTickAnchor(anchor, 120, 100);
        when(client.getTickCount()).thenReturn(126);
        peer.queueLocal(SpecialWeapon.BANDOS_GODSWORD, 1, 20, 301, "Bob");
        peer.onGameTick();

        SpecHistoryEntry first = tracker.specHistory().get(0);
        SpecHistoryEntry second = peer.specHistory().get(0);
        assertEquals(107L, first.getEncounterTick());
        assertEquals(106L, second.getEncounterTick());
        assertEquals(anchor, first.getTickAnchor());
        assertEquals(anchor, second.getTickAnchor());
        assertTrue(EncounterTimeline.before(second, first));
        assertFalse(EncounterTimeline.before(first, second));
    }

    @Test public void sameRaidElapsedTickIsASameTickNotAnArrivalOrder()
    {
        String anchor = "raid-time:v1:toa:i731";
        tracker.setSharedTickAnchor(anchor, 5000, 100);
        local(5007, SpecialWeapon.DRAGON_WARHAMMER, 1);

        DefenceTracker peer = new DefenceTracker(client, mock(BetterPartyDefenceConfig.class));
        peer.setSharedTickAnchor(anchor, 120, 100);
        when(client.getTickCount()).thenReturn(127);
        peer.queueLocal(SpecialWeapon.BANDOS_GODSWORD, 1, 20, 301, "Bob");
        peer.onGameTick();

        SpecHistoryEntry first = tracker.specHistory().get(0);
        SpecHistoryEntry second = peer.specHistory().get(0);
        assertEquals(107L, first.getEncounterTick());
        assertEquals(107L, second.getEncounterTick());
        assertFalse(EncounterTimeline.before(first, second));
        assertFalse(EncounterTimeline.before(second, first));
    }

    @Test public void differentRaidTimeDomainsRemainUnordered()
    {
        tracker.setSharedTickAnchor("raid-time:v1:toa:i1", 1000, 0);
        local(1005, SpecialWeapon.DRAGON_WARHAMMER, 1);
        SpecHistoryEntry local = tracker.specHistory().get(0);
        SpecHistoryEntry other = new SpecHistoryEntry("Bob", SpecialWeapon.BANDOS_GODSWORD, 20,
            "other", "raid-time:v1:toa:i2", 0);

        assertFalse(EncounterTimeline.before(local, other));
        assertFalse(EncounterTimeline.before(other, local));
    }

    @Test public void raidTimeClockAloneCannotMergeSeparateBossAttempts()
    {
        String anchor = "raid-time:v1:toa:i731";
        tracker.setSharedTickAnchor(anchor, 5000, 100);
        local(5007, SpecialWeapon.DRAGON_WARHAMMER, 1);
        SyncState original = tracker.syncState();

        DefenceTracker peer = new DefenceTracker(client, mock(BetterPartyDefenceConfig.class));
        peer.setSharedTickAnchor(anchor, 120, 100);
        when(client.getTickCount()).thenReturn(127);
        peer.queueLocal(SpecialWeapon.BANDOS_GODSWORD, 1, 20, 301, "Bob");
        peer.onGameTick();

        assertFalse(tracker.mergeEventSyncState(peer.syncState(), npc));
        assertEquals(original, tracker.syncState());
    }

    @Test public void preRaidNegativeTimerTickFallsBackToLocalAnchor()
    {
        tracker.setSharedTickAnchor("raid-time:v1:toa:i1", 5000, 0);
        local(4999, SpecialWeapon.DRAGON_WARHAMMER, 1);
        SpecHistoryEntry entry = tracker.specHistory().get(0);

        assertEquals(entry.getEventId(), entry.getTickAnchor());
        assertEquals(0L, entry.getEncounterTick());
    }

    @Test public void recoveryWaitsForPendingLocalSpec()
    {
        local(100, SpecialWeapon.DRAGON_WARHAMMER, 1);
        SpecHistoryEntry root = tracker.specHistory().get(0);
        SpecHistoryEntry other = new SpecHistoryEntry("Bob", SpecialWeapon.BANDOS_GODSWORD, 20,
            "other", "other", 0);
        SyncState incoming = state(Arrays.asList(root, other));
        when(client.getTickCount()).thenReturn(102);
        tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, 1, 1, 301, "Alice");
        assertFalse(tracker.mergeEventSyncState(incoming, npc));
        tracker.onGameTick();
        assertTrue(tracker.mergeEventSyncState(incoming, npc));
        assertEquals(3, tracker.specHistory().size());
    }

    @Test public void gsonWireSerializationRetainsEventIdentity()
    {
        local(100, SpecialWeapon.DRAGON_WARHAMMER, 1);
        BpdDefenceSync wire = new BpdDefenceSync(301, tracker.syncState(), 0, 0, 1, 1, 0, 100, 9);
        com.google.gson.Gson gson = new com.google.gson.Gson();
        BpdDefenceSync restored = gson.fromJson(gson.toJson(wire), BpdDefenceSync.class);
        assertEquals(tracker.syncState(), restored.toSyncState());
        assertEquals(EncounterTimeline.VERSION, restored.getEventVersion());
    }

    @Test public void replayCapacityReturnsBaselineWithoutApplyingGuessedEvents()
    {
        List<SpecHistoryEntry> events = new ArrayList<>();
        for (int i = 0; i <= EncounterTimeline.MAX_EVENTS; i++)
        {
            events.add(new SpecHistoryEntry("Alice", SpecialWeapon.BANDOS_GODSWORD, 1,
                "capacity-" + i, "clock", i));
        }
        Integer result = EncounterTimeline.replay(events, 310, new EncounterTimeline.Replay<Integer>()
        {
            public Integer apply(Integer value, SpecHistoryEntry event)
            { throw new AssertionError("Capacity overflow must not evaluate an invented order"); }
            public Integer conservative(Integer a, Integer b) { return Math.max(a, b); }
        });
        assertEquals(Integer.valueOf(310), result);
    }

    @Test public void trackerStaysConservativeAfterIdentifiedHistoryExceedsCapacity()
    {
        local(100, SpecialWeapon.DRAGON_WARHAMMER, 1);
        List<SpecHistoryEntry> events = new ArrayList<>();
        for (int i = 0; i < EncounterTimeline.MAX_EVENTS; i++)
        {
            events.add(new SpecHistoryEntry("Alice", SpecialWeapon.BANDOS_GODSWORD, 1,
                "capacity-" + i, "clock", i));
        }
        tracker.applySyncState(state(events), npc);
        local(500, SpecialWeapon.DRAGON_WARHAMMER, 1);
        assertEquals(310, tracker.state().getCurrent());
        assertEquals(257, tracker.specHistory().size());
        local(501, SpecialWeapon.BANDOS_GODSWORD, 20);
        assertEquals(310, tracker.state().getCurrent());
        assertEquals(258, tracker.specHistory().size());
    }

    @Test public void formattedLegacyEchoCanAcquireIdentityAndRecoverDifferentTickSpec()
    {
        local(100, SpecialWeapon.DRAGON_WARHAMMER, 1);
        SpecHistoryEntry root=tracker.specHistory().get(0);
        tracker.queue(SpecialWeapon.BANDOS_GODSWORD,1,20,301,"<col=ff0000>BOB</col>");
        tracker.onGameTick();
        SpecHistoryEntry remote=new SpecHistoryEntry("bob",SpecialWeapon.BANDOS_GODSWORD,20,
            "remote",root.getTickAnchor(),5);
        assertTrue(tracker.mergeEventSyncState(state(Arrays.asList(root,remote)),npc));
        assertEquals(177,tracker.state().getCurrent());
        assertEquals(2,tracker.specHistory().size());
        assertTrue(tracker.specHistory().contains(remote));
    }

    @Test public void formattedLegacySharedEventStillProvesOverlap()
    {
        tracker.queue(SpecialWeapon.DRAGON_WARHAMMER,1,1,301,"<col=ff0000>ALICE</col>");
        tracker.onGameTick();
        SpecHistoryEntry root=new SpecHistoryEntry("alice",SpecialWeapon.DRAGON_WARHAMMER,1,
            "root","root",0);
        SpecHistoryEntry remote=new SpecHistoryEntry("Bob",SpecialWeapon.BANDOS_GODSWORD,20,
            "remote","root",5);
        assertTrue(tracker.mergeEventSyncState(state(Arrays.asList(root,remote)),npc));
        assertEquals(177,tracker.state().getCurrent());
    }

    @Test public void differentPlayersWithOtherwiseSamePayloadDoNotProveOverlap()
    {
        tracker.queue(SpecialWeapon.DRAGON_WARHAMMER,1,1,301,"Alice");
        tracker.onGameTick();
        SpecHistoryEntry other=new SpecHistoryEntry("Carol",SpecialWeapon.DRAGON_WARHAMMER,1,
            "other","other",0);
        assertFalse(tracker.mergeEventSyncState(state(Arrays.asList(other)),npc));
        assertEquals(1,tracker.specHistory().size());
    }
}
