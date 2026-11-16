package net.betterpartydefence;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Development launcher: ./gradlew run */
public class BetterPartyDefencePluginTest {
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception {
		// RuneLite's launcher exposes a generic varargs API.
		ExternalPluginManager.loadBuiltin(BetterPartyDefencePlugin.class);
		RuneLite.main(args);
	}
}
