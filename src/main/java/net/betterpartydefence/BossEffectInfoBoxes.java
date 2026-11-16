package net.betterpartydefence;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;
import net.betterpartydefence.DefenceTracker.EffectState;
import net.betterpartydefence.DefenceTracker.SpellDrain;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;

// Status-bar presentation for landed boss effects.
final class BossEffectInfoBoxes {
	private enum Effect {
		VULNERABILITY("Vulnerability"),
		SHADOW_BARRAGE("Shadow Barrage"),
		RED_KERIS("Red Keris");

		private final String label;

		Effect(String label) {
			this.label = label;
		}
	}

	private final Client client;
	private final DefenceTracker tracker;
	private final Map<Effect, EffectBox> boxes = new EnumMap<>(Effect.class);

	BossEffectInfoBoxes(Client client, DefenceTracker tracker) {
		this.client = client;
		this.tracker = tracker;
	}

	void update(Plugin plugin, InfoBoxManager manager, ItemManager items, SkillIconManager icons,
			boolean showRedKeris, boolean showShadowBarrage, boolean showVulnerability) {
		int tick = client.getTickCount();
		NPC selected = selectedBoss();
		EffectState state = tracker.effectState(selected, false);
		int kerisRemaining = state == null ? 0 : Math.max(0, state.kerisExpiry - tick);

		updateBox(Effect.VULNERABILITY,
				showVulnerability && ((state != null && state.vulnerabilityLanded)
						|| tracker.hasLandedSpell(selected, SpellDrain.VULNERABILITY)
						|| tracker.hasLandedSpell(selected, SpellDrain.VULNERABILITY_TOME)),
				"", "Landed", plugin, manager, items, icons);
		updateBox(Effect.SHADOW_BARRAGE,
				showShadowBarrage && ((state != null && state.shadowLanded)
						|| tracker.hasLandedSpell(selected, SpellDrain.SHADOW_BARRAGE)),
				"", state != null && state.shadowLandedOnReducedDefence
						? "Landed Â· Defence already reduced"
						: "Landed",
				plugin, manager, items, icons);
		updateBox(Effect.RED_KERIS, showRedKeris && kerisRemaining > 0,
				Integer.toString(kerisRemaining),
				"Landed Â· Target takes increased damage", plugin, manager, items, icons);
	}

	void hide(InfoBoxManager manager) {
		for (EffectBox box : boxes.values()) {
			manager.removeInfoBox(box);
		}
		boxes.clear();
	}

	private NPC selectedBoss() {
		Player local = client.getLocalPlayer();
		Actor target = local == null ? null : local.getInteracting();
		if (target != null) {
			return target instanceof NPC && isCurrentActor((NPC) target) ? (NPC) target : null;
		}
		DefenceTracker.DefenceState state = tracker.state();
		return state != null && tracker.hasBoundNpc() ? npcAtIndex(state.getNpcIndex()) : null;
	}

	private NPC npcAtIndex(int index) {
		WorldView world = client.getTopLevelWorldView();
		return world == null || world.npcs() == null ? null : world.npcs().byIndex(index);
	}

	private boolean isCurrentActor(NPC npc) {
		return BossDefence.isLiveNpc(npc) && npcAtIndex(npc.getIndex()) == npc;
	}

	private void updateBox(Effect effect, boolean show, String text, String status, Plugin plugin,
			InfoBoxManager manager, ItemManager items, SkillIconManager icons) {
		EffectBox box = boxes.get(effect);
		if (!show) {
			if (box != null) {
				manager.removeInfoBox(box);
				boxes.remove(effect);
			}
			return;
		}
		if (box == null) {
			BufferedImage icon = effect == Effect.VULNERABILITY
					? icons.getSkillImage(Skill.MAGIC)
					: items.getImage(effect == Effect.RED_KERIS
							? ItemID.KERIS_PARTISAN_CORRUPTION
							: ItemID.ANCIENT_SCEPTRE_SHADOW);
			box = new EffectBox(icon, plugin);
			boxes.put(effect, box);
			manager.addInfoBox(box);
		}
		box.text = text;
		box.setTooltip(effect.label + "<br>" + status);
	}

	private static final class EffectBox extends InfoBox {
		private String text = "";

		private EffectBox(BufferedImage image, Plugin plugin) {
			super(image, plugin);
		}

		@Override
		public String getText() {
			return text;
		}

		@Override
		public Color getTextColor() {
			return Color.WHITE;
		}
	}
}
