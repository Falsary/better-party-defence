package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

public class ToaEncounterCoordinatorTest {
	@Test
	public void fullPartyWipeResetsRememberedBossOncePerWipe() {
		Client client = mock(Client.class);
		WorldView world = mock(WorldView.class);
		DefenceTracker tracker = mock(DefenceTracker.class);
		when(client.getTopLevelWorldView()).thenReturn(world);
		when(world.isInstance()).thenReturn(true);
		when(client.getVarbitValue(VarbitID.TOA_CLIENT_P0)).thenReturn(30);
		when(client.getVarbitValue(VarbitID.TOA_CLIENT_P1)).thenReturn(30);
		when(tracker.hasRememberedState(BossDefence.BA_BA)).thenReturn(true);

		List<BossDefence> resets = new ArrayList<>();
		ToaEncounterCoordinator coordinator =
				new ToaEncounterCoordinator(client, tracker, resets::add);
		coordinator.onGameTick();
		coordinator.onGameTick();
		assertEquals(List.of(BossDefence.BA_BA), resets);

		when(client.getVarbitValue(VarbitID.TOA_CLIENT_P1)).thenReturn(20);
		coordinator.onGameTick();
		when(client.getVarbitValue(VarbitID.TOA_CLIENT_P1)).thenReturn(30);
		coordinator.onGameTick();
		assertEquals(List.of(BossDefence.BA_BA, BossDefence.BA_BA), resets);
	}
}
