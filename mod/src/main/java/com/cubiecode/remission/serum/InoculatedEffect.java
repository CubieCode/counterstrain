package com.cubiecode.remission.serum;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * {@code remission:inoculated}: while it lasts, a player's viral load gain is scaled by {@link SerumSettings#inoculationFactor}
 * ({@link Serums#gainFactor}). It does nothing on its own each tick, and milk clears it like any other effect.
 */
public class InoculatedEffect extends MobEffect {
    /** Effect colour (particles and potion tint): a pale serum green. */
    public static final int COLOR = 0x9BE0B4;

    public InoculatedEffect() {
        super(MobEffectCategory.BENEFICIAL, COLOR);
    }
}
