package net.betterpartydefence;

import java.awt.Font;
import java.io.File;
import net.runelite.client.ui.FontManager;

/** Loads the selected tracker fonts, including optional local TTF/OTF files. */
final class TrackerFontManager
{
	private final BetterPartyDefenceConfig config;
	private String cachedPath;
	private Font cachedCustom;
	private String cachedPreviousPath;
	private Font cachedPreviousCustom;

	TrackerFontManager(BetterPartyDefenceConfig config)
	{
		this.config = config;
	}

	Font font()
	{
		return font(config.defenceFont(), config.defenceFontSize(), config.defenceBoldText(), false);
	}

	Font previousTargetFont()
	{
		return font(config.previousTargetFont(), config.previousTargetFontSize(),
			config.previousTargetBoldText(), true);
	}

	private Font font(TrackerFont selection, int configuredSize, boolean bold, boolean previous)
	{
		int size = Math.max(8, Math.min(48, configuredSize));
		Font base;
		switch (selection)
		{
			case SANS_SERIF:
				base = new Font(Font.SANS_SERIF, Font.PLAIN, size);
				break;
			case SERIF:
				base = new Font(Font.SERIF, Font.PLAIN, size);
				break;
			case MONOSPACED:
				base = new Font(Font.MONOSPACED, Font.PLAIN, size);
				break;
			case ADD_CUSTOM:
			case CUSTOM:
				base = customFont(previous);
				if (base == null)
				{
					base = FontManager.getRunescapeSmallFont();
				}
				base = base.deriveFont((float) size);
				break;
			case RUNESCAPE:
			default:
				base = FontManager.getRunescapeSmallFont().deriveFont((float) size);
				break;
		}
		return bold ? base.deriveFont(Font.BOLD) : base.deriveFont(Font.PLAIN);
	}

	private Font customFont(boolean previous)
	{
		String path = previous ? config.previousTargetCustomFontPath() : config.customFontPath();
		if (path == null || path.trim().isEmpty())
		{
			return null;
		}

		if (previous)
		{
			if (path.equals(cachedPreviousPath))
			{
				return cachedPreviousCustom;
			}
			cachedPreviousPath = path;
			cachedPreviousCustom = loadFont(path);
			return cachedPreviousCustom;
		}

		if (path.equals(cachedPath))
		{
			return cachedCustom;
		}
		cachedPath = path;
		cachedCustom = loadFont(path);
		return cachedCustom;
	}

	private static Font loadFont(String path)
	{
		File file = new File(path);
		if (!file.isFile())
		{
			return null;
		}
		try
		{
			return Font.createFont(Font.TRUETYPE_FONT, file);
		}
		catch (Exception ignored)
		{
			try
			{
				return Font.createFont(Font.TYPE1_FONT, file);
			}
			catch (Exception ignoredAgain)
			{
				return null;
			}
		}
	}
}
