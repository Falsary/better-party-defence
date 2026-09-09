package net.betterpartydefence;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Experimental Better Party Defence encounter-reset message. It invalidates the absolute
 * Defence snapshot and spec history for one logical target when an encounter authoritatively
 * ends while the surrounding world/raid session may continue.
 */
public class BpdEncounterReset extends PartyMemberMessage
{
	public static final int PROTOCOL_VERSION = 1;

	private int protocolVersion;
	private int world;
	private String bossType;
	private int scopeType;
	private int scopeId;
	private long sentAtMillis;

	/** Required for party-message deserialisation. */
	public BpdEncounterReset()
	{
	}

	public BpdEncounterReset(int world, BossDefence boss, int scopeType, int scopeId)
	{
		this.protocolVersion = PROTOCOL_VERSION;
		this.world = world;
		this.bossType = boss == null ? null : boss.name();
		this.scopeType = scopeType;
		this.scopeId = scopeId;
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
	public String getBossType() { return bossType; }
	public int getScopeType() { return scopeType; }
	public int getScopeId() { return scopeId; }
	public long getSentAtMillis() { return sentAtMillis; }
}
