package net.betterpartydefence;

import java.awt.image.BufferedImage;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.SpritePixels;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.util.ImageUtil;

/** Supplies small skill icons, optionally honoring active RuneLite sprite/theme overrides. */
final class SkillIconSource
{
	private static final int ICON_SIZE = 16;
	private final Client client;
	private final SkillIconManager skillIconManager;
	private final BetterPartyDefenceConfig config;
	private SpritePixels lastDefenceOverride;
	private SpritePixels lastMagicOverride;
	private BufferedImage themedDefence;
	private BufferedImage themedMagic;
	private BufferedImage classicDefence;
	private BufferedImage classicMagic;

	SkillIconSource(Client client, SkillIconManager skillIconManager, BetterPartyDefenceConfig config)
	{
		this.client = client;
		this.skillIconManager = skillIconManager;
		this.config = config;
	}

	BufferedImage defence()
	{
		return defence(config.defenceUseThemeSkillIcons());
	}

	BufferedImage defence(boolean useTheme)
	{
		return icon(Skill.DEFENCE, SpriteID.Staticons.DEFENCE, true, useTheme);
	}

	BufferedImage magic()
	{
		return magic(config.defenceUseThemeSkillIcons());
	}

	BufferedImage magic(boolean useTheme)
	{
		return icon(Skill.MAGIC, SpriteID.Staticons.MAGIC, false, useTheme);
	}

	private BufferedImage icon(Skill skill, int spriteId, boolean defence, boolean useTheme)
	{
		if (useTheme)
		{
			SpritePixels override = client.getSpriteOverrides().get(spriteId);
			if (override != null)
			{
				if (defence)
				{
					if (override != lastDefenceOverride || themedDefence == null)
					{
						lastDefenceOverride = override;
						themedDefence = ImageUtil.resizeImage(override.toBufferedImage(), ICON_SIZE, ICON_SIZE);
					}
					return themedDefence;
				}
				if (override != lastMagicOverride || themedMagic == null)
				{
					lastMagicOverride = override;
					themedMagic = ImageUtil.resizeImage(override.toBufferedImage(), ICON_SIZE, ICON_SIZE);
				}
				return themedMagic;
			}
		}
		if (defence)
		{
			if (classicDefence == null)
			{
				classicDefence = ImageUtil.resizeImage(skillIconManager.getSkillImage(skill), ICON_SIZE, ICON_SIZE);
			}
			return classicDefence;
		}
		if (classicMagic == null)
		{
			classicMagic = ImageUtil.resizeImage(skillIconManager.getSkillImage(skill), ICON_SIZE, ICON_SIZE);
		}
		return classicMagic;
	}
}
