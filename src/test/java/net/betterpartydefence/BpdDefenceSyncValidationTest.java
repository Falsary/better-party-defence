package net.betterpartydefence;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Collections;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.betterpartydefence.DefenceTracker.SyncState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;
import static org.junit.Assert.*;

public class BpdDefenceSyncValidationTest
{
    private final Gson gson = new Gson();

    private JsonObject packet()
    {
        SpecHistoryEntry row = new SpecHistoryEntry("Alice",SpecialWeapon.DRAGON_WARHAMMER,1,
            "event-1","raid-start:1:7",4);
        SyncState state = new SyncState("Chaos Elemental",BossDefence.CHAOS_ELEMENTAL,
            189,0,270,270,270,270,270,70,70,false,false,false,true,
            Collections.singletonList(row));
        return gson.toJsonTree(new BpdDefenceSync(301,state,0,0,3300,3900,2,100,1)).getAsJsonObject();
    }

    private SyncState decode(JsonObject json)
    {
        return gson.fromJson(json,BpdDefenceSync.class).toSyncState();
    }

    @Test public void completeSnapshotRetainsEventIdentity()
    {
        SyncState decoded = decode(packet());
        assertNotNull(decoded);
        assertEquals("event-1",decoded.getHistory().get(0).getEventId());
        assertEquals(4,decoded.getHistory().get(0).getEncounterTick());
    }

    @Test public void legacyPacketWithoutOptionalFieldsRemainsReadable()
    {
        String legacy = "{\"protocolVersion\":1,\"world\":301,\"bossType\":\"CHAOS_ELEMENTAL\","
            + "\"bossX\":3300,\"bossY\":3900,\"bossPlane\":2,\"partyId\":7,\"drained\":true,"
            + "\"current\":189,\"min\":0,\"base\":270,\"attackLevel\":265,\"strengthLevel\":260,"
            + "\"magicLevel\":270,\"magicBaseLevel\":270,\"magicDef\":70,\"magicBaseDef\":70,"
            + "\"historyPlayers\":[\"Alice\"],\"historyWeapons\":[\"DRAGON_WARHAMMER\"],\"historyHits\":[1]}";
        BpdDefenceSync message = gson.fromJson(legacy, BpdDefenceSync.class);
        assertTrue(message.isSupportedProtocol());
        assertEquals(7L, message.getPartyId());
        assertEquals(new WorldPoint(3300, 3900, 2), message.getBossLocation());
        SyncState decoded = message.toSyncState();
        assertNotNull(decoded);
        assertEquals(265, decoded.getAttackLevel());
        assertEquals(260, decoded.getStrengthLevel());
        assertNull(decoded.getHistory().get(0).getEventId());
        assertTrue(gson.toJson(packet()).length() < legacy.length());
    }

    @Test public void compactSnapshotOmitsDefaultsAndPacksTheBossTile()
    {
        JsonObject json = packet();
        for (String omitted : new String[]{"partyId", "bossX", "bossY", "bossPlane", "at", "st",
            "n", "u", "dm", "eo", "s", "i"})
        {
            assertFalse(omitted, json.has(omitted));
        }
        assertTrue(json.get("p").isJsonPrimitive());
        BpdDefenceSync message = gson.fromJson(json, BpdDefenceSync.class);
        assertEquals(new WorldPoint(3300, 3900, 2), message.getBossLocation());
        assertEquals(270, message.toSyncState().getAttackLevel());
        assertEquals(270, message.toSyncState().getStrengthLevel());
        assertEquals(100, message.getHealthPercent());

        json.addProperty("p", 1 << 30);
        assertNull(gson.fromJson(json, BpdDefenceSync.class).getBossLocation());
    }

    @Test public void changedBossLevelsAndHistorySurviveCompactRoundTrip()
    {
        SpecHistoryEntry row = new SpecHistoryEntry("Alice", SpecialWeapon.BANDOS_GODSWORD,
            null, 50, "event-1", "raid-start:1:7", 4, 123456L);
        SyncState state = new SyncState("Corporeal Beast", BossDefence.CORPOREAL_BEAST,
            0, 0, 310, 0, 17, 120, 150, -5, -5, false, false, false, true,
            Collections.singletonList(row));
        BpdDefenceSync message = new BpdDefenceSync(301, state, BpdDefenceSync.SCOPE_INSTANCE,
            42, 3300, 3900, 2, 80, 9);
        JsonObject json = gson.toJsonTree(message).getAsJsonObject();
        assertFalse(json.has("h"));
        assertEquals(-1, gson.fromJson(json, BpdDefenceSync.class).getHealthPercent());
        assertEquals(state, decode(json));
    }

    static BpdDefenceSync withLegacyPartyId(BpdDefenceSync message, long partyId)
    {
        Gson gson = new Gson();
        JsonObject json = gson.toJsonTree(message).getAsJsonObject();
        json.addProperty("v", 1);
        json.addProperty("partyId", partyId);
        return gson.fromJson(json, BpdDefenceSync.class);
    }

    @Test public void mismatchedArraysCannotSilentlyLoseEvents()
    {
        JsonObject json = packet();
        json.getAsJsonArray("hh").add(50);
        assertNull(decode(json));
    }

    @Test public void unknownWeaponRejectsEntireSnapshot()
    {
        JsonObject json = packet();
        json.getAsJsonArray("hw").set(0,gson.toJsonTree("UNSUPPORTED"));
        assertNull(decode(json));
    }

    @Test public void invalidAbsoluteTotalIsRejected()
    {
        JsonObject json = packet(); json.addProperty("d",-1);
        assertNull(decode(json));
        json = packet(); json.addProperty("d",271);
        assertNull(decode(json));
    }

    @Test public void uncertainPeerCannotSupplyAnAuthoritativeTotal()
    {
        JsonObject json = packet(); json.addProperty("u",true);
        assertNull(decode(json));
    }

    @Test public void negativeHitAndMissingHistoryAreRejected()
    {
        JsonObject json = packet();
        json.getAsJsonArray("hh").set(0,gson.toJsonTree(-1));
        assertNull(decode(json));
        json = packet(); json.remove("hp");
        assertNull(decode(json));
    }

    @Test public void unsupportedEventVersionFallsBackWithoutDroppingTheRow()
    {
        JsonObject json = packet(); json.addProperty("ev",999);
        SyncState decoded = decode(json);
        assertNotNull(decoded);
        assertEquals(1,decoded.getHistory().size());
        assertNull(decoded.getHistory().get(0).getEventId());
        assertEquals(189,decoded.getCurrent());
    }
}
