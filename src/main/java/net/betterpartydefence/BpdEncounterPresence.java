package net.betterpartydefence;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Experimental Better Party Defence encounter-presence heartbeat. It lets BPD clients in the
 * same Hub Party prove which actual raid/instance they are currently in before accepting normal
 * RuneLite SpecialCounterUpdate events from one another.
 */
public class BpdEncounterPresence extends PartyMemberMessage
{
	public static final int PROTOCOL_VERSION = 1;

	private int protocolVersion;
	private int world;
	private int scopeType;
	private int scopeId;
	private long sentAtMillis;

	/** Required for party-message deserialisation. */
	public BpdEncounterPresence()
	{
	}

	public BpdEncounterPresence(int world, int scopeType, int scopeId)
	{
		this.protocolVersion = PROTOCOL_VERSION;
		this.world = world;
		this.scopeType = scopeType;
		this.scopeId = scopeId;
		this.sentAtMillis = System.currentTimeMillis();
	}

	public int getProtocolVersion() { return protocolVersion; }
	public int getWorld() { return world; }
	public int getScopeType() { return scopeType; }
	public int getScopeId() { return scopeId; }
	public long getSentAtMillis() { return sentAtMillis; }
}
