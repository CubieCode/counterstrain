package com.cubiecode.remission.harness;

import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.research.Bestiary;
import com.cubiecode.remission.research.Scanning;
import com.cubiecode.remission.world.ChunkCorruption;
import com.cubiecode.remission.world.FesteringStages;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Harness-only commands, registered only while a scene runs, for setup that has no player-facing command.
 * {@code harness scan <targets>} records a host scan of every host among the targets in every player's bestiary, as the
 * scanner would, without needing a scanner interaction. {@code harness player break <pos>}, {@code harness player scan_host
 * <targets>} and {@code harness player scan_block <pos>} make every player break the block or scan with a scanner (put
 * in the main hand, cooldown cleared) through the same server code a real break or scanner use runs, so stats,
 * advancement triggers and quest tasks see a player's action. {@code harness fester <from> <to> <level>} sets the
 * Strain level of the chunks a box covers and festers them at once; {@code harness resend <from> <to>} sends the chunks a
 * box covers to clients again (P4.2).
 */
final class HarnessCommands {
    private static final SimpleCommandExceptionType NO_HOSTS =
            new SimpleCommandExceptionType(Component.literal("None of the targets is a host"));

    private HarnessCommands() {
    }

    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("harness")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("scan").then(Commands.argument("targets", EntityArgument.entities())
                        .executes(context -> {
                            int scanned = 0;
                            for (Entity entity : EntityArgument.getEntities(context, "targets")) {
                                if (entity instanceof LivingEntity living && Corruption.isHost(living)) {
                                    CorruptionState state = Corruption.get(living);
                                    for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
                                        Bestiary.recordScan(player, BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()),
                                                state.stage());
                                    }
                                    scanned++;
                                }
                            }
                            if (scanned == 0) {
                                throw NO_HOSTS.create();
                            }
                            return scanned;
                        })))
                .then(Commands.literal("player")
                        .then(Commands.literal("break").then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> {
                                    BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
                                    return eachPlayer(context, player -> player.gameMode.destroyBlock(pos));
                                })))
                        .then(Commands.literal("scan_host").then(Commands.argument("targets", EntityArgument.entities())
                                .executes(context -> {
                                    var targets = EntityArgument.getEntities(context, "targets");
                                    return eachPlayer(context, player -> targets.forEach(target -> {
                                        if (target instanceof LivingEntity living) {
                                            Scanning.scanEntity(withScanner(player), living, InteractionHand.MAIN_HAND);
                                        }
                                    }));
                                })))
                        .then(Commands.literal("scan_block").then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> {
                                    BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
                                    return eachPlayer(context, player ->
                                            Scanning.scanBlock(withScanner(player), player.level(), pos, InteractionHand.MAIN_HAND));
                                }))))
                .then(Commands.literal("resend").then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                .executes(context -> resend(context.getSource().getLevel(),
                                        BlockPosArgument.getBlockPos(context, "from"),
                                        BlockPosArgument.getBlockPos(context, "to"))))))
                .then(Commands.literal("fester").then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                .then(Commands.argument("level", IntegerArgumentType.integer(0, ChunkCorruption.MAX_LEVEL))
                                        .executes(context -> fester(context.getSource().getLevel(),
                                                BlockPosArgument.getBlockPos(context, "from"),
                                                BlockPosArgument.getBlockPos(context, "to"),
                                                IntegerArgumentType.getInteger(context, "level"))))))));
    }

    private static int eachPlayer(CommandContext<CommandSourceStack> context, Consumer<ServerPlayer> action) {
        var players = context.getSource().getServer().getPlayerList().getPlayers();
        players.forEach(action);
        return players.size();
    }

    private static ServerPlayer withScanner(ServerPlayer player) {
        if (!player.getMainHandItem().is(RemissionItems.SCANNER.get())) {
            player.setItemInHand(InteractionHand.MAIN_HAND, RemissionItems.SCANNER.get().getDefaultInstance());
        }
        player.getCooldowns().removeCooldown(RemissionItems.SCANNER.get());
        return player;
    }

    /**
     * Queues every loaded chunk the box overlaps to be sent again, in full, to every player who has it. Setup edits a
     * chunk while it may still be on its way to the client; vanilla sends no light update to a player for a chunk still
     * pending, and the chunk packet can carry light from before the edit, so the client may keep stale sky light. Run it
     * from a shot's {@code commands}, after setup, when the server's light has settled. Returns the number of chunks.
     */
    private static int resend(ServerLevel level, BlockPos from, BlockPos to) {
        int chunks = 0;
        for (ChunkPos pos : ChunkPos.rangeClosed(new ChunkPos(from), new ChunkPos(to)).toList()) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk != null) {
                for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(pos, false)) {
                    player.connection.chunkSender.markChunkPendingToSend(chunk);
                }
                chunks++;
            }
        }
        return chunks;
    }

    /**
     * Sets the Strain level of every chunk overlapping the box from {@code from} to {@code to} and festers each at
     * once to its stage (blocks, and the biome flip at the last stage), as the budgeted stage work would over a few
     * ticks. No spread events are posted. Returns the number of chunks.
     */
    private static int fester(ServerLevel level, BlockPos from, BlockPos to, int value) {
        ChunkPos min = new ChunkPos(new BlockPos(Math.min(from.getX(), to.getX()), 0, Math.min(from.getZ(), to.getZ())));
        ChunkPos max = new ChunkPos(new BlockPos(Math.max(from.getX(), to.getX()), 0, Math.max(from.getZ(), to.getZ())));
        int chunks = 0;
        for (int x = min.x; x <= max.x; x++) {
            for (int z = min.z; z <= max.z; z++) {
                LevelChunk chunk = level.getChunk(x, z);
                chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(value, level.getGameTime()));
                FesteringStages.festerNow(level, chunk);
                chunks++;
            }
        }
        return chunks;
    }
}
