package net.betterpartydefence;

import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.specialcounter.SpecialCounterPlugin;

// Locate the Special Attack Counter that owns standard party broadcasts.
final class SpecialCounterState {
	private SpecialCounterState() {
	}

	static boolean isActive(PluginManager pluginManager) {
		for (Plugin plugin : pluginManager.getPlugins()) {
			if (plugin instanceof SpecialCounterPlugin && pluginManager.isPluginActive(plugin)) {
				return true;
			}
		}
		return false;
	}
}
