package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class NpcTargetResolverTest
{
	private final WorldView worldView = mock(WorldView.class);
	private final List<NPC> sceneNpcs = new ArrayList<>();

	@SuppressWarnings("unchecked")
	private void loadScene()
	{
		IndexedObjectSet<? extends NPC> npcs = mock(IndexedObjectSet.class);
		doReturn(npcs).when(worldView).npcs();
		when(npcs.iterator()).thenAnswer(invocation -> sceneNpcs.iterator());
	}

	private NPC npc(String name, int id, int index, WorldPoint location)
	{
		NPC npc = mock(NPC.class);
		when(npc.getName()).thenReturn(name);
		when(npc.getId()).thenReturn(id);
		when(npc.getIndex()).thenReturn(index);
		when(npc.getWorldLocation()).thenReturn(location);
		when(npc.getHealthRatio()).thenReturn(1);
		sceneNpcs.add(npc);
		return npc;
	}

	@Test
	public void twoSameTypeActorsRemainAmbiguousWithoutNewEvidence()
	{
		loadScene();
		npc("Chaos Elemental", 1, 10, new WorldPoint(1000, 1000, 0));
		npc("Chaos Elemental", 2, 11, new WorldPoint(1001, 1000, 0));

		assertNull(NpcTargetResolver.uniqueLiveNpc(worldView, BossDefence.CHAOS_ELEMENTAL));
	}

	@Test
	public void senderCoordinatesSelectOneSameTypeActor()
	{
		loadScene();
		NPC first = npc("Chaos Elemental", 1, 10, new WorldPoint(1000, 1000, 0));
		npc("Chaos Elemental", 2, 11, new WorldPoint(1020, 1020, 0));

		assertEquals(first, NpcTargetResolver.uniqueLiveNpcNear(
			worldView, BossDefence.CHAOS_ELEMENTAL, 1000, 1000, 0, 2));
	}

	@Test
	public void rememberedNpcIdAndLocationSelectTheOriginalTarget()
	{
		loadScene();
		npc("Chaos Elemental", 1, 10, new WorldPoint(1000, 1000, 0));
		NPC second = npc("Chaos Elemental", 2, 11, new WorldPoint(1020, 1020, 0));

		assertEquals(second, NpcTargetResolver.uniqueLiveNpcFor(
			worldView, BossDefence.CHAOS_ELEMENTAL, 2, new WorldPoint(1020, 1020, 0), 2));
	}

	@Test
	public void rememberedTargetDoesNotMatchActorWithoutCurrentTile()
	{
		loadScene();
		npc("Chaos Elemental", 1, 10, new WorldPoint(1000, 1000, 0));
		NPC replacement = npc("Chaos Elemental", 1, 10, null);

		assertFalse(NpcTargetResolver.matches(
			replacement, BossDefence.CHAOS_ELEMENTAL, 1, new WorldPoint(1000, 1000, 0), 2));
	}
}
