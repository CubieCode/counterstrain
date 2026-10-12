package com.cubiecode.remission.infection;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.config.RemissionCommonConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Whether an entity is in contact with an infectious source (P4.8): touching a block in {@link #INFECTIOUS} (standing
 * on it, inside it or against it), or in water in a chunk at or above {@code [infection] festeringLandMinStage}. The
 * query works for any entity, players included; only {@link ContactTracker} turns contact into infection, and only for
 * mobs. Server side; reads loaded chunks only, never loads one.
 */
public final class Contact {
    /** Blocks that infect by contact. Remission ships festering grass, the festered blocks and festering veins. */
    public static final TagKey<Block> INFECTIOUS = TagKey.create(Registries.BLOCK, Remission.id("infectious"));
    /** How far outside its bounding box an entity still touches a block: standing on it or leaning against it. */
    public static final double TOUCH = 1.0E-3;

    private Contact() {
    }

    /** What an entity is in contact with; {@link #NONE} if nothing infectious. */
    public enum Kind {
        NONE,
        /** A block in {@link #INFECTIOUS}. */
        BLOCK,
        /** Water in a chunk at or above the festering land minimum stage. */
        WATER
    }

    /** Whether {@code entity} is in contact with an infectious block or festering water now. */
    public static boolean inContact(Entity entity) {
        return of(entity) != Kind.NONE;
    }

    /** What {@code entity} is in contact with, with festering water from the configured minimum stage. */
    public static Kind of(Entity entity) {
        return of(entity, ExposureSettings.levelForStage(RemissionCommonConfig.INFECTION_FESTERING_LAND_MIN_STAGE.get()));
    }

    /**
     * What {@code entity} is in contact with: an infectious block whose shape reaches its bounding box grown by
     * {@link #TOUCH} (blocks first), else water reaching into its bounding box when its chunk's Strain level is at least
     * {@code waterMinLevel}, else {@link Kind#NONE}. Blocks in chunks that are not loaded are ignored.
     */
    public static Kind of(Entity entity, int waterMinLevel) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return Kind.NONE;
        }
        AABB box = entity.getBoundingBox();
        AABB touch = box.inflate(TOUCH);
        int minX = Mth.floor(touch.minX);
        int maxX = Mth.floor(touch.maxX);
        int minY = Math.max(Mth.floor(touch.minY), level.getMinBuildHeight());
        int maxY = Math.min(Mth.floor(touch.maxY), level.getMaxBuildHeight() - 1);
        int minZ = Mth.floor(touch.minZ);
        int maxZ = Mth.floor(touch.maxZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean water = false;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(x),
                        SectionPos.blockToSectionCoord(z));
                if (chunk == null) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    pos.set(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (state.is(INFECTIOUS) && touches(state.getShape(level, pos), pos, touch)) {
                        return Kind.BLOCK;
                    }
                    if (!water) {
                        FluidState fluid = state.getFluidState();
                        water = fluid.is(FluidTags.WATER) && y + fluid.getHeight(level, pos) > box.minY;
                    }
                }
            }
        }
        return water && CorruptionQueries.chunkLevel(level, entity.chunkPosition()) >= waterMinLevel ? Kind.WATER : Kind.NONE;
    }

    /** Whether {@code shape} at {@code pos} reaches {@code box}; an empty shape counts as the whole block. */
    private static boolean touches(VoxelShape shape, BlockPos pos, AABB box) {
        if (shape.isEmpty()) {
            return box.intersects(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        }
        for (AABB part : shape.toAabbs()) {
            if (part.move(pos).intersects(box)) {
                return true;
            }
        }
        return false;
    }
}
