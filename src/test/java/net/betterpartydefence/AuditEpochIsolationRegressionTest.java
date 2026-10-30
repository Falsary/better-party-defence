package net.betterpartydefence;

import org.junit.Test;
import static org.junit.Assert.*;

public class AuditEpochIsolationRegressionTest
{
    private RaidTimerClock isolated()
    {
        RaidTimerClock clock=new RaidTimerClock();
        assertTrue(clock.sample(RaidTimerClock.SOURCE_TOA,"c77",500,false,1000));
        assertFalse(clock.sample(RaidTimerClock.SOURCE_TOA,"c77",10,false,1004));
        assertFalse(clock.isActive());
        assertTrue(clock.sample(RaidTimerClock.SOURCE_TOA,"c77",11,false,1005));
        return clock;
    }

    @Test public void ordinaryResampleMustNotRejoinPreCorrectionDomain()
    {
        RaidTimerClock clock=isolated();
        String isolatedDomain=clock.domain();
        assertTrue(isolatedDomain.endsWith(":g1"));
        assertTrue(clock.sample(RaidTimerClock.SOURCE_TOA,"c77",12,false,1006));
        assertEquals("Normal timer updates must preserve the isolated epoch",isolatedDomain,clock.domain());
    }

    @Test public void isolatedEpochMustRetainAdvertisedUncertainty()
    {
        RaidTimerClock clock=isolated();
        assertEquals("Appending generation must not turn an uncertain timer into an exact clock",
            RaidTimerClock.TIMER_UNCERTAINTY_TICKS,EncounterTimeline.tickUncertainty(clock.domain()));
    }

    @Test public void separateAmbiguousResetsMustNotReuseTheSameEpoch()
    {
        RaidTimerClock clock=isolated();
        String firstEpoch=clock.domain();
        assertFalse(clock.sample(RaidTimerClock.SOURCE_TOA,"c77",3,false,1010));
        assertTrue(clock.sample(RaidTimerClock.SOURCE_TOA,"c77",4,false,1011));
        assertNotEquals("Two incompatible mappings cannot reuse the same epoch domain",firstEpoch,clock.domain());
    }
}
