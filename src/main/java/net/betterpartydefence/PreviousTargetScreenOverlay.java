package net.betterpartydefence;

import java.awt.Dimension;
import java.awt.Graphics2D;
import net.betterpartydefence.DefenceTracker.DefenceState;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Independently movable detached overlay for the experimental previous-target display. */
public class PreviousTargetScreenOverlay extends Overlay
{
	private final BetterPartyDefenceConfig config;
	private final PreviousTargetDisplayState previousTargetState;
	private final TrackerFontManager fontManager;
	private final PreviousTargetOverlayRenderer renderer;

	public PreviousTargetScreenOverlay(BetterPartyDefenceConfig config,
		PreviousTargetDisplayState previousTargetState, SkillIconSource skillIcons,
		TrackerFontManager fontManager)
	{
		this.config = config;
		this.previousTargetState = previousTargetState;
		this.fontManager = fontManager;
		this.renderer = new PreviousTargetOverlayRenderer(config, skillIcons);
		setPosition(OverlayPosition.TOP_RIGHT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
		setSnappable(true);
		setResizable(false);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.defenceHpBar() || !config.previousTargetDisplay()
			|| config.previousTargetDisplayMode() != DefenceDisplayMode.DETACHED)
		{
			return null;
		}

		DefenceState previous = previousTargetState.previousState();
		if (previous == null)
		{
			return null;
		}

		graphics.setFont(fontManager.previousTargetFont());
		return renderer.renderDetached(graphics, previous);
	}
}
