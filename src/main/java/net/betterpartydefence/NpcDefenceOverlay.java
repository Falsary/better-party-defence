package net.betterpartydefence;

import java.awt.Dimension;
import java.awt.Graphics2D;
import net.betterpartydefence.BetterPartyDefenceConfig.DefenceOverlayPosition;
import net.betterpartydefence.DefenceTracker.DefenceState;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

public class NpcDefenceOverlay extends Overlay {
	private final Client client;
	private final DefenceTracker tracker;
	private final BetterPartyDefenceConfig config;
	private final DefenceOverlayRenderer renderer;
	private final InitialSyncDisplayGate initialSyncDisplayGate;

	public NpcDefenceOverlay(Client client, DefenceTracker tracker, BetterPartyDefenceConfig config,
			DefenceOverlayRenderer.IconSource skillIcons, InitialSyncDisplayGate initialSyncDisplayGate) {
		this.client = client;
		this.tracker = tracker;
		this.config = config;
		this.renderer = new DefenceOverlayRenderer(config, skillIcons);
		this.initialSyncDisplayGate = initialSyncDisplayGate;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		if (!config.defenceHpBar()) {
			return null;
		}

		graphics.setFont(renderer.font());
		DefenceOverlayPosition position = config.defenceHpBarPosition();
		int yOffset = config.defenceHpBarYOffset();
		for (DefenceState state : tracker.states()) {
			if (!isDisplayable(state)) {
				continue;
			}
			NPC npc = liveNpc(state.getNpcIndex());
			if (npc != null) {
				renderAtNpc(graphics, state, npc, position, yOffset);
			}
		}
		return null;
	}

	private void renderAtNpc(Graphics2D graphics, DefenceState state, NPC npc,
			DefenceOverlayPosition position, int yOffset) {
		// Ocellus P2 uses the model midpoint as its display height.
		int zOffset;
		if (BossDefence.isAttackableOcellusPhaseTwo(npc)) {
			zOffset = (int) (npc.getLogicalHeight() * 0.5 * position.getHeightFactor());
		} else {
			zOffset = (int) (npc.getLogicalHeight() * position.getHeightFactor()) + position.getHeightOffset();
		}
		Point anchor = npc.getCanvasTextLocation(graphics, "", zOffset);
		if (anchor == null) {
			return;
		}
		int centreX = anchor.getX() + position.getXNudge();
		int baseline = anchor.getY() - yOffset;
		renderer.renderAt(graphics, state, centreX, baseline);
	}

	private boolean isDisplayable(DefenceState state) {
		// Config controls previews only; drained state is always shown.
		return state != null && (state.isDrained() || config.defenceAlwaysShow())
				&& state.getNpcIndex() >= 0
				&& !initialSyncDisplayGate.isWaiting(state, client.getTickCount());
	}

	private NPC liveNpc(int index) {
		if (index < 0 || client.getTopLevelWorldView() == null || client.getTopLevelWorldView().npcs() == null) {
			return null;
		}
		NPC npc = client.getTopLevelWorldView().npcs().byIndex(index);
		return BossDefence.isLiveNpc(npc) ? npc : null;
	}
}
