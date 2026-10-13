package com.cubiecode.remission.commands;

import com.cubiecode.remission.api.VariantEmergedEvent;
import com.cubiecode.remission.variant.VariantPressure;
import com.cubiecode.remission.variant.VariantRecords;
import com.cubiecode.remission.variant.Variants;
import com.cubiecode.remission.world.NidusIndex;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * {@code /remission variant} (under {@link RemissionCommands}' permission level; P7.5), for quests and scripts:
 * {@code set <nidus> <variant>} makes a variant emerge at the nidus at that position (posting the cancellable
 * {@link VariantEmergedEvent} with cause {@code COMMAND}, firing {@code remission:variant_emerged} for players in its
 * land), {@code clear <nidus>} takes it away again, and {@code query <nidus>} reports its variant and pressure. The
 * position must be an indexed nidus; it need not be loaded.
 */
final class VariantCommands {
    private static final SimpleCommandExceptionType ERROR_NOT_NIDUS = new SimpleCommandExceptionType(
            Component.translatable("commands.remission.variant.failed.not_nidus"));
    private static final DynamicCommandExceptionType ERROR_UNKNOWN = new DynamicCommandExceptionType(
            id -> Component.translatable("commands.remission.variant.failed.unknown", String.valueOf(id)));
    private static final SimpleCommandExceptionType ERROR_CANCELLED = new SimpleCommandExceptionType(
            Component.translatable("commands.remission.variant.set.failed.cancelled"));
    private static final SimpleCommandExceptionType ERROR_NONE = new SimpleCommandExceptionType(
            Component.translatable("commands.remission.variant.clear.failed"));

    private static final SuggestionProvider<CommandSourceStack> VARIANTS = (context, builder) ->
            SharedSuggestionProvider.suggestResource(Variants.all(context.getSource().registryAccess()).stream()
                    .map(Map.Entry::getKey), builder);

    private VariantCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("variant")
                .then(Commands.literal("set")
                        .then(Commands.argument("nidus", BlockPosArgument.blockPos())
                                .then(Commands.argument("variant", ResourceLocationArgument.id()).suggests(VARIANTS)
                                        .executes(context -> set(context.getSource(),
                                                BlockPosArgument.getBlockPos(context, "nidus"),
                                                ResourceLocationArgument.getId(context, "variant"))))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("nidus", BlockPosArgument.blockPos())
                                .executes(context -> clear(context.getSource(), BlockPosArgument.getBlockPos(context, "nidus")))))
                .then(Commands.literal("query")
                        .then(Commands.argument("nidus", BlockPosArgument.blockPos())
                                .executes(context -> query(context.getSource(), BlockPosArgument.getBlockPos(context, "nidus")))));
    }

    private static BlockPos nidus(ServerLevel level, BlockPos pos) throws CommandSyntaxException {
        if (!NidusIndex.get(level).contains(pos)) {
            throw ERROR_NOT_NIDUS.create();
        }
        return pos;
    }

    private static int set(CommandSourceStack source, BlockPos pos, ResourceLocation variant) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        nidus(level, pos);
        if (Variants.get(level.registryAccess(), variant).isEmpty()) {
            throw ERROR_UNKNOWN.create(variant);
        }
        if (!VariantPressure.emerge(level, pos, variant, VariantEmergedEvent.Cause.COMMAND)) {
            throw ERROR_CANCELLED.create();
        }
        source.sendSuccess(() -> Component.translatable("commands.remission.variant.set.success", Variants.name(variant),
                pos.getX(), pos.getY(), pos.getZ()), true);
        return 1;
    }

    private static int clear(CommandSourceStack source, BlockPos pos) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        nidus(level, pos);
        if (!VariantPressure.clear(level, pos)) {
            throw ERROR_NONE.create();
        }
        source.sendSuccess(() -> Component.translatable("commands.remission.variant.clear.success",
                pos.getX(), pos.getY(), pos.getZ()), true);
        return 1;
    }

    /** Reports the nidus's variant and pressure; returns 1 with a variant, else 0. */
    private static int query(CommandSourceStack source, BlockPos pos) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        nidus(level, pos);
        VariantRecords.Entry entry = VariantRecords.get(level).get(pos);
        Component variant = entry == null || entry.variant().isEmpty()
                ? Component.translatable("commands.remission.variant.query.none")
                : Variants.name(entry.variant().get());
        Component pressure = entry == null || entry.pressure().isEmpty()
                ? Component.translatable("commands.remission.variant.query.none")
                : ComponentUtils.formatList(entry.pressure().entrySet(), each -> Component.translatable(
                        "commands.remission.variant.query.entry", Variants.name(each.getKey()), Math.round(each.getValue())));
        int total = entry == null ? 0 : Math.round(entry.total());
        source.sendSuccess(() -> Component.translatable("commands.remission.variant.query.success",
                pos.getX(), pos.getY(), pos.getZ(), variant, pressure, total), false);
        return entry != null && entry.variant().isPresent() ? 1 : 0;
    }
}
