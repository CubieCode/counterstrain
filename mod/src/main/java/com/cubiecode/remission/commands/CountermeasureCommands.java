package com.cubiecode.remission.commands;

import com.cubiecode.remission.api.CountermeasureGrantedEvent;
import com.cubiecode.remission.api.StrainHazards;
import com.cubiecode.remission.countermeasure.Countermeasures;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /remission countermeasure} (under {@link RemissionCommands}' permission level; P5.7): {@code grant <targets>
 * <hazard>} acts on each target and their online teammates, as a protocol does; {@code revoke <targets> <hazard>} on
 * each target's whole team, offline members at their next login;
 * {@code list <target>} reports what one player holds. Hazards are plain ids; Remission's and every held one are
 * suggested, and any id is accepted, so an integration's hazard works before anything uses it.
 */
final class CountermeasureCommands {
    private static final SuggestionProvider<CommandSourceStack> HAZARDS = (context, builder) -> {
        Set<ResourceLocation> known = new TreeSet<>();
        known.add(StrainHazards.CONTACT);
        context.getSource().getServer().getPlayerList().getPlayers()
                .forEach(player -> known.addAll(StrainHazards.countermeasures(player)));
        return SharedSuggestionProvider.suggestResource(known, builder);
    };

    private CountermeasureCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("countermeasure")
                .then(Commands.literal("grant")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("hazard", ResourceLocationArgument.id()).suggests(HAZARDS)
                                        .executes(context -> grant(context.getSource(),
                                                EntityArgument.getPlayers(context, "targets"),
                                                ResourceLocationArgument.getId(context, "hazard"))))))
                .then(Commands.literal("revoke")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("hazard", ResourceLocationArgument.id()).suggests(HAZARDS)
                                        .executes(context -> revoke(context.getSource(),
                                                EntityArgument.getPlayers(context, "targets"),
                                                ResourceLocationArgument.getId(context, "hazard"))))))
                .then(Commands.literal("list")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(context -> list(context.getSource(), EntityArgument.getPlayer(context, "target")))));
    }

    /** Grants to each target and their online teammates; returns how many players gained it. */
    private static int grant(CommandSourceStack source, Collection<ServerPlayer> targets, ResourceLocation hazard) {
        int gained = 0;
        for (ServerPlayer target : targets) {
            gained += Countermeasures.grant(target, hazard, CountermeasureGrantedEvent.Cause.COMMAND);
        }
        int count = gained;
        source.sendSuccess(() -> Component.translatable("commands.remission.countermeasure.grant.success",
                StrainHazards.displayName(hazard), count), true);
        return count;
    }

    /** Revokes from each target's team; returns how many players lost it, offline ones included. */
    private static int revoke(CommandSourceStack source, Collection<ServerPlayer> targets, ResourceLocation hazard) {
        int lost = 0;
        for (ServerPlayer target : targets) {
            lost += Countermeasures.revoke(target, hazard);
        }
        int count = lost;
        source.sendSuccess(() -> Component.translatable("commands.remission.countermeasure.revoke.success",
                StrainHazards.displayName(hazard), count), true);
        return count;
    }

    /** Reports the target's countermeasures; returns how many they hold. */
    private static int list(CommandSourceStack source, ServerPlayer target) {
        Set<ResourceLocation> held = StrainHazards.countermeasures(target);
        if (held.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.remission.countermeasure.list.none",
                    target.getDisplayName()), false);
        } else {
            Component names = ComponentUtils.formatList(held, hazard -> Component.translatable(
                    "commands.remission.countermeasure.list.entry", StrainHazards.displayName(hazard), hazard.toString()));
            source.sendSuccess(() -> Component.translatable("commands.remission.countermeasure.list.success",
                    target.getDisplayName(), held.size(), names), false);
        }
        return held.size();
    }
}
