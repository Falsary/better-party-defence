package net.betterpartydefence;

import java.util.ArrayList;
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
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.specialcounter.SpecialCounterUpdate;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

// Detects local defence-draining special attacks.
// Broadcasts the standard party message when SAC is inactive.
@Slf4j
@Singleton
class LocalDefenceSpecDetector {
	private static final int RALOS_UNKNOWN = -1;
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
	private long hitsplatObservedAtMillis;
	private boolean hpChangedOnSpecCycle;
	private final List<Integer> hitsplats = new ArrayList<>();

	@Inject
	LocalDefenceSpecDetector(
			Client client,
			ClientThread clientThread,
			PartyService partyService,
			PluginManager pluginManager,
			DefenceTracker defenceTracker) {
		this.client = client;
		this.clientThread = clientThread;
		this.partyService = partyService;
		this.pluginManager = pluginManager;
		this.defenceTracker = defenceTracker;
	}

	void reset() {
		specialPercentage = -1;
		lastHitPointsExperience = -1;
		lastHpChangeCycle = -1;
		clearPending();
	}

	void onFakeXpDrop(FakeXpDrop event) {
		if (event.getSkill() == Skill.HITPOINTS) {
			lastHpChangeCycle = client.getGameCycle();
		}
	}

	void onStatChanged(StatChanged event) {
		if (event.getSkill() != Skill.HITPOINTS) {
			return;
		}

		if (event.getXp() > lastHitPointsExperience) {
			lastHpChangeCycle = client.getGameCycle();
		}
		lastHitPointsExperience = event.getXp();
	}

	void onVarbitChanged(VarbitChanged event) {
		if (event.getVarpId() != VarPlayerID.SA_ENERGY) {
			return;
		}

		int current = event.getValue();
		if (specialPercentage == -1 || current >= specialPercentage) {
			specialPercentage = current;
			return;
		}

		// Defer the equipment read, but capture the current target now.
		// The target can disappear before equipment is read.
		specialPercentage = current;
		final int serverTick = client.getTickCount();
		final NPC castTarget = interactingNpc();
		clientThread.invokeLater(() -> beginSpec(serverTick, castTarget));
	}

	private NPC interactingNpc() {
		Player local = client.getLocalPlayer();
		Actor interacting = local == null ? null : local.getInteracting();
		return interacting instanceof NPC ? (NPC) interacting : null;
	}

	// Prefer the captured cast target.
	// Only reuse the live interaction when it is the same NPC.
	NPC resolveTarget(NPC castTarget) {
		NPC live = interactingNpc();
		if (castTarget == null) {
			// No captured target; use the current interaction.
			return live;
		}
		if (live != null && sameCapturedTarget(live, castTarget)) {
			return live;
		}
		if (live != null) {
			log.debug("Local defence spec keeps captured target {} and ignores new interaction {}",
					castTarget.getName(), live.getName());
		}
		if (castTarget.getName() == null || BossDefence.matchingNpc(castTarget) == null) {
			return null;
		}
		log.debug("Local defence spec target resolved from captured actor: target={} npc={}",
				castTarget.getName(), castTarget.getIndex());
		return castTarget;
	}

	private static boolean sameCapturedTarget(NPC live, NPC cast) {
		return live == cast || (live.getId() == cast.getId() && live.getIndex() == cast.getIndex());
	}

	private void beginSpec(int serverTick, NPC castTarget) {
		SpecialWeapon used = usedSupportedWeapon();
		if (used == null) {
			return;
		}

		NPC npc = resolveTarget(castTarget);
		if (npc == null) {
			log.debug("Local defence spec {} ignored: no NPC target", used);
			return;
		}

		if (npc.getName() == null || BossDefence.matchingNpc(npc) == null) {
			log.debug("Local defence spec {} ignored: unsupported target {}", used, npc.getName());
			return;
		}

		weapon = used;
		target = npc;
		hpChangedOnSpecCycle = lastHpChangeCycle == client.getGameCycle();
		hitsplatTick = serverTick + hitDelay(used, npc);
		hitsplats.clear();
		hitsplatObservedAtMillis = 0L;
		log.debug("Local defence spec armed: weapon={} target={} npc={} expectedTick={}",
				weapon, npc.getName(), npc.getIndex(), hitsplatTick);
	}

	void onHitsplatApplied(HitsplatApplied event) {
		if (weapon == null || target == null) {
			return;
		}

		Hitsplat splat = event.getHitsplat();
		Actor observedActor = event.getActor();
		if (!splat.isMine() || !(observedActor instanceof NPC)
				|| !sameLogicalTarget(target, (NPC) observedActor)) {
			// Exclude other actors and non-owned hitsplats.
			log.debug("Local defence spec hitsplat ignored: mine={} armed={}:{} observed={}:{}",
					splat.isMine(), target.getName(), target.getId(),
					observedActor == null ? "null" : observedActor.getName(),
					observedActor instanceof NPC ? ((NPC) observedActor).getId() : -1);
			return;
		}
		target = (NPC) observedActor;

		int tick = client.getTickCount();
		if (tick == hitsplatTick) {
			if (hitsplats.isEmpty()) {
				hitsplatObservedAtMillis = System.currentTimeMillis();
			}
			hitsplats.add(splat.getAmount());
			log.debug("Local defence spec hitsplat accepted: weapon={} amount={} type={} tick={} expected={}",
					weapon, splat.getAmount(), splat.getHitsplatType(), tick, hitsplatTick);
		} else {
			log.debug("Local defence spec hitsplat ignored: tick mismatch tick={} expected={} amount={} type={}",
					tick, hitsplatTick, splat.getAmount(), splat.getHitsplatType());
		}
	}

	// Process captured hits before the tracker reads its queue.
	void onGameTick() {
		if (weapon == null || target == null) {
			return;
		}

		int tick = client.getTickCount();
		log.debug("Local defence spec tick check: weapon={} tick={} expected={} hitsplats={}",
				weapon, tick, hitsplatTick, hitsplats.size());

		// Elder Maul uses the HP-change signal.
		if (weapon == SpecialWeapon.ELDER_MAUL) {
			record(weapon, hpChangedOnSpecCycle ? 1 : 0, target);
			clearPending();
			return;
		}

		// Allow one extra tick for late hitsplat callbacks.
		if ((tick == hitsplatTick || tick == hitsplatTick + 1) && !hitsplats.isEmpty()) {
			int rawHit;
			if (weapon == SpecialWeapon.TONALZTICS_OF_RALOS) {
				// Delayed weapon hits precede thrall and Vengeance.
				// Ralos uses the first two hitsplats.
				rawHit = countConfirmedRalosHits(hitsplats);
			} else {
				// Instant hits follow thrall damage.
				rawHit = resolveObservedHit(weapon, hitsplats);
			}
			if (rawHit >= 0) {
				record(weapon, rawHit, target);
				clearPending();
				return;
			}
		}
		if (tick > hitsplatTick) {
			log.debug("Local defence spec timed out waiting for hitsplat: {}", weapon);
			clearPending();
		}
	}

	// Match RuneLite's first-vs-last hitsplat rule.
	static int resolveObservedHit(SpecialWeapon weapon, List<Integer> observedHits) {
		if (weapon == null || observedHits == null || observedHits.isEmpty()) {
			throw new IllegalArgumentException("A weapon and observed hitsplat are required");
		}
		return observedHits.get(weapon.getHitDelay(1) > 1 ? 0 : observedHits.size() - 1);
	}

	// Count Ralos from the first two glaive hitsplats.
	// Zero is a miss; incomplete pairs stay unresolved.
	static int countConfirmedRalosHits(List<Integer> orderedHitsplats) {
		if (orderedHitsplats == null || orderedHitsplats.size() < 2) {
			return RALOS_UNKNOWN;
		}
		Integer first = orderedHitsplats.get(0);
		Integer second = orderedHitsplats.get(1);
		if (first == null || second == null || first < 0 || second < 0) {
			return RALOS_UNKNOWN;
		}
		return (first > 0 ? 1 : 0) + (second > 0 ? 1 : 0);
	}

	private static boolean sameLogicalTarget(NPC armed, NPC observed) {
		if (armed == observed) {
			return true;
		}
		if (armed == null || observed == null || armed.isDead()) {
			return false;
		}

		BossDefence boss = BossDefence.matchingNpc(armed);
		if (boss == null || BossDefence.matchingNpc(observed) != boss
				|| observed.getId() != armed.getId()) {
			return false;
		}
		if (boss == BossDefence.GIANT_MOLE && armed.getIndex() == observed.getIndex()) {
			return true;
		}
		return TargetResolver.matches(
				observed, boss, armed.getId(), armed.getWorldLocation(), 2);
	}

	private void record(SpecialWeapon used, int rawHit, NPC npc) {
		if (npc == null) {
			return;
		}

		int world = client.getWorld();
		int npcIndex = npc.getIndex();
		log.debug("Local defence spec: weapon={} hit={} target={} npc={} world={}",
				used, rawHit, npc.getName(), npcIndex, world);

		Player local = client.getLocalPlayer();
		String playerName = local != null && local.getName() != null ? local.getName() : "You";

		// Apply locally before optional party broadcast.
		defenceTracker.queueLocal(used, npcIndex, rawHit, world, playerName, npc,
				hitsplatObservedAtMillis > 0 ? hitsplatObservedAtMillis : System.currentTimeMillis());

		// Special Attack Counter already sends this when active.
		if (partyService.isInParty() && !isSpecialCounterActive()) {
			if (local != null) {
				partyService.send(new SpecialCounterUpdate(npcIndex, used, rawHit, world, local.getId()));
				log.debug("Broadcast local defence spec through Hub Party session: {}", used);
			}
		}
	}

	private SpecialWeapon usedSupportedWeapon() {
		ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		if (equipment == null) {
			return null;
		}

		Item item = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (item == null) {
			return null;
		}

		return SpecialWeaponRules.byItemId(item.getId());
	}

	private int hitDelay(SpecialWeapon used, Actor actor) {
		Player local = client.getLocalPlayer();
		if (local == null || actor == null) {
			return 1;
		}

		WorldPoint playerPoint = local.getWorldLocation();
		WorldArea targetArea = actor.getWorldArea();
		if (playerPoint == null || targetArea == null) {
			return 1;
		}
		return used.getHitDelay(targetArea.distanceTo(playerPoint));
	}

	private boolean isSpecialCounterActive() {
		return SpecialCounterState.isActive(pluginManager);
	}

	private void clearPending() {
		weapon = null;
		target = null;
		hitsplatTick = -1;
		hpChangedOnSpecCycle = false;
		hitsplats.clear();
		hitsplatObservedAtMillis = 0L;
	}
}
