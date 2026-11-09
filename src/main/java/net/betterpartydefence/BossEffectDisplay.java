package net.betterpartydefence;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.betterpartydefence.DefenceTracker.EffectState;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;

/** Per-actor combat effect observations for the selected target's infoboxes. */
final class BossEffectDisplay
{
	static final int KERIS_EFFECT_TICKS = 10;
	private static final int VULNERABILITY_PROJECTILE = 168;
	private static final int CHARGED_TOME_OF_WATER = 25574;
	private static final int ANCIENT_BARRAGE_ANIMATION = 1979;
	private static final int CLIENT_CYCLES_PER_TICK = 30;
	private static final int NO_TICK = Integer.MIN_VALUE;

	private enum Effect
	{
		VULNERABILITY("Vulnerability"),
		SHADOW_BARRAGE("Shadow Barrage"),
		RED_KERIS("Red Keris");

		private final String label;

		Effect(String label)
		{
			this.label = label;
		}
	}

	private final Client client;
	private final ClientThread clientThread;
	private final DefenceTracker tracker;
	private final Map<NPC, Observation> observations = new IdentityHashMap<>();
	private final Map<Effect, EffectBox> boxes = new EnumMap<>(Effect.class);
	private NPC shadowCasterTarget;
	private int shadowCastTick = NO_TICK;
	private int specialEnergy;
	private NPC kerisTarget;
	private int kerisHitTick = NO_TICK;
	private int kerisHitAmount;
	private long kerisHitAtMillis;
	private int kerisArmGeneration;

	BossEffectDisplay(Client client, ClientThread clientThread, DefenceTracker tracker)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.tracker = tracker;
		this.specialEnergy = client.getVarpValue(VarPlayerID.SA_ENERGY);
	}

	void onLocalAnimation(Actor actor)
	{
		Player player = client.getLocalPlayer();
		if (player == null || actor != player || player.getAnimation() != ANCIENT_BARRAGE_ANIMATION || !hasShadowSceptre())
		{
			return;
		}

		Actor target = player.getInteracting();
		if (target instanceof NPC && BossDefence.matchingNpc((NPC) target) != null)
		{
			shadowCasterTarget = (NPC) target;
			shadowCastTick = client.getTickCount();
		}
	}

	void onProjectileMoved(Projectile projectile)
	{
		Player player = client.getLocalPlayer();
		if (player == null || projectile.getId() != VULNERABILITY_PROJECTILE
			|| projectile.getSourceActor() != player || !(projectile.getTargetActor() instanceof NPC))
		{
			return;
		}
		NPC npc = (NPC) projectile.getTargetActor();
		if (BossDefence.matchingNpc(npc) == null)
		{
			return;
		}
		Observation state = observations.computeIfAbsent(npc, key -> new Observation());
		if (state.vulnerabilityProjectile != projectile)
		{
			state.vulnerabilityProjectile = projectile;
			state.vulnerabilitySpell = equippedItem(EquipmentInventorySlot.SHIELD) == CHARGED_TOME_OF_WATER
				? SpellDrain.VULNERABILITY_TOME : SpellDrain.VULNERABILITY;
		}
	}

	void onGraphicChanged(Actor actor)
	{
		if (!(actor instanceof NPC))
		{
			return;
		}
		NPC npc = (NPC) actor;
		if (BossDefence.matchingNpc(npc) == null)
		{
			return;
		}

		int tick = client.getTickCount();
		for (ActorSpotAnim spot : npc.getSpotAnims())
		{
			if (spot.getId() == SpotanimID.VULNERABILITY_IMPACT)
			{
				EffectState effects = tracker.effectState(npc, true);
				if (effects != null)
				{
					effects.vulnerabilityLanded = true;
					Observation state = observations.get(npc);
					if (state != null && state.vulnerabilityGraphicCycle != spot.getStartCycle())
					{
						state.vulnerabilityGraphicCycle = spot.getStartCycle();
						if (state.vulnerabilitySpell != null && Math.abs(
							state.vulnerabilityProjectile.getEndCycle() - spot.getStartCycle()) <= CLIENT_CYCLES_PER_TICK)
						{
							tracker.queueSpell(state.vulnerabilitySpell, npc, tick);
							state.vulnerabilitySpell = null;
						}
					}
				}
			}
			else if (spot.getId() == SpotanimID.SHADOW_BARRAGE_IMPACT)
			{
				Observation state = observations.computeIfAbsent(npc, key -> new Observation());
				if (spot.getStartCycle() != state.shadowGraphicCycle)
				{
					state.shadowGraphicCycle = spot.getStartCycle();
					state.shadowGraphicTick = tick;
					confirmShadow(state, npc);
				}
			}
		}
	}

	void onSpecialEnergy(int energy)
	{
		int spent = specialEnergy - energy;
		specialEnergy = energy;
		if ((spent != 750 && spent != 375) || weaponId() != ItemID.KERIS_PARTISAN_CORRUPTION)
		{
			return;
		}

		int hitTick = client.getTickCount() + 1;
		int generation = ++kerisArmGeneration;
		clientThread.invokeLater(() ->
		{
			if (generation != kerisArmGeneration)
			{
				return;
			}
			Player local = client.getLocalPlayer();
			Actor target = local == null ? null : local.getInteracting();
			if (target instanceof NPC && BossDefence.matchingNpc((NPC) target) != null)
			{
				kerisTarget = (NPC) target;
				kerisHitTick = hitTick;
				kerisHitAmount = 0;
			}
		});
	}

	void onHitsplatApplied(HitsplatApplied event)
	{
		if (!(event.getActor() instanceof NPC) || !event.getHitsplat().isMine())
		{
			return;
		}
		NPC npc = (NPC) event.getActor();
		int tick = client.getTickCount();
		int damage = event.getHitsplat().getAmount();
		if (npc == kerisTarget && tick == kerisHitTick)
		{
			// The instant weapon's result comes after other owned hits on this tick.
			kerisHitAmount = damage;
			kerisHitAtMillis = System.currentTimeMillis();
		}
		if (npc == shadowCasterTarget && tick - shadowCastTick >= 0
			&& tick - shadowCastTick <= 1 && damage > 0)
		{
			Observation state = observations.computeIfAbsent(npc, key -> new Observation());
			state.shadowHitTick = tick;
			confirmShadow(state, npc);
		}
	}

	private void confirmShadow(Observation state, NPC npc)
	{
		if (npc == shadowCasterTarget && state.shadowGraphicTick != NO_TICK
			&& state.shadowHitTick == state.shadowGraphicTick
			&& state.shadowHitTick - shadowCastTick >= 0 && state.shadowHitTick - shadowCastTick <= 1)
		{
			tracker.queueSpell(SpellDrain.SHADOW_BARRAGE, npc, state.shadowHitTick);
			shadowCasterTarget = null;
		}
	}

	void settleKeris(BetterPartyDefencePlugin plugin)
	{
		int tick = client.getTickCount();
		if (kerisTarget == null || tick <= kerisHitTick)
		{
			return;
		}

		NPC target = kerisTarget;
		kerisTarget = null;
		int expiry = kerisHitTick + KERIS_EFFECT_TICKS;
		if (kerisHitAmount > 0 && tick < expiry && isCurrentActor(target))
		{
			applyKerisTimer(target, expiry, kerisHitAtMillis);
			plugin.broadcastKeris(target, kerisHitTick, kerisHitAtMillis);
		}
	}

	void applyKerisTimer(NPC target, int expiry, long hitAtMillis)
	{
		EffectState effects = tracker.effectState(target, true);
		if (effects != null && expiry > effects.kerisExpiry && hitAtMillis != effects.kerisHitAtMillis)
		{
			effects.kerisExpiry = expiry;
			effects.kerisHitAtMillis = hitAtMillis;
		}
	}

	void update(Plugin plugin, InfoBoxManager manager, ItemManager items, SkillIconManager icons,
		boolean showRedKeris, boolean showShadowBarrage, boolean showVulnerability)
	{
		int tick = client.getTickCount();
		observations.keySet().removeIf(npc -> !isCurrentActor(npc));
		if (shadowCasterTarget != null && tick - shadowCastTick > 1)
		{
			shadowCasterTarget = null;
		}

		NPC selected = selectedBoss();
		EffectState state = tracker.effectState(selected, false);
		int kerisRemaining = state == null ? 0 : Math.max(0, state.kerisExpiry - tick);
		updateBox(Effect.VULNERABILITY, showVulnerability && (state != null && state.vulnerabilityLanded
			|| tracker.hasLandedSpell(selected, SpellDrain.VULNERABILITY)
			|| tracker.hasLandedSpell(selected, SpellDrain.VULNERABILITY_TOME)), "",
			"Landed", plugin, manager, items, icons);
		updateBox(Effect.SHADOW_BARRAGE, showShadowBarrage && (state != null && state.shadowLanded
			|| tracker.hasLandedSpell(selected, SpellDrain.SHADOW_BARRAGE)), "",
			state != null && state.shadowLandedOnReducedDefence
				? "Landed · Defence already reduced" : "Landed", plugin, manager, items, icons);
		updateBox(Effect.RED_KERIS, showRedKeris && kerisRemaining > 0, Integer.toString(kerisRemaining),
			"Landed · Target takes increased damage", plugin, manager, items, icons);
	}

	void clearBoss(BossDefence boss)
	{
		observations.keySet().removeIf(npc -> BossDefence.matchingNpc(npc) == boss);
		if (kerisTarget != null && BossDefence.matchingNpc(kerisTarget) == boss)
		{
			kerisTarget = null;
		}
		if (shadowCasterTarget != null && BossDefence.matchingNpc(shadowCasterTarget) == boss)
		{
			shadowCasterTarget = null;
		}
	}

	void reset(InfoBoxManager manager)
	{
		observations.clear();
		kerisTarget = null;
		kerisArmGeneration++;
		shadowCasterTarget = null;
		specialEnergy = client.getVarpValue(VarPlayerID.SA_ENERGY);
		hide(manager);
	}

	void hide(InfoBoxManager manager)
	{
		for (EffectBox box : boxes.values())
		{
			manager.removeInfoBox(box);
		}
		boxes.clear();
	}

	private boolean hasShadowSceptre()
	{
		int item = weaponId();
		return item == ItemID.ANCIENT_SCEPTRE_SHADOW || item == ItemID.ANCIENT_SCEPTRE_SHADOW_TROUVER;
	}

	private int weaponId()
	{
		return equippedItem(EquipmentInventorySlot.WEAPON);
	}

	private int equippedItem(EquipmentInventorySlot slot)
	{
		ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		Item item = equipment == null ? null : equipment.getItem(slot.getSlotIdx());
		return item == null ? -1 : item.getId();
	}

	private NPC selectedBoss()
	{
		Player local = client.getLocalPlayer();
		Actor target = local == null ? null : local.getInteracting();
		if (target != null)
		{
			return target instanceof NPC && isCurrentActor((NPC) target) ? (NPC) target : null;
		}
		DefenceTracker.DefenceState state = tracker.state();
		return state != null && tracker.hasBoundNpc() ? npcAtIndex(state.getNpcIndex()) : null;
	}

	private NPC npcAtIndex(int index)
	{
		WorldView world = client.getTopLevelWorldView();
		return world == null || world.npcs() == null ? null : world.npcs().byIndex(index);
	}

	private boolean isCurrentActor(NPC npc)
	{
		return BossDefence.isLiveNpc(npc) && npcAtIndex(npc.getIndex()) == npc;
	}

	private void updateBox(Effect effect, boolean show, String text, String status, Plugin plugin,
		InfoBoxManager manager, ItemManager items, SkillIconManager icons)
	{
		EffectBox box = boxes.get(effect);
		if (!show)
		{
			if (box != null)
			{
				manager.removeInfoBox(box);
				boxes.remove(effect);
			}
			return;
		}
		if (box == null)
		{
			BufferedImage icon = effect == Effect.VULNERABILITY ? icons.getSkillImage(Skill.MAGIC)
				: items.getImage(effect == Effect.RED_KERIS ? ItemID.KERIS_PARTISAN_CORRUPTION : ItemID.ANCIENT_SCEPTRE_SHADOW);
			box = new EffectBox(icon, plugin);
			boxes.put(effect, box);
			manager.addInfoBox(box);
		}
		box.text = text;
		box.setTooltip(effect.label + "<br>" + status);
	}

	private static final class Observation
	{
		private int shadowGraphicCycle = NO_TICK;
		private int shadowGraphicTick = NO_TICK;
		private int shadowHitTick = NO_TICK;
		private Projectile vulnerabilityProjectile;
		SpellDrain vulnerabilitySpell;
		int vulnerabilityGraphicCycle = NO_TICK;
	}

	private static final class EffectBox extends InfoBox
	{
		private String text = "";

		private EffectBox(BufferedImage image, Plugin plugin)
		{
			super(image, plugin);
		}

		@Override
		public String getText()
		{
			return text;
		}

		@Override
		public Color getTextColor()
		{
			return Color.WHITE;
		}
	}
}
