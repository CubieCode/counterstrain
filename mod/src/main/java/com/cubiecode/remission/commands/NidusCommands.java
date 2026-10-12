package com.cubiecode.remission.commands;

import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.world.NidusIndex;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code /remission nidus} (under {@link RemissionCommands}' permission level; P8.2): {@code seed <pos>} places a nidus at
 * a loaded position, replacing the block there, so quests and scripts can start a new source of the Strain. The block's
 * own placement indexes it, so it spreads from the next pass like any other nidus.
 */
final class NidusCommands {
    private static final SimpleCommandExceptionType ERROR_PRESENT = new SimpleCommandExceptionType(
            Component.translatable("commands.remission.nidus.seed.failed.present"));
    private static final SimpleCommandExceptionType ERROR_BLOCKED = new SimpleCommandExceptionType(
            Component.translatable("commands.remission.nidus.seed.failed.blocked"));
    private static final SimpleCommandExceptionType ERROR_NOT_INDEXED = new SimpleCommandExceptionType(
            Component.translatable("commands.remission.nidus.seed.failed.not_indexed"));

    private NidusCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("nidus")
                .then(Commands.literal("seed")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> seed(context.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(context, "pos")))));
    }

    /**
     * Places a nidus at {@code pos}; fails on a nidus already there, an unbreakable block (bedrock, barriers, end portal
     * frames) or a block entity, which a quest must not destroy. Returns 1.
     */
    private static int seed(CommandSourceStack source, BlockPos pos) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        BlockState state = level.getBlockState(pos);
        if (state.is(RemissionBlocks.NIDUS.get())) {
            throw ERROR_PRESENT.create();
        }
        if (state.getDestroySpeed(level, pos) < 0 || level.getBlockEntity(pos) != null) {
            throw ERROR_BLOCKED.create();
        }
        if (!level.setBlockAndUpdate(pos, RemissionBlocks.NIDUS.get().defaultBlockState())) {
            throw ERROR_BLOCKED.create();
        }
        if (!NidusIndex.get(level).contains(pos)) {
            // Placed but not indexed (something replaced it at once): put the old block back rather than leave a nidus
            // that never spreads.
            level.setBlockAndUpdate(pos, state);
            throw ERROR_NOT_INDEXED.create();
        }
        source.sendSuccess(() -> Component.translatable("commands.remission.nidus.seed.success",
                pos.getX(), pos.getY(), pos.getZ()), true);
        return 1;
    }
}
