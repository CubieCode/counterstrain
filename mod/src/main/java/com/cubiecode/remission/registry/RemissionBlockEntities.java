package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.world.NidusBlockEntity;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Block entity type registrations. */
public final class RemissionBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Remission.MOD_ID);

    public static final Supplier<BlockEntityType<NidusBlockEntity>> NIDUS = BLOCK_ENTITY_TYPES.register("nidus",
            () -> BlockEntityType.Builder.of(NidusBlockEntity::new, RemissionBlocks.NIDUS.get()).build(null));

    private RemissionBlockEntities() {
    }
}
