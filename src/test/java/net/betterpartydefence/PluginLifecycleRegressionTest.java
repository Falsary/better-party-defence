package net.betterpartydefence;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import org.junit.Before;
import org.junit.Test;

public class PluginLifecycleRegressionTest
{
	private BetterPartyDefencePlugin plugin;
	private WSClient wsClient;

	@Before
	public void setUp() throws Exception
	{
		plugin = new BetterPartyDefencePlugin();
		wsClient = mock(WSClient.class);
		Client client = mock(Client.class);
		ClientThread clientThread = mock(ClientThread.class);
		PartyService partyService = mock(PartyService.class);
		PluginManager pluginManager = mock(PluginManager.class);
		BetterPartyDefenceConfig config = mock(BetterPartyDefenceConfig.class);

		when(pluginManager.getPlugins()).thenReturn(Collections.emptyList());
		when(partyService.isInParty()).thenReturn(false);
		DefenceTracker tracker = new DefenceTracker(client, config);

		set("client", client);
		set("clientThread", clientThread);
		set("partyService", partyService);
		set("wsClient", wsClient);
		set("pluginManager", pluginManager);
		set("overlayManager", mock(OverlayManager.class));
		set("itemManager", mock(ItemManager.class));
		set("skillIconManager", mock(SkillIconManager.class));
		set("defenceTracker", tracker);
		set("localSpecDetector", mock(LocalDefenceSpecDetector.class));
		set("config", config);
		set("configManager", mock(ConfigManager.class));
		set("infoBoxManager", mock(InfoBoxManager.class));
	}

	@Test
	public void shutdownUnregistersEveryRegisteredMessageType()
	{
		plugin.startUp();
		plugin.shutDown();

		verify(wsClient).unregisterMessage(BpdDefenceSync.class);
		verify(wsClient).unregisterMessage(BpdSpecEvent.class);
		verify(wsClient).unregisterMessage(BpdEncounterPresence.class);
		verify(wsClient).unregisterMessage(BpdBossPresence.class);
		verify(wsClient).unregisterMessage(BpdEncounterReset.class);
		verify(wsClient).unregisterMessage(BpdKerisTimer.class);
	}

	private void set(String name, Object value) throws Exception
	{
		Field field = BetterPartyDefencePlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(plugin, value);
	}
}
