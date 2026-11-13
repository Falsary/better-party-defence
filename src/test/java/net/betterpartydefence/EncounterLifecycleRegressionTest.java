package net.betterpartydefence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import net.betterpartydefence.DefenceTracker.DefenceState;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.betterpartydefence.DefenceTracker.SyncState;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Before;
import org.junit.Test;

/** Characterizes complete per-boss cleanup at authoritative lifecycle boundaries. */
public class EncounterLifecycleRegressionTest
{
	private static final int WORLD = 301;
	private static final int NPC_INDEX = 81;

	private BetterPartyDefencePlugin plugin;
	private DefenceTracker tracker;
	private Client client;
	private InitialSyncDisplayGate displayGate;

	@Before
	public void setUp() throws Exception
	{
		client = mock(Client.class);
		WorldView worldView = mock(WorldView.class);
		IndexedObjectSet<? extends NPC> npcs = mock(IndexedObjectSet.class);
		NPC npc = mock(NPC.class);
		BetterPartyDefenceConfig config = mock(BetterPartyDefenceConfig.class);

		when(client.getWorld()).thenReturn(WORLD);
		when(client.getTickCount()).thenReturn(100);
		when(client.getTopLevelWorldView()).thenReturn(worldView);
		when(client.getVarbitValue(anyInt())).thenReturn(0);
		doReturn(npcs).when(worldView).npcs();
		when(worldView.isInstance()).thenReturn(false);
		when(npc.getName()).thenReturn("Tekton");
		when(npc.getIndex()).thenReturn(NPC_INDEX);
		when(npc.getId()).thenReturn(1);
		when(npc.getHealthRatio()).thenReturn(1);
		when(npcs.byIndex(NPC_INDEX)).thenReturn(npc);

		tracker = new DefenceTracker(client, config);
		plugin = new BetterPartyDefencePlugin();
		pluginField("client").set(plugin, client);
		pluginField("config").set(plugin, config);
		pluginField("defenceTracker").set(plugin, tracker);
		pluginField("partyService").set(plugin, mock(PartyService.class));
		displayGate = (InitialSyncDisplayGate) pluginField("initialSyncDisplayGate").get(plugin);

		tracker.applySyncState(tektonState(), npc);
	}

	@Test
	public void raidScopeEndClearsAllBossScopedReconciliationMetadata() throws Exception
	{
		Map<BossDefence, Object> versions = privateMap("syncStateVersions");
		Constructor<?> versionConstructor = Class.forName(
			"net.betterpartydefence.BetterPartyDefencePlugin$VersionedSyncState")
			.getDeclaredConstructor(long.class, int.class);
		versionConstructor.setAccessible(true);
		versions.put(BossDefence.TEKTON, versionConstructor.newInstance(99L, 123));

		Constructor<?> scopeConstructor = Class.forName(
			"net.betterpartydefence.BetterPartyDefencePlugin$SyncScope")
			.getDeclaredConstructor(int.class, int.class);
		scopeConstructor.setAccessible(true);
		privateMap("retainedRaidScopes").put(BossDefence.TEKTON,
			scopeConstructor.newInstance(BpdDefenceSync.SCOPE_COX, 77));
		Set<?> visibleBosses = markTektonVisible();
		BpdDefenceSync queuedSnapshot = new BpdDefenceSync(
			WORLD, tektonState(), BpdDefenceSync.SCOPE_COX, 77, 0, 0, 0, 100, 99);
		privateDeque("pendingSnapshots").addLast(queuedSnapshot);
		Constructor<?> pendingWorldConstructor = Class.forName(
			"net.betterpartydefence.BetterPartyDefencePlugin$PendingWorldSync")
			.getDeclaredConstructor(BpdDefenceSync.class, SyncState.class);
		pendingWorldConstructor.setAccessible(true);
		privateMap("pendingWorldSyncs").put(BossDefence.TEKTON,
			pendingWorldConstructor.newInstance(queuedSnapshot, tektonState()));

		Method reconcile = BetterPartyDefencePlugin.class.getDeclaredMethod(
			"reconcileRaidEncounterLifecycle");
		reconcile.setAccessible(true);
		reconcile.invoke(plugin);

		assertFalse(tracker.hasRememberedState(BossDefence.TEKTON));
		assertTrue("logical versions belong to the ended encounter", versions.isEmpty());
		assertTrue("retained raid scopes belong to the ended encounter",
			privateMap("retainedRaidScopes").isEmpty());
		assertTrue("visibility belongs to the ended encounter", visibleBosses.isEmpty());
		assertTrue("queued snapshots from the ended encounter must be discarded",
			privateDeque("pendingSnapshots").isEmpty());
		assertTrue("pending world sync belongs to the ended encounter",
			privateMap("pendingWorldSyncs").isEmpty());
	}

	@Test
	public void raidScopeEndClearsOnlyThatBossesInitialDisplayGate() throws Exception
	{
		DefenceState endedState = new DefenceState(NPC_INDEX, "Tekton", 205, 0, 205,
			0, 0, 0, 0, 0, 0, false, false);
		displayGate.arm(NPC_INDEX, BossDefence.TEKTON, client.getTickCount(), 4);
		displayGate.arm(82, BossDefence.VORKATH, client.getTickCount(), 4);

		Constructor<?> scopeConstructor = Class.forName(
			"net.betterpartydefence.BetterPartyDefencePlugin$SyncScope")
			.getDeclaredConstructor(int.class, int.class);
		scopeConstructor.setAccessible(true);
		privateMap("retainedRaidScopes").put(BossDefence.TEKTON,
			scopeConstructor.newInstance(BpdDefenceSync.SCOPE_COX, 77));

		Method reconcile = BetterPartyDefencePlugin.class.getDeclaredMethod(
			"reconcileRaidEncounterLifecycle");
		reconcile.setAccessible(true);
		reconcile.invoke(plugin);

		assertFalse("the ended encounter must not retain a presentation grace gate",
			displayGate.isWaiting(endedState, client.getTickCount()));
		assertTrue("an unrelated boss gate must remain untouched",
			displayGate.isWaiting(new DefenceState(82, "Vorkath", 100, 0, 100,
				0, 0, 0, 0, 0, 0, false, false), client.getTickCount()));
	}

	private SyncState tektonState()
	{
		SpecHistoryEntry event = new SpecHistoryEntry(
			"Alice", SpecialWeapon.DRAGON_WARHAMMER, 1);
		return new SyncState("Tekton", BossDefence.TEKTON, 139, 0, 205,
			390, 390, 205, 205, 0, 0, true, false, false, true,
			Collections.singletonList(event));
	}

	private Field pluginField(String name) throws Exception
	{
		Field field = BetterPartyDefencePlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	@SuppressWarnings("unchecked")
	private Set<BossDefence> markTektonVisible() throws Exception
	{
		Set<BossDefence> visibleBosses = (Set<BossDefence>) pluginField("visibleTrackedBosses").get(plugin);
		visibleBosses.add(BossDefence.TEKTON);
		return visibleBosses;
	}

	@SuppressWarnings("unchecked")
	private Map<BossDefence, Object> privateMap(String name) throws Exception
	{
		return (Map<BossDefence, Object>) pluginField(name).get(plugin);
	}

	@SuppressWarnings("unchecked")
	private Deque<BpdDefenceSync> privateDeque(String name) throws Exception
	{
		return (Deque<BpdDefenceSync>) pluginField(name).get(plugin);
	}
}
