package net.betterpartydefence;

import java.util.function.Supplier;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptEvent;
import net.runelite.api.WorldView;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.util.Text;

/**
 * Converts verified raid timer readings into a shared event clock.
 */
final class RaidTimerCoordinator
{
	private static final int COX_TIMER_STALE_TICKS = 4;

	private final Client client;
	private final DefenceTracker defenceTracker;
	private final Supplier<String> raidBegunScopeKey;
	private final RaidTimerClock clock = new RaidTimerClock();

	private boolean sharedRaidClockActive;
	private int sharedRaidStartTick;
	private int lastCoxTimerValue = Integer.MIN_VALUE;
	private int lastCoxTimerAdvanceTick = -1;
	private boolean coxTimerStale;

	RaidTimerCoordinator(Client client, DefenceTracker defenceTracker, Supplier<String> raidBegunScopeKey)
	{
		this.client = client;
		this.defenceTracker = defenceTracker;
		this.raidBegunScopeKey = raidBegunScopeKey;
	}

	void tick(int tick)
	{
		pollCoxTimer(tick);
		applySharedTickAnchor(tick);
	}

	void onChatMessage(ChatMessage event)
	{
		if (event == null || event.getType() != ChatMessageType.FRIENDSCHATNOTIFICATION
			|| client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) != 1)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage());
		if (message == null)
		{
			return;
		}
		if (message.startsWith("The raid has begun!") && !sharedRaidClockActive)
		{
			sharedRaidStartTick = client.getTickCount();
			sharedRaidClockActive = true;
			updateFallbackTickAnchor(sharedRaidStartTick);
		}
		else if (message.startsWith("Congratulations - your raid is complete!"))
		{
			sharedRaidClockActive = false;
			if (clock.isActiveFor(RaidTimerClock.SOURCE_COX))
			{
				clock.clear();
			}
			defenceTracker.setSharedTickAnchor(null, 0);
		}
	}

	void onScriptPreFired(ScriptPreFired event)
	{
		if (event == null || RaidTimerClock.sourceForScript(event.getScriptId()) == -1)
		{
			return;
		}
		ScriptEvent scriptEvent = event.getScriptEvent();
		if (scriptEvent == null)
		{
			return;
		}
		RaidTimerClock.TimerInput input = RaidTimerClock.parseTimerScript(
			event.getScriptId(), scriptEvent.getArguments());
		if (input == null)
		{
			return;
		}

		int tick = client.getTickCount();
		if (input.elapsedTicks < 0)
		{
			if (clock.isActiveFor(input.source))
			{
				clock.clear();
			}
			applySharedTickAnchor(tick);
			return;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || !worldView.isInstance())
		{
			return;
		}
		String scope = timerScope(input.source, worldView);
		if (scope != null)
		{
			clock.sample(input.source, scope, input.elapsedTicks, input.stopped, tick);
		}
		applySharedTickAnchor(tick);
	}

	void pollCoxTimer(int tick)
	{
		int coxInRaid = client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON);
		int coxProgress = client.getVarbitValue(VarbitID.RAIDS_CLIENT_PROGRESS);
		if (coxInRaid == 1 && coxProgress >= 1 && coxProgress < 5)
		{
			int elapsed = client.getVarbitValue(VarbitID.RAIDS_TIMER);
			if (elapsed != lastCoxTimerValue)
			{
				lastCoxTimerValue = elapsed;
				lastCoxTimerAdvanceTick = tick;
				coxTimerStale = false;
			}
			String scope = coxTimerScope(client.getTopLevelWorldView());
			if (clock.isActiveFor(RaidTimerClock.SOURCE_COX)
				&& lastCoxTimerAdvanceTick >= 0
				&& tick - lastCoxTimerAdvanceTick > COX_TIMER_STALE_TICKS)
			{
				clock.clear();
				coxTimerStale = true;
			}
			else if (!coxTimerStale && scope != null && elapsed >= 0
				&& (elapsed > 0 || sharedRaidClockActive))
			{
				clock.sample(RaidTimerClock.SOURCE_COX, scope, elapsed, false, tick);
			}
		}
		else if (coxInRaid == 1 && coxProgress >= 5)
		{
			if (clock.isActiveFor(RaidTimerClock.SOURCE_COX))
			{
				clock.markStopped();
			}
		}
		else
		{
			if (clock.isActiveFor(RaidTimerClock.SOURCE_COX))
			{
				clock.clear();
			}
			resetCoxTimer();
		}

		WorldView worldView = client.getTopLevelWorldView();
		boolean instance = worldView != null && worldView.isInstance();
		if (!instance)
		{
			if (clock.isActiveFor(RaidTimerClock.SOURCE_TOA))
			{
				clock.clear();
			}
			if (clock.isActiveFor(RaidTimerClock.SOURCE_TFA))
			{
				clock.clear();
			}
		}
	}

	void applySharedTickAnchor(int tick)
	{
		if (clock.isActive())
		{
			if (clock.isStopped())
			{
				defenceTracker.setSharedTickAnchor(null, 0);
			}
			else
			{
				defenceTracker.setSharedTickAnchor(
					clock.domain(), clock.anchorLocalTick(), clock.anchorRaidTick());
			}
			return;
		}
		updateFallbackTickAnchor(tick);
	}

	void updateFallbackTickAnchor(int tick)
	{
		if (client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) != 1)
		{
			sharedRaidClockActive = false;
		}
		if (!sharedRaidClockActive)
		{
			defenceTracker.setSharedTickAnchor(null, 0);
			return;
		}

		String scope = raidBegunScopeKey.get();
		if (scope == null)
		{
			defenceTracker.setSharedTickAnchor(null, 0);
			return;
		}
		defenceTracker.setSharedTickAnchor("raid-begun:v2:cox:" + scope, sharedRaidStartTick);
	}

	void clear()
	{
		sharedRaidClockActive = false;
		clock.clear();
		resetCoxTimer();
		defenceTracker.setSharedTickAnchor(null, 0);
	}

	boolean isActiveFor(int source)
	{
		return clock.isActiveFor(source);
	}

	long tickAt(int localTick)
	{
		return clock.tickAt(localTick);
	}

	String timerScope(int source, WorldView worldView)
	{
		if (source == RaidTimerClock.SOURCE_TFA)
		{
			int controller = client.getVarpValue(VarPlayerID.TFA_MYCONTROLLER);
			return controller > 0 ? "c" + controller : null;
		}
		if (source == RaidTimerClock.SOURCE_TOA)
		{
			int controller = client.getVarpValue(VarPlayerID.TOA_MYCONTROLLER);
			if (controller > 0)
			{
				return "c" + controller;
			}
			return worldView != null && worldView.isInstance()
				? "i" + EncounterScopes.instanceFingerprint(worldView)
				: null;
		}
		return null;
	}

	private String coxTimerScope(WorldView worldView)
	{
		if (worldView != null && worldView.isInstance())
		{
			return "i" + EncounterScopes.instanceFingerprint(worldView);
		}
		int controller = client.getVarpValue(VarPlayerID.RAIDS_PARTY_GROUPHOLDER);
		return controller > 0 ? "c" + controller : null;
	}

	private void resetCoxTimer()
	{
		lastCoxTimerValue = Integer.MIN_VALUE;
		lastCoxTimerAdvanceTick = -1;
		coxTimerStale = false;
	}
}
