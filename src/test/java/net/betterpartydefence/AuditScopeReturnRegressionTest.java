package net.betterpartydefence;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

public class AuditScopeReturnRegressionTest
{
    private SpecHistoryEntry event(String id,RaidTimerClock clock,int tick,SpecialWeapon weapon,int hit)
    { return new SpecHistoryEntry(id,weapon,hit,id,clock.domain(),clock.tickAt(tick)); }

    private void assertConservative(SpecHistoryEntry earlier,SpecHistoryEntry later)
    {
        Integer result=EncounterTimeline.replay(Arrays.asList(earlier,later),310,
            new EncounterTimeline.Replay<Integer>()
            {
                public Integer apply(Integer defence,SpecHistoryEntry e)
                {
                    return e.getWeapon()==SpecialWeapon.DRAGON_WARHAMMER
                        ? defence-defence*30/100 : Math.max(0,defence-e.getHit()*2);
                }
                public Integer conservative(Integer a,Integer b) { return Math.max(a,b); }
            });
        assertEquals("A room change cannot certify compatibility with pre-correction timestamps",
            Integer.valueOf(189),result);
    }

    @Test public void returningToPreviousRoomMustNotRestorePreCorrectionDomain()
    {
        RaidTimerClock clock=new RaidTimerClock();
        clock.sample(RaidTimerClock.SOURCE_TOA,"i111",500,false,1000);
        SpecHistoryEntry earlier=event("bgs",clock,1001,SpecialWeapon.BANDOS_GODSWORD,20);
        assertFalse(clock.sample(RaidTimerClock.SOURCE_TOA,"i111",10,false,1004));
        assertTrue(clock.sample(RaidTimerClock.SOURCE_TOA,"i111",11,false,1005));
        clock.sample(RaidTimerClock.SOURCE_TOA,"i222",12,false,1006);
        clock.sample(RaidTimerClock.SOURCE_TOA,"i111",13,false,1007);
        SpecHistoryEntry later=event("dwh",clock,1007,SpecialWeapon.DRAGON_WARHAMMER,1);
        assertConservative(earlier,later);
    }

    @Test public void scopeChangeWhileInactiveMustNotRestoreOldDomainOnReturn()
    {
        RaidTimerClock clock=new RaidTimerClock();
        clock.sample(RaidTimerClock.SOURCE_TOA,"i111",500,false,1000);
        SpecHistoryEntry earlier=event("bgs",clock,1001,SpecialWeapon.BANDOS_GODSWORD,20);
        assertFalse(clock.sample(RaidTimerClock.SOURCE_TOA,"i111",10,false,1004));
        assertTrue(clock.sample(RaidTimerClock.SOURCE_TOA,"i222",11,false,1005));
        clock.sample(RaidTimerClock.SOURCE_TOA,"i111",12,false,1006);
        SpecHistoryEntry later=event("dwh",clock,1006,SpecialWeapon.DRAGON_WARHAMMER,1);
        assertConservative(earlier,later);
    }
}
