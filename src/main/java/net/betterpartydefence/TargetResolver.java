package net.betterpartydefence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * Resolves tracked bosses from local NPCs using stable actor and location evidence.
 */
final class TargetResolver
{
	private TargetResolver()
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

	static NPC uniqueLiveNpc(WorldView worldView, BossDefence boss)
	{
		return unique(liveCandidates(worldView, boss));
	}

	static NPC uniqueLiveNpcNear(
		WorldView worldView, BossDefence boss, int x, int y, int plane, int tolerance)
	{
		if (x == 0 && y == 0)
		{
			return null;
		}

		return uniqueCandidateAt(worldView, boss, -1, x, y, plane, tolerance);
	}

	static NPC uniqueLiveNpcFor(
		WorldView worldView, BossDefence boss, int npcId, WorldPoint lastLocation, int tolerance)
	{
		if (npcId < 0 || lastLocation == null)
		{
			return null;
		}
		return uniqueCandidateAt(worldView, boss, npcId,
			lastLocation.getX(), lastLocation.getY(), lastLocation.getPlane(), tolerance);
	}

	// A negative NPC id means the sender has only location evidence.
	private static NPC uniqueCandidateAt(
		WorldView worldView, BossDefence boss, int npcId, int x, int y, int plane, int tolerance)
	{
		NPC match = null;
		for (NPC npc : liveCandidates(worldView, boss))
		{
			if (npcId >= 0 && npc.getId() != npcId)
			{
				continue;
			}
			if (!isNear(npc.getWorldLocation(), x, y, plane, tolerance))
			{
				continue;
			}
			if (match != null)
			{
				return null;
			}
			match = npc;
		}
		return match;
	}

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
		return isNear(point, lastLocation, tolerance);
	}

	private static boolean isNear(WorldPoint point, WorldPoint target, int tolerance)
	{
		return point != null && isNear(point, target.getX(), target.getY(), target.getPlane(), tolerance);
	}

	private static boolean isNear(WorldPoint point, int x, int y, int plane, int tolerance)
	{
		return point != null && point.getPlane() == plane
			&& Math.abs(point.getX() - x) <= tolerance
			&& Math.abs(point.getY() - y) <= tolerance;
	}

	private static NPC unique(List<NPC> candidates)
	{
		return candidates.size() == 1 ? candidates.get(0) : null;
	}
}
