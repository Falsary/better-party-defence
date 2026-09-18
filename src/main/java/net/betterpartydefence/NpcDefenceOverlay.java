package net.betterpartydefence;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
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
	private final PreviousTargetDisplayState previousTargetState;
	private final InitialSyncDisplayGate initialSyncDisplayGate;

	public NpcDefenceOverlay(Client client, DefenceTracker tracker, BetterPartyDefenceConfig config,
		SkillIconSource skillIcons, TrackerFontManager fontManager,
		PreviousTargetDisplayState previousTargetState, InitialSyncDisplayGate initialSyncDisplayGate)
	{
		this.client = client;
		this.tracker = tracker;
		this.config = config;
		this.fontManager = fontManager;
		this.renderer = new DefenceOverlayRenderer(config, skillIcons);
		this.previousTargetState = previousTargetState;
		this.initialSyncDisplayGate = initialSyncDisplayGate;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.defenceHpBar())
		{
			return null;
		}

		graphics.setFont(fontManager.font());
		DefenceState current = tracker.state();
		List<DefenceState> states = tracker.states();

		if (!config.previousTargetDisplay())
		{
			renderLegacyAttached(graphics, states);
			return null;
		}

		renderMainTarget(graphics, current);

		DefenceState previous = previousTargetState.previousState();
		if (previous != null)
		{
			NPC npc = liveNpc(previous.getNpcIndex());
			if (npc != null)
			{
				renderAtNpc(graphics, previous, npc,
					config.defenceHpBarPosition(), config.defenceHpBarYOffset());
			}
		}
		return null;
	}

	/** Exact pre-feature attached rendering path, used whenever the experiment is disabled. */
	private void renderLegacyAttached(Graphics2D graphics, List<DefenceState> states)
	{
		DefenceOverlayPosition position = config.defenceHpBarPosition();
		for (DefenceState state : states)
		{
			if (!isDisplayable(state))
			{
				continue;
			}
			NPC npc = liveNpc(state.getNpcIndex());
			if (npc != null)
			{
				renderAtNpc(graphics, state, npc, position, config.defenceHpBarYOffset());
			}
		}
	}

	private void renderMainTarget(Graphics2D graphics, DefenceState current)
	{
		if (!isDisplayable(current))
		{
			return;
		}
		NPC npc = liveNpc(current.getNpcIndex());
		if (npc != null)
		{
			renderAtNpc(graphics, current, npc,
				config.defenceHpBarPosition(), config.defenceHpBarYOffset());
		}
	}

	private void renderAtNpc(Graphics2D graphics, DefenceState state, NPC npc,
		DefenceOverlayPosition position, int yOffset)
	{
		int zOffset = (int) (npc.getLogicalHeight() * position.getHeightFactor()) + position.getHeightOffset();
		Point anchor = npc.getCanvasTextLocation(graphics, "", zOffset);
		if (anchor == null)
		{
			return;
		}
		int centreX = anchor.getX() + position.getXNudge();
		int baseline = anchor.getY() - yOffset;
		renderer.renderAt(graphics, state, centreX, baseline);
	}

	private boolean isDisplayable(DefenceState state)
	{
		// The checkbox controls only optional pre-spec preview. Any actual local/party drain
		// remains displayable because drained=true is part of the stored/shared state.
		return state != null
			&& (state.isDrained() || config.defenceAlwaysShow())
			&& state.getNpcIndex() >= 0
			&& !initialSyncDisplayGate.isWaiting(state, client.getTickCount());
	}

	private NPC liveNpc(int index)
	{
		if (index < 0 || client.getTopLevelWorldView() == null || client.getTopLevelWorldView().npcs() == null)
		{
			return null;
		}
		NPC npc = client.getTopLevelWorldView().npcs().byIndex(index);
		return npc != null && !npc.isDead() && npc.getHealthRatio() != 0 ? npc : null;
	}
}
