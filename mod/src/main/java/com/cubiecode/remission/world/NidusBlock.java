package com.cubiecode.remission.world;

import com.cubiecode.remission.quest.QuestProgress;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** {@code remission:nidus}: a source of the Strain. Indexed while it exists; breaking it stops its spread. */
public final class NidusBlock extends BaseEntityBlock {
    public static final MapCodec<NidusBlock> CODEC = simpleCodec(NidusBlock::new);

    public NidusBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<NidusBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NidusBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !oldState.is(this)) {
            FesteringLand.nidusPresent(serverLevel, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (level instanceof ServerLevel serverLevel && !newState.is(this)) {
            FesteringLand.nidusRemoved(serverLevel, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // Only a player's break counts for quests; the nidus destroyed event covers every removal.
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest,
            FluidState fluid) {
        boolean removed = super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
        if (removed && player instanceof ServerPlayer serverPlayer && !level.getBlockState(pos).is(this)) {
            QuestProgress.nidusDestroyed(serverPlayer, pos);
        }
        return removed;
    }
}
