package net.betterpartydefence;

import java.awt.Dimension;
import java.awt.Graphics2D;
import net.betterpartydefence.DefenceTracker.DefenceState;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Draws the tracked NPC's Defence in scene space. */
public class NpcDefenceOverlay extends Overlay
{
	private final Client client;
	private final DefenceTracker tracker;
	private final BetterPartyDefenceConfig config;
	private final TrackerFontManager fontManager;
	private final DefenceOverlayRenderer renderer;

	public NpcDefenceOverlay(Client client, DefenceTracker tracker, BetterPartyDefenceConfig config,
		SkillIconSource skillIcons, TrackerFontManager fontManager)
	{
		this.client = client;
		this.tracker = tracker;
		this.config = config;
		this.fontManager = fontManager;
		this.renderer = new DefenceOverlayRenderer(config, skillIcons);
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.defenceHpBar() || config.defenceDisplayMode() != DefenceDisplayMode.NPC)
		{
			return null;
		}
		DefenceState state = tracker.state();
		if (state == null)
		{
			return null;
		}
		NPC npc = npcByIndex(state.getNpcIndex());
		if (npc == null)
		{
			return null;
		}

		graphics.setFont(fontManager.font());
		DefenceOverlayPosition position = config.defenceHpBarPosition();
		int zOffset = (int) (npc.getLogicalHeight() * position.getHeightFactor()) + position.getHeightOffset();
		Point anchor = npc.getCanvasTextLocation(graphics, "", zOffset);
		if (anchor == null)
		{
			return null;
		}
		int centreX = anchor.getX() + position.getXNudge();
		int baseline = anchor.getY() - config.defenceHpBarYOffset();
		renderer.renderAt(graphics, state, centreX, baseline);
		return null;
	}

	private NPC npcByIndex(int index)
	{
		return client.getTopLevelWorldView().npcs().byIndex(index);
	}
}
