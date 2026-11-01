package net.betterpartydefence;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Lightweight BPD proximity message for one concrete supported boss. A client sends this when the
 * boss is actually rendered, allowing another BPD peer that still holds valid remembered state for
 * the same encounter to answer with an absolute {@link BpdDefenceSync} snapshot immediately.
 */
public class BpdBossPresence extends PartyMemberMessage
{
	public static final int PROTOCOL_VERSION = 1;

	private int protocolVersion;
	private int world;
	private String bossType;
	private int scopeType;
	private int scopeId;
	private int bossX;
	private int bossY;
	private int bossPlane;
	private int healthPercent;
	private long sentAtMillis;
	/** Sender's PartyService session. Zero means a legacy sender with no party id. */
	private long partyId;

	/** Required for party-message deserialisation. */
	public BpdBossPresence()
	{
	}

	public BpdBossPresence(
		int world,
		BossDefence boss,
		int scopeType,
		int scopeId,
		int bossX,
		int bossY,
		int bossPlane,
		int healthPercent)
	{
		this.protocolVersion = PROTOCOL_VERSION;
		this.world = world;
		this.bossType = boss == null ? null : boss.name();
		this.scopeType = scopeType;
		this.scopeId = scopeId;
		this.bossX = bossX;
		this.bossY = bossY;
		this.bossPlane = bossPlane;
		this.healthPercent = healthPercent;
		this.sentAtMillis = System.currentTimeMillis();
	}

	public BossDefence toBossType()
	{
		if (bossType == null)
		{
			return null;
		}
		try
		{
			return BossDefence.valueOf(bossType);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	public int getProtocolVersion() { return protocolVersion; }
	public int getWorld() { return world; }
	public int getScopeType() { return scopeType; }
	public int getScopeId() { return scopeId; }
	public int getBossX() { return bossX; }
	public int getBossY() { return bossY; }
	public int getBossPlane() { return bossPlane; }
	public int getHealthPercent() { return healthPercent; }
	public long getSentAtMillis() { return sentAtMillis; }
	public long getPartyId() { return partyId; }
	public BpdBossPresence withPartyId(long partyId) { this.partyId = partyId; return this; }
}
