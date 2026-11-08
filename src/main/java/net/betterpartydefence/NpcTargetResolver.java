package net.betterpartydefence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * Shared live-NPC resolution policy. A local scene index is a binding detail, not a
 * cross-client identity: it can be reused after an NPC despawns. Callers therefore
 * receive either one uniquely identified live target or no target at all.
 */
final class NpcTargetResolver
{
	private NpcTargetResolver()
	{
	}

	static List<NPC> liveCandidates(WorldView worldView, BossDefence boss)
	{
		if (worldView == null || worldView.npcs() == null || boss == null)
		{
			return Collections.emptyList();
		}

		List<NPC> candidates = new ArrayList<>();
		Iterator<? extends NPC> iterator = worldView.npcs().iterator();
		while (iterator != null && iterator.hasNext())
		{
			NPC npc = iterator.next();
			if (BossDefence.isLiveNpc(npc) && npc.getName() != null
				&& BossDefence.matchingNpc(npc) == boss)
			{
				candidates.add(npc);
			}
		}
		return candidates;
	}

	/** Returns the only live actor for a boss, or null when the boss is absent/ambiguous. */
	static NPC uniqueLiveNpc(WorldView worldView, BossDefence boss)
	{
		return unique(liveCandidates(worldView, boss));
	}

	/**
	 * Resolve a party snapshot that contains the sender's observed tile. This is useful when
	 * two same-type actors are rendered: only a candidate at the sender's position is a
	 * plausible match. If more than one candidate remains, the caller must stay unresolved.
	 */
	static NPC uniqueLiveNpcNear(
		WorldView worldView, BossDefence boss, int x, int y, int plane, int tolerance)
	{
		// Unset wire coordinates are encoded as zero by legacy senders.
		if (x == 0 && y == 0)
		{
			return null;
		}

		List<NPC> matches = new ArrayList<>();
		for (NPC npc : liveCandidates(worldView, boss))
		{
			WorldPoint point = npc.getWorldLocation();
			if (point != null && point.getPlane() == plane
				&& Math.abs(point.getX() - x) <= tolerance
				&& Math.abs(point.getY() - y) <= tolerance)
			{
				matches.add(npc);
			}
		}
		return unique(matches);
	}

	/**
	 * Resolve a remembered local actor. Exact scene identity is preferred; a movement tolerance
	 * is permitted because an NPC can walk between observations while it is out of render.
	 */
	static NPC uniqueLiveNpcFor(
		WorldView worldView, BossDefence boss, int npcId, WorldPoint lastLocation, int tolerance)
	{
		if (npcId < 0 || lastLocation == null)
		{
			return null;
		}

		List<NPC> matches = new ArrayList<>();
		for (NPC npc : liveCandidates(worldView, boss))
		{
			WorldPoint point = npc.getWorldLocation();
			if (npc.getId() == npcId && point != null
				&& point.getPlane() == lastLocation.getPlane()
				&& Math.abs(point.getX() - lastLocation.getX()) <= tolerance
				&& Math.abs(point.getY() - lastLocation.getY()) <= tolerance)
			{
				matches.add(npc);
			}
		}
		return unique(matches);
	}

	/** Whether a current actor still matches the local identity recorded with an observation. */
	static boolean matches(
		NPC npc, BossDefence boss, int npcId, WorldPoint lastLocation, int tolerance)
	{
		if (npc == null || boss == null || BossDefence.matchingNpc(npc) != boss || npc.getId() != npcId)
		{
			return false;
		}
		WorldPoint point = npc.getWorldLocation();
		if (lastLocation == null)
		{
			return true;
		}
		return point != null
			&& point.getPlane() == lastLocation.getPlane()
			&& Math.abs(point.getX() - lastLocation.getX()) <= tolerance
			&& Math.abs(point.getY() - lastLocation.getY()) <= tolerance;
	}

	private static NPC unique(List<NPC> candidates)
	{
		return candidates.size() == 1 ? candidates.get(0) : null;
	}
}
