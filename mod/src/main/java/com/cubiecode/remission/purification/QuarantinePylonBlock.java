package com.cubiecode.remission.purification;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code remission:quarantine_pylon}: wards the chunks within {@code [purification] pylonRadius} of its own while it
 * stands (indexed in {@link PylonIndex}). Pistons cannot move it, so the index never goes stale through a push.
 */
public final class QuarantinePylonBlock extends BaseEntityBlock {
    public static final MapCodec<QuarantinePylonBlock> CODEC = simpleCodec(QuarantinePylonBlock::new);

    public QuarantinePylonBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<QuarantinePylonBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new QuarantinePylonBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !oldState.is(this)) {
            Purification.pylonPresent(serverLevel, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (level instanceof ServerLevel serverLevel && !newState.is(this)) {
            Purification.pylonRemoved(serverLevel, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
