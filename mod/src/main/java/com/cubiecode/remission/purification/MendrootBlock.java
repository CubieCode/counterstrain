package com.cubiecode.remission.purification;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * {@code remission:mendroot}: a cleansing plant. It grows where a sapling would (dirt, grass, farmland, festering grass
 * included) and, every {@code [purification] mendrootIntervalTicks} on a scheduled tick (saved with the chunk), cleanses
 * the festered blocks and veins around it and lowers its chunk's Strain level ({@link Purification#mendroot}). Placed by
 * its block item, so anything that places blocks (players, deployers, drones) can plant it.
 */
public final class MendrootBlock extends BushBlock {
    public static final MapCodec<MendrootBlock> CODEC = simpleCodec(MendrootBlock::new);
    private static final VoxelShape SHAPE = Block.box(3.0, 0.0, 3.0, 13.0, 12.0, 13.0);

    public MendrootBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<MendrootBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Vec3 offset = state.getOffset(level, pos);
        return SHAPE.move(offset.x, offset.y, offset.z);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel && !oldState.is(this)) {
            level.scheduleTick(pos, this, PurificationSettings.fromConfig().mendrootIntervalTicks());
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        PurificationSettings settings = PurificationSettings.fromConfig();
        Purification.mendroot(level, pos, settings);
        level.scheduleTick(pos, this, settings.mendrootIntervalTicks());
    }
}
