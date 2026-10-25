package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import net.betterpartydefence.DefenceTracker.DefenceState;
import net.runelite.client.plugins.Plugin;
import org.junit.Test;

/** The Defence infobox is white until the overhead enters low or fully-drained status. */
public class DefenceInfoBoxColorTest
{
    private static final Color HIGH = new Color(0xeeeeee);
    private static final Color LOW = new Color(0xcca233);
    private static final Color CAPPED = new Color(0x579549);
    private static final Color DRAIN_AMOUNT = new Color(0xcc0066);

    private BetterPartyDefenceConfig config()
    {
        BetterPartyDefenceConfig config = mock(BetterPartyDefenceConfig.class);
        when(config.defenceHighColor()).thenReturn(HIGH);
        when(config.defenceLowColor()).thenReturn(LOW);
        when(config.defenceCappedColor()).thenReturn(CAPPED);
        when(config.defenceDrainColor()).thenReturn(DRAIN_AMOUNT);
        when(config.defenceLowThresholdUnit()).thenReturn(DefenceThresholdUnit.LEVELS);
        when(config.defenceLowThreshold()).thenReturn(10);
        return config;
    }

    private static DefenceState state(long current, long floor, long base)
    {
        return new DefenceState(24, "Test Boss", current, floor, base,
            25000, 25000, 100, 100, 290, 290, true, true);
    }

    private static void check(DefenceState state, BetterPartyDefenceConfig config, Color expected)
    {
        DefenceTracker tracker = mock(DefenceTracker.class);
        when(tracker.state()).thenReturn(state);
        DefenceInfoBox box = new DefenceInfoBox(
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
            mock(Plugin.class), tracker, config);
        assertEquals("Defence infobox colour", expected, box.getTextColor());
        // Independently configured drain-amount colour must never drive this text.
        org.junit.Assert.assertNotEquals(DRAIN_AMOUNT, box.getTextColor());
    }

    @Test
    public void cappedBossUsesFullyDrainedColourEvenWithPositiveDefenceFloor()
    {
        check(state(156, 156, 260), config(), CAPPED); // Archive Guardian
        check(state(0, 0, 100), config(), CAPPED);     // Dagon'hai monk
        check(state(150, 150, 250), config(), CAPPED); // Ocellus final phase
    }

    @Test
    public void lowDefenceUsesLowColourUntilTheFloorIsReached()
    {
        check(state(161, 156, 260), config(), LOW);
        check(state(166, 156, 260), config(), LOW);
    }

    @Test
    public void defenceAboveLowThresholdUsesNormalColour()
    {
        check(state(182, 156, 260), config(), Color.WHITE);
    }

    @Test
    public void infoboxStaysWhiteEvenWhenOverheadHighColourIsNotWhite()
    {
        BetterPartyDefenceConfig c = config();
        DefenceState s = state(182, 156, 260);
        assertEquals(HIGH, DefenceReadout.defenceColor(s, c));
        check(s, c, Color.WHITE);
    }

    @Test
    public void percentLowThresholdAlsoMatchesOverheadTransitions()
    {
        BetterPartyDefenceConfig c = config();
        when(c.defenceLowThresholdUnit()).thenReturn(DefenceThresholdUnit.PERCENT);
        when(c.defenceLowThreshold()).thenReturn(10);
        // 10% of the 104 drainable Defence levels = 10 (integer arithmetic).
        check(state(167, 156, 260), c, Color.WHITE);
        check(state(166, 156, 260), c, LOW);
        check(state(156, 156, 260), c, CAPPED);
    }

    @Test
    public void magicDefenceInfoboxColourIsUntouched()
    {
        BetterPartyDefenceConfig c = config();
        Color magicColour = new Color(0x0088ee);
        when(c.magicDefenceColor()).thenReturn(magicColour);
        DefenceTracker tracker = mock(DefenceTracker.class);
        when(tracker.state()).thenReturn(state(156, 156, 260));
        DefenceInfoBox box = new DefenceInfoBox(
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
            mock(Plugin.class), tracker, c, DefenceInfoBox.Stat.MAGIC_DEFENCE);
        assertEquals(magicColour, box.getTextColor());
    }

    @Test
    public void noActiveTargetUsesWhiteFallback()
    {
        DefenceTracker tracker = mock(DefenceTracker.class);
        DefenceInfoBox box = new DefenceInfoBox(
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
            mock(Plugin.class), tracker, config());
        assertEquals(Color.WHITE, box.getTextColor());
    }
}
