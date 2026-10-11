package com.cubiecode.remission.world;

import com.cubiecode.remission.registry.RemissionBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The nidus block entity. It holds no data yet; it re-indexes its nidus when loaded, which covers nidi placed by
 * world generation or structures, where {@link NidusBlock#onPlace} does not run.
 */
public final class NidusBlockEntity extends BlockEntity {
    public NidusBlockEntity(BlockPos pos, BlockState state) {
        super(RemissionBlockEntities.NIDUS.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            FesteringLand.nidusPresent(serverLevel, worldPosition);
        }
    }
}
