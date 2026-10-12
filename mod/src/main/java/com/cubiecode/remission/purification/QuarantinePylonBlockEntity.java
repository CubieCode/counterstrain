package com.cubiecode.remission.purification;

import com.cubiecode.remission.registry.RemissionBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The quarantine pylon block entity. It holds no data; it re-indexes its pylon when loaded, which covers pylons placed
 * by structures (a pack's crash site), where {@link QuarantinePylonBlock#onPlace} does not run.
 */
public final class QuarantinePylonBlockEntity extends BlockEntity {
    public QuarantinePylonBlockEntity(BlockPos pos, BlockState state) {
        super(RemissionBlockEntities.QUARANTINE_PYLON.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            Purification.pylonPresent(serverLevel, worldPosition);
        }
    }
}
