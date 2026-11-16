package net.betterpartydefence;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.Value;
import net.betterpartydefence.BetterPartyDefenceConfig.DefenceDrainFormat;
import net.betterpartydefence.BetterPartyDefenceConfig.DefenceValueFormat;
import net.betterpartydefence.BetterPartyDefenceConfig.MagicDefenceDisplay;
import net.betterpartydefence.DefenceTracker.DefenceState;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.Skill;
import net.runelite.api.SpritePixels;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.ImageUtil;

// Draws the scene tracker from configured display options.
final class DefenceOverlayRenderer {
	private static final Color PLATE_COLOR = new Color(0, 0, 0, 150);
	private static final int GAP = 3;
	private static final int SEGMENT_GAP = 8;
	private static final int ARROW_WIDTH = 7;

	private final BetterPartyDefenceConfig config;
	private final IconSource skillIcons;
	private String cachedFontPath;
	private Font cachedCustomFont;

	@Value
	private static class Segment {
		BufferedImage icon;
		String text;
		Color color;
		String drain;
	}

	DefenceOverlayRenderer(BetterPartyDefenceConfig config, IconSource skillIcons) {
		this.config = config;
		this.skillIcons = skillIcons;
	}

	Font font() {
		int size = Math.max(8, Math.min(48, config.defenceFontSize()));
		Font base;
		switch (config.defenceFont()) {
			case SANS_SERIF :
				base = new Font(Font.SANS_SERIF, Font.PLAIN, size);
				break;
			case SERIF :
				base = new Font(Font.SERIF, Font.PLAIN, size);
				break;
			case MONOSPACED :
				base = new Font(Font.MONOSPACED, Font.PLAIN, size);
				break;
			case ADD_CUSTOM :
			case CUSTOM :
				base = customFont();
				if (base == null) {
					base = FontManager.getRunescapeSmallFont();
				}
				base = base.deriveFont((float) size);
				break;
			case RUNESCAPE :
			default :
				base = FontManager.getRunescapeSmallFont().deriveFont((float) size);
				break;
		}
		return base.deriveFont(config.defenceBoldText() ? Font.BOLD : Font.PLAIN);
	}

	private Font customFont() {
		String path = config.customFontPath();
		if (path == null || path.trim().isEmpty()) {
			return null;
		}
		if (!path.equals(cachedFontPath)) {
			cachedFontPath = path;
			cachedCustomFont = loadFont(path);
		}
		return cachedCustomFont;
	}

	private static Font loadFont(String path) {
		File file = new File(path);
		if (!file.isFile()) {
			return null;
		}
		try {
			return Font.createFont(Font.TRUETYPE_FONT, file);
		} catch (Exception ignored) {
			try {
				return Font.createFont(Font.TYPE1_FONT, file);
			} catch (Exception ignoredAgain) {
				return null;
			}
		}
	}

	// Draw the tracker around a scene anchor.
	void renderAt(Graphics2D graphics, DefenceState state, int centreX, int baseline) {
		FontMetrics fm = graphics.getFontMetrics();
		List<List<Segment>> rows = rows(state);
		for (int i = 0; i < rows.size(); i++) {
			drawRow(graphics, fm, centreX, baseline + i * fm.getHeight(), rows.get(i));
		}
	}

	private List<List<Segment>> rows(DefenceState state) {
		Segment defence = defenceSegment(state);
		// Keep Defence visible; suppress unverified TFA Magic.
		Segment magic = config.magicDefence() && DefenceReadout.magicDefenceVisible(state)
				? magicSegment(state)
				: null;
		if (magic == null) {
			return Collections.singletonList(Collections.singletonList(defence));
		}
		if (config.magicDefenceSameRow()) {
			return Collections.singletonList(Arrays.asList(defence, magic));
		}
		List<List<Segment>> rows = new ArrayList<>(2);
		rows.add(Collections.singletonList(defence));
		rows.add(Collections.singletonList(magic));
		return rows;
	}

	private Segment defenceSegment(DefenceState state) {
		boolean full = config.defenceShowFullLevel();
		long current = DefenceReadout.shownDefence(state, full);
		long base = DefenceReadout.shownBaseDefence(state, full);
		return new Segment(iconOrNull(skillIcons.defence()),
				DefenceReadout.value(config.defenceValueFormat(), current, base),
				DefenceReadout.defenceColor(state, config),
				DefenceReadout.drain(config.defenceDrainFormat(), current, base));
	}

	private Segment magicSegment(DefenceState state) {
		long rollPercent = DefenceReadout.percentRemaining(state.getMagicRoll(), state.getMagicBaseRoll());
		String text;
		String drain = null;
		switch (config.magicDefenceDisplay()) {
			case BONUS :
				text = DefenceReadout.value(config.defenceValueFormat(), state.getMagicDef(), state.getMagicBaseDef());
				drain = DefenceReadout.drain(config.defenceDrainFormat(), state.getMagicDef(), state.getMagicBaseDef());
				break;
			case LEVEL :
				text = DefenceReadout.value(config.defenceValueFormat(), state.getMagicLevel(),
						state.getMagicBaseLevel());
				drain = DefenceReadout.drain(config.defenceDrainFormat(), state.getMagicLevel(),
						state.getMagicBaseLevel());
				break;
			case PERCENT :
				if (rollPercent >= 100) {
					return null;
				}
				text = rollPercent + "%";
				break;
			case BOTH :
				text = rollPercent < 100
						? state.getMagicDef() + "  " + rollPercent + "%"
						: Long.toString(state.getMagicDef());
				drain = DefenceReadout.drain(config.defenceDrainFormat(), state.getMagicDef(), state.getMagicBaseDef());
				break;
			default :
				return null;
		}
		return new Segment(iconOrNull(skillIcons.magic()), text, config.magicDefenceColor(), drain);
	}

	private BufferedImage iconOrNull(BufferedImage image) {
		return config.defenceShowIcons() ? image : null;
	}

	private void drawRow(Graphics2D graphics, FontMetrics fm, int centreX, int baseline, List<Segment> segments) {
		int totalW = rowWidth(fm, segments);
		int x = centreX - totalW / 2;
		if (config.defenceTextPlate()) {
			graphics.setColor(PLATE_COLOR);
			graphics.fillRect(x - 2, baseline - fm.getAscent() - 1, totalW + 4, fm.getHeight() + 2);
		}
		for (Segment segment : segments) {
			x = drawSegment(graphics, fm, x, baseline, segment) + SEGMENT_GAP;
		}
	}

	private static int rowWidth(FontMetrics fm, List<Segment> segments) {
		int totalW = SEGMENT_GAP * (segments.size() - 1);
		for (Segment segment : segments) {
			totalW += width(fm, segment);
		}
		return totalW;
	}

	private static int width(FontMetrics fm, Segment segment) {
		int w = fm.stringWidth(segment.getText());
		if (segment.getIcon() != null) {
			w += segment.getIcon().getWidth() + GAP;
		}
		if (segment.getDrain() != null) {
			w += GAP + ARROW_WIDTH + 2 + fm.stringWidth(segment.getDrain());
		}
		return w;
	}

	private int drawSegment(Graphics2D graphics, FontMetrics fm, int x, int baseline, Segment segment) {
		int cursor = x;
		BufferedImage image = segment.getIcon();
		if (image != null) {
			graphics.drawImage(image, cursor, baseline - image.getHeight() + 2, null);
			cursor += image.getWidth() + GAP;
		}
		OverlayUtil.renderTextLocation(graphics, new Point(cursor, baseline), segment.getText(), segment.getColor());
		cursor += fm.stringWidth(segment.getText());
		if (segment.getDrain() != null) {
			cursor += GAP;
			drawDownArrow(graphics, cursor, baseline, fm.getAscent());
			cursor += ARROW_WIDTH + 2;
			OverlayUtil.renderTextLocation(graphics, new Point(cursor, baseline), segment.getDrain(),
					config.defenceDrainColor());
			cursor += fm.stringWidth(segment.getDrain());
		}
		return cursor;
	}

	private void drawDownArrow(Graphics2D graphics, int x, int baseline, int ascent) {
		int top = baseline - ascent + 2;
		int bottom = baseline - 1;
		Polygon tri = new Polygon();
		tri.addPoint(x, top);
		tri.addPoint(x + ARROW_WIDTH, top);
		tri.addPoint(x + ARROW_WIDTH / 2, bottom);
		graphics.setColor(config.defenceDrainColor());
		graphics.fill(tri);
	}

	static final class IconSource {
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

		IconSource(Client client, SkillIconManager skillIconManager, BetterPartyDefenceConfig config) {
			this.client = client;
			this.skillIconManager = skillIconManager;
			this.config = config;
		}

		BufferedImage defence() {
			return defence(config.defenceUseThemeSkillIcons());
		}

		BufferedImage defence(boolean useTheme) {
			return icon(Skill.DEFENCE, SpriteID.Staticons.DEFENCE, true, useTheme);
		}

		BufferedImage magic() {
			return magic(config.defenceUseThemeSkillIcons());
		}

		BufferedImage magic(boolean useTheme) {
			return icon(Skill.MAGIC, SpriteID.Staticons.MAGIC, false, useTheme);
		}

		private BufferedImage icon(Skill skill, int spriteId, boolean defence, boolean useTheme) {
			if (useTheme) {
				SpritePixels override = client.getSpriteOverrides().get(spriteId);
				if (override != null) {
					if (defence) {
						if (override != lastDefenceOverride || themedDefence == null) {
							lastDefenceOverride = override;
							themedDefence = ImageUtil.resizeImage(
									override.toBufferedImage(), ICON_SIZE, ICON_SIZE);
						}
						return themedDefence;
					}
					if (override != lastMagicOverride || themedMagic == null) {
						lastMagicOverride = override;
						themedMagic = ImageUtil.resizeImage(
								override.toBufferedImage(), ICON_SIZE, ICON_SIZE);
					}
					return themedMagic;
				}
			}
			if (defence) {
				if (classicDefence == null) {
					classicDefence = ImageUtil.resizeImage(
							skillIconManager.getSkillImage(skill), ICON_SIZE, ICON_SIZE);
				}
				return classicDefence;
			}
			if (classicMagic == null) {
				classicMagic = ImageUtil.resizeImage(
						skillIconManager.getSkillImage(skill), ICON_SIZE, ICON_SIZE);
			}
			return classicMagic;
		}
	}
}
