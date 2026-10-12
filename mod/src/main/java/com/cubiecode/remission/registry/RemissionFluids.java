package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.pathfinder.PathType;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Fluid types and fluids. Their liquid blocks and buckets are in {@link RemissionBlocks} and {@link RemissionItems}. */
public final class RemissionFluids {
    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, Remission.MOD_ID);
    public static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(Registries.FLUID, Remission.MOD_ID);

    /** Miasma (P7.1): the Strain's extracted resource, a thick fluid that flows like a slow water. */
    public static final DeferredHolder<FluidType, FluidType> MIASMA_TYPE = FLUID_TYPES.register("miasma",
            () -> new FluidType(FluidType.Properties.create()
                    .descriptionId("block.remission.miasma")
                    .density(1500)
                    .viscosity(3000)
                    .motionScale(0.007D)
                    .canSwim(true)
                    .canDrown(true)
                    .canExtinguish(true)
                    .canConvertToSource(false)
                    .supportsBoating(false)
                    .canHydrate(false)
                    .pathType(PathType.WATER)
                    .adjacentPathType(null)
                    .sound(SoundActions.BUCKET_FILL, SoundEvents.BUCKET_FILL)
                    .sound(SoundActions.BUCKET_EMPTY, SoundEvents.BUCKET_EMPTY)));

    public static final DeferredHolder<Fluid, FlowingFluid> MIASMA = FLUIDS.register("miasma",
            () -> new BaseFlowingFluid.Source(miasmaProperties()));
    public static final DeferredHolder<Fluid, FlowingFluid> FLOWING_MIASMA = FLUIDS.register("flowing_miasma",
            () -> new BaseFlowingFluid.Flowing(miasmaProperties()));

    private RemissionFluids() {
    }

    private static BaseFlowingFluid.Properties miasmaProperties() {
        return new BaseFlowingFluid.Properties(MIASMA_TYPE, MIASMA, FLOWING_MIASMA)
                .bucket(RemissionItems.MIASMA_BUCKET)
                .block(RemissionBlocks.MIASMA)
                .slopeFindDistance(2)
                .levelDecreasePerBlock(2)
                .tickRate(15)
                .explosionResistance(100.0F);
    }
}
