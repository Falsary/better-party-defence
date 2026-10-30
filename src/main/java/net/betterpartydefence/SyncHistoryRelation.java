package net.betterpartydefence;

import java.util.List;
import java.util.Locale;
import net.runelite.client.util.Text;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.betterpartydefence.DefenceTracker.SyncState;

/** Legacy snapshots have no event identity or global combat time. Only exact prefixes are comparable. */
final class SyncHistoryRelation
{
	enum Result { INITIAL, SAME, EXTENSION, STALE, CONFLICT }

	private SyncHistoryRelation() {}

	private static String name(String value)
	{
		return value == null ? "" : Text.toJagexName(Text.removeTags(value)).toLowerCase(Locale.ROOT);
	}

	static Result compare(SyncState local, SyncState incoming)
	{
		if (local == null) { return Result.INITIAL; }
		if (local.getBossType() != incoming.getBossType()
			|| local.getBase() != incoming.getBase() || local.getMin() != incoming.getMin()
			|| local.getMagicBaseLevel() != incoming.getMagicBaseLevel()
			|| local.getMagicBaseDef() != incoming.getMagicBaseDef()
			|| local.isMagicUsesDefence() != incoming.isMagicUsesDefence()
			|| local.isDemon() != incoming.isDemon())
		{
			return Result.CONFLICT;
		}
		List<SpecHistoryEntry> a = local.getHistory(), b = incoming.getHistory();
		if (a == null || b == null) { return Result.CONFLICT; }
		int prefix = Math.min(a.size(), b.size());
		for (int i = 0; i < prefix; i++)
		{
			SpecHistoryEntry x = a.get(i), y = b.get(i);
			if (x == null || y == null || x.getWeapon() != y.getWeapon() || x.getHit() != y.getHit()
				|| !name(x.getPlayerName()).equals(name(y.getPlayerName()))) { return Result.CONFLICT; }
		}
		if (a.size() > b.size()) { return Result.STALE; }
		if (a.size() < b.size()) { return Result.EXTENSION; }
		return local.getCurrent() == incoming.getCurrent()
			&& local.getAttackLevel() == incoming.getAttackLevel()
			&& local.getStrengthLevel() == incoming.getStrengthLevel()
			&& local.getMagicLevel() == incoming.getMagicLevel()
			&& local.getMagicDef() == incoming.getMagicDef()
			&& local.isAccursedApplied() == incoming.isAccursedApplied()
			&& local.isDrained() == incoming.isDrained() ? Result.SAME : Result.CONFLICT;
	}
}
