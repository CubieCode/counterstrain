package com.cubiecode.remission.commands;

import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.Collection;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /remission} debug commands (permission level 2): {@code infect <targets> <level>}, {@code cleanse <targets>} and
 * {@code inspect <target>}. They read and write host state only through {@link Corruption}.
 */
public final class RemissionCommands {
    public static final int PERMISSION_LEVEL = 2;

    private static final SimpleCommandExceptionType ERROR_NO_LIVING_TARGETS = new SimpleCommandExceptionType(
            Component.translatable("commands.remission.failed.no_living_targets"));
    private static final DynamicCommandExceptionType ERROR_NOT_LIVING = new DynamicCommandExceptionType(
            entity -> Component.translatable("commands.remission.failed.not_living", entity));

    private RemissionCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("remission")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(Commands.literal("infect")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, CorruptionState.MAX_LEVEL))
                                        .executes(context -> infect(context.getSource(),
                                                EntityArgument.getEntities(context, "targets"),
                                                IntegerArgumentType.getInteger(context, "level"))))))
                .then(Commands.literal("cleanse")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(context -> cleanse(context.getSource(),
                                        EntityArgument.getEntities(context, "targets")))))
                .then(Commands.literal("inspect")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(context -> inspect(context.getSource(),
                                        EntityArgument.getEntity(context, "target"))))));
    }

    /** Sets every living target to {@code level}; returns the number of targets changed. */
    private static int infect(CommandSourceStack source, Collection<? extends Entity> targets, int level)
            throws CommandSyntaxException {
        List<LivingEntity> hosts = livingTargets(targets);
        CorruptionState state = CorruptionState.NONE;
        for (LivingEntity host : hosts) {
            state = Corruption.set(host, level);
        }
        if (hosts.size() == 1) {
            CorruptionState set = state;
            source.sendSuccess(() -> Component.translatable("commands.remission.infect.success.single",
                    hosts.getFirst().getDisplayName(), set.level(), set.stage()), true);
        } else {
            source.sendSuccess(() -> Component.translatable("commands.remission.infect.success.multiple",
                    hosts.size(), level), true);
        }
        return hosts.size();
    }

    /** Sets every living target to level 0; returns the number of targets cleansed. */
    private static int cleanse(CommandSourceStack source, Collection<? extends Entity> targets) throws CommandSyntaxException {
        List<LivingEntity> hosts = livingTargets(targets);
        for (LivingEntity host : hosts) {
            Corruption.set(host, 0);
        }
        if (hosts.size() == 1) {
            source.sendSuccess(() -> Component.translatable("commands.remission.cleanse.success.single",
                    hosts.getFirst().getDisplayName()), true);
        } else {
            source.sendSuccess(() -> Component.translatable("commands.remission.cleanse.success.multiple",
                    hosts.size()), true);
        }
        return hosts.size();
    }

    /** Reports the target's Strain level and host stage; returns the level. */
    private static int inspect(CommandSourceStack source, Entity target) throws CommandSyntaxException {
        if (!(target instanceof LivingEntity living)) {
            throw ERROR_NOT_LIVING.create(target.getDisplayName());
        }
        CorruptionState state = Corruption.get(living);
        if (state.level() == 0) {
            source.sendSuccess(() -> Component.translatable("commands.remission.inspect.clean",
                    living.getDisplayName()), false);
        } else {
            source.sendSuccess(() -> Component.translatable("commands.remission.inspect.success",
                    living.getDisplayName(), state.level(), state.stage()), false);
        }
        return state.level();
    }

    private static List<LivingEntity> livingTargets(Collection<? extends Entity> targets) throws CommandSyntaxException {
        List<LivingEntity> living = targets.stream()
                .filter(LivingEntity.class::isInstance)
                .map(LivingEntity.class::cast)
                .toList();
        if (living.isEmpty()) {
            throw ERROR_NO_LIVING_TARGETS.create();
        }
        return living;
    }
}
