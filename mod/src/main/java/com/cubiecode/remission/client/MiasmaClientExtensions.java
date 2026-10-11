package com.cubiecode.remission.client;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.registry.RemissionFluids;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/** Miasma's fluid textures (P7.1). The textures carry their own colour, so there is no tint. */
final class MiasmaClientExtensions {
    private static final ResourceLocation STILL = Remission.id("block/miasma_still");
    private static final ResourceLocation FLOWING = Remission.id("block/miasma_flow");

    private MiasmaClientExtensions() {
    }

    static void register(RegisterClientExtensionsEvent event) {
        event.registerFluidType(new IClientFluidTypeExtensions() {
            @Override
            public ResourceLocation getStillTexture() {
                return STILL;
            }

            @Override
            public ResourceLocation getFlowingTexture() {
                return FLOWING;
            }
        }, RemissionFluids.MIASMA_TYPE.get());
    }
}
