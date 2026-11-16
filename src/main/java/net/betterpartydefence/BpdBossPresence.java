package net.betterpartydefence;

import lombok.ToString;
import net.runelite.client.party.messages.PartyMemberMessage;

// Request stored defence history after a boss appears.
@ToString(doNotUseGetters = true)
public class BpdBossPresence extends PartyMemberMessage {
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
	private BpdDefenceSync offeredHistory;
	// Sender party session; zero is legacy.
	@ToString.Exclude
	private long partyId;

	// Gson.
	public BpdBossPresence() {
	}

	public BpdBossPresence(
			int world,
			BossDefence boss,
			int scopeType,
			int scopeId,
			int bossX,
			int bossY,
			int bossPlane,
			int healthPercent) {
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
	public int getScopeType() {
		return scopeType;
	}
	public int getScopeId() {
		return scopeId;
	}
	public int getBossX() {
		return bossX;
	}
	public int getBossY() {
		return bossY;
	}
	public int getBossPlane() {
		return bossPlane;
	}
	public int getHealthPercent() {
		return healthPercent;
	}
	public long getSentAtMillis() {
		return sentAtMillis;
	}
	public long getPartyId() {
		return partyId;
	}
	public BpdBossPresence withPartyId(long partyId) {
		this.partyId = partyId;
		return this;
	}
	BpdDefenceSync getOfferedHistory() {
		return offeredHistory;
	}
	void offerHistory(BpdDefenceSync history) {
		offeredHistory = history;
	}
}
