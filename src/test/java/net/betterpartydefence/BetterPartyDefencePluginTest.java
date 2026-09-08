package net.betterpartydefence;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Development launcher: ./gradlew run */
public class BetterPartyDefencePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BetterPartyDefencePlugin.class);
		RuneLite.main(args);
	}
}
