package com.cubiecode.remission.harness;

import com.cubiecode.remission.api.CorruptionCause;
import com.cubiecode.remission.api.HazardNotices;
import com.cubiecode.remission.api.NidusDamagedEvent;
import com.cubiecode.remission.api.ViralLoadChangedEvent;
import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.cubiecode.remission.notice.HazardNoticeTracker;
import com.cubiecode.remission.purification.Purification;
import com.cubiecode.remission.purification.PurificationSettings;
import com.cubiecode.remission.purification.ReclaimerBlockEntity;
import com.cubiecode.remission.purification.ReclaimerSettings;
import com.cubiecode.remission.purification.RemissionZoneSettings;
import com.cubiecode.remission.purification.RemissionZones;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.research.Bestiary;
import com.cubiecode.remission.research.Scanning;
import com.cubiecode.remission.revenant.Revenant;
import com.cubiecode.remission.revenant.Revenants;
import com.cubiecode.remission.surge.SurgeController;
import com.cubiecode.remission.surge.SurgeHosts;
import com.cubiecode.remission.viralload.ViralLoad;
import com.cubiecode.remission.viralload.ViralLoadState;
import com.cubiecode.remission.world.ChunkCorruption;
import com.cubiecode.remission.world.FesteringStages;
import com.cubiecode.remission.world.FesteringVeins;
import com.cubiecode.remission.world.NidusBlockEntity;
import com.cubiecode.remission.world.NidusSettings;
import com.cubiecode.remission.world.NidusTentacles;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Harness-only commands, registered only while a scene runs, for setup that has no player-facing command.
 * {@code harness scan <targets>} records a host scan of every host among the targets in every player's bestiary, as the
 * scanner would, without needing a scanner interaction. {@code harness player break <pos>}, {@code harness player scan_host
 * <targets>} and {@code harness player scan_block <pos>} make every player break the block or scan with a scanner (put
 * in the main hand, cooldown cleared) through the same server code a real break or scanner use runs, so stats,
 * advancement triggers and quest tasks see a player's action. {@code harness player use <pos>} makes every player use the
 * block with whatever it holds (a machine opens its menu, P7.2). {@code harness fester <from> <to> <level>} sets the
 * Strain level of the chunks a box covers and festers them at once, and {@code harness fester <from> <to>} festers them
 * at once to the stage they have (P8.2); {@code harness resend <from> <to>} sends the chunks a
 * box covers to clients again (P4.2). {@code harness surge hosts <centre> <count> <radius>} and {@code harness surge
 * clouds <centre> <count>} spawn surge hosts and release miasma clouds through the surge code with a fixed seed (P4.3).
 * {@code harness viral_load <targets> <load> [rising|falling]} sets players' viral load and the trend their HUD shows
 * (P5.1). {@code harness purify} runs the level's quarantine pylon passes until every loaded warded chunk is in remission
 * and does every queued cleanse at once, as the budgeted passes would over many ticks (P5.2).
 * {@code harness revenant <pos> <owner> <level> <yaw>} raises a revenant of the owner player there, through the
 * revenant code, wearing copies of the owner's armour and main-hand item, at that Strain level and facing (P5.3).
 * {@code harness nidus <pos> <vitality>} strikes a nidus down to that percent of its vitality through the fight code, as
 * the nearest player, so the thresholds it passes start their surges (P4.10); {@code harness nidus <pos> lash} lashes the
 * players its tentacles track now, {@code harness nidus <pos> tentacle <index>} strikes that tentacle (a hit and a
 * flinch), {@code harness nidus <pos> lash_at <bearing>} shows a lash toward a bearing with no player, and
 * {@code harness nidus <pos> kill} strikes the final blow, starting its death; {@code harness nidi
 * periodic_lashes <true|false>} turns periodic lashes off for the session or back to the config (P4.19). {@code harness regrow} regrows every
 * column of every loaded chunk in remission at once, as the budgeted regrowth would over {@code regrowthTicks} (P5.5).
 */
final class HarnessCommands {
    private static final SimpleCommandExceptionType NO_HOSTS =
            new SimpleCommandExceptionType(Component.literal("None of the targets is a host"));
    private static final SimpleCommandExceptionType NO_NIDUS =
            new SimpleCommandExceptionType(Component.literal("No nidus there"));
    private static final SimpleCommandExceptionType NO_RECLAIMER =
            new SimpleCommandExceptionType(Component.literal("No reclaimer there"));
    private static final SimpleCommandExceptionType NO_SPOT =
            new SimpleCommandExceptionType(Component.literal("No festering spot nearby"));

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
                        .then(Commands.literal("use").then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> {
                                    BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
                                    return eachPlayer(context, player -> player.gameMode.useItemOn(player, player.level(),
                                            player.getMainHandItem(), InteractionHand.MAIN_HAND,
                                            new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)));
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
                .then(Commands.literal("surge")
                        .then(Commands.literal("hosts").then(Commands.argument("centre", BlockPosArgument.blockPos())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                                .executes(context -> surgeHosts(context.getSource().getLevel(),
                                                        BlockPosArgument.getBlockPos(context, "centre"),
                                                        IntegerArgumentType.getInteger(context, "count"),
                                                        IntegerArgumentType.getInteger(context, "radius")))))))
                        .then(Commands.literal("clouds").then(Commands.argument("centre", BlockPosArgument.blockPos())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 32))
                                        .executes(context -> surgeClouds(context.getSource().getLevel(),
                                                BlockPosArgument.getBlockPos(context, "centre"),
                                                IntegerArgumentType.getInteger(context, "count")))))))
                .then(Commands.literal("viral_load").then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("load", FloatArgumentType.floatArg(0.0F, ViralLoadState.MAX))
                                .executes(context -> viralLoad(context, ViralLoadState.Trend.STEADY))
                                .then(Commands.literal("rising").executes(context -> viralLoad(context, ViralLoadState.Trend.RISING)))
                                .then(Commands.literal("falling").executes(context -> viralLoad(context, ViralLoadState.Trend.FALLING))))))
                .then(Commands.literal("fester").then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                .executes(context -> festerAtStage(context.getSource().getLevel(),
                                        BlockPosArgument.getBlockPos(context, "from"),
                                        BlockPosArgument.getBlockPos(context, "to")))
                                .then(Commands.argument("level", IntegerArgumentType.integer(0, ChunkCorruption.MAX_LEVEL))
                                        .executes(context -> fester(context.getSource().getLevel(),
                                                BlockPosArgument.getBlockPos(context, "from"),
                                                BlockPosArgument.getBlockPos(context, "to"),
                                                IntegerArgumentType.getInteger(context, "level")))))))
                .then(Commands.literal("purify").executes(context -> Purification.engine(context.getSource().getLevel())
                        .settleNow(PurificationSettings.fromConfig())))
                .then(Commands.literal("regrow").executes(context -> RemissionZones.engine(context.getSource().getLevel())
                        .regrowNow(RemissionZoneSettings.fromConfig())))
                .then(Commands.literal("reclaim").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("operations", IntegerArgumentType.integer(1, 100_000))
                                .executes(context -> reclaim(context.getSource().getLevel(),
                                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                        IntegerArgumentType.getInteger(context, "operations"))))))
                .then(Commands.literal("veins").then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                .executes(context -> veins(context.getSource().getLevel(),
                                        BlockPosArgument.getBlockPos(context, "from"),
                                        BlockPosArgument.getBlockPos(context, "to"))))))
                .then(Commands.literal("revenant").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("owner", EntityArgument.player())
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, CorruptionState.MAX_LEVEL))
                                        .then(Commands.argument("yaw", FloatArgumentType.floatArg(-180.0F, 180.0F))
                                                .executes(context -> revenant(context.getSource().getLevel(),
                                                        BlockPosArgument.getBlockPos(context, "pos"),
                                                        EntityArgument.getPlayer(context, "owner"),
                                                        IntegerArgumentType.getInteger(context, "level"),
                                                        FloatArgumentType.getFloat(context, "yaw"))))))))
                .then(Commands.literal("notice").then(Commands.argument("hazard", ResourceLocationArgument.id())
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                        .executes(context -> notice(context.getSource().getLevel(),
                                                ResourceLocationArgument.getId(context, "hazard"),
                                                BlockPosArgument.getBlockPos(context, "pos"),
                                                IntegerArgumentType.getInteger(context, "count")))))))
                .then(Commands.literal("nidus").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("vitality", IntegerArgumentType.integer(1, 100))
                                .executes(context -> nidus(context.getSource().getLevel(),
                                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                        IntegerArgumentType.getInteger(context, "vitality"))))
                        .then(Commands.literal("lash").executes(context -> nidusLash(context.getSource().getLevel(),
                                BlockPosArgument.getLoadedBlockPos(context, "pos"))))
                        .then(Commands.literal("lash_at").then(Commands.argument("bearing", FloatArgumentType.floatArg(-180, 360))
                                .executes(context -> nidusLashAt(context.getSource().getLevel(),
                                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                        FloatArgumentType.getFloat(context, "bearing")))))
                        .then(Commands.literal("tentacle").then(Commands.argument("index",
                                        IntegerArgumentType.integer(0, NidusTentacles.COUNT - 1))
                                .executes(context -> nidusTentacle(context.getSource().getLevel(),
                                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                        IntegerArgumentType.getInteger(context, "index")))))
                        .then(Commands.literal("kill").executes(context -> nidusKill(context.getSource().getLevel(),
                                BlockPosArgument.getLoadedBlockPos(context, "pos"))))))
                .then(Commands.literal("nidi").then(Commands.literal("periodic_lashes")
                        .then(Commands.argument("on", BoolArgumentType.bool())
                                .executes(context -> periodicLashes(BoolArgumentType.getBool(context, "on")))))));
    }

    /**
     * Reports {@code count} strikes of {@code hazard} at {@code pos} through the hazard notice API (P5.8), once with each
     * player as the placer, then sends every open group at once instead of after the grouping time.
     */
    private static int notice(ServerLevel level, ResourceLocation hazard, BlockPos pos, int count) {
        List<ServerPlayer> players = level.getServer().getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            for (int i = 0; i < count; i++) {
                HazardNotices.report(level, hazard, pos, player.getUUID());
            }
        }
        HazardNoticeTracker.sendAllNow(level.getServer());
        return players.size();
    }

    /**
     * Strikes the nidus at {@code pos} down to {@code percent} of its vitality through the fight code, as the nearest
     * player (P4.10): a first strike starts the fight, then one strike deals the rest, so every threshold it passes starts
     * its surge and lashes as in a real fight. Fails without a nidus there; does nothing if it is already that low.
     */
    private static int nidus(ServerLevel level, BlockPos pos, int percent) throws CommandSyntaxException {
        if (!(level.getBlockEntity(pos) instanceof NidusBlockEntity nidus)) {
            throw NO_NIDUS.create();
        }
        NidusSettings settings = NidusSettings.current();
        ServerPlayer player = level.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, -1.0, false)
                instanceof ServerPlayer nearest ? nearest : null;
        long now = level.getGameTime();
        if (!nidus.fighting()) {
            nidus.damage(level, NidusDamagedEvent.Kind.STRIKE, player, null, 0.001F, settings, now);
        }
        float max = nidus.maxVitality(settings);
        float rest = max * (1.0F - percent / 100.0F) - nidus.damageTaken();
        if (rest > 0) {
            nidus.damage(level, NidusDamagedEvent.Kind.STRIKE, player, null, rest, settings, now);
        }
        return Math.round(nidus.vitality(settings));
    }

    private static NidusBlockEntity nidusAt(ServerLevel level, BlockPos pos) throws CommandSyntaxException {
        if (!(level.getBlockEntity(pos) instanceof NidusBlockEntity nidus)) {
            throw NO_NIDUS.create();
        }
        return nidus;
    }

    @Nullable
    private static ServerPlayer nearestPlayer(ServerLevel level, BlockPos pos) {
        return level.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, -1.0, false)
                instanceof ServerPlayer nearest ? nearest : null;
    }

    /**
     * Turns nidi's periodic lashes (P4.19) off for the rest of the session, or back to the config, so a scene with a
     * survival player near a nidus is not knocked about between shots; lashes the scene asks for still happen.
     */
    private static int periodicLashes(boolean on) {
        NidusSettings.override(on ? null : NidusSettings.fromConfig().withLashIntervalTicks(0));
        return 1;
    }

    /** Lashes now with the nidus at {@code pos}: each tentacle with a tracked player turns at it (P4.19). */
    private static int nidusLash(ServerLevel level, BlockPos pos) throws CommandSyntaxException {
        nidusAt(level, pos).lash(level, NidusSettings.current(), level.getGameTime());
        return 1;
    }

    /**
     * Shows a lash toward {@code bearing} (degrees, 0 north, 90 east) by the tentacle resting closest to it, with no
     * player to strike: the event clients play and the server's aim (so the hitbox follows), nothing lands (P4.19).
     */
    private static int nidusLashAt(ServerLevel level, BlockPos pos, float bearing) throws CommandSyntaxException {
        NidusBlockEntity nidus = nidusAt(level, pos);
        int best = 0;
        for (int tentacle = 1; tentacle < NidusTentacles.COUNT; tentacle++) {
            if (Math.abs(Mth.wrapDegrees(bearing - NidusTentacles.restBearing(tentacle)))
                    < Math.abs(Mth.wrapDegrees(bearing - NidusTentacles.restBearing(best)))) {
                best = tentacle;
            }
        }
        nidus.aim().lash(best, bearing, level.getGameTime());
        level.blockEvent(pos, nidus.getBlockState().getBlock(), NidusBlockEntity.EVENT_LASH, NidusBlockEntity.lashParam(best, bearing));
        return best;
    }

    /**
     * Strikes tentacle {@code index} of the nidus at {@code pos} for 1% of its vitality as the nearest player, through the
     * fight code: the hit reaction, the red flash and that tentacle's flinch (P4.19).
     */
    private static int nidusTentacle(ServerLevel level, BlockPos pos, int index) throws CommandSyntaxException {
        NidusBlockEntity nidus = nidusAt(level, pos);
        NidusSettings settings = NidusSettings.current();
        float amount = nidus.maxVitality(settings) * 0.01F;
        nidus.damage(level, NidusDamagedEvent.Kind.STRIKE, nearestPlayer(level, pos), null, amount, settings, level.getGameTime(),
                index + 1);
        return 1;
    }

    /** Strikes the final blow on the nidus at {@code pos} as the nearest player: it plays its death, then goes (P4.19). */
    private static int nidusKill(ServerLevel level, BlockPos pos) throws CommandSyntaxException {
        NidusBlockEntity nidus = nidusAt(level, pos);
        NidusSettings settings = NidusSettings.current();
        nidus.damage(level, NidusDamagedEvent.Kind.STRIKE, nearestPlayer(level, pos), null, nidus.maxVitality(settings) * 2.0F,
                settings, level.getGameTime());
        return nidus.dying() ? 1 : 0;
    }

    /**
     * Raises a revenant of {@code owner} at {@code pos} through the revenant code, as the owner's Strain death there would:
     * it holds and wears copies of the owner's armour and main-hand item (the owner keeps theirs), then is set to
     * {@code level} and turned to {@code yaw}, without AI so it holds its pose (P5.3).
     */
    private static int revenant(ServerLevel level, BlockPos pos, ServerPlayer owner, int strain, float yaw) {
        Map<EquipmentSlot, ItemStack> worn = new EnumMap<>(EquipmentSlot.class);
        List<ItemStack> items = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = owner.getItemBySlot(slot);
            if (!stack.isEmpty() && (slot.isArmor() || slot == EquipmentSlot.MAINHAND)) {
                worn.put(slot, stack.copy());
                items.add(stack.copy());
            }
        }
        Revenant revenant = Revenants.rise(level, owner, level.damageSources().generic(), pos, items, 0, 0.0F, worn);
        if (revenant == null) {
            return 0;
        }
        revenant.setNoAi(true);
        revenant.setSilent(true);
        revenant.moveTo(revenant.getX(), revenant.getY(), revenant.getZ(), yaw, 0.0F);
        revenant.setYHeadRot(yaw);
        revenant.setYBodyRot(yaw);
        Corruption.set(revenant, strain, CorruptionCause.COMMAND);
        return 1;
    }

    private static int eachPlayer(CommandContext<CommandSourceStack> context, Consumer<ServerPlayer> action) {
        var players = context.getSource().getServer().getPlayerList().getPlayers();
        players.forEach(action);
        return players.size();
    }

    /**
     * Sets the viral load and trend of every target player through the server code, which syncs it to their HUD. Scenes
     * freeze ticks, and viral load does not update while they are frozen, so the value holds for every shot (P5.1).
     */
    private static int viralLoad(CommandContext<CommandSourceStack> context, ViralLoadState.Trend trend) throws CommandSyntaxException {
        float load = FloatArgumentType.getFloat(context, "load");
        var players = EntityArgument.getPlayers(context, "targets");
        players.forEach(player -> ViralLoad.set(player, load, trend, ViralLoadChangedEvent.Cause.OTHER));
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

    /**
     * Festers every loaded chunk the box overlaps at once to its current stage, as the budgeted stage work would over a
     * few ticks (P8.2: after {@code remission region advance}, whose stage events queue that work, so a scene's land is
     * finished before its shot). Returns the number of loaded chunks.
     */
    private static int festerAtStage(ServerLevel level, BlockPos from, BlockPos to) {
        int chunks = 0;
        for (ChunkPos pos : ChunkPos.rangeClosed(new ChunkPos(from), new ChunkPos(to)).toList()) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk != null) {
                FesteringStages.festerNow(level, chunk);
                chunks++;
            }
        }
        return chunks;
    }

    /**
     * Runs up to {@code operations} operations of the reclaimer at {@code pos} at once with the configured settings (P7.4),
     * stopping when one drains nothing, as its ticker would over that many intervals with a generator topping its energy
     * up between them (its fuel is used as usual), then finishes every queued chunk cleanse. Returns the operations that
     * drained a chunk; fails without a reclaimer there.
     */
    private static int reclaim(ServerLevel level, BlockPos pos, int operations) throws CommandSyntaxException {
        if (!(level.getBlockEntity(pos) instanceof ReclaimerBlockEntity reclaimer)) {
            throw NO_RECLAIMER.create();
        }
        ReclaimerSettings settings = ReclaimerSettings.fromConfig();
        int ran = 0;
        while (ran < operations) {
            while (reclaimer.energy().receiveEnergy(Integer.MAX_VALUE, false) > 0) {
                // a generator between operations
            }
            if (reclaimer.operate(level, settings) != ReclaimerBlockEntity.Status.RUNNING) {
                break;
            }
            ran++;
        }
        Purification.engine(level).tick(Integer.MAX_VALUE);
        return ran;
    }

    /**
     * Grows the festering veins of every loaded chunk the box overlaps at once, at each chunk's current stage, as the
     * budgeted vein pass would over a few ticks (P4.8). Run it after {@code harness fester} has set the stages. Returns
     * the number of blocks changed.
     */
    private static int veins(ServerLevel level, BlockPos from, BlockPos to) {
        int changed = 0;
        for (ChunkPos pos : ChunkPos.rangeClosed(new ChunkPos(from), new ChunkPos(to)).toList()) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk != null) {
                changed += FesteringVeins.growNow(level, chunk);
            }
        }
        return changed;
    }

    /**
     * Spawns {@code count} surge hosts within {@code radius} blocks of {@code centre} through the surge spawn code
     * ({@link SurgeHosts}: festering chunks, surface or covered spots near the centre's height, type spawn rules), with a
     * random seeded from the arguments and no spawn finalisation, so a scene gets the same hosts every run. Returns the
     * number spawned; fails if none could be.
     */
    private static int surgeHosts(ServerLevel level, BlockPos centre, int count, int radius) throws CommandSyntaxException {
        RandomSource random = RandomSource.create(centre.asLong() * 31 + count * 17L + radius);
        int spawned = SurgeHosts.spawnAround(level, centre, count, 1, radius,
                RemissionCommonConfig.SURGE_HOST_LEVEL.get(), random, false, chunk -> true).size();
        if (spawned == 0) {
            throw NO_SPOT.create();
        }
        return spawned;
    }

    /**
     * Releases {@code count} miasma clouds over festering chunks around {@code centre} through the surge release code,
     * with a random seeded from the arguments. Returns the number released; fails if no chunk nearby is festering.
     */
    private static int surgeClouds(ServerLevel level, BlockPos centre, int count) throws CommandSyntaxException {
        RandomSource random = RandomSource.create(centre.asLong() * 31 + count);
        int released = SurgeController.releaseClouds(level, List.of(new ChunkPos(centre)), count, random, chunk -> true).size();
        if (released == 0) {
            throw NO_SPOT.create();
        }
        return released;
    }
}
