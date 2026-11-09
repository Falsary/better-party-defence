package net.betterpartydefence;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.party.messages.PartyMemberMessage;

/** A landed Red Keris effect. It carries its original impact time and no Defence state. */
public class BpdKerisTimer extends PartyMemberMessage
{
	static final int PROTOCOL_VERSION = 1;
	private static final int TICK_MILLIS = 600;

	private int protocolVersion;
	private long partyId;
	private int world;
	private BossDefence boss;
	private int scopeType;
	private int scopeId;
	private int npcId;
	private int bossX;
	private int bossY;
	private int bossPlane;
	private long hitAtMillis;
	private String clockScope;
	private long hitRaidTick;

	public BpdKerisTimer()
	{
	}

	BpdKerisTimer(long partyId, int world, BossDefence boss, int scopeType, int scopeId,
		int npcId, WorldPoint point, long hitAtMillis, String clockScope, long hitRaidTick)
	{
		protocolVersion = PROTOCOL_VERSION;
		this.partyId = partyId;
		this.world = world;
		this.boss = boss;
		this.scopeType = scopeType;
		this.scopeId = scopeId;
		this.npcId = npcId;
		bossX = point.getX();
		bossY = point.getY();
		bossPlane = point.getPlane();
		this.hitAtMillis = hitAtMillis;
		this.clockScope = clockScope;
		this.hitRaidTick = hitRaidTick;
	}

	int remainingTicks(long now, String localClockScope, long raidTick)
	{
		int duration = BossEffectDisplay.KERIS_EFFECT_TICKS;
		long age = now - hitAtMillis;
		if (hitAtMillis <= 0 || age < -TICK_MILLIS || age >= duration * TICK_MILLIS)
		{
			return 0;
		}
		if (clockScope != null && clockScope.equals(localClockScope) && hitRaidTick >= 0 && raidTick >= 0)
		{
			long elapsed = raidTick - hitRaidTick;
			return elapsed < 0 || elapsed >= duration ? 0 : duration - (int) elapsed;
		}
		return duration - (int) (Math.max(0, age) / TICK_MILLIS);
	}

	int getProtocolVersion() { return protocolVersion; }
	long getPartyId() { return partyId; }
	int getWorld() { return world; }
	BossDefence getBoss() { return boss; }
	int getScopeType() { return scopeType; }
	int getScopeId() { return scopeId; }
	int getNpcId() { return npcId; }
	int getBossX() { return bossX; }
	int getBossY() { return bossY; }
	int getBossPlane() { return bossPlane; }
	long getHitAtMillis() { return hitAtMillis; }
}
