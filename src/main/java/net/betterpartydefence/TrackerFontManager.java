package net.betterpartydefence;

import java.awt.Font;
import java.io.File;
import net.runelite.client.ui.FontManager;

/** Loads the selected tracker font, including an optional local TTF/OTF file. */
final class TrackerFontManager
{
	private final BetterPartyDefenceConfig config;
	private String cachedPath;
	private Font cachedCustom;

	TrackerFontManager(BetterPartyDefenceConfig config)
	{
		this.config = config;
	}

	Font font()
	{
		int size = Math.max(8, Math.min(48, config.defenceFontSize()));
		Font base;
		switch (config.defenceFont())
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
				base = customFont();
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
		return config.defenceBoldText() ? base.deriveFont(Font.BOLD) : base.deriveFont(Font.PLAIN);
	}

	private Font customFont()
	{
		String path = config.customFontPath();
		if (path == null || path.trim().isEmpty())
		{
			return null;
		}
		if (path.equals(cachedPath))
		{
			return cachedCustom;
		}
		cachedPath = path;
		cachedCustom = null;
		File file = new File(path);
		if (!file.isFile())
		{
			return null;
		}
		try
		{
			cachedCustom = Font.createFont(Font.TRUETYPE_FONT, file);
		}
		catch (Exception ignored)
		{
			try
			{
				cachedCustom = Font.createFont(Font.TYPE1_FONT, file);
			}
			catch (Exception ignoredAgain)
			{
				cachedCustom = null;
			}
		}
		return cachedCustom;
	}
}
