package net.betterpartydefence;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import lombok.ToString;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.party.messages.PartyMemberMessage;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

/** One observation from its originator. The party transport supplies the sender identity. */
@Getter
@ToString(doNotUseGetters = true)
public final class BpdSpecEvent extends PartyMemberMessage
{
	@SerializedName("w") private int world;
	@SerializedName("s") private int scopeType;
	@SerializedName("i") private Integer scopeId;
	@SerializedName("b") private BossDefence boss;
	@ToString.Exclude
	@SerializedName("p") private int tile;
	@SerializedName("n") private int npcIndex;
	@SerializedName("hp") private Integer healthPercent;
	@SerializedName("a") private String anchor;
	@SerializedName("t") private long tick;
	@SerializedName("u") private Long observedAt;
	@SerializedName("m") private long sentAt;
	@SerializedName("e") private String eventId;
	@SerializedName("r") private String previousId;
	@SerializedName("k") private SpecialWeapon weapon;
	@SerializedName("l") private SpellDrain spell;
	@SerializedName("h") private int hit;

	private BpdSpecEvent()
	{
		// Gson.
	}

	BpdSpecEvent(int world, int scopeType, int scopeId, BossDefence boss, int npcIndex, int tile, int healthPercent,
		SpecHistoryEntry entry, String previousId)
	{
		this.world = world;
		this.scopeType = scopeType;
		this.scopeId = scopeId == 0 ? null : scopeId;
		this.boss = boss;
		this.tile = tile;
		this.npcIndex = npcIndex;
		this.healthPercent = healthPercent >= 0 ? healthPercent : null;
		this.anchor = entry.getTickAnchor().equals(entry.getEventId()) ? null : entry.getTickAnchor();
		this.tick = entry.getEncounterTick();
		this.sentAt = System.currentTimeMillis();
		this.observedAt = entry.getObservedAtMillis() > 0 ? entry.getObservedAtMillis() : null;
		this.eventId = entry.getEventId();
		this.previousId = previousId;
		this.weapon = entry.getWeapon();
		this.spell = entry.getSpell();
		this.hit = entry.getHit();
	}

	int getScopeId()
	{
		return scopeId == null ? 0 : scopeId;
	}

	int getHealthPercent()
	{
		return healthPercent == null ? -1 : healthPercent;
	}

	@ToString.Include
	WorldPoint location()
	{
		return tile > 0 && tile >>> 30 == 0 ? WorldPoint.fromCoord(tile) : null;
	}

	SpecHistoryEntry historyEntry(String player)
	{
		if (!validId(eventId) || anchor != null && !validId(anchor)
			|| previousId != null && (!validId(previousId) || eventId.equals(previousId))
			|| tick < 0 || hit < 0 || hit > 65535 || weapon == SpecialWeapon.TONALZTICS_OF_RALOS && hit > 2
			|| healthPercent != null && (healthPercent < 0 || healthPercent > 100))
		{
			return null;
		}
		SpecHistoryEntry entry = new SpecHistoryEntry(player, weapon, spell, hit, eventId,
			anchor == null ? eventId : anchor, tick,
			observedAt != null && observedAt > 0 && observedAt <= sentAt ? observedAt : 0);
		return entry.isSupported() ? entry : null;
	}

	private static boolean validId(String id)
	{
		return id != null && !id.isEmpty() && id.length() <= 128;
	}

}
