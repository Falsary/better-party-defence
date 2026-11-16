package net.betterpartydefence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.BiConsumer;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.betterpartydefence.DefenceTracker.SpellDrain;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

// Bounded queues for specs whose local target is not yet unambiguous.
@Slf4j
final class PendingSpecQueue {
	private static final int HELD_DRAIN_TTL_TICKS = 200;
	private static final int MAX_HELD_DRAINS = 256;
	private static final int MAX_HELD_DRAINS_PER_TARGET = 64;
	private static final int PENDING_TARGET_DISTANCE = 2;

	private final Client client;
	private final ConcurrentLinkedDeque<Observation> pending = new ConcurrentLinkedDeque<>();
	private final List<HeldQueue> held = new ArrayList<>();

	PendingSpecQueue(Client client) {
		this.client = client;
	}

	// One queued defence-draining observation.
	@Value
	static class Observation {
		SpecialWeapon weapon;
		SpellDrain spell;
		int npcIndex;
		int hit;
		int world;
		String playerName;
		String eventId;
		int observedTick;
		long observedAtMillis;
		String tickAnchor;
		int tickAnchorTick;
		long tickAnchorValue;
		TargetEvidence target;
		int receivedTick;
		DefenceTracker.SpecHistoryEntry partyEntry;
	}

	// Target details found on this client.
	@Value
	static class TargetEvidence {
		BossDefence bossType;
		int npcId;
		WorldPoint location;
		// The local NPC object is used to detect index reuse.
		NPC actor;

		static TargetEvidence from(NPC npc) {
			if (npc == null) {
				return null;
			}
			BossDefence boss = BossDefence.matchingNpc(npc);
			return boss == null ? null : new TargetEvidence(boss, npc.getId(), npc.getWorldLocation(), npc);
		}

		boolean matches(NPC npc) {
			if (actor != null) {
				return npc == actor && BossDefence.isLiveNpc(npc);
			}
			return TargetResolver.matches(npc, bossType, npcId, location, PENDING_TARGET_DISTANCE);
		}
	}

	// Ordered observations for one unresolved actor.
	private static class HeldQueue {
		final PendingTarget target;
		final List<Observation> observations = new ArrayList<>();
		// Never guess after the target becomes ambiguous.
		boolean ambiguous;

		HeldQueue(PendingTarget target) {
			this.target = target;
		}
	}

	// Exact is set when the local detector supplies the NPC.
	// Legacy party packets provide only a scene-index hint.
	private static class PendingTarget {
		final int npcIndex;
		BossDefence bossType;
		int npcId;
		WorldPoint location;
		final NPC indexedActor;
		final boolean exact;

		PendingTarget(
				int npcIndex, BossDefence bossType, int npcId, WorldPoint location,
				NPC indexedActor, boolean exact) {
			this.npcIndex = npcIndex;
			this.bossType = bossType;
			this.npcId = npcId;
			this.location = location;
			this.indexedActor = indexedActor;
			this.exact = exact;
		}

		static PendingTarget from(Observation observation, NPC indexedActor) {
			TargetEvidence target = observation.getTarget();
			if (target != null) {
				return new PendingTarget(observation.getNpcIndex(), target.getBossType(), target.getNpcId(),
						target.getLocation(), target.getActor(), true);
			}

			BossDefence boss = BossDefence.matchingNpc(indexedActor);
			if (boss != null) {
				return new PendingTarget(observation.getNpcIndex(), boss, indexedActor.getId(),
						indexedActor.getWorldLocation(), indexedActor, false);
			}
			return new PendingTarget(observation.getNpcIndex(), null, -1, null, null, false);
		}

		boolean sameTarget(PendingTarget other) {
			return other != null && exact == other.exact && npcIndex == other.npcIndex
					&& (indexedActor != null || other.indexedActor != null
							? indexedActor == other.indexedActor
							: bossType == other.bossType && npcId == other.npcId
									&& Objects.equals(location, other.location));
		}
	}

	static TargetEvidence captureTarget(NPC npc) {
		return TargetEvidence.from(npc);
	}

	void add(Observation observation, boolean priority) {
		if (priority) {
			pending.addFirst(observation);
		} else {
			pending.addLast(observation);
		}
	}

	Observation poll() {
		return pending.poll();
	}

	// A local spec includes the NPC object.
	// Legacy party specs require one unambiguous local target.
	NPC resolveTarget(Observation observation) {
		NPC indexed = npcByIndex(observation.getNpcIndex());
		if (observation.getTarget() != null) {
			return observation.getTarget().matches(indexed) ? indexed : null;
		}
		if (indexed == null || !BossDefence.isLiveNpc(indexed)) {
			return null;
		}
		BossDefence boss = BossDefence.matchingNpc(indexed);
		if (boss == null) {
			return null;
		}
		NPC unique = TargetResolver.uniqueLiveNpc(client.getTopLevelWorldView(), boss);
		if (unique != null) {
			return unique;
		}
		// Use the indexed actor only when no competing candidate exists.
		return TargetResolver.liveCandidates(client.getTopLevelWorldView(), boss).isEmpty()
				? indexed
				: null;
	}

	// Keep one queue for each unresolved NPC.
	void hold(Observation observation) {
		NPC indexed = npcByIndex(observation.getNpcIndex());
		PendingTarget target = PendingTarget.from(observation, indexed);
		HeldQueue queue = null;
		for (HeldQueue candidate : held) {
			if (candidate.target.sameTarget(target)) {
				queue = candidate;
				break;
			}
		}
		if (queue == null) {
			queue = new HeldQueue(target);
			held.add(queue);
		}
		queue.observations.add(observation);

		if (!target.exact && target.bossType != null
				&& TargetResolver.liveCandidates(client.getTopLevelWorldView(), target.bossType).size() > 1) {
			queue.ambiguous = true;
		}
		while (queue.observations.size() > MAX_HELD_DRAINS_PER_TARGET) {
			queue.observations.remove(0);
		}
		trimToCapacity();
	}

	// Retry unresolved targets independently.
	// Keep queue order within each target.
	void replay(BiConsumer<Observation, NPC> apply) {
		int tick = client.getTickCount();
		List<HeldQueue> attempt = new ArrayList<>(held);
		for (HeldQueue queue : attempt) {
			if (!held.contains(queue)) {
				continue;
			}
			int beforeExpiry = queue.observations.size();
			queue.observations.removeIf(observation ->
					tick - observation.getReceivedTick() > HELD_DRAIN_TTL_TICKS);
			if (queue.observations.isEmpty()) {
				if (beforeExpiry > 0) {
					log.debug("Dropping {} held drains for npc {} after {} ticks",
							beforeExpiry, queue.target.npcIndex, HELD_DRAIN_TTL_TICKS);
				}
				held.remove(queue);
				continue;
			}

			NPC indexed = npcByIndex(queue.target.npcIndex);
			if (queue.target.indexedActor != null && indexed != null
					&& indexed != queue.target.indexedActor) {
				log.debug("Dropping held drains for reused npc index {}", queue.target.npcIndex);
				held.remove(queue);
				continue;
			}
			if (queue.target.indexedActor != null && queue.target.indexedActor.isDead()) {
				held.remove(queue);
				continue;
			}

			NPC target = resolveHeldTarget(queue, indexed);
			if (target == null) {
				continue;
			}
			held.remove(queue);
			for (Observation observation : queue.observations) {
				apply.accept(observation, target);
			}
		}
	}

	private NPC resolveHeldTarget(HeldQueue queue, NPC indexed) {
		PendingTarget pendingTarget = queue.target;
		if (pendingTarget.exact) {
			return pendingTarget.indexedActor != null && indexed == pendingTarget.indexedActor
					&& BossDefence.isLiveNpc(indexed) ? indexed : null;
		}
		// A legacy scene index only helps find the NPC on this client.
		// Use it only when one matching NPC is visible.
		BossDefence boss = pendingTarget.bossType;
		if (boss == null && BossDefence.isLiveNpc(indexed)) {
			boss = BossDefence.matchingNpc(indexed);
		}
		if (boss == null) {
			return null;
		}

		List<NPC> candidates = TargetResolver.liveCandidates(client.getTopLevelWorldView(), boss);
		if (candidates.size() > 1) {
			queue.ambiguous = true;
		}
		if (queue.ambiguous) {
			return null;
		}
		if (candidates.size() == 1) {
			NPC candidate = candidates.get(0);
			if (pendingTarget.indexedActor == null && candidate.getIndex() != pendingTarget.npcIndex) {
				return null;
			}
			return candidate;
		}
		return candidates.isEmpty() && BossDefence.isLiveNpc(indexed)
				&& BossDefence.matchingNpc(indexed) == boss ? indexed : null;
	}

	private void trimToCapacity() {
		int total = heldCount();
		while (total > MAX_HELD_DRAINS) {
			HeldQueue oldest = null;
			for (HeldQueue queue : held) {
				if (!queue.observations.isEmpty() && (oldest == null
						|| queue.observations.get(0).getReceivedTick()
								< oldest.observations.get(0).getReceivedTick())) {
					oldest = queue;
				}
			}
			if (oldest == null) {
				break;
			}
			oldest.observations.remove(0);
			if (oldest.observations.isEmpty()) {
				held.remove(oldest);
			}
			total--;
		}
	}

	private int heldCount() {
		int count = 0;
		for (HeldQueue queue : held) {
			count += queue.observations.size();
		}
		return count;
	}

	void clearDeferredForTarget(int npcIndex, NPC actor) {
		boolean hasIndex = npcIndex >= 0;
		held.removeIf(queue -> (hasIndex && queue.target.npcIndex == npcIndex)
				|| (actor != null && queue.target.indexedActor == actor));
		pending.removeIf(observation -> (hasIndex && observation.getNpcIndex() == npcIndex)
				|| (actor != null && observation.getTarget() != null
						&& observation.getTarget().getActor() == actor));
	}

	void clearDeferredForBoss(BossDefence boss) {
		held.removeIf(queue -> queue.target.bossType == boss);
		pending.removeIf(observation -> observation.getTarget() != null
				&& observation.getTarget().getBossType() == boss);
	}

	boolean isEmpty() {
		return pending.isEmpty() && held.isEmpty();
	}

	boolean hasForBoss(BossDefence boss) {
		for (Observation observation : pending) {
			BossDefence observationBoss = bossType(observation);
			if (observationBoss == null || observationBoss == boss) {
				return true;
			}
		}
		for (HeldQueue queue : held) {
			if (queue.target.bossType == null || queue.target.bossType == boss) {
				return true;
			}
		}
		return false;
	}

	boolean hasForTarget(int npcIndex) {
		return pending.stream().anyMatch(observation -> observation.getNpcIndex() == npcIndex)
				|| held.stream().anyMatch(queue -> queue.target.npcIndex == npcIndex);
	}

	private BossDefence bossType(Observation observation) {
		if (observation.getTarget() != null) {
			return observation.getTarget().getBossType();
		}
		return BossDefence.matchingNpc(npcByIndex(observation.getNpcIndex()));
	}

	private NPC npcByIndex(int index) {
		WorldView worldView = client.getTopLevelWorldView();
		return worldView == null || worldView.npcs() == null ? null : worldView.npcs().byIndex(index);
	}

	void clear() {
		pending.clear();
		held.clear();
	}
}
