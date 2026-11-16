package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.party.PartyService;
import org.junit.Test;

public class EncounterScopeResolverTest {
	@Test
	public void healthPercentageClampsKnownValuesAndPreservesUnknownValues() {
		NPC npc = mock(NPC.class);
		when(npc.getHealthRatio()).thenReturn(3);
		when(npc.getHealthScale()).thenReturn(4);
		assertEquals(75, EncounterScopeResolver.healthPercent(npc));

		when(npc.getHealthRatio()).thenReturn(-1);
		assertEquals(-1, EncounterScopeResolver.healthPercent(npc));
		assertEquals(-1, EncounterScopeResolver.healthPercent(null));
	}

	@Test
	public void healthToleranceMatchesOnlyWhenBothClientsKnowTheValue() {
		NPC npc = mock(NPC.class);
		when(npc.getHealthRatio()).thenReturn(50);
		when(npc.getHealthScale()).thenReturn(100);

		assertTrue(EncounterScopeResolver.healthMatches(npc, 70));
		assertFalse(EncounterScopeResolver.healthMatches(npc, 71));
		assertTrue(EncounterScopeResolver.healthMatches(npc, -1));

		when(npc.getHealthRatio()).thenReturn(-1);
		assertTrue(EncounterScopeResolver.healthMatches(npc, 20));
	}

	@Test
	public void missingRaidControllerUsesInstanceForTransportButNotRaidLifecycle() {
		Client client = mock(Client.class);
		WorldView world = mock(WorldView.class);
		when(client.getTopLevelWorldView()).thenReturn(world);
		when(world.isInstance()).thenReturn(true);
		when(world.getBaseX()).thenReturn(1000);
		when(world.getBaseY()).thenReturn(2000);
		EncounterScopeResolver resolver = new EncounterScopeResolver(client, mock(PartyService.class));
		int fingerprint = EncounterScopes.instanceFingerprint(world);

		assertEquals(new SyncScope(BpdDefenceSync.SCOPE_INSTANCE, fingerprint),
				resolver.forBoss(BossDefence.TEKTON, null));
		assertNull(resolver.currentRaidScopeForBoss(BossDefence.TEKTON));
		assertTrue(resolver.scopeMatches(BpdDefenceSync.SCOPE_INSTANCE, fingerprint, BossDefence.TEKTON));
		assertFalse(resolver.scopeMatches(BpdDefenceSync.SCOPE_INSTANCE, fingerprint + 1, BossDefence.TEKTON));

		when(client.getVarpValue(VarPlayerID.RAIDS_PARTY_GROUPHOLDER)).thenReturn(42);
		assertEquals(new SyncScope(BpdDefenceSync.SCOPE_COX, 42),
				resolver.forBoss(BossDefence.TEKTON, null));
		assertEquals(new SyncScope(BpdDefenceSync.SCOPE_COX, 42),
				resolver.currentRaidScopeForBoss(BossDefence.TEKTON));
	}
}
