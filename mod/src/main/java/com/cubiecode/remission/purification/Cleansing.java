package com.cubiecode.remission.purification;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.BlockPurifiedEvent;
import com.cubiecode.remission.api.PurificationCause;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.world.FesteringStages;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/**
 * What purification turns festering land back into (P5.2). A festered block with a {@code remission:cleanses_into}
 * entry is replaced by that block, keeping the state properties both share (axis, snowy, leaf distance and
 * persistence); a festering vein is cleared to air. The fester biome of a chunk is restored from its level's biome
 * source, which is what generated it. Server side, loaded chunks only; one {@link BlockPurifiedEvent} per changed block.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class Cleansing {
    /**
     * {@code data/<ns>/data_maps/block/cleanses_into.json}: the block a festered block turns back into when it is
     * cleansed. Keys are block ids or block tags.
     */
    public static final DataMapType<Block, CleanseBlock> CLEANSES_INTO = DataMapType.builder(
            Remission.id("cleanses_into"), Registries.BLOCK, CleanseBlock.CODEC).build();
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private Cleansing() {
    }

    /**
     * One {@code cleanses_into} value.
     *
     * @param block the clean block it turns back into; state properties both blocks share carry over
     */
    public record CleanseBlock(Block block) {
        public static final Codec<CleanseBlock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BuiltInRegistries.BLOCK.byNameCodec().fieldOf("block").forGetter(CleanseBlock::block)
        ).apply(instance, CleanseBlock::new));
    }

    /** The state {@code state} is cleansed into: air for a festering vein, its {@code cleanses_into} target, or null. */
    @Nullable
    public static BlockState cleansed(BlockState state) {
        if (state.is(RemissionBlocks.FESTERING_VEIN.get())) {
            return Blocks.AIR.defaultBlockState();
        }
        CleanseBlock entry = state.getBlockHolder().getData(CLEANSES_INTO);
        if (entry == null || state.is(entry.block()) || state.hasBlockEntity()) {
            return null;
        }
        return FesteringStages.copyProperties(state, entry.block().defaultBlockState());
    }

    /** Whether {@code state} would be changed by cleansing. */
    public static boolean cleansable(BlockState state) {
        return !state.isAir() && cleansed(state) != null;
    }

    /** Cleanses the block at {@code pos} if it is festered; returns whether it changed. */
    public static boolean cleanseBlock(ServerLevel level, BlockPos pos, BlockState state, PurificationCause cause) {
        BlockState clean = state.isAir() ? null : cleansed(state);
        if (clean == null || !level.setBlock(pos, clean, FLAGS)) {
            return false;
        }
        NeoForge.EVENT_BUS.post(new BlockPurifiedEvent(level, pos, state, clean, cause));
        return true;
    }

    /**
     * Cleanses every festered block and vein in the cube of {@code radius} blocks around {@code centre}, in loaded chunks
     * only, in a fixed order (x, then z, then y). Returns the number of blocks changed.
     */
    public static int cleanseBox(ServerLevel level, BlockPos centre, int radius, PurificationCause cause) {
        int changed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
            for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(x),
                        SectionPos.blockToSectionCoord(z));
                if (chunk == null) {
                    continue;
                }
                for (int y = centre.getY() - radius; y <= centre.getY() + radius; y++) {
                    pos.set(x, y, z);
                    if (level.isOutsideBuildHeight(pos)) {
                        continue;
                    }
                    if (cleanseBlock(level, pos.immutable(), chunk.getBlockState(pos), cause)) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /** Whether {@code section}'s palette holds a block cleansing would change; a section without one is skipped. */
    public static boolean mayCleanse(LevelChunkSection section) {
        return !section.hasOnlyAir() && section.getStates().maybeHas(Cleansing::cleansable);
    }

    /** Cleanses one block layer of {@code chunk} at height {@code y}. Returns the number of blocks changed. */
    public static int cleanseLayer(ServerLevel level, LevelChunk chunk, int y, PurificationCause cause) {
        if (y < chunk.getMinBuildHeight() || y >= chunk.getMaxBuildHeight()) {
            return 0;
        }
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
        if (!mayCleanse(section)) {
            return 0;
        }
        ChunkPos chunkPos = chunk.getPos();
        int changed = 0;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                BlockState state = section.getBlockState(localX, y & 15, localZ);
                if (!state.isAir() && cleanseBlock(level, new BlockPos(chunkPos.getBlockX(localX), y, chunkPos.getBlockZ(localZ)),
                        state, cause)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * Finishes a chunk's cleanse: restores its fester biome cells ({@link #restoreBiomes}) and forgets the festering stage
     * its land was brought up to, so the stage work festers it again if the Strain returns.
     */
    public static void finishChunk(ServerLevel level, LevelChunk chunk) {
        restoreBiomes(level, chunk);
        chunk.removeData(RemissionAttachments.FESTERED_STAGE);
    }

    /** Cleanses every layer of a loaded chunk at once, ignoring the budget (harness and tests). Returns blocks changed. */
    public static int cleanseNow(ServerLevel level, LevelChunk chunk, PurificationCause cause) {
        int changed = 0;
        for (int y = chunk.getMinBuildHeight(); y < chunk.getMaxBuildHeight(); y++) {
            changed += cleanseLayer(level, chunk, y, cause);
        }
        finishChunk(level, chunk);
        return changed;
    }

    /**
     * Turns every biome cell of {@code chunk} holding a fester biome ({@code remission:fester} or a {@code fester_variant}
     * target) back into the biome the level's biome source generates there, and resends the chunk's biomes to players
     * who have it, as the flip does. Returns whether any cell changed.
     */
    public static boolean restoreBiomes(ServerLevel level, LevelChunk chunk) {
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        Set<ResourceKey<Biome>> festered = FesteringStages.festerBiomes(biomes);
        BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
        boolean[] changed = {false};
        chunk.fillBiomesFromNoise((x, y, z, sampler) -> {
            Holder<Biome> current = chunk.getNoiseBiome(x, y, z);
            if (current.unwrapKey().filter(festered::contains).isEmpty()) {
                return current;
            }
            Holder<Biome> generated = source.getNoiseBiome(x, y, z, sampler);
            if (generated != current) {
                changed[0] = true;
            }
            return generated;
        }, level.getChunkSource().randomState().sampler());
        if (changed[0]) {
            chunk.setUnsaved(true);
            level.getChunkSource().chunkMap.resendBiomesForChunks(List.of(chunk));
        }
        return changed[0];
    }

    @SubscribeEvent
    static void register(RegisterDataMapTypesEvent event) {
        event.register(CLEANSES_INTO);
    }
}
