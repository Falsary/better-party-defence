package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.ScriptEvent;
import net.runelite.api.WorldView;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Before;
import org.junit.Test;

public class RaidTimerPluginTest
{
	private BetterPartyDefencePlugin plugin;
	private DefenceTracker tracker;
	private Client client;
	private WorldView world;
	private NPC npc;

	private void set(String name, Object value) throws Exception
	{
		Field field = BetterPartyDefencePlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(plugin, value);
	}

	@Before @SuppressWarnings("unchecked") public void setup() throws Exception
	{
		client = mock(Client.class);
		world = mock(WorldView.class);
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
		plugin = new BetterPartyDefencePlugin();
		set("client", client);
		set("defenceTracker", tracker);
	}

	private void timerScript(int scriptId, int elapsed, int stopped)
	{
		ScriptEvent scriptEvent = mock(ScriptEvent.class);
		when(scriptEvent.getArguments()).thenReturn(new Object[]{scriptId, elapsed, stopped});
		ScriptPreFired event = new ScriptPreFired(scriptId);
		event.setScriptEvent(scriptEvent);
		plugin.onScriptPreFired(event);
	}

	private SpecHistoryEntry localSpec(int tick)
	{
		when(client.getTickCount()).thenReturn(tick);
		tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, 1, 1, 301, "Alice");
		tracker.onGameTick();
		return tracker.specHistory().get(0);
	}

	private void coxPoll(int tick) throws Exception
	{
		Method update = BetterPartyDefencePlugin.class.getDeclaredMethod("updateRaidTimerClock", int.class);
		update.setAccessible(true);
		update.invoke(plugin, tick);
		Method apply = BetterPartyDefencePlugin.class.getDeclaredMethod("applySharedRaidTickAnchor", int.class);
		apply.setAccessible(true);
		apply.invoke(plugin, tick);
	}

	@Test public void tfaTimerScriptAnchorsRaidElapsedTicks()
	{
		when(world.isInstance()).thenReturn(true);
		when(client.getVarpValue(net.runelite.api.gameval.VarPlayerID.TFA_MYCONTROLLER)).thenReturn(77);
		when(client.getTickCount()).thenReturn(5_000);

		timerScript(RaidTimerClock.TFA_TIMER_SCRIPT, 0, 0);
		SpecHistoryEntry entry = localSpec(5_007);

		assertEquals("raid-time:v1:tfa:c77:u2", entry.getTickAnchor());
		assertEquals(7L, entry.getEncounterTick());
	}

	@Test public void toaTimerUsesAStableControllerScopeAcrossRooms()
	{
		when(world.isInstance()).thenReturn(true);
		when(client.getVarpValue(net.runelite.api.gameval.VarPlayerID.TOA_MYCONTROLLER)).thenReturn(88);
		when(client.getTickCount()).thenReturn(5_000);
		timerScript(RaidTimerClock.TOA_TIMER_SCRIPT, 0, 0);

		when(world.getBaseX()).thenReturn(4_000);
		when(client.getTickCount()).thenReturn(5_005);
		timerScript(RaidTimerClock.TOA_TIMER_SCRIPT, 5, 0);
		SpecHistoryEntry entry = localSpec(5_007);

		assertEquals("raid-time:v1:toa:c88:u2", entry.getTickAnchor());
		assertEquals(7L, entry.getEncounterTick());
	}

	@Test public void toaTimerScriptUsesTheSharedInstanceScope()
	{
		when(world.isInstance()).thenReturn(true);
		when(client.getTickCount()).thenReturn(9_000);

		timerScript(RaidTimerClock.TOA_TIMER_SCRIPT, 120, 0);
		SpecHistoryEntry entry = localSpec(9_004);

		assertTrue(entry.getTickAnchor().startsWith("raid-time:v1:toa:i"));
		assertEquals(124L, entry.getEncounterTick());
	}

	@Test public void tfaTimerKeepsItsControllerScopeAcrossRoomScenes()
	{
		when(world.isInstance()).thenReturn(true);
		when(client.getVarpValue(net.runelite.api.gameval.VarPlayerID.TFA_MYCONTROLLER)).thenReturn(77);
		when(client.getTickCount()).thenReturn(5_000);
		timerScript(RaidTimerClock.TFA_TIMER_SCRIPT, 0, 0);

		// A later room has a different scene, but the same party controller and the same
		// running timer. The established domain must not change mid-raid.
		when(world.getBaseX()).thenReturn(4_000);
		when(client.getTickCount()).thenReturn(5_005);
		timerScript(RaidTimerClock.TFA_TIMER_SCRIPT, 5, 0);
		SpecHistoryEntry entry = localSpec(5_007);

		assertEquals("raid-time:v1:tfa:c77:u2", entry.getTickAnchor());
		assertEquals(7L, entry.getEncounterTick());
	}

	@Test public void stoppedTimerStopsAnchoringNewSpecs()
	{
		when(world.isInstance()).thenReturn(true);
		when(client.getVarpValue(net.runelite.api.gameval.VarPlayerID.TFA_MYCONTROLLER)).thenReturn(77);
		when(client.getTickCount()).thenReturn(5_000);
		timerScript(RaidTimerClock.TFA_TIMER_SCRIPT, 0, 0);

		when(client.getTickCount()).thenReturn(5_020);
		timerScript(RaidTimerClock.TFA_TIMER_SCRIPT, 20, 1);
		SpecHistoryEntry entry = localSpec(5_021);

		assertEquals(entry.getEventId(), entry.getTickAnchor());
		assertEquals(0L, entry.getEncounterTick());
	}

	@Test public void clearedTimerDoesNotLeaveAStaleAnchor()
	{
		when(world.isInstance()).thenReturn(true);
		when(client.getVarpValue(net.runelite.api.gameval.VarPlayerID.TFA_MYCONTROLLER)).thenReturn(77);
		when(client.getTickCount()).thenReturn(5_000);
		timerScript(RaidTimerClock.TFA_TIMER_SCRIPT, 0, 0);

		when(client.getTickCount()).thenReturn(5_010);
		timerScript(RaidTimerClock.TFA_TIMER_SCRIPT, -1, 0);
		SpecHistoryEntry entry = localSpec(5_011);

		assertEquals(entry.getEventId(), entry.getTickAnchor());
	}

	@Test public void timerOutsideAnInstanceIsIgnored()
	{
		when(world.isInstance()).thenReturn(false);
		when(client.getTickCount()).thenReturn(5_000);
		timerScript(RaidTimerClock.TFA_TIMER_SCRIPT, 0, 0);

		SpecHistoryEntry entry = localSpec(5_005);

		assertEquals(entry.getEventId(), entry.getTickAnchor());
	}

	@Test public void coxProgressZeroIsStillTheLobby()
	{
		when(world.isInstance()).thenReturn(true);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(1);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_PROGRESS)).thenReturn(0);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_TIMER)).thenReturn(0);
		when(client.getTickCount()).thenReturn(5_000);

		try
		{
			coxPoll(5_000);
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
		SpecHistoryEntry entry = localSpec(5_003);

		assertEquals(entry.getEventId(), entry.getTickAnchor());
	}

	@Test public void coxRunningTimerAnchorsProgressElapsedTicks()
	{
		when(world.isInstance()).thenReturn(true);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(1);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_PROGRESS)).thenReturn(1);
		when(client.getTickCount()).thenReturn(5_000);

		for (int i = 0; i < 5; i++)
		{
			when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_TIMER)).thenReturn(30 + i);
			try
			{
				coxPoll(5_000 + i);
			}
			catch (Exception e)
			{
				throw new AssertionError(e);
			}
		}
		SpecHistoryEntry entry = localSpec(5_006);

		assertTrue(entry.getTickAnchor().startsWith("raid-time:v1:cox:i"));
		assertEquals(36L, entry.getEncounterTick());
	}

	@Test public void frozenCoxTimerFallsBackInsteadOfFreezingElapsedTime()
	{
		when(world.isInstance()).thenReturn(true);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(1);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_PROGRESS)).thenReturn(1);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_TIMER)).thenReturn(30);
		when(client.getTickCount()).thenReturn(5_000);

		for (int tick = 5_000; tick <= 5_010; tick++)
		{
			try
			{
				coxPoll(tick);
			}
			catch (Exception e)
			{
				throw new AssertionError(e);
			}
		}
		SpecHistoryEntry entry = localSpec(5_011);

		assertEquals(entry.getEventId(), entry.getTickAnchor());
	}

	@Test public void coxExitClearsTheTimerDomain()
	{
		when(world.isInstance()).thenReturn(true);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(1);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_PROGRESS)).thenReturn(1);
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_TIMER)).thenReturn(30);
		when(client.getTickCount()).thenReturn(5_000);
		try
		{
			coxPoll(5_000);
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}

		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(0);
		try
		{
			coxPoll(5_001);
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
		SpecHistoryEntry entry = localSpec(5_002);

		assertEquals(entry.getEventId(), entry.getTickAnchor());
	}
}
