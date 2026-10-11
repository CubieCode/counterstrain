package com.cubiecode.remission.harness;

import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.research.Bestiary;
import com.cubiecode.remission.research.Scanning;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Harness-only commands, registered only while a scene runs, for setup that has no player-facing command.
 * {@code harness scan <targets>} records a host scan of every host among the targets in every player's bestiary, as the
 * scanner would, without needing a scanner interaction. {@code harness player break <pos>}, {@code harness player scan_host
 * <targets>} and {@code harness player scan_block <pos>} make every player break the block or scan with a scanner (put
 * in the main hand, cooldown cleared) through the same server code a real break or scanner use runs, so stats,
 * advancement triggers and quest tasks see a player's action.
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
                                })))));
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
}
