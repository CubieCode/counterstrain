package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.serum.InoculatedEffect;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Mob effects. */
public final class RemissionMobEffects {
    public static final DeferredRegister<MobEffect> MOB_EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, Remission.MOD_ID);

    /** Given by a serum (P7.3): viral load gain is scaled down while it lasts. */
    public static final DeferredHolder<MobEffect, InoculatedEffect> INOCULATED = MOB_EFFECTS.register("inoculated",
            InoculatedEffect::new);

    private RemissionMobEffects() {
    }
}
