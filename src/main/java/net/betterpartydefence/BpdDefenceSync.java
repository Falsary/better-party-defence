package net.betterpartydefence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.betterpartydefence.DefenceTracker.SyncState;
import net.runelite.client.party.messages.PartyMemberMessage;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

/**
 * Experimental Better Party Defence party message. This is deliberately additive to RuneLite's
 * normal SpecialCounterUpdate path: it carries an absolute tracker snapshot so another BPD user
 * in the same encounter can stay in sync even when that boss actor is not loaded on their client.
 */
public class BpdDefenceSync extends PartyMemberMessage
{
	public static final int PROTOCOL_VERSION = 1;
	public static final int SCOPE_WORLD = 0;
	public static final int SCOPE_COX = 1;
	public static final int SCOPE_TOB = 2;
	public static final int SCOPE_INSTANCE = 3;

	private int protocolVersion;
	private int world;
	private String bossType;
	private String bossName;
	private int scopeType;
	private int scopeId;
	private int bossX;
	private int bossY;
	private int bossPlane;
	private int healthPercent;
	private long sentAtMillis;
	/** Sender's PartyService session. Zero means a legacy sender with no party id. */
	private long partyId;
	/** Logical snapshot version. Zero denotes a legacy sender with no version field. */
	private long stateVersion;
	/** Received from event-rewrite peers; uncertain totals cannot be authoritative. */
	private boolean syncUncertain;

	private long current;
	private long min;
	private long base;
	private long attackLevel;
	private long strengthLevel;
	private long magicLevel;
	private long magicBaseLevel;
	private long magicDef;
	private long magicBaseDef;
	private boolean magicUsesDefence;
	private boolean demon;
	private boolean accursedApplied;
	private boolean drained;

	private List<String> historyPlayers;
	private List<String> historyWeapons;
	private List<Integer> historyHits;
	/** Additive extension: protocol 1 snapshots remain readable by installed BPD clients. */
	private int eventVersion;
	private List<String> historyEventIds;
	private List<String> historyTickAnchors;
	private List<Long> historyEncounterTicks;

	/** Required for party-message deserialisation. */
	public BpdDefenceSync()
	{
	}

	/** Retained for callers compiled against the original unversioned message. */
	public BpdDefenceSync(int world, SyncState state, int scopeType, int scopeId,
		int bossX, int bossY, int bossPlane, int healthPercent)
	{
		this(world, state, scopeType, scopeId, bossX, bossY, bossPlane, healthPercent, 0L);
	}

	public BpdDefenceSync(
		int world,
		SyncState state,
		int scopeType,
		int scopeId,
		int bossX,
		int bossY,
		int bossPlane,
		int healthPercent,
		long stateVersion)
	{
		this.protocolVersion = PROTOCOL_VERSION;
		this.world = world;
		this.bossType = state.getBossType().name();
		this.bossName = state.getBossName();
		this.scopeType = scopeType;
		this.scopeId = scopeId;
		this.bossX = bossX;
		this.bossY = bossY;
		this.bossPlane = bossPlane;
		this.healthPercent = healthPercent;
		this.sentAtMillis = System.currentTimeMillis();
		this.stateVersion = Math.max(0L, stateVersion);
		this.current = state.getCurrent();
		this.min = state.getMin();
		this.base = state.getBase();
		this.attackLevel = state.getAttackLevel();
		this.strengthLevel = state.getStrengthLevel();
		this.magicLevel = state.getMagicLevel();
		this.magicBaseLevel = state.getMagicBaseLevel();
		this.magicDef = state.getMagicDef();
		this.magicBaseDef = state.getMagicBaseDef();
		this.magicUsesDefence = state.isMagicUsesDefence();
		this.demon = state.isDemon();
		this.accursedApplied = state.isAccursedApplied();
		this.drained = state.isDrained();

		List<SpecHistoryEntry> history = state.getHistory();
		this.eventVersion = EncounterTimeline.VERSION;
		this.historyEventIds = new ArrayList<>(history.size());
		this.historyTickAnchors = new ArrayList<>(history.size());
		this.historyEncounterTicks = new ArrayList<>(history.size());
		this.historyPlayers = new ArrayList<>(history.size());
		this.historyWeapons = new ArrayList<>(history.size());
		this.historyHits = new ArrayList<>(history.size());
		for (SpecHistoryEntry entry : history)
		{
			historyEventIds.add(entry.getEventId());
			historyTickAnchors.add(entry.getTickAnchor());
			historyEncounterTicks.add(entry.getEncounterTick());
			historyPlayers.add(entry.getPlayerName());
			historyWeapons.add(entry.getWeapon() == null ? "" : entry.getWeapon().name());
			historyHits.add(entry.getHit());
		}
	}

	public SyncState toSyncState()
	{
		BossDefence boss;
		try
		{
			boss = BossDefence.valueOf(bossType);
		}
		catch (RuntimeException ex)
		{
			return null;
		}

		// Reject partial histories rather than trusting a total after silently dropping rows.
		if (syncUncertain || historyPlayers == null || historyWeapons == null || historyHits == null
			|| historyPlayers.size() != historyWeapons.size() || historyPlayers.size() != historyHits.size()
			|| historyPlayers.size() > 4096 || (drained && historyPlayers.isEmpty())
			|| base < 0 || min < 0 || min > base || current < min || current > base
			|| attackLevel < 0 || strengthLevel < 0 || magicLevel < 0 || magicBaseLevel < 0)
		{
			return null;
		}
		List<SpecHistoryEntry> history = new ArrayList<>();
		int size = historyPlayers.size();
		boolean eventsSupported = eventVersion == EncounterTimeline.VERSION
			&& size <= EncounterTimeline.MAX_EVENTS
			&& historyEventIds != null && historyEventIds.size() == size
			&& historyTickAnchors != null && historyTickAnchors.size() == size
			&& historyEncounterTicks != null && historyEncounterTicks.size() == size;
		for (int i = 0; i < size; i++)
		{
			try
			{
				String weaponName = historyWeapons.get(i);
				Integer hit = historyHits.get(i);
				if (weaponName == null || hit == null || hit < 0)
				{
					return null;
				}
				SpecialWeapon weapon = SpecialWeapon.valueOf(weaponName);
				if (!DefenceTracker.isSupportedWeapon(weapon)) { return null; }
				String player = historyPlayers.get(i);
				if (player == null || player.trim().isEmpty())
				{
					player = "Unknown";
				}
				String id = eventsSupported ? historyEventIds.get(i) : null;
				String anchor = eventsSupported ? historyTickAnchors.get(i) : null;
				Long tick = eventsSupported ? historyEncounterTicks.get(i) : null;
				if (id != null && !id.isEmpty() && id.length() <= 128 && anchor != null
					&& !anchor.isEmpty() && anchor.length() <= 128 && tick != null && tick >= 0)
				{
					history.add(new SpecHistoryEntry(player, weapon, hit, id, anchor, tick));
				}
				else
				{
					history.add(new SpecHistoryEntry(player, weapon, hit));
				}
			}
			catch (RuntimeException ignored)
			{
				// An unknown row cannot safely justify this absolute snapshot.
				return null;
			}
		}

		return new SyncState(
			bossName == null ? boss.getNpcName() : bossName,
			boss,
			current,
			min,
			base,
			attackLevel,
			strengthLevel,
			magicLevel,
			magicBaseLevel,
			magicDef,
			magicBaseDef,
			magicUsesDefence,
			demon,
			accursedApplied,
			drained,
			Collections.unmodifiableList(history));
	}

	public int getEventVersion() { return eventVersion; }
	public int getProtocolVersion() { return protocolVersion; }
	public int getWorld() { return world; }
	public String getBossType() { return bossType; }
	public String getBossName() { return bossName; }
	public int getScopeType() { return scopeType; }
	public int getScopeId() { return scopeId; }
	public int getBossX() { return bossX; }
	public int getBossY() { return bossY; }
	public int getBossPlane() { return bossPlane; }
	public int getHealthPercent() { return healthPercent; }
	public long getSentAtMillis() { return sentAtMillis; }
	public long getPartyId() { return partyId; }
	public BpdDefenceSync withPartyId(long partyId) { this.partyId = partyId; return this; }
	public long getStateVersion() { return stateVersion; }
	public long getCurrent() { return current; }
	public long getMin() { return min; }
	public long getBase() { return base; }
	public long getAttackLevel() { return attackLevel; }
	public long getStrengthLevel() { return strengthLevel; }
	public long getMagicLevel() { return magicLevel; }
	public long getMagicBaseLevel() { return magicBaseLevel; }
	public long getMagicDef() { return magicDef; }
	public long getMagicBaseDef() { return magicBaseDef; }
	public boolean isMagicUsesDefence() { return magicUsesDefence; }
	public boolean isDemon() { return demon; }
	public boolean isAccursedApplied() { return accursedApplied; }
	public boolean isDrained() { return drained; }
	public List<String> getHistoryPlayers() { return historyPlayers; }
	public List<String> getHistoryWeapons() { return historyWeapons; }
	public List<Integer> getHistoryHits() { return historyHits; }
}
