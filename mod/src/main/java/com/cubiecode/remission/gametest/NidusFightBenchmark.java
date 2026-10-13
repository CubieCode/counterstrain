package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.surge.Surges;
import com.cubiecode.remission.viralload.ViralLoad;
import com.cubiecode.remission.world.ChunkCorruption;
import com.cubiecode.remission.world.NidusBlockEntity;
import com.cubiecode.remission.world.NidusIndex;
import com.cubiecode.remission.world.NidusSettings;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import org.jetbrains.annotations.Nullable;

/**
 * The scripted nidus fight for P4.10's balance check ("a fight against a full-grown nidus with iron gear is winnable but
 * costly"), since foundry has no input device for a played one. Registered only when the {@code REMISSION_NIDUS_FIGHT}
 * environment variable is set (a number of trials, default 3), each trial in its own batch so their surges never meet.
 *
 * <p>Each trial: Normal difficulty, the {@code [nidi]} and {@code [surges]} config as loaded, a full-grown nidus (indexed
 * {@code growthTicks} ago) on a 5x5-chunk square of stage 4 festering land far from the test grid, and one survival
 * player in full iron armour with an iron sword and full food, put in the level's player list so surge waves spawn
 * around it and hosts target it, with viral load, food and regeneration running as for a real player (its ticks are run
 * here, as its connection would).
 * It stands at the nidus and strikes it through the left-click path whenever its swing is fully charged and the strike
 * cooldown has passed. Trials run twice: first a player that never fights the hosts (the worst case), then one that,
 * when charged, first hits a host targeting it within reach ({@code Player#attack}) until the nidus is past its last
 * threshold (then it only strikes the nidus, racing the final surge), and eats a bread (32 ticks) whenever its food drops
 * below what natural regeneration needs. The server does not move players (their clients do), so a lash's
 * knockback is modelled as {@link #KNOCKBACK_PAUSE_TICKS} without striking (walking back). It writes
 * {@code nidus-fight-<trial>.txt} (time to kill, strikes, viral load, health lost, lowest health, survived) and logs it.
 */
@GameTestHolder(Remission.MOD_ID)
public final class NidusFightBenchmark {
    /** Ticks a lashed player spends walking back before it strikes again (knockback 1.5 is about 3 blocks). */
    public static final int KNOCKBACK_PAUSE_TICKS = 20;
    private static final int MAX_TICKS = 6000;
    /** Blocks within which the defending policy hits a host that targets the player (vanilla reach is 3). */
    private static final double DEFEND_REACH = 3.0;
    /** Food level at or below which the defending policy eats a bread (natural regeneration needs 18). */
    private static final int EAT_AT_FOOD = 17;
    private static final int RADIUS_CHUNKS = 2;

    private NidusFightBenchmark() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> trials() {
        String env = System.getenv("REMISSION_NIDUS_FIGHT");
        if (env == null) {
            return List.of();
        }
        int count = env.isBlank() ? 3 : Integer.parseInt(env.trim());
        List<TestFunction> trials = new ArrayList<>();
        for (boolean defends : new boolean[] {false, true}) {
            for (int i = 1; i <= count; i++) {
                int trial = i + (defends ? count : 0);
                trials.add(new TestFunction("nidusFight" + trial, "remission.nidusfight_" + trial, "remission:empty",
                        MAX_TICKS + 200, 0L, true, helper -> fight(helper, trial, defends)));
            }
        }
        return trials;
    }

    private static final class Run {
        final List<String> out = new ArrayList<>();
        long start;
        long lastStrike = -1_000_000L;
        long pauseUntil;
        int strikes;
        int lashes;
        int thresholdsSeen;
        float health;
        float healthLost;
        float lowestHealth;
        float maxViralLoad;
        int hostsSeen;
        int hostHits;
        int breadEaten;
        long eatingUntil;
        int durabilityUsed;
        boolean done;
    }

    private static void fight(GameTestHelper helper, int trial, boolean defends) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Difficulty difficulty = server.getWorldData().getDifficulty();
        server.setDifficulty(Difficulty.NORMAL, true);
        NidusSettings settings = NidusSettings.current();

        ChunkPos own = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        ChunkPos centre = new ChunkPos(own.x + 400 + trial * 40, own.z - 200);
        List<LevelChunk> chunks = new ArrayList<>();
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                level.setChunkForced(centre.x + dx, centre.z + dz, true);
                LevelChunk chunk = level.getChunk(centre.x + dx, centre.z + dz);
                chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(90, level.getGameTime()));
                chunks.add(chunk);
            }
        }
        int x = centre.getMiddleBlockX();
        int z = centre.getMiddleBlockZ();
        BlockPos nidus = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
        level.setBlockAndUpdate(nidus, RemissionBlocks.NIDUS.get().defaultBlockState());
        NidusIndex index = NidusIndex.get(level);
        index.remove(nidus);
        index.add(nidus, level.getGameTime() - settings.growthTicks());

        ServerPlayer player = player(level, nidus.east(2));
        // Into the level's player list without the join event (JourneyMap's join listener takes the gametest server for
        // an integrated one and loads client classes), so surge waves and hosts find it as they find any player.
        levelPlayers(level).add(player);
        Run run = new Run();
        run.start = level.getGameTime();
        run.health = player.getHealth();
        run.lowestHealth = run.health;
        NidusBlockEntity entity = (NidusBlockEntity) level.getBlockEntity(nidus);
        run.out.add(String.format("difficulty %s; policy: %s", level.getDifficulty(), defends
                ? "strikes the nidus, but first hits any host targeting it within " + DEFEND_REACH + " blocks when charged"
                        + " until the last threshold has passed (then only the nidus), and eats a bread whenever its food is "
                        + EAT_AT_FOOD + " or less"
                : "strikes the nidus only, never fights the hosts"));
        run.out.add(String.format("trial %d: full-grown nidus, vitality %.0f for one player; [nidi] %s", trial,
                entity.maxVitality(settings), settings));

        helper.onEachTick(() -> {
            if (run.done) {
                return;
            }
            long now = level.getGameTime();
            long t = now - run.start;
            player.moveTo(nidus.getX() + 2.5, nidus.getY(), nidus.getZ() + 0.5, 90.0F, 30.0F);
            // What the level's entity tick (tick count, ServerPlayer.tick) and the connection (doTick) do for a real player.
            player.tickCount++;
            player.tick();
            player.doTick();
            float health = player.getHealth();
            if (health < run.health) {
                run.healthLost += run.health - health;
            }
            run.health = health;
            run.lowestHealth = Math.min(run.lowestHealth, health);
            run.maxViralLoad = Math.max(run.maxViralLoad, ViralLoad.get(player));
            if (!player.getMainHandItem().isEmpty()) {
                run.durabilityUsed = player.getMainHandItem().getDamageValue();
            }
            boolean present = level.getBlockState(nidus).is(RemissionBlocks.NIDUS.get());
            if (present && entity.thresholdsPassed() > run.thresholdsSeen) {
                run.lashes += entity.thresholdsPassed() - run.thresholdsSeen;
                run.thresholdsSeen = entity.thresholdsPassed();
                run.pauseUntil = now + KNOCKBACK_PAUSE_TICKS;
                run.out.add(String.format("  t=%5.1fs threshold %d: vitality %.0f/%.0f, health %.1f, viral load %.1f, effects %s", t / 20.0,
                        run.thresholdsSeen, entity.vitality(settings), entity.maxVitality(settings), health, ViralLoad.get(player),
                        player.getActiveEffects().stream().map(effect -> effect.getEffect().getRegisteredName()).toList()));
            }
            if (t % 200 == 0) {
                run.hostsSeen = Math.max(run.hostsSeen, level.getEntitiesOfClass(Mob.class, area(nidus), Mob::isAlive).size());
            }
            boolean dead = player.isDeadOrDying();
            if (!present || dead || t >= MAX_TICKS) {
                run.done = true;
                run.out.add(String.format("result: %s after %.1f s; %d strikes, %d lashes; viral load %.1f at the end (max %.1f);"
                                + " health lost %.1f (lowest %.1f of %.1f); survived %s; most hosts alive at once (sampled) %d;"
                                + " sword durability used %d",
                        !present ? "nidus destroyed" : dead ? "player died" : "time out", t / 20.0, run.strikes, run.lashes,
                        ViralLoad.get(player), run.maxViralLoad, run.healthLost, run.lowestHealth, player.getMaxHealth(), !dead,
                        run.hostsSeen, run.durabilityUsed) + (defends ? ", hits on hosts " + run.hostHits + ", bread eaten " + run.breadEaten : ""));
                finish(helper, level, server, difficulty, player, nidus, chunks, trial, run);
                return;
            }
            boolean charged = player.getAttackStrengthScale(0.5F) >= 1.0F;
            boolean cooled = now - run.lastStrike >= settings.strikeCooldownTicks();
            if (defends && now < run.eatingUntil) {
                return;
            }
            if (defends && player.getFoodData().getFoodLevel() <= EAT_AT_FOOD) {
                // Eating a bread: 32 ticks without swinging, then its food and saturation.
                run.eatingUntil = now + 32;
                run.breadEaten++;
                player.getFoodData().eat(5, 6.0F);
                return;
            }
            boolean burning = entity.thresholdsPassed() >= settings.thresholds().size();
            if (charged && defends && !burning) {
                Mob host = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(DEFEND_REACH),
                        mob -> mob.isAlive() && mob.getTarget() == player).stream()
                        .min(java.util.Comparator.comparingDouble(mob -> mob.distanceToSqr(player))).orElse(null);
                if (host != null) {
                    player.attack(host);
                    run.hostHits++;
                    return;
                }
            }
            if (charged && cooled && now >= run.pauseUntil) {
                player.gameMode.handleBlockBreakAction(nidus, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                        Direction.EAST, level.getMaxBuildHeight(), 0);
                player.gameMode.handleBlockBreakAction(nidus, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                        Direction.EAST, level.getMaxBuildHeight(), 0);
                run.lastStrike = now;
                run.strikes++;
            }
        });
    }

    private static void finish(GameTestHelper helper, ServerLevel level, MinecraftServer server, Difficulty difficulty,
                               ServerPlayer player, BlockPos nidus, List<LevelChunk> chunks, int trial, Run run) {
        run.out.forEach(line -> Remission.LOGGER.info("[nidus fight] {}", line));
        try {
            Files.write(Path.of("nidus-fight-" + trial + ".txt"), run.out);
        } catch (IOException e) {
            Remission.LOGGER.warn("[nidus fight] could not write the result", e);
        }
        Surges.controller(level).stop(level.getGameTime());
        levelPlayers(level).remove(player);
        level.getEntities((Entity) null, area(nidus), entity -> !(entity instanceof ServerPlayer)).forEach(Entity::discard);
        if (level.getBlockState(nidus).is(RemissionBlocks.NIDUS.get())) {
            level.removeBlock(nidus, false);
        }
        for (LevelChunk chunk : chunks) {
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
            level.setChunkForced(chunk.getPos().x, chunk.getPos().z, false);
        }
        server.setDifficulty(difficulty, true);
        helper.succeed();
    }

    private static List<ServerPlayer> levelPlayers(ServerLevel level) {
        return ObfuscationReflectionHelper.getPrivateValue(ServerLevel.class, level, "players");
    }

    private static AABB area(BlockPos nidus) {
        int reach = RADIUS_CHUNKS * 16 + 16;
        return new AABB(nidus).inflate(reach, 64, reach);
    }

    /** A survival player in full iron armour with an iron sword, whose connection drops what it is sent. */
    private static ServerPlayer player(ServerLevel level, BlockPos pos) {
        MinecraftServer server = level.getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "nidus-fighter"), false);
        ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        new ServerGamePacketListenerImpl(server, connection, player, cookie) {
            @Override
            public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
            }
        };
        player.setGameMode(GameType.SURVIVAL);
        player.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 90.0F, 30.0F);
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        return player;
    }
}
