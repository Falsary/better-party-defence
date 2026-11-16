package net.betterpartydefence;

import lombok.ToString;
import net.runelite.client.party.messages.PartyMemberMessage;

// Clear one boss encounter while the party remains active.
@ToString(doNotUseGetters = true)
public class BpdEncounterReset extends PartyMemberMessage {
	public static final int PROTOCOL_VERSION = 1;

	private int protocolVersion;
	private int world;
	private String bossType;
	private int scopeType;
	private int scopeId;
	private long sentAtMillis;
	// Sender party session; zero is legacy.
	@ToString.Exclude
	private long partyId;

	// Gson.
	public BpdEncounterReset() {
	}

	public BpdEncounterReset(int world, BossDefence boss, int scopeType, int scopeId) {
		this.protocolVersion = PROTOCOL_VERSION;
		this.world = world;
		this.bossType = boss == null ? null : boss.name();
		this.scopeType = scopeType;
		this.scopeId = scopeId;
		this.sentAtMillis = System.currentTimeMillis();
	}

	public BossDefence toBossType() {
		if (bossType == null) {
			return null;
		}
		try {
			return BossDefence.valueOf(bossType).trackingType();
		} catch (RuntimeException ex) {
			return null;
		}
	}

	public int getProtocolVersion() {
		return protocolVersion;
	}
	public int getWorld() {
		return world;
	}
	public String getBossType() {
		return bossType;
	}
	public int getScopeType() {
		return scopeType;
	}
	public int getScopeId() {
		return scopeId;
	}
	public long getSentAtMillis() {
		return sentAtMillis;
	}
	public long getPartyId() {
		return partyId;
	}
	public BpdEncounterReset withPartyId(long partyId) {
		this.partyId = partyId;
		return this;
	}
}
