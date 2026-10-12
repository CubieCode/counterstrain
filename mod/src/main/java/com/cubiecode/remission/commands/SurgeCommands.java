package com.cubiecode.remission.commands;

import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.surge.Surge;
import com.cubiecode.remission.surge.SurgeController;
import com.cubiecode.remission.surge.SurgeData;
import com.cubiecode.remission.surge.Surges;
import com.cubiecode.remission.world.Nidus;
import com.cubiecode.remission.world.NidusIndex;
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
import org.jetbrains.annotations.Nullable;

/**
 * {@code /remission surge} (under {@link RemissionCommands}' permission level): {@code start [pos] [intensity]} begins
 * a surge's warning from the nearest nidus whose area holds {@code pos} (default: the source's position),
 * {@code stop} ends the surge in the source's level, and {@code status} reports it or when the next one is due.
 */
final class SurgeCommands {
    private static final SimpleCommandExceptionType ERROR_RUNNING = new SimpleCommandExceptionType(
            Component.translatable("commands.remission.surge.failed.running"));
    private static final SimpleCommandExceptionType ERROR_NO_NIDUS = new SimpleCommandExceptionType(
            Component.translatable("commands.remission.surge.failed.no_nidus"));
    private static final SimpleCommandExceptionType ERROR_NONE = new SimpleCommandExceptionType(
            Component.translatable("commands.remission.surge.failed.none"));

    private SurgeCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("surge")
                .then(Commands.literal("start")
                        .executes(context -> start(context.getSource(), BlockPos.containing(context.getSource().getPosition()), 1))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> start(context.getSource(),
                                        BlockPosArgument.getBlockPos(context, "pos"), 1))
                                .then(Commands.argument("intensity", IntegerArgumentType.integer(1, Surge.MAX_INTENSITY))
                                        .executes(context -> start(context.getSource(),
                                                BlockPosArgument.getBlockPos(context, "pos"),
                                                IntegerArgumentType.getInteger(context, "intensity"))))))
                .then(Commands.literal("stop").executes(context -> stop(context.getSource())))
                .then(Commands.literal("status").executes(context -> status(context.getSource())));
    }

    private static int start(CommandSourceStack source, BlockPos pos, int intensity) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        SurgeController surges = Surges.controller(level);
        if (surges.current() != null) {
            throw ERROR_RUNNING.create();
        }
        BlockPos origin = nearestNidus(level, surges, pos);
        if (origin == null || !surges.start(origin, intensity, level.getGameTime())) {
            throw ERROR_NO_NIDUS.create();
        }
        int warning = (int) (surges.current().startTime() - level.getGameTime());
        source.sendSuccess(() -> Component.translatable("commands.remission.surge.start.success",
                origin.getX(), origin.getY(), origin.getZ(), intensity, warning), true);
        return 1;
    }

    private static int stop(CommandSourceStack source) throws CommandSyntaxException {
        if (!Surges.controller(source.getLevel()).stop(source.getLevel().getGameTime())) {
            throw ERROR_NONE.create();
        }
        source.sendSuccess(() -> Component.translatable("commands.remission.surge.stop.success"), true);
        return 1;
    }

    /** Reports the surge (returns its intensity) or the ticks until the next one is due (returns 0). */
    private static int status(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        SurgeController surges = Surges.controller(level);
        Surge surge = surges.current();
        long now = level.getGameTime();
        if (surge == null) {
            long due = surges.data().nextDue();
            if (!RemissionCommonConfig.SURGES_SCHEDULED.get() || due == SurgeData.UNSCHEDULED) {
                source.sendSuccess(() -> Component.translatable("commands.remission.surge.status.unscheduled"), false);
                return 0;
            }
            long ticks = Math.max(0, due - now);
            source.sendSuccess(() -> Component.translatable("commands.remission.surge.status.none", ticks), false);
            return 0;
        }
        BlockPos origin = surge.origin();
        if (surge.started()) {
            source.sendSuccess(() -> Component.translatable("commands.remission.surge.status.running",
                    origin.getX(), origin.getY(), origin.getZ(), surge.intensity(), surge.endTime() - now), false);
        } else {
            source.sendSuccess(() -> Component.translatable("commands.remission.surge.status.warning",
                    origin.getX(), origin.getY(), origin.getZ(), surge.intensity(), surge.startTime() - now), false);
        }
        return surge.intensity();
    }

    /** The indexed nidus whose area holds {@code pos} nearest to it (ties: index order), or null. */
    @Nullable
    private static BlockPos nearestNidus(ServerLevel level, SurgeController surges, BlockPos pos) {
        ChunkPos chunk = new ChunkPos(pos);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Nidus nidus : NidusIndex.get(level).nidi()) {
            double distance = nidus.pos().distSqr(pos);
            if (surges.inArea(nidus.pos(), chunk) && distance < bestDistance) {
                best = nidus.pos();
                bestDistance = distance;
            }
        }
        return best;
    }
}
