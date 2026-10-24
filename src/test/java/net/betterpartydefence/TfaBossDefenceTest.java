package net.betterpartydefence;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.NPC;
import org.junit.Test;
import static org.mockito.Mockito.*;

/** Current Jagex 21 October TFA caps, distinct same-name actors, and phase transitions. */
public class TfaBossDefenceTest
{
    private static NPC npc(String name, int id, int combat)
    {
        NPC n = mock(NPC.class);
        when(n.getName()).thenReturn(name);
        when(n.getId()).thenReturn(id);
        when(n.getCombatLevel()).thenReturn(combat);
        return n;
    }

    @Test
    public void defenceFloorsMatchJagexOctober21Hotfix()
    {
        Object[][] cases = {
            {BossDefence.TFA_ARCHIVE_GUARDIAN, 260, 156},
            {BossDefence.TFA_EZEKIEL, 130, 78},
            {BossDefence.TFA_BORK, 300, 180},
            {BossDefence.TFA_SMORKS, 220, 0},
            {BossDefence.TFA_MOIA, 195, 117},
            {BossDefence.TFA_DREADNOTH, 125, 0},
            {BossDefence.TFA_XARTH, 170, 102},
            {BossDefence.TFA_DAGONHAI_ELDER, 100, 0},
            {BossDefence.TFA_DAGONHAI_MONK_16645, 100, 0},
            {BossDefence.TFA_DAGONHAI_MONK_16646, 100, 0},
            {BossDefence.TFA_OCELLUS_P1, 215, 129},
            {BossDefence.TFA_OCELLUS_P3, 250, 150}
        };
        for (Object[] entry : cases)
        {
            BossDefence boss = (BossDefence) entry[0];
            int base = (Integer) entry[1];
            int floor = (Integer) entry[2];
            assertTrue(boss.name(), boss.has(BossDefence.Flag.TFA));
            assertEquals(boss.name(), base, boss.getBaseDef());
            assertEquals(boss.name(), floor, boss.getMinDef());
            assertTrue(boss.getMinDef() >= 0 && boss.getMinDef() <= boss.getBaseDef());
        }
    }

    @Test
    public void allDagonhaiTrioActorsAreIndependent()
    {
        BossDefence elder = BossDefence.matchingNpc(npc("Dagon'hai Elder", 16643, 427));
        BossDefence monkA = BossDefence.matchingNpc(npc("Dagon'hai Monk", 16645, 427));
        BossDefence monkB = BossDefence.matchingNpc(npc("Dagon'hai Monk", 16646, 427));
        Set<BossDefence> distinct = new HashSet<>();
        distinct.add(elder);
        distinct.add(monkA);
        distinct.add(monkB);
        assertEquals(3, distinct.size());
        assertNull(BossDefence.matchingNpc(npc("Dagon'hai Monk", 16699, 427)));
        assertNull(BossDefence.matchingNpcName("Dagon'hai Monk"));
    }

    @Test
    public void ocellusPhaseOneThreeAndIdleActorAreDistinct()
    {
        assertNull(BossDefence.matchingNpc(npc("Ocellus", 16672, 0)));
        assertEquals(BossDefence.TFA_OCELLUS_P1,
            BossDefence.matchingNpc(npc("Ocellus", 16673, 1121)));
        assertEquals(BossDefence.TFA_OCELLUS_P1,
            BossDefence.matchingNpc(npc("Ocellus", 16674, 1121)));
        assertEquals(BossDefence.TFA_OCELLUS_P3,
            BossDefence.matchingNpc(npc("Ocellus", 16677, 1197)));
        assertEquals(BossDefence.TFA_OCELLUS_P3,
            BossDefence.matchingNpc(npc("Ocellus", 16677, 1601)));
        assertNull(BossDefence.matchingNpc(npc("Ocellus", 16675, 0)));
        assertNull(BossDefence.matchingNpcName("Ocellus"));
    }

    @Test
    public void quadrantTransitionsCannotResetOcellusPhaseThree()
    {
        BossMechanics m = BossMechanics.forBoss(BossDefence.TFA_OCELLUS_P3);
        assertTrue(m.isPhasePersistent());
        assertTrue(m.preserveStateAtZeroHp(16677));
        assertFalse(m.resetsStatsOnActorEntry(16677));
        assertFalse(m.endsEncounterOnActorEntry(16677));
        assertFalse(BossMechanics.forBoss(BossDefence.TFA_OCELLUS_P1)
            .resetsStatsOnActorEntry(16674));
    }
}
