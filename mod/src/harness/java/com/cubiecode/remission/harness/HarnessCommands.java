package com.cubiecode.remission.harness;

import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.cubiecode.remission.research.Bestiary;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Harness-only commands, registered only while a scene runs, for setup that has no player-facing command.
 * {@code harness scan <targets>} records a host scan of every host among the targets in every player's bestiary, as the
 * scanner would, without needing a scanner interaction.
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
                        }))));
    }
}
