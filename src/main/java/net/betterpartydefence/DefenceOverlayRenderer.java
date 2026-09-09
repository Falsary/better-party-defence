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

/**
 * Shared renderer for both the NPC-attached and detached tracker.
 * Keeping the drawing in one place guarantees Detached is literally the same
 * readout as Attached; only the anchor point is different.
 */
final class DefenceOverlayRenderer
{
	private static final Color PLATE_COLOR = new Color(0, 0, 0, 150);
	private static final int GAP = 3;
	private static final int SEGMENT_GAP = 8;
	private static final int ARROW_WIDTH = 7;
	private static final int EDGE_PADDING = 2;

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

	DefenceOverlayRenderer(BetterPartyDefenceConfig config, SkillIconSource skillIcons)
	{
		this.config = config;
		this.skillIcons = skillIcons;
	}

	/** Draw the tracker centred on an arbitrary anchor, used by the NPC overlay. */
	void renderAt(Graphics2D graphics, DefenceState state, int centreX, int baseline)
	{
		FontMetrics fm = graphics.getFontMetrics();
		List<List<Segment>> rows = rows(state);
		for (int i = 0; i < rows.size(); i++)
		{
			drawRow(graphics, fm, centreX, baseline + i * fm.getHeight(), rows.get(i));
		}
	}

	/**
	 * Draw the same tracker at (0,0) for a movable RuneLite overlay and return its
	 * exact draggable bounds.
	 */
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
		Segment magic = config.magicDefence() ? magicSegment(state) : null;
		if (magic == null)
		{
			return Collections.singletonList(Collections.singletonList(defence));
		}
		if (config.magicDefenceSameRow())
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
		boolean full = config.defenceShowFullLevel();
		long current = DefenceReadout.shownDefence(state, full);
		long base = DefenceReadout.shownBaseDefence(state, full);
		return new Segment(iconOrNull(skillIcons.defence()),
			DefenceReadout.value(config.defenceValueFormat(), current, base),
			DefenceReadout.defenceColor(state, config),
			DefenceReadout.drain(config.defenceDrainFormat(), current, base));
	}

	private Segment magicSegment(DefenceState state)
	{
		long rollPercent = DefenceReadout.percentRemaining(state.getMagicRoll(), state.getMagicBaseRoll());
		String text;
		String drain = null;
		switch (config.magicDefenceDisplay())
		{
			case BONUS:
				text = DefenceReadout.value(config.defenceValueFormat(), state.getMagicDef(), state.getMagicBaseDef());
				drain = DefenceReadout.drain(config.defenceDrainFormat(), state.getMagicDef(), state.getMagicBaseDef());
				break;
			case LEVEL:
				text = DefenceReadout.value(config.defenceValueFormat(), state.getMagicLevel(), state.getMagicBaseLevel());
				drain = DefenceReadout.drain(config.defenceDrainFormat(), state.getMagicLevel(), state.getMagicBaseLevel());
				break;
			case PERCENT:
				if (rollPercent >= 100)
				{
					return null;
				}
				text = rollPercent + "%";
				break;
			case BOTH:
				text = rollPercent < 100 ? state.getMagicDef() + "  " + rollPercent + "%" : Long.toString(state.getMagicDef());
				drain = DefenceReadout.drain(config.defenceDrainFormat(), state.getMagicDef(), state.getMagicBaseDef());
				break;
			default:
				return null;
		}
		return new Segment(iconOrNull(skillIcons.magic()), text, config.magicDefenceColor(), drain);
	}

	private BufferedImage iconOrNull(BufferedImage image)
	{
		return config.defenceShowIcons() ? image : null;
	}

	private void drawRow(Graphics2D graphics, FontMetrics fm, int centreX, int baseline, List<Segment> segments)
	{
		int totalW = rowWidth(fm, segments);
		int x = centreX - totalW / 2;
		if (config.defenceTextPlate())
		{
			graphics.setColor(PLATE_COLOR);
			graphics.fillRect(x - 2, baseline - fm.getAscent() - 1, totalW + 4, fm.getHeight() + 2);
		}
		for (Segment segment : segments)
		{
			x = drawSegment(graphics, fm, x, baseline, segment) + SEGMENT_GAP;
		}
	}

	private static int rowWidth(FontMetrics fm, List<Segment> segments)
	{
		int totalW = SEGMENT_GAP * (segments.size() - 1);
		for (Segment segment : segments)
		{
			totalW += width(fm, segment);
		}
		return totalW;
	}

	private static int width(FontMetrics fm, Segment segment)
	{
		int w = fm.stringWidth(segment.getText());
		if (segment.getIcon() != null)
		{
			w += segment.getIcon().getWidth() + GAP;
		}
		if (segment.getDrain() != null)
		{
			w += GAP + ARROW_WIDTH + 2 + fm.stringWidth(segment.getDrain());
		}
		return w;
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
			OverlayUtil.renderTextLocation(graphics, new Point(cursor, baseline), segment.getDrain(), config.defenceDrainColor());
			cursor += fm.stringWidth(segment.getDrain());
		}
		return cursor;
	}

	private void drawDownArrow(Graphics2D graphics, int x, int baseline, int ascent)
	{
		int top = baseline - ascent + 2;
		int bottom = baseline - 1;
		Polygon tri = new Polygon();
		tri.addPoint(x, top);
		tri.addPoint(x + ARROW_WIDTH, top);
		tri.addPoint(x + ARROW_WIDTH / 2, bottom);
		graphics.setColor(config.defenceDrainColor());
		graphics.fill(tri);
	}
}
