package net.betterpartydefence;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Collections;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.betterpartydefence.DefenceTracker.SyncState;
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
        return gson.toJsonTree(new BpdDefenceSync(301,state,0,0,1,1,0,100,1)).getAsJsonObject();
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
        JsonObject json = packet();
        for (String key : new String[]{"eventVersion","historyEventIds","historyTickAnchors","historyEncounterTicks","syncUncertain"})
        { json.remove(key); }
        SyncState decoded = decode(json);
        assertNotNull(decoded);
        assertNull(decoded.getHistory().get(0).getEventId());
    }

    @Test public void mismatchedArraysCannotSilentlyLoseEvents()
    {
        JsonObject json = packet();
        json.getAsJsonArray("historyHits").add(50);
        assertNull(decode(json));
    }

    @Test public void unknownWeaponRejectsEntireSnapshot()
    {
        JsonObject json = packet();
        json.getAsJsonArray("historyWeapons").set(0,gson.toJsonTree("UNSUPPORTED"));
        assertNull(decode(json));
    }

    @Test public void invalidAbsoluteTotalIsRejected()
    {
        JsonObject json = packet(); json.addProperty("current",-1);
        assertNull(decode(json));
        json = packet(); json.addProperty("current",271);
        assertNull(decode(json));
    }

    @Test public void uncertainPeerCannotSupplyAnAuthoritativeTotal()
    {
        JsonObject json = packet(); json.addProperty("syncUncertain",true);
        assertNull(decode(json));
    }

    @Test public void negativeHitAndMissingHistoryAreRejected()
    {
        JsonObject json = packet();
        json.getAsJsonArray("historyHits").set(0,gson.toJsonTree(-1));
        assertNull(decode(json));
        json = packet(); json.remove("historyPlayers");
        assertNull(decode(json));
    }

    @Test public void unsupportedEventVersionFallsBackWithoutDroppingTheRow()
    {
        JsonObject json = packet(); json.addProperty("eventVersion",999);
        SyncState decoded = decode(json);
        assertNotNull(decoded);
        assertEquals(1,decoded.getHistory().size());
        assertNull(decoded.getHistory().get(0).getEventId());
        assertEquals(189,decoded.getCurrent());
    }
}
