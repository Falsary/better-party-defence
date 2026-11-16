package net.betterpartydefence;

import java.io.File;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import net.betterpartydefence.BetterPartyDefenceConfig.TrackerFont;
import net.runelite.client.config.ConfigManager;

// Selects and stores a local font without blocking the game thread.
final class CustomFontChooser {
	private final BetterPartyDefenceConfig config;
	private final ConfigManager configManager;

	CustomFontChooser(BetterPartyDefenceConfig config, ConfigManager configManager) {
		this.config = config;
		this.configManager = configManager;
	}

	void choose() {
		SwingUtilities.invokeLater(() -> {
			JFileChooser chooser = new JFileChooser();
			chooser.setDialogTitle("Choose a font for Better Party Defence");
			chooser.setFileFilter(new FileNameExtensionFilter("Font files (*.ttf, *.otf)", "ttf", "otf"));

			String current = config.customFontPath();
			if (current != null && !current.trim().isEmpty()) {
				File file = new File(current);
				if (file.getParentFile() != null) {
					chooser.setCurrentDirectory(file.getParentFile());
				}
			}

			if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
				setConfiguration(BetterPartyDefenceConfig.KEY_CUSTOM_FONT_PATH,
						chooser.getSelectedFile().getAbsolutePath());
				setConfiguration(BetterPartyDefenceConfig.KEY_DEFENCE_FONT, TrackerFont.CUSTOM);
			} else {
				TrackerFont fallback = current == null || current.trim().isEmpty()
						? TrackerFont.RUNESCAPE
						: TrackerFont.CUSTOM;
				setConfiguration(BetterPartyDefenceConfig.KEY_DEFENCE_FONT, fallback);
			}
		});
	}

	private void setConfiguration(String key, Object value) {
		configManager.setConfiguration(BetterPartyDefenceConfig.GROUP, key, value);
	}
}
