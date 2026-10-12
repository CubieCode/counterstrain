package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.purification.QuarantinePylonBlockEntity;
import com.cubiecode.remission.tech.MachineBlockEntity;
import com.cubiecode.remission.tech.MachineKind;
import com.cubiecode.remission.world.NidusBlockEntity;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Block entity type registrations. */
public final class RemissionBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Remission.MOD_ID);

    public static final Supplier<BlockEntityType<NidusBlockEntity>> NIDUS = BLOCK_ENTITY_TYPES.register("nidus",
            () -> BlockEntityType.Builder.of(NidusBlockEntity::new, RemissionBlocks.NIDUS.get()).build(null));

    // Machines (P7.2)
    public static final Supplier<BlockEntityType<MachineBlockEntity>> ANALYZER = machine(MachineKind.ANALYZER, RemissionBlocks.ANALYZER);
    public static final Supplier<BlockEntityType<MachineBlockEntity>> SEQUENCER = machine(MachineKind.SEQUENCER, RemissionBlocks.SEQUENCER);
    public static final Supplier<BlockEntityType<MachineBlockEntity>> SYNTHESIZER = machine(MachineKind.SYNTHESIZER, RemissionBlocks.SYNTHESIZER);

    // Purification (P5.2)
    public static final Supplier<BlockEntityType<QuarantinePylonBlockEntity>> QUARANTINE_PYLON = BLOCK_ENTITY_TYPES.register(
            "quarantine_pylon", () -> BlockEntityType.Builder.of(QuarantinePylonBlockEntity::new,
                    RemissionBlocks.QUARANTINE_PYLON.get()).build(null));

    private static Supplier<BlockEntityType<MachineBlockEntity>> machine(MachineKind kind, Supplier<? extends Block> block) {
        return BLOCK_ENTITY_TYPES.register(kind.blockName(),
                () -> BlockEntityType.Builder.<MachineBlockEntity>of((pos, state) -> new MachineBlockEntity(kind, pos, state), block.get()).build(null));
    }

    private RemissionBlockEntities() {
    }
}
