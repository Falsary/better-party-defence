package net.betterpartydefence;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.Value;
import net.betterpartydefence.DefenceTracker.DefenceState;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.OverlayUtil;

/** Draws the experimental previous-target display using its own independent UI profile. */
final class PreviousTargetOverlayRenderer
{
	private static final Color PLATE_COLOR = new Color(0, 0, 0, 150);
	private static final int GAP = 3;
	private static final int SEGMENT_GAP = 8;
	private static final int ARROW_WIDTH = 7;
	private static final int EDGE_PADDING = 2;
	private static final int PERCENT = 100;

	private final BetterPartyDefenceConfig config;
	private final SkillIconSource skillIcons;

	@Value
	private static class Segment
	{
		BufferedImage icon;
		String text;
		Color color;
		String drain;
	}

	PreviousTargetOverlayRenderer(BetterPartyDefenceConfig config, SkillIconSource skillIcons)
	{
		this.config = config;
		this.skillIcons = skillIcons;
	}

	void renderAt(Graphics2D graphics, DefenceState state, int centreX, int baseline)
	{
		FontMetrics fm = graphics.getFontMetrics();
		List<List<Segment>> rows = rows(state);
		for (int i = 0; i < rows.size(); i++)
		{
			drawRow(graphics, fm, centreX, baseline + i * fm.getHeight(), rows.get(i));
		}
	}

	Dimension renderDetached(Graphics2D graphics, DefenceState state)
	{
		FontMetrics fm = graphics.getFontMetrics();
		List<List<Segment>> rows = rows(state);
		if (rows.isEmpty())
		{
			return null;
		}

		int maxRowWidth = 0;
		int maxIconHeight = 0;
		for (List<Segment> row : rows)
		{
			maxRowWidth = Math.max(maxRowWidth, rowWidth(fm, row));
			for (Segment segment : row)
			{
				if (segment.getIcon() != null)
				{
					maxIconHeight = Math.max(maxIconHeight, segment.getIcon().getHeight());
				}
			}
		}

		int firstBaseline = Math.max(fm.getAscent() + 1, maxIconHeight - 2) + EDGE_PADDING;
		int width = maxRowWidth + EDGE_PADDING * 2;
		int centreX = width / 2;
		for (int i = 0; i < rows.size(); i++)
		{
			drawRow(graphics, fm, centreX, firstBaseline + i * fm.getHeight(), rows.get(i));
		}

		int lastBaseline = firstBaseline + (rows.size() - 1) * fm.getHeight();
		int bottom = lastBaseline + Math.max(fm.getDescent() + 2, 3) + EDGE_PADDING;
		return new Dimension(width, bottom);
	}

	private List<List<Segment>> rows(DefenceState state)
	{
		Segment defence = defenceSegment(state);
		Segment magic = config.previousTargetMagicDefence() ? magicSegment(state) : null;
		if (magic == null)
		{
			return Collections.singletonList(Collections.singletonList(defence));
		}
		if (config.previousTargetMagicDefenceSameRow())
		{
			return Collections.singletonList(Arrays.asList(defence, magic));
		}
		List<List<Segment>> rows = new ArrayList<>(2);
		rows.add(Collections.singletonList(defence));
		rows.add(Collections.singletonList(magic));
		return rows;
	}

	private Segment defenceSegment(DefenceState state)
	{
		boolean full = config.previousTargetShowFullLevel();
		long current = DefenceReadout.shownDefence(state, full);
		long base = DefenceReadout.shownBaseDefence(state, full);
		return new Segment(iconOrNull(skillIcons.defence(config.previousTargetUseThemeSkillIcons())),
			DefenceReadout.value(config.previousTargetValueFormat(), current, base),
			defenceColor(state),
			DefenceReadout.drain(config.previousTargetDrainFormat(), current, base));
	}

	private Segment magicSegment(DefenceState state)
	{
		long rollPercent = DefenceReadout.percentRemaining(state.getMagicRoll(), state.getMagicBaseRoll());
		String text;
		String drain = null;
		switch (config.previousTargetMagicDefenceDisplay())
		{
			case BONUS:
				text = DefenceReadout.value(config.previousTargetValueFormat(), state.getMagicDef(), state.getMagicBaseDef());
				drain = DefenceReadout.drain(config.previousTargetDrainFormat(), state.getMagicDef(), state.getMagicBaseDef());
				break;
			case LEVEL:
				text = DefenceReadout.value(config.previousTargetValueFormat(), state.getMagicLevel(), state.getMagicBaseLevel());
				drain = DefenceReadout.drain(config.previousTargetDrainFormat(), state.getMagicLevel(), state.getMagicBaseLevel());
				break;
			case PERCENT:
				if (rollPercent >= PERCENT)
				{
					return null;
				}
				text = rollPercent + "%";
				break;
			case BOTH:
				text = rollPercent < PERCENT
					? state.getMagicDef() + "  " + rollPercent + "%"
					: Long.toString(state.getMagicDef());
				drain = DefenceReadout.drain(config.previousTargetDrainFormat(), state.getMagicDef(), state.getMagicBaseDef());
				break;
			default:
				return null;
		}
		return new Segment(iconOrNull(skillIcons.magic(config.previousTargetUseThemeSkillIcons())),
			text, config.previousTargetMagicDefenceColor(), drain);
	}

	private Color defenceColor(DefenceState state)
	{
		long aboveFloor = Math.max(state.getCurrent() - state.getMin(), 0);
		if (aboveFloor == 0)
		{
			return config.previousTargetCappedColor();
		}
		long threshold = config.previousTargetLowThresholdUnit() == DefenceThresholdUnit.PERCENT
			? (state.getBase() - state.getMin()) * Math.min(PERCENT, config.previousTargetLowThreshold()) / PERCENT
			: config.previousTargetLowThreshold();
		return aboveFloor <= threshold ? config.previousTargetLowColor() : config.previousTargetHighColor();
	}

	private BufferedImage iconOrNull(BufferedImage image)
	{
		return config.previousTargetShowIcons() ? image : null;
	}

	private void drawRow(Graphics2D graphics, FontMetrics fm, int centreX, int baseline, List<Segment> segments)
	{
		int totalWidth = rowWidth(fm, segments);
		int x = centreX - totalWidth / 2;
		if (config.previousTargetTextPlate())
		{
			graphics.setColor(PLATE_COLOR);
			graphics.fillRect(x - 2, baseline - fm.getAscent() - 1, totalWidth + 4, fm.getHeight() + 2);
		}
		for (Segment segment : segments)
		{
			x = drawSegment(graphics, fm, x, baseline, segment) + SEGMENT_GAP;
		}
	}

	private static int rowWidth(FontMetrics fm, List<Segment> segments)
	{
		int totalWidth = SEGMENT_GAP * (segments.size() - 1);
		for (Segment segment : segments)
		{
			totalWidth += width(fm, segment);
		}
		return totalWidth;
	}

	private static int width(FontMetrics fm, Segment segment)
	{
		int width = fm.stringWidth(segment.getText());
		if (segment.getIcon() != null)
		{
			width += segment.getIcon().getWidth() + GAP;
		}
		if (segment.getDrain() != null)
		{
			width += GAP + ARROW_WIDTH + 2 + fm.stringWidth(segment.getDrain());
		}
		return width;
	}

	private int drawSegment(Graphics2D graphics, FontMetrics fm, int x, int baseline, Segment segment)
	{
		int cursor = x;
		BufferedImage image = segment.getIcon();
		if (image != null)
		{
			graphics.drawImage(image, cursor, baseline - image.getHeight() + 2, null);
			cursor += image.getWidth() + GAP;
		}
		OverlayUtil.renderTextLocation(graphics, new Point(cursor, baseline), segment.getText(), segment.getColor());
		cursor += fm.stringWidth(segment.getText());
		if (segment.getDrain() != null)
		{
			cursor += GAP;
			drawDownArrow(graphics, cursor, baseline, fm.getAscent());
			cursor += ARROW_WIDTH + 2;
			OverlayUtil.renderTextLocation(graphics, new Point(cursor, baseline), segment.getDrain(), config.previousTargetDrainColor());
			cursor += fm.stringWidth(segment.getDrain());
		}
		return cursor;
	}

	private void drawDownArrow(Graphics2D graphics, int x, int baseline, int ascent)
	{
		int top = baseline - ascent + 2;
		int bottom = baseline - 1;
		Polygon triangle = new Polygon();
		triangle.addPoint(x, top);
		triangle.addPoint(x + ARROW_WIDTH, top);
		triangle.addPoint(x + ARROW_WIDTH / 2, bottom);
		graphics.setColor(config.previousTargetDrainColor());
		graphics.fill(triangle);
	}
}
