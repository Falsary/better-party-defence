package net.betterpartydefence;

import java.awt.image.BufferedImage;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.SpriteID;
import net.runelite.api.SpritePixels;
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
		return icon(Skill.DEFENCE, SpriteID.SKILL_DEFENCE, true);
	}

	BufferedImage magic()
	{
		return icon(Skill.MAGIC, SpriteID.SKILL_MAGIC, false);
	}

	private BufferedImage icon(Skill skill, int spriteId, boolean defence)
	{
		if (config.defenceUseThemeSkillIcons())
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
