package com.cubiecode.remission.commands;

import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.world.FesteringLand;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * {@code /remission region} (under {@link RemissionCommands}' permission level; P8.2): {@code advance <pos> <radius>
 * [stages]} raises every loaded chunk within {@code radius} chunks (Manhattan distance, the shape of a nidus's reach) of
 * the chunk holding {@code pos} by {@code stages} festering stages (default 1, capped at the last stage), to the lowest
 * Strain level of the new stage. Clean chunks start festering, and chunks in remission leave it, as with spread. Each
 * change posts {@code ChunkStageChangedEvent}, so the land festers through the usual stage work; unloaded chunks are
 * skipped, never loaded.
 */
final class RegionCommands {
    static final int MAX_RADIUS = 32;

    private static final SimpleCommandExceptionType ERROR_UNCHANGED = new SimpleCommandExceptionType(
            Component.translatable("commands.remission.region.advance.failed"));

    private RegionCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("region")
                .then(Commands.literal("advance")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("radius", IntegerArgumentType.integer(0, MAX_RADIUS))
                                        .executes(context -> advance(context.getSource(),
                                                BlockPosArgument.getBlockPos(context, "pos"),
                                                IntegerArgumentType.getInteger(context, "radius"), 1))
                                        .then(Commands.argument("stages", IntegerArgumentType.integer(1))
                                                .executes(context -> advance(context.getSource(),
                                                        BlockPosArgument.getBlockPos(context, "pos"),
                                                        IntegerArgumentType.getInteger(context, "radius"),
                                                        IntegerArgumentType.getInteger(context, "stages")))))));
    }

    /** Returns the number of chunks that rose a stage or more; fails when none could. */
    private static int advance(CommandSourceStack source, BlockPos pos, int radius, int stages) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        ChunkPos centre = new ChunkPos(pos);
        int maxStage = CorruptionQueries.maxChunkStage();
        // Clamped here rather than in the argument, since the stage count follows the config after registration; this also
        // keeps stage + step from overflowing for huge values.
        int step = Math.min(stages, maxStage);
        int advanced = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            int reach = radius - Math.abs(dx);
            for (int dz = -reach; dz <= reach; dz++) {
                ChunkPos chunk = new ChunkPos(centre.x + dx, centre.z + dz);
                LevelChunk loaded = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
                if (loaded == null) {
                    continue;
                }
                int current = CorruptionQueries.chunkLevel(level, chunk);
                int target = Math.min(CorruptionQueries.chunkStageFor(current) + step, maxStage);
                int value = lowestLevelAtStage(current, target);
                if (value > current) {
                    FesteringLand.setChunkLevel(level, loaded, value);
                    advanced++;
                }
            }
        }
        if (advanced == 0) {
            throw ERROR_UNCHANGED.create();
        }
        int count = advanced;
        source.sendSuccess(() -> Component.translatable("commands.remission.region.advance.success",
                count, radius, centre.x, centre.z), true);
        return advanced;
    }

    /**
     * The lowest Strain level above {@code current} that reaches festering stage {@code stage}, or {@code current} when it
     * already does (or no level does).
     */
    static int lowestLevelAtStage(int current, int stage) {
        if (CorruptionQueries.chunkStageFor(current) >= stage) {
            return current;
        }
        for (int value = current + 1; value <= CorruptionQueries.MAX_LEVEL; value++) {
            if (CorruptionQueries.chunkStageFor(value) >= stage) {
                return value;
            }
        }
        return current;
    }
}
