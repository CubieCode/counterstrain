package com.cubiecode.remission.integration.mekanism;

import com.cubiecode.remission.Remission;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Optional Mekanism integration (P7.1): the {@code remission:miasma} chemical. Mekanism is {@code compileOnly}; this class
 * is loaded only from {@link Remission}'s constructor after a {@code ModList} check, so nothing here runs without it.
 */
public final class MekanismIntegration {
    public static final String MOD_ID = "mekanism";

    private static final DeferredRegister<Chemical> CHEMICALS =
            DeferredRegister.create(MekanismAPI.CHEMICAL_REGISTRY_NAME, Remission.MOD_ID);

    /** Miasma as a chemical, drawn with Mekanism's generic texture in miasma's violet. */
    public static final DeferredHolder<Chemical, Chemical> MIASMA = CHEMICALS.register("miasma",
            () -> new Chemical(ChemicalBuilder.builder().tint(0x6B3F8A)));

    private MekanismIntegration() {
    }

    public static void register(IEventBus modBus) {
        CHEMICALS.register(modBus);
        Remission.LOGGER.info("Mekanism present: registering the miasma chemical");
    }
}
