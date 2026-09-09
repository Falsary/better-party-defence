package net.betterpartydefence;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.util.List;
import net.betterpartydefence.DefenceTracker.DefenceState;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.client.ui.FontManager;
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
	private final PreviousTargetOverlayRenderer previousRenderer;

	/** Rendering-only target history for the experimental previous-target marker. */
	private boolean previousTrackingActive;
	private int lastActiveNpcIndex = -1;
	private BossDefence lastActiveBoss;
	private Integer previousTargetNpcIndex;

	public NpcDefenceOverlay(Client client, DefenceTracker tracker, BetterPartyDefenceConfig config,
		SkillIconSource skillIcons, TrackerFontManager fontManager)
	{
		this.client = client;
		this.tracker = tracker;
		this.config = config;
		this.fontManager = fontManager;
		this.renderer = new DefenceOverlayRenderer(config, skillIcons);
		this.previousRenderer = new PreviousTargetOverlayRenderer(config, skillIcons);
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

		DefenceState current = tracker.state();
		List<DefenceState> states = tracker.states();
		if (!config.previousTargetDisplay())
		{
			resetPreviousTargetTracking(current);
			if (config.defenceDisplayMode() == DefenceDisplayMode.NPC)
			{
				renderLegacyAttached(graphics, states);
			}
			return null;
		}

		updatePreviousTarget(current, states);

		// The active target keeps the existing display behavior and styling. In detached mode it
		// is rendered only by ScreenDefenceOverlay, so it is never duplicated above the NPC.
		if (config.defenceDisplayMode() == DefenceDisplayMode.NPC)
		{
			graphics.setFont(fontManager.font());
			renderMainTarget(graphics, current);
		}

		// The previous marker is always a simple attached, above-HP-bar display with independent
		// typography/icon controls. It never changes or mutates the underlying tracker state.
		DefenceState previous = stateByIndex(states, previousTargetNpcIndex);
		if (previous != null && previous.isDrained()
			&& (current == null || previous.getNpcIndex() != current.getNpcIndex()))
		{
			NPC npc = liveNpc(previous.getNpcIndex());
			if (npc != null)
			{
				graphics.setFont(previousTargetFont());
				renderPreviousTarget(graphics, previous, npc);
			}
		}
		return null;
	}

	/** Exact pre-feature attached rendering path, used whenever the experiment is disabled. */
	private void renderLegacyAttached(Graphics2D graphics, List<DefenceState> states)
	{
		graphics.setFont(fontManager.font());
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
				renderAtNpc(graphics, renderer, state, npc, position, config.defenceHpBarYOffset());
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
			renderAtNpc(graphics, renderer, current, npc,
				config.defenceHpBarPosition(), config.defenceHpBarYOffset());
		}
	}

	private void renderPreviousTarget(Graphics2D graphics, DefenceState previous, NPC npc)
	{
		DefenceOverlayPosition position = DefenceOverlayPosition.ABOVE_HP_BAR;
		int zOffset = (int) (npc.getLogicalHeight() * position.getHeightFactor()) + position.getHeightOffset();
		Point anchor = npc.getCanvasTextLocation(graphics, "", zOffset);
		if (anchor == null)
		{
			return;
		}
		int centreX = anchor.getX() + position.getXNudge();
		previousRenderer.renderAt(graphics, previous, centreX, anchor.getY());
	}

	private void renderAtNpc(Graphics2D graphics, DefenceOverlayRenderer targetRenderer,
		DefenceState state, NPC npc, DefenceOverlayPosition position, int yOffset)
	{
		int zOffset = (int) (npc.getLogicalHeight() * position.getHeightFactor()) + position.getHeightOffset();
		Point anchor = npc.getCanvasTextLocation(graphics, "", zOffset);
		if (anchor == null)
		{
			return;
		}
		int centreX = anchor.getX() + position.getXNudge();
		int baseline = anchor.getY() - yOffset;
		targetRenderer.renderAt(graphics, state, centreX, baseline);
	}

	private void updatePreviousTarget(DefenceState current, List<DefenceState> states)
	{
		if (!previousTrackingActive)
		{
			previousTrackingActive = true;
			lastActiveNpcIndex = boundIndex(current);
			lastActiveBoss = lastActiveNpcIndex >= 0 ? tracker.trackedBossType() : null;
			previousTargetNpcIndex = null;
			return;
		}

		if (states.isEmpty())
		{
			lastActiveNpcIndex = -1;
			lastActiveBoss = null;
			previousTargetNpcIndex = null;
			return;
		}

		int currentIndex = boundIndex(current);
		if (currentIndex < 0 || currentIndex == lastActiveNpcIndex)
		{
			if (stateByIndex(states, previousTargetNpcIndex) == null)
			{
				previousTargetNpcIndex = null;
			}
			return;
		}

		BossDefence currentBoss = tracker.trackedBossType();
		DefenceState prior = stateByIndex(states, lastActiveNpcIndex);
		NPC priorNpc = prior == null ? null : npcByIndex(prior.getNpcIndex());

		// A same-boss actor replacement with the old actor gone is a rebind/phase transition,
		// not a user-visible target switch. Do not manufacture a sticky duplicate for it.
		boolean sameLogicalRebind = prior != null && lastActiveBoss == currentBoss && priorNpc == null;
		if (!sameLogicalRebind && prior != null && prior.isDrained())
		{
			previousTargetNpcIndex = prior.getNpcIndex();
		}
		else if (prior == null)
		{
			// The old target ended/was removed, so an older marker must not leak forward.
			previousTargetNpcIndex = null;
		}

		lastActiveNpcIndex = currentIndex;
		lastActiveBoss = currentBoss;
		if (previousTargetNpcIndex != null && previousTargetNpcIndex == currentIndex)
		{
			previousTargetNpcIndex = null;
		}
	}

	private void resetPreviousTargetTracking(DefenceState current)
	{
		previousTrackingActive = false;
		lastActiveNpcIndex = boundIndex(current);
		lastActiveBoss = lastActiveNpcIndex >= 0 ? tracker.trackedBossType() : null;
		previousTargetNpcIndex = null;
	}

	private boolean isDisplayable(DefenceState state)
	{
		// The checkbox controls only optional pre-spec preview. Any actual local/party drain
		// remains displayable because drained=true is part of the stored/shared state.
		return state != null && (state.isDrained() || config.defenceAlwaysShow()) && state.getNpcIndex() >= 0;
	}

	private static int boundIndex(DefenceState state)
	{
		return state == null ? -1 : state.getNpcIndex();
	}

	private static DefenceState stateByIndex(List<DefenceState> states, Integer index)
	{
		if (index == null || index < 0)
		{
			return null;
		}
		for (DefenceState state : states)
		{
			if (state != null && state.getNpcIndex() == index)
			{
				return state;
			}
		}
		return null;
	}

	private Font previousTargetFont()
	{
		int size = Math.max(8, Math.min(48, config.previousTargetFontSize()));
		Font base = FontManager.getRunescapeSmallFont().deriveFont((float) size);
		return base.deriveFont(config.previousTargetBoldText() ? Font.BOLD : Font.PLAIN);
	}

	private NPC liveNpc(int index)
	{
		NPC npc = npcByIndex(index);
		return npc != null && !npc.isDead() && npc.getHealthRatio() != 0 ? npc : null;
	}

	private NPC npcByIndex(int index)
	{
		if (index < 0 || client.getTopLevelWorldView() == null || client.getTopLevelWorldView().npcs() == null)
		{
			return null;
		}
		return client.getTopLevelWorldView().npcs().byIndex(index);
	}
}
