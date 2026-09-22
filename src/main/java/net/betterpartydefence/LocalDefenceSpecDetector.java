package net.betterpartydefence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Hitsplat;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.specialcounter.SpecialCounterPlugin;
import net.runelite.client.plugins.specialcounter.SpecialCounterUpdate;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

/**
 * Minimal local special-attack detector for defence-draining weapons only.
 *
 * <p>This intentionally contains no infoboxes, player drops, notifications, or generic
 * special-counter UI. It exists so Better Party Defence can track the local player's
 * defence specs even when RuneLite's separate Special Attack Counter plugin is disabled.
 * When in the Hub Party session it also publishes the standard {@link SpecialCounterUpdate}
 * message if the core Special Attack Counter is not already doing so.</p>
 */
@Slf4j
@Singleton
class LocalDefenceSpecDetector
{
	private final Client client;
	private final ClientThread clientThread;
	private final PartyService partyService;
	private final PluginManager pluginManager;
	private final DefenceTracker defenceTracker;

	private int specialPercentage = -1;
	private int lastHitPointsExperience = -1;
	private int lastHpChangeCycle = -1;

	private SpecialWeapon weapon;
	private NPC target;
	private int hitsplatTick = -1;
	private boolean hpChangedOnSpecCycle;
	private final List<Hitsplat> hitsplats = new ArrayList<>();

	@Inject
	LocalDefenceSpecDetector(
		Client client,
		ClientThread clientThread,
		PartyService partyService,
		PluginManager pluginManager,
		DefenceTracker defenceTracker)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.partyService = partyService;
		this.pluginManager = pluginManager;
		this.defenceTracker = defenceTracker;
	}

	void reset()
	{
		specialPercentage = -1;
		lastHitPointsExperience = -1;
		lastHpChangeCycle = -1;
		clearPending();
	}

	void onFakeXpDrop(FakeXpDrop event)
	{
		if (event.getSkill() == Skill.HITPOINTS)
		{
			lastHpChangeCycle = client.getGameCycle();
		}
	}

	void onStatChanged(StatChanged event)
	{
		if (event.getSkill() != Skill.HITPOINTS)
		{
			return;
		}

		if (event.getXp() > lastHitPointsExperience)
		{
			lastHpChangeCycle = client.getGameCycle();
		}
		lastHitPointsExperience = event.getXp();
	}

	void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarpId() != VarPlayerID.SA_ENERGY)
		{
			return;
		}

		int current = event.getValue();
		if (specialPercentage == -1 || current >= specialPercentage)
		{
			specialPercentage = current;
			return;
		}

		// Special energy went down. Defer target/equipment reads until player/NPC update has completed.
		specialPercentage = current;
		final int serverTick = client.getTickCount();
		clientThread.invokeLater(() -> beginSpec(serverTick));
	}

	private void beginSpec(int serverTick)
	{
		SpecialWeapon used = usedSupportedWeapon();
		if (used == null)
		{
			return;
		}

		Player local = client.getLocalPlayer();
		Actor interacting = local == null ? null : local.getInteracting();
		if (!(interacting instanceof NPC))
		{
			log.debug("Local defence spec {} ignored: no NPC target", used);
			return;
		}

		NPC npc = (NPC) interacting;
		if (npc.getName() == null || BossDefence.matchingNpc(npc.getId(), npc.getName()) == null)
		{
			log.debug("Local defence spec {} ignored: unsupported target {}", used, npc.getName());
			return;
		}

		weapon = used;
		target = npc;
		hpChangedOnSpecCycle = lastHpChangeCycle == client.getGameCycle();
		hitsplatTick = serverTick + hitDelay(used, npc);
		hitsplats.clear();
		log.debug("Local defence spec armed: weapon={} target={} npc={} expectedTick={}",
			weapon, npc.getName(), npc.getIndex(), hitsplatTick);
	}

	void onHitsplatApplied(HitsplatApplied event)
	{
		if (weapon == null || target == null)
		{
			return;
		}

		Hitsplat splat = event.getHitsplat();
		if (!splat.isMine() || event.getActor() != target)
		{
			return;
		}

		if (client.getTickCount() == hitsplatTick)
		{
			hitsplats.add(splat);
		}
	}

	/** Called once per game tick before the DefenceTracker consumes its queue. */
	void onGameTick()
	{
		if (weapon == null || target == null)
		{
			return;
		}

		int tick = client.getTickCount();

		// Elder Maul is resolved from the HP-change signal by RuneLite's own special-counter logic.
		if (weapon == SpecialWeapon.ELDER_MAUL)
		{
			record(weapon, hpChangedOnSpecCycle ? 1 : 0, target);
			clearPending();
			return;
		}

		if (tick == hitsplatTick)
		{
			if (weapon == SpecialWeapon.TONALZTICS_OF_RALOS)
			{
				if (hitsplats.size() < 2)
				{
					return;
				}
				Hitsplat last = hitsplats.get(hitsplats.size() - 1);
				Hitsplat previous = hitsplats.get(hitsplats.size() - 2);
				int connected = Math.min(last.getAmount(), 1) + Math.min(previous.getAmount(), 1);
				record(weapon, connected, target);
			}
			else
			{
				if (hitsplats.isEmpty())
				{
					return;
				}
				// The weapon hitsplat is the last mine hitsplat on the target for the expected tick.
				int rawHit = hitsplats.get(hitsplats.size() - 1).getAmount();
				record(weapon, rawHit, target);
			}
			clearPending();
		}
		else if (tick > hitsplatTick)
		{
			// Magic attacks can splash without producing a HitsplatApplied event. For the two
			// supported magic special attacks, the spent special energy + armed target is enough
			// to know the attempt occurred, so preserve it as a 0-hit miss for the Magic info-box
			// history instead of silently dropping it.
			if (recordsSplashAsMiss(weapon))
			{
				log.debug("Local magic defence spec splashed with no hitsplat: {}", weapon);
				record(weapon, 0, target);
			}
			else
			{
				log.debug("Local defence spec timed out waiting for hitsplat: {}", weapon);
			}
			clearPending();
		}
	}

	/** Supported Magic attacks which can splash without a 0-damage hitsplat event. */
	static boolean recordsSplashAsMiss(SpecialWeapon used)
	{
		return used == SpecialWeapon.EYE_OF_AYAK || used == SpecialWeapon.ACCURSED_SCEPTRE;
	}

	private void record(SpecialWeapon used, int rawHit, NPC npc)
	{
		if (npc == null)
		{
			return;
		}

		int world = client.getWorld();
		int npcIndex = npc.getIndex();
		log.debug("Local defence spec: weapon={} hit={} target={} npc={} world={}",
			used, rawHit, npc.getName(), npcIndex, world);

		Player local = client.getLocalPlayer();
		String playerName = local != null && local.getName() != null ? local.getName() : "You";

		// Apply locally immediately; no party or websocket round-trip is required for our own overlay.
		// Pass the player name with the drain so the info-box hover history is tied to the same
		// accepted event as the Defence calculation.
		defenceTracker.queue(used, npcIndex, rawHit, world, playerName);

		// Hub Party Panel uses this same PartyService session. If RuneLite's core Special Attack
		// Counter is active it already sends this exact message, so never duplicate it.
		if (partyService.isInParty() && !isSpecialCounterActive())
		{
			if (local != null)
			{
				partyService.send(new SpecialCounterUpdate(npcIndex, used, rawHit, world, local.getId()));
				log.debug("Broadcast local defence spec through Hub Party session: {}", used);
			}
		}
	}

	private SpecialWeapon usedSupportedWeapon()
	{
		ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		if (equipment == null)
		{
			return null;
		}

		Item item = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (item == null)
		{
			return null;
		}

		int itemId = item.getId();
		for (SpecialWeapon candidate : SpecialWeapon.values())
		{
			if (DefenceTracker.isSupportedWeapon(candidate)
				&& Arrays.stream(candidate.getItemID()).anyMatch(id -> id == itemId))
			{
				return candidate;
			}
		}
		return null;
	}

	private int hitDelay(SpecialWeapon used, Actor actor)
	{
		Player local = client.getLocalPlayer();
		if (local == null || actor == null)
		{
			return 1;
		}

		WorldPoint playerPoint = local.getWorldLocation();
		WorldArea targetArea = actor.getWorldArea();
		if (playerPoint == null || targetArea == null)
		{
			return 1;
		}
		return used.getHitDelay(targetArea.distanceTo(playerPoint));
	}

	private boolean isSpecialCounterActive()
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (plugin instanceof SpecialCounterPlugin && pluginManager.isPluginActive(plugin))
			{
				return true;
			}
		}
		return false;
	}

	private void clearPending()
	{
		weapon = null;
		target = null;
		hitsplatTick = -1;
		hpChangedOnSpecCycle = false;
		hitsplats.clear();
	}
}
