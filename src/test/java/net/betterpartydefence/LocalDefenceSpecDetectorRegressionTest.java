package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.betterpartydefence.DefenceTracker.SyncState;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.specialcounter.SpecialCounterPlugin;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/**
 * Regression coverage for the local DWH capture path. These tests call the detector's real
 * VarbitChanged, HitsplatApplied, and tick methods rather than queueing tracker events directly.
 */
public class LocalDefenceSpecDetectorRegressionTest
{
	@Test
	public void landedDwhQueuesThroughTheDetector()
	{
		Harness h = new Harness();
		h.armDwh();
		h.tick = 101;
		h.hitsplat(h.target, 1, true);
		h.detector.onGameTick();
		h.tracker.onGameTick();

		assertEquals(1, h.tracker.specHistory().size());
		assertTrue(h.tracker.state().isDrained());
	}

	@Test
	public void hitsplatArrivingAfterTheExpectedGameTickStillQueues()
	{
		Harness h = new Harness();
		h.armDwh();
		h.tick = 101;
		h.detector.onGameTick();
		h.hitsplat(h.target, 1, true);
		h.tick = 102;
		h.detector.onGameTick();
		h.tracker.onGameTick();

		assertEquals("the DWH must not be lost when the callback follows GameTick",
			1, h.tracker.specHistory().size());
	}

	@Test
	public void incompleteRalosPairDoesNotQueueOnTheNextTick()
	{
		Harness h = new Harness();
		h.armSpec(SpecialWeapon.TONALZTICS_OF_RALOS);
		h.tick = h.expectedHitsplatTick();
		h.hitsplat(h.target, 12, true);
		h.detector.onGameTick();
		h.tick++;
		h.detector.onGameTick();
		h.tracker.onGameTick();

		assertTrue(h.tracker.specHistory().isEmpty());
	}

	@Test
	public void completeRalosPairAfterTheExpectedGameTickQueuesOnce()
	{
		Harness h = new Harness();
		h.armSpec(SpecialWeapon.TONALZTICS_OF_RALOS);
		h.tick = h.expectedHitsplatTick();
		h.hitsplat(h.target, 12, true);
		h.detector.onGameTick();
		h.hitsplat(h.target, 0, true);
		h.tick++;
		h.detector.onGameTick();
		h.tracker.onGameTick();
		h.tick++;
		h.detector.onGameTick();
		h.tracker.onGameTick();

		List<SpecHistoryEntry> history = h.tracker.specHistory();
		assertEquals(1, history.size());
		assertEquals(SpecialWeapon.TONALZTICS_OF_RALOS, history.get(0).getWeapon());
		assertEquals(1, history.get(0).getHit());
	}

	@Test
	public void replacementMoleActorWithSameLogicalIdentityKeepsTheDwh()
	{
		Harness h = new Harness();
		h.armDwh();
		NPC replacement = h.replacementMole();
		h.tick = 101;
		h.hitsplat(replacement, 1, true);
		h.detector.onGameTick();
		h.tracker.onGameTick();

		assertEquals("a burrow actor replacement must not discard the armed DWH",
			1, h.tracker.specHistory().size());
	}

	@Test
	public void confirmedDeadReplacementDoesNotQueueTheDwh()
	{
		Harness h = new Harness();
		h.armDwh();
		NPC replacement = h.replacementMole();
		when(h.target.isDead()).thenReturn(true);
		h.tick = 101;
		h.hitsplat(replacement, 1, true);
		h.detector.onGameTick();
		h.tracker.onGameTick();

		assertEquals(0, h.tracker.specHistory().size());
	}

	@Test
	public void localMissDoesNotDrainDefence()
	{
		Harness h = new Harness();
		h.armDwh();
		h.tick = 101;
		h.hitsplat(h.target, 0, true);
		h.detector.onGameTick();
		h.tracker.onGameTick();

		assertEquals("a miss must not apply a DWH drain", 200L, h.tracker.state().getCurrent());
	}

	@Test
	public void teammateHitDoesNotQueueTheLocalDwh()
	{
		Harness h = new Harness();
		h.armDwh();
		h.tick = 101;
		h.hitsplat(h.target, 1, false);
		h.detector.onGameTick();
		h.tracker.onGameTick();

		assertEquals(0, h.tracker.specHistory().size());
	}

	@Test
	public void thrallActorCannotSubstituteForTheArmedMoleHit()
	{
		Harness h = new Harness();
		h.armDwh();
		NPC thrall = mock(NPC.class);
		when(thrall.getName()).thenReturn("Thrall");
		when(thrall.getId()).thenReturn(999);
		when(thrall.getIndex()).thenReturn(82);
		h.tick = 101;
		h.hitsplat(thrall, 1, true);
		h.detector.onGameTick();
		h.tracker.onGameTick();

		assertEquals(0, h.tracker.specHistory().size());
	}

	@Test
	public void sacActiveDoesNotPreventLocalDwhCaptureWithoutAnSacUpdate()
	{
		Harness h = new Harness();
		h.activateSac();
		h.armDwh();
		h.tick = 101;
		h.hitsplat(h.target, 1, true);
		h.detector.onGameTick();
		h.tracker.onGameTick();

		assertEquals(1, h.tracker.specHistory().size());
	}

	@Test
	public void twoDwhsAfterReturnProduceTheExpectedHistoryAndDefence()
	{
		Harness h = new Harness();
		SyncState prior = moleState(64);
		h.tracker.applySyncState(prior, h.target);

		h.armDwh();
		h.tick = 101;
		h.hitsplat(h.target, 1, true);
		h.detector.onGameTick();
		h.tracker.onGameTick();
		h.tick++;

		h.armDwh();
		h.tick = 103;
		h.hitsplat(h.target, 1, true);
		h.detector.onGameTick();
		h.tracker.onGameTick();

		assertEquals("two returned-client DWHs must both be recorded",
			3, h.tracker.specHistory().size());
		assertEquals(32L, h.tracker.state().getCurrent());
	}

	private static SyncState moleState(long current)
	{
		SpecHistoryEntry event = new SpecHistoryEntry(
			"Alice", SpecialWeapon.DRAGON_WARHAMMER, 1);
		return new SyncState("Giant Mole", BossDefence.GIANT_MOLE, current, 0, 200,
			200, 200, 100, 100, 0, 0, false, false, false, true,
			Collections.singletonList(event));
	}

	private static final class Harness
	{
		private final Map<Integer, NPC> npcsByIndex = new LinkedHashMap<>();
		private final Client client = mock(Client.class);
		private final WorldView worldView = mock(WorldView.class);
		@SuppressWarnings("unchecked")
		private final IndexedObjectSet<NPC> npcs = mock(IndexedObjectSet.class);
		private final Player localPlayer = mock(Player.class);
		private final PartyService party = mock(PartyService.class);
		private final PluginManager pluginManager = mock(PluginManager.class);
		private final ItemContainer equipment = mock(ItemContainer.class);
		private final Item weaponItem = mock(Item.class);
		private final ClientThread clientThread = mock(ClientThread.class);
		private final BetterPartyDefenceConfig config = mock(BetterPartyDefenceConfig.class);
		private final DefenceTracker tracker;
		private final List<Runnable> queued = new ArrayList<>();
		private NPC target;
		private int tick = 100;

		@SuppressWarnings("unchecked")
		private Harness()
		{
			when(client.getWorld()).thenReturn(301);
			when(client.getTickCount()).thenAnswer(invocation -> tick);
			when(client.getGameCycle()).thenReturn(1);
			when(client.getTopLevelWorldView()).thenReturn(worldView);
			when(client.getLocalPlayer()).thenReturn(localPlayer);
			when(client.getVarbitValue(anyInt())).thenReturn(1);
			when(client.getItemContainer(InventoryID.WORN)).thenReturn(equipment);
			when(equipment.getItem(anyInt())).thenReturn(weaponItem);
			when(weaponItem.getId()).thenReturn(SpecialWeapon.DRAGON_WARHAMMER.getItemID()[0]);
			when(localPlayer.getInteracting()).thenReturn(target);
			when(localPlayer.getWorldLocation()).thenReturn(new WorldPoint(3000, 3000, 0));
			when(pluginManager.getPlugins()).thenReturn(Collections.emptyList());
			when(party.isInParty()).thenReturn(false);
			doReturn(npcs).when(worldView).npcs();
			when(worldView.isInstance()).thenReturn(false);
			when(npcs.byIndex(anyInt())).thenAnswer(
				invocation -> npcsByIndex.get(invocation.<Integer>getArgument(0)));
			when(npcs.iterator()).thenAnswer(
				invocation -> new ArrayList<>(npcsByIndex.values()).iterator());
			doAnswer(invocation -> {
				queued.add(invocation.getArgument(0));
				return null;
			}).when(clientThread).invokeLater(any(Runnable.class));

			when(config.syncWithOtherPartyDefenceUsers()).thenReturn(true);
			when(config.defenceAlwaysShow()).thenReturn(false);
			tracker = new DefenceTracker(client, config);
			target = addMole();
			when(localPlayer.getInteracting()).thenReturn(target);
			detector = new LocalDefenceSpecDetector(
				client, clientThread, party, pluginManager, tracker);
		}

		private final LocalDefenceSpecDetector detector;

		private NPC addMole()
		{
			NPC mole = mock(NPC.class);
			when(mole.getName()).thenReturn("Giant Mole");
			when(mole.getIndex()).thenReturn(81);
			when(mole.getId()).thenReturn(1);
			when(mole.getHealthRatio()).thenReturn(1);
			when(mole.getHealthScale()).thenReturn(1);
			when(mole.isDead()).thenReturn(false);
			when(mole.getWorldLocation()).thenReturn(new WorldPoint(3000, 3000, 0));
			npcsByIndex.put(81, mole);
			return mole;
		}

		private NPC replacementMole()
		{
			NPC replacement = addMole();
			when(localPlayer.getInteracting()).thenReturn(replacement);
			return replacement;
		}

		private void armDwh()
		{
			armSpec(SpecialWeapon.DRAGON_WARHAMMER);
		}

		private void armSpec(SpecialWeapon weapon)
		{
			when(weaponItem.getId()).thenReturn(weapon.getItemID()[0]);
			VarbitChanged energy = mock(VarbitChanged.class);
			when(energy.getVarpId()).thenReturn(VarPlayerID.SA_ENERGY);
			when(energy.getValue()).thenReturn(100);
			detector.onVarbitChanged(energy);
			when(energy.getValue()).thenReturn(50);
			detector.onVarbitChanged(energy);
			assertFalse(queued.isEmpty());
			queued.remove(0).run();
		}

		private void hitsplat(NPC actor, int amount, boolean mine)
		{
			Hitsplat splat = mock(Hitsplat.class);
			when(splat.isMine()).thenReturn(mine);
			when(splat.getAmount()).thenReturn(amount);
			when(splat.getHitsplatType()).thenReturn(0);
			HitsplatApplied event = mock(HitsplatApplied.class);
			when(event.getHitsplat()).thenReturn(splat);
			when(event.getActor()).thenReturn(actor);
			detector.onHitsplatApplied(event);
		}

		private void activateSac()
		{
			SpecialCounterPlugin sac = mock(SpecialCounterPlugin.class);
			when(pluginManager.getPlugins()).thenReturn(Collections.singletonList(sac));
			when(pluginManager.isPluginActive(sac)).thenReturn(true);
		}

		private int expectedHitsplatTick()
		{
			try
			{
				Field field = LocalDefenceSpecDetector.class.getDeclaredField("hitsplatTick");
				field.setAccessible(true);
				return (int) field.get(detector);
			}
			catch (ReflectiveOperationException ex)
			{
				throw new AssertionError(ex);
			}
		}
	}
}
