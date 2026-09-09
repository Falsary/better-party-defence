package net.betterpartydefence;

import java.awt.Dimension;
import java.awt.Graphics2D;
import net.betterpartydefence.DefenceTracker.DefenceState;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Movable detached copy of the exact NPC Defence readout. Hold Alt and drag to reposition it. */
public class ScreenDefenceOverlay extends Overlay
{
	private final DefenceTracker tracker;
	private final BetterPartyDefenceConfig config;
	private final TrackerFontManager fontManager;
	private final DefenceOverlayRenderer renderer;

	public ScreenDefenceOverlay(DefenceTracker tracker, BetterPartyDefenceConfig config,
		SkillIconSource skillIcons, TrackerFontManager fontManager)
	{
		this.tracker = tracker;
		this.config = config;
		this.fontManager = fontManager;
		this.renderer = new DefenceOverlayRenderer(config, skillIcons);
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
		setSnappable(true);
		setResizable(false);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.defenceHpBar() || config.defenceDisplayMode() != DefenceDisplayMode.DETACHED)
		{
			return null;
		}
		DefenceState state = tracker.state();
		if (state == null)
		{
			return null;
		}

		graphics.setFont(fontManager.font());
		return renderer.renderDetached(graphics, state);
	}
}
