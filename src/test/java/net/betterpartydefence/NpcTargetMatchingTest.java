package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
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

public class NpcTargetMatchingTest {
	private final WorldView worldView = mock(WorldView.class);
	private final List<NPC> sceneNpcs = new ArrayList<>();

	@SuppressWarnings("unchecked")
	private void loadScene() {
		IndexedObjectSet<? extends NPC> npcs = mock(IndexedObjectSet.class);
		doReturn(npcs).when(worldView).npcs();
		when(npcs.iterator()).thenAnswer(invocation -> sceneNpcs.iterator());
	}

	private NPC npc(String name, int id, int index, WorldPoint location) {
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
	public void twoSameTypeActorsRemainAmbiguousWithoutNewEvidence() {
		loadScene();
		npc("Chaos Elemental", 1, 10, new WorldPoint(1000, 1000, 0));
		npc("Chaos Elemental", 2, 11, new WorldPoint(1001, 1000, 0));

		assertNull(TargetResolver.uniqueLiveNpc(worldView, BossDefence.CHAOS_ELEMENTAL));
	}

	@Test
	public void senderCoordinatesSelectOneSameTypeActor() {
		loadScene();
		NPC first = npc("Chaos Elemental", 1, 10, new WorldPoint(1000, 1000, 0));
		npc("Chaos Elemental", 2, 11, new WorldPoint(1020, 1020, 0));

		assertEquals(first, TargetResolver.uniqueLiveNpcNear(
				worldView, BossDefence.CHAOS_ELEMENTAL, 1000, 1000, 0, 2));
	}

	@Test
	public void rememberedNpcIdAndLocationSelectTheOriginalTarget() {
		loadScene();
		npc("Chaos Elemental", 1, 10, new WorldPoint(1000, 1000, 0));
		NPC second = npc("Chaos Elemental", 2, 11, new WorldPoint(1020, 1020, 0));

		assertEquals(second, TargetResolver.uniqueLiveNpcFor(
				worldView, BossDefence.CHAOS_ELEMENTAL, 2, new WorldPoint(1020, 1020, 0), 2));
	}

	@Test
	public void rememberedTargetDoesNotMatchActorWithoutCurrentTile() {
		loadScene();
		npc("Chaos Elemental", 1, 10, new WorldPoint(1000, 1000, 0));
		NPC replacement = npc("Chaos Elemental", 1, 10, null);

		assertFalse(TargetResolver.matches(
				replacement, BossDefence.CHAOS_ELEMENTAL, 1, new WorldPoint(1000, 1000, 0), 2));
	}

	@Test
	public void locationToleranceIncludesDiagonalBoundaryButNotAnotherPlane() {
		loadScene();
		NPC target = npc("Chaos Elemental", 1, 10, new WorldPoint(1002, 1002, 0));
		NPC otherPlane = npc("Chaos Elemental", 1, 11, new WorldPoint(1000, 1000, 1));
		WorldPoint origin = new WorldPoint(1000, 1000, 0);

		assertEquals(target, TargetResolver.uniqueLiveNpcNear(
				worldView, BossDefence.CHAOS_ELEMENTAL, 1000, 1000, 0, 2));
		assertEquals(target, TargetResolver.uniqueLiveNpcFor(
				worldView, BossDefence.CHAOS_ELEMENTAL, 1, origin, 2));
		assertTrue(TargetResolver.matches(target, BossDefence.CHAOS_ELEMENTAL, 1, origin, 2));
		assertFalse(TargetResolver.matches(otherPlane, BossDefence.CHAOS_ELEMENTAL, 1, origin, 2));
	}

	@Test
	public void queuedEvidenceKeepsActorIdentityAndLocationGuards() {
		NPC target = npc("Chaos Elemental", 1, 10, new WorldPoint(1000, 1000, 0));
		NPC replacement = npc("Chaos Elemental", 1, 10, new WorldPoint(1000, 1000, 0));
		PendingSpecQueue.TargetEvidence exact = PendingSpecQueue.TargetEvidence.from(target);
		PendingSpecQueue.TargetEvidence location = new PendingSpecQueue.TargetEvidence(
				BossDefence.CHAOS_ELEMENTAL, 1, new WorldPoint(1000, 1000, 0), null);

		assertTrue(exact.matches(target));
		assertFalse(exact.matches(replacement));
		assertTrue(location.matches(replacement));
		when(replacement.getWorldLocation()).thenReturn(new WorldPoint(1003, 1000, 0));
		assertFalse(location.matches(replacement));
	}
}
