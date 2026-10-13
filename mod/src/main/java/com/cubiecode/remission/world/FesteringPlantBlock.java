package com.cubiecode.remission.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Short grass or a fern in festering land (P4.16): {@code remission:festering_short_grass} and
 * {@code remission:festering_fern}. Shaped like short grass, but bone meal does nothing to it, so it never grows into a
 * tall plant on its own. The tall ones are plain {@link net.minecraft.world.level.block.DoublePlantBlock}s.
 */
public final class FesteringPlantBlock extends BushBlock {
    public static final MapCodec<FesteringPlantBlock> CODEC = simpleCodec(FesteringPlantBlock::new);
    private static final VoxelShape SHAPE = Block.box(2.0, 0.0, 2.0, 14.0, 13.0, 14.0);

    public FesteringPlantBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<FesteringPlantBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Vec3 offset = state.getOffset(level, pos);
        return SHAPE.move(offset.x, offset.y, offset.z);
    }
}
