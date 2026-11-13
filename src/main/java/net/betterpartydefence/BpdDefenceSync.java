package net.betterpartydefence;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import lombok.ToString;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.betterpartydefence.DefenceTracker.SyncState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.party.messages.PartyMemberMessage;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

/**
 * Experimental Better Party Defence party message. This is deliberately additive to RuneLite's
 * normal SpecialCounterUpdate path: it carries an absolute tracker snapshot so another BPD user
 * in the same encounter can stay in sync even when that boss actor is not loaded on their client.
 */
@ToString(doNotUseGetters = true)
public class BpdDefenceSync extends PartyMemberMessage
{
	public static final int PROTOCOL_VERSION = 2;
	private static final int LEGACY_PROTOCOL_VERSION = 1;
	private static final int COORDINATE_MASK = 0x3fff;
	public static final int SCOPE_WORLD = 0;
	public static final int SCOPE_COX = 1;
	public static final int SCOPE_TOB = 2;
	public static final int SCOPE_INSTANCE = 3;

	@SerializedName(value = "v", alternate = "protocolVersion")
	private int protocolVersion;

	@SerializedName(value = "w", alternate = "world")
	private int world;

	@SerializedName(value = "b", alternate = "bossType")
	private String bossType;

	@SerializedName(value = "n", alternate = "bossName")
	private String bossName;

	@SerializedName(value = "s", alternate = "scopeType")
	private Integer scopeType;

	@SerializedName(value = "i", alternate = "scopeId")
	private Integer scopeId;

	@SerializedName("p")
	@ToString.Exclude
	private Integer bossLocation;

	@SerializedName(value = "h", alternate = "healthPercent")
	private Integer healthPercent;

	@SerializedName(value = "t", alternate = "sentAtMillis")
	private long sentAtMillis;

	@SerializedName(value = "r", alternate = "stateVersion")
	private Long stateVersion;

	@SerializedName(value = "u", alternate = "syncUncertain")
	private Boolean syncUncertain;

	@SerializedName(value = "d", alternate = "current")
	private long current;

	@SerializedName(value = "f", alternate = "min")
	private long min;

	@SerializedName(value = "a", alternate = "base")
	private long base;

	@SerializedName(value = "at", alternate = "attackLevel")
	private Long attackLevel;

	@SerializedName(value = "st", alternate = "strengthLevel")
	private Long strengthLevel;

	@SerializedName(value = "ml", alternate = "magicLevel")
	private long magicLevel;

	@SerializedName(value = "mb", alternate = "magicBaseLevel")
	private long magicBaseLevel;

	@SerializedName(value = "md", alternate = "magicDef")
	private Long magicDef;

	@SerializedName(value = "m", alternate = "magicBaseDef")
	private Long magicBaseDef;

	@SerializedName(value = "mu", alternate = "magicUsesDefence")
	private Boolean magicUsesDefence;

	@SerializedName(value = "dm", alternate = "demon")
	private Boolean demon;

	@SerializedName(value = "ac", alternate = "accursedApplied")
	private Boolean accursedApplied;

	@SerializedName(value = "dr", alternate = "drained")
	private Boolean drained;

	@SerializedName(value = "hp", alternate = "historyPlayers")
	private List<String> historyPlayers;

	@SerializedName(value = "hw", alternate = "historyWeapons")
	private List<String> historyWeapons;

	@SerializedName(value = "hh", alternate = "historyHits")
	private List<Integer> historyHits;

	@SerializedName(value = "ev", alternate = "eventVersion")
	private Integer eventVersion;

	@SerializedName(value = "ei", alternate = "historyEventIds")
	private List<String> historyEventIds;

	@SerializedName(value = "ea", alternate = "historyTickAnchors")
	private List<String> historyTickAnchors;

	@SerializedName(value = "et", alternate = "historyEncounterTicks")
	private List<Long> historyEncounterTicks;

	@SerializedName(value = "eo", alternate = "historyObservedAtMillis")
	private List<Long> historyObservedAtMillis;

	// Read-only compatibility fields for version 1 snapshots.
	@ToString.Exclude
	private Integer bossX;
	@ToString.Exclude
	private Integer bossY;
	@ToString.Exclude
	private Integer bossPlane;
	@ToString.Exclude
	private Long partyId;

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
		this.bossName = state.getBossType().getNpcName().equals(state.getBossName()) ? null : state.getBossName();
		this.scopeType = scopeType == SCOPE_WORLD ? null : scopeType;
		this.scopeId = scopeId == 0 ? null : scopeId;
		this.bossLocation = packBossLocation(bossX, bossY, bossPlane);
		this.healthPercent = scopeType == SCOPE_WORLD && healthPercent >= 0 ? healthPercent : null;
		this.sentAtMillis = System.currentTimeMillis();
		this.stateVersion = stateVersion > 0 ? stateVersion : null;
		this.current = state.getCurrent();
		this.min = state.getMin();
		this.base = state.getBase();
		this.attackLevel = state.getAttackLevel() == state.getBossType().getBaseAtk() ? null : state.getAttackLevel();
		this.strengthLevel = state.getStrengthLevel() == state.getBossType().getBaseStr() ? null : state.getStrengthLevel();
		this.magicLevel = state.getMagicLevel();
		this.magicBaseLevel = state.getMagicBaseLevel();
		this.magicDef = state.getMagicDef() == 0 ? null : state.getMagicDef();
		this.magicBaseDef = state.getMagicBaseDef() == 0 ? null : state.getMagicBaseDef();
		this.magicUsesDefence = state.isMagicUsesDefence() ? Boolean.TRUE : null;
		this.demon = state.isDemon() ? Boolean.TRUE : null;
		this.accursedApplied = state.isAccursedApplied() ? Boolean.TRUE : null;
		this.drained = state.isDrained() ? Boolean.TRUE : null;

		List<SpecHistoryEntry> history = state.getHistory();

		this.historyEventIds = new ArrayList<>(history.size());
		this.historyTickAnchors = new ArrayList<>(history.size());
		this.historyEncounterTicks = new ArrayList<>(history.size());
		this.historyObservedAtMillis = new ArrayList<>(history.size());
		this.historyPlayers = new ArrayList<>(history.size());
		this.historyWeapons = new ArrayList<>(history.size());
		this.historyHits = new ArrayList<>(history.size());
		for (SpecHistoryEntry entry : history)
		{
			historyEventIds.add(entry.getEventId());
			historyTickAnchors.add(entry.getTickAnchor());
			historyEncounterTicks.add(entry.getEncounterTick());
			historyObservedAtMillis.add(entry.getObservedAtMillis() > 0 ? entry.getObservedAtMillis() : null);
			historyPlayers.add(entry.getPlayerName());
			historyWeapons.add(entry.getSpell() == null ? (entry.getWeapon() == null ? "" : entry.getWeapon().name())
				: "spell:" + entry.getSpell().name());
			historyHits.add(entry.getHit());
		}
		if (historyEventIds.stream().anyMatch(Objects::nonNull))
		{
			this.eventVersion = EncounterTimeline.VERSION;
		}
		else
		{
			historyEventIds = null;
			historyTickAnchors = null;
			historyEncounterTicks = null;
		}
		if (historyObservedAtMillis.stream().allMatch(Objects::isNull))
		{
			historyObservedAtMillis = null;
		}
	}

	static Integer packBossLocation(int x, int y, int plane)
	{
		if ((x == 0 && y == 0) || (x & ~COORDINATE_MASK) != 0
			|| (y & ~COORDINATE_MASK) != 0 || plane < 0 || plane > 3)
		{
			return null;
		}
		return (plane << 28) | (x << 14) | y;
	}

	@ToString.Include(name = "location")
	public WorldPoint getBossLocation()
	{
		if (bossLocation != null)
		{
			return bossLocation > 0 && bossLocation >>> 30 == 0 ? WorldPoint.fromCoord(bossLocation) : null;
		}
		Integer legacy = bossX == null || bossY == null || bossPlane == null
			? null : packBossLocation(bossX, bossY, bossPlane);
		return legacy == null ? null : WorldPoint.fromCoord(legacy);
	}

	public boolean isSupportedProtocol()
	{
		return protocolVersion == PROTOCOL_VERSION || protocolVersion == LEGACY_PROTOCOL_VERSION;
	}

	public SyncState toSyncState()
	{
		if (!isSupportedProtocol()) { return null; }
		BossDefence boss;
		try
		{
			boss = BossDefence.valueOf(bossType);
		}
		catch (RuntimeException ex)
		{
			return null;
		}

		// These are NPC levels used by BGS spillover. Compact snapshots omit baseline values.
		long bossAttack = attackLevel != null ? attackLevel
			: protocolVersion == LEGACY_PROTOCOL_VERSION ? 0L : boss.getBaseAtk();
		long bossStrength = strengthLevel != null ? strengthLevel
			: protocolVersion == LEGACY_PROTOCOL_VERSION ? 0L : boss.getBaseStr();

		// Reject partial histories rather than trusting a total after silently dropping rows.
		if (Boolean.TRUE.equals(syncUncertain) || historyPlayers == null || historyWeapons == null || historyHits == null
			|| historyPlayers.size() != historyWeapons.size() || historyPlayers.size() != historyHits.size()
			|| historyPlayers.size() > 4096 || (isDrained() && historyPlayers.isEmpty())
			|| base < 0 || min < 0 || min > base || current < min || current > base
			|| bossAttack < 0 || bossStrength < 0 || magicLevel < 0 || magicBaseLevel < 0)
		{
			return null;
		}
		List<SpecHistoryEntry> history = new ArrayList<>();
		int size = historyPlayers.size();
		boolean eventsSupported = getEventVersion() == EncounterTimeline.VERSION
			&& size <= EncounterTimeline.MAX_EVENTS
			&& historyEventIds != null && historyEventIds.size() == size
			&& historyTickAnchors != null && historyTickAnchors.size() == size
			&& historyEncounterTicks != null && historyEncounterTicks.size() == size;
		boolean timestampsSupported = eventsSupported && historyObservedAtMillis != null
			&& historyObservedAtMillis.size() == size;
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
				SpellDrain spell = weaponName.startsWith("spell:") ? SpellDrain.valueOf(weaponName.substring(6)) : null;
				SpecialWeapon weapon = spell == null ? SpecialWeapon.valueOf(weaponName) : null;
				if (spell == null ? !DefenceTracker.isSupportedWeapon(weapon) : hit != 1) { return null; }
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
					Long observedAt = timestampsSupported ? historyObservedAtMillis.get(i) : null;
					history.add(new SpecHistoryEntry(player, weapon, spell, hit, id, anchor, tick,
						observedAt == null ? 0L : Math.max(0L, observedAt)));
				}
				else
				{
					history.add(new SpecHistoryEntry(player, weapon, spell, hit, null, null, -1));
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
			bossAttack,
			bossStrength,
			magicLevel,
			magicBaseLevel,
			magicDef == null ? 0L : magicDef,
			magicBaseDef == null ? 0L : magicBaseDef,
			Boolean.TRUE.equals(magicUsesDefence),
			Boolean.TRUE.equals(demon),
			Boolean.TRUE.equals(accursedApplied),
			Boolean.TRUE.equals(drained),
			Collections.unmodifiableList(history));
	}

	public int getEventVersion() { return eventVersion == null ? 0 : eventVersion; }
	public int getProtocolVersion() { return protocolVersion; }
	public int getWorld() { return world; }
	public String getBossType() { return bossType; }
	public int getScopeType() { return scopeType == null ? SCOPE_WORLD : scopeType; }
	public int getScopeId() { return scopeId == null ? 0 : scopeId; }
	public int getHealthPercent() { return healthPercent == null ? -1 : healthPercent; }
	public long getSentAtMillis() { return sentAtMillis; }
	public long getPartyId() { return partyId == null ? 0L : partyId; }
	public long getStateVersion() { return stateVersion == null ? 0L : stateVersion; }
	public boolean isDrained() { return Boolean.TRUE.equals(drained); }
}
