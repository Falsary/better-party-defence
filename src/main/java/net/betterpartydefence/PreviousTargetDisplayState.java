package net.betterpartydefence;

import java.util.List;
import net.betterpartydefence.DefenceTracker.DefenceState;
import net.runelite.api.Client;
import net.runelite.api.NPC;

/**
 * Rendering-only memory for the experimental previous-target display.
 *
 * <p>This never mutates DefenceTracker state. It only remembers which already-tracked target was
 * active immediately before the current one, and only exposes it while that concrete NPC is still
 * live in this client's scene.
 */
final class PreviousTargetDisplayState
{
	private final Client client;
	private final DefenceTracker tracker;
	private final BetterPartyDefenceConfig config;

	private boolean trackingActive;
	private int lastActiveNpcIndex = -1;
	private BossDefence lastActiveBoss;
	private Integer previousTargetNpcIndex;

	PreviousTargetDisplayState(Client client, DefenceTracker tracker, BetterPartyDefenceConfig config)
	{
		this.client = client;
		this.tracker = tracker;
		this.config = config;
	}

	void update()
	{
		DefenceState current = tracker.state();
		List<DefenceState> states = tracker.states();

		if (!config.defenceHpBar() || !config.previousTargetDisplay())
		{
			reset(current);
			return;
		}

		if (!trackingActive)
		{
			trackingActive = true;
			lastActiveNpcIndex = boundIndex(current);
			lastActiveBoss = lastActiveNpcIndex >= 0 ? tracker.trackedBossType() : null;
			previousTargetNpcIndex = null;
			return;
		}

		if (states.isEmpty())
		{
			clear();
			return;
		}

		int currentIndex = boundIndex(current);
		BossDefence currentBoss = current == null ? null : tracker.trackedBossType();

		// Sotetseg's combat/maze actor replacements belong to one logical encounter and its
		// Defence is intentionally restored by DefenceTracker at each maze. Never let those
		// actor swaps enter the experimental previous-target display, otherwise a stale pre-maze
		// snapshot can appear beside the authoritative current Sotetseg state.
		if (currentBoss == BossDefence.SOTETSEG || lastActiveBoss == BossDefence.SOTETSEG)
		{
			previousTargetNpcIndex = null;
			lastActiveNpcIndex = currentIndex;
			lastActiveBoss = currentBoss;
			return;
		}

		if (currentIndex < 0 || currentIndex == lastActiveNpcIndex)
		{
			if (stateByIndex(states, previousTargetNpcIndex) == null)
			{
				previousTargetNpcIndex = null;
			}
			return;
		}

		DefenceState prior = stateByIndex(states, lastActiveNpcIndex);
		NPC priorNpc = prior == null ? null : liveNpc(prior.getNpcIndex());

		// Actor replacement for the same logical boss (phase/rebind/render churn) is not a user
		// target switch. It must not manufacture a sticky copy of the current encounter.
		boolean sameLogicalRebind = prior != null && lastActiveBoss == currentBoss && priorNpc == null;
		if (!sameLogicalRebind && prior != null && prior.isDrained())
		{
			previousTargetNpcIndex = prior.getNpcIndex();
		}
		else if (prior == null || sameLogicalRebind)
		{
			previousTargetNpcIndex = null;
		}

		lastActiveNpcIndex = currentIndex;
		lastActiveBoss = currentBoss;
		if (previousTargetNpcIndex != null && previousTargetNpcIndex == currentIndex)
		{
			previousTargetNpcIndex = null;
		}
	}

	DefenceState previousState()
	{
		if (!config.defenceHpBar() || !config.previousTargetDisplay())
		{
			return null;
		}
		DefenceState previous = stateByIndex(tracker.states(), previousTargetNpcIndex);
		DefenceState current = tracker.state();
		if (previous == null || !previous.isDrained()
			|| (current != null && previous.getNpcIndex() == current.getNpcIndex()))
		{
			return null;
		}
		return liveNpc(previous.getNpcIndex()) == null ? null : previous;
	}

	void clear()
	{
		trackingActive = false;
		lastActiveNpcIndex = -1;
		lastActiveBoss = null;
		previousTargetNpcIndex = null;
	}

	private void reset(DefenceState current)
	{
		trackingActive = false;
		lastActiveNpcIndex = boundIndex(current);
		lastActiveBoss = lastActiveNpcIndex >= 0 ? tracker.trackedBossType() : null;
		previousTargetNpcIndex = null;
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
