package net.betterpartydefence;

import java.awt.Color;
import java.awt.image.BufferedImage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/** Weapon-icon presentation for BPD's own encounter history; not a SAC counter. */
final class SpecWeaponInfoBox extends InfoBox
{
    private final SpecialWeapon weapon;
    private int value;
    private int misses;

    SpecWeaponInfoBox(BufferedImage image, Plugin plugin, SpecialWeapon weapon)
    {
        super(image, plugin);
        this.weapon = weapon;
        setTooltip(weapon.getName());
    }

    SpecialWeapon weapon()
    {
        return weapon;
    }

    void update(SpecWeaponSummary summary)
    {
        this.value = summary.value;
        this.misses = summary.missedAttempts;
        setTooltip(summary.tooltip());
    }

    @Override
    public String getText()
    {
        return Integer.toString(value);
    }

    @Override
    public Color getTextColor()
    {
        // A spec which missed still appears, but never pretends the hit landed.
        return value == 0 && misses > 0 ? Color.RED : Color.WHITE;
    }
}
