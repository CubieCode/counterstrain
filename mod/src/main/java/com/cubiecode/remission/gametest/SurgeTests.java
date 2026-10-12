package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CorruptionCause;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.HostCorruptedEvent;
import com.cubiecode.remission.api.SurgeEndedEvent;
import com.cubiecode.remission.api.SurgeStartedEvent;
import com.cubiecode.remission.commands.RemissionCommands;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionEntityTypes;
import com.cubiecode.remission.registry.RemissionStats;
import com.cubiecode.remission.surge.MiasmaCloud;
import com.cubiecode.remission.surge.Surge;
import com.cubiecode.remission.surge.SurgeController;
import com.cubiecode.remission.surge.SurgeData;
import com.cubiecode.remission.surge.SurgeHosts;
import com.cubiecode.remission.surge.SurgeSettings;
import com.cubiecode.remission.surge.Surges;
import com.cubiecode.remission.world.ChunkCorruption;
import com.cubiecode.remission.world.Nidus;
import com.cubiecode.remission.world.SpreadEngine;
import com.cubiecode.remission.world.SpreadSettings;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.Stats;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Surges (P4.3): scheduling and the surge's phases, events, trigger and stat; spread acceleration on top of the spread
 * attempt; host spawning on the surface and under cover; miasma cloud release, drift and lifetime; the commands. Tests
 * that need land work in a square of force-loaded chunks far from the test grid, one square per test, and run in their
 * own batch.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurgeTests {
    private static final String BATCH = "surges";
    /** Chunks east of the test grid where each test's land square starts; squares are {@link #SQUARE} chunks apart. */
    private static final int FAR = 160;
    // Wide enough that squares of tests placed a few chunks apart on the grid never come within cloud range of another.
    private static final int SQUARE = 24;
    /** Level 30: festering stage 2 with the default thresholds (grass and trees fester, stone stays). */
    private static final int LAND_LEVEL = 30;
    private static final SpreadSettings SPREAD = new SpreadSettings(400, 5, 2, 10, 40, 256, 32);
    private static final SurgeSettings SETTINGS = new SurgeSettings(true, 100, 20, 60, 4, 2, 50, 4, 2);

    private SurgeTests() {
    }

    // ---- land -------------------------------------------------------------------------------------------------------

    /** A 3x3 square of force-loaded, festering chunks; {@link #centre} is the middle one. */
    private record Land(ServerLevel level, ChunkPos centre, List<LevelChunk> chunks) {
        BlockPos ground(int dx, int dz) {
            int x = centre.getMiddleBlockX() + dx;
            int z = centre.getMiddleBlockZ() + dz;
            return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
        }

        boolean contains(ChunkPos chunk) {
            return Math.abs(chunk.x - centre.x) <= 1 && Math.abs(chunk.z - centre.z) <= 1;
        }

        void release() {
            AABB box = new AABB(centre.getMinBlockX() - 16, level.getMinBuildHeight(), centre.getMinBlockZ() - 16,
                    centre.getMaxBlockX() + 17, level.getMaxBuildHeight(), centre.getMaxBlockZ() + 17);
            level.getEntities((Entity) null, box, entity -> !(entity instanceof ServerPlayer)).forEach(Entity::discard);
            for (LevelChunk chunk : chunks) {
                chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
                level.setChunkForced(chunk.getPos().x, chunk.getPos().z, false);
            }
        }
    }

    private static Land land(GameTestHelper helper, int square, int landLevel) {
        ServerLevel level = helper.getLevel();
        ChunkPos own = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        ChunkPos centre = new ChunkPos(own.x + FAR + square * SQUARE, own.z);
        List<LevelChunk> chunks = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setChunkForced(centre.x + dx, centre.z + dz, true);
                LevelChunk chunk = level.getChunk(centre.x + dx, centre.z + dz);
                if (landLevel > 0) {
                    chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(landLevel, level.getGameTime()));
                } else {
                    chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
                }
                chunks.add(chunk);
            }
        }
        return new Land(level, centre, chunks);
    }

    /**
     * Runs {@code test} once every chunk of {@code land} is entity-ticking: entities added before that stay out of the
     * level's area queries, and how long a freshly forced chunk takes varies with the machine's load.
     */
    private static void whenTicking(GameTestHelper helper, Land land, Runnable test) {
        helper.startSequence()
                .thenWaitUntil(() -> land.chunks().forEach(chunk -> helper.assertTrue(
                        land.level().isPositionEntityTicking(chunk.getPos().getWorldPosition()), "chunk is not entity-ticking")))
                .thenExecute(test);
    }

    private static <E extends Event> List<E> listen(Class<E> type, List<Consumer<?>> listeners) {
        List<E> events = new ArrayList<>();
        Consumer<E> listener = events::add;
        NeoForge.EVENT_BUS.addListener(type, listener);
        listeners.add(listener);
        return events;
    }

    private static void unlisten(List<Consumer<?>> listeners) {
        listeners.forEach(NeoForge.EVENT_BUS::unregister);
    }

    private static SurgeController controller(ServerLevel level, SurgeSettings settings, List<ServerPlayer> players) {
        return new SurgeController(level, new SurgeData(), () -> settings, () -> SPREAD, new SurgeController.Players() {
            @Override
            public List<ServerPlayer> inLevel() {
                return players;
            }

            @Override
            public ServerPlayer byId(UUID id) {
                return players.stream().filter(player -> player.getUUID().equals(id)).findFirst().orElse(null);
            }
        });
    }

    // ---- scheduling, phases, events, trigger and stat ----------------------------------------------------------------

    /**
     * A scheduled surge comes due after the interval, waits for a player in a nidus's area, warns, starts (event, clouds,
     * a first wave of surge hosts), and on ending awards the player who was caught in it, then schedules the next.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 200)
    public static void scheduledSurgeRunsItsCourse(GameTestHelper helper) {
        Land land = land(helper, 0, LAND_LEVEL);
        whenTicking(helper, land, () -> {
            ServerLevel level = land.level();
            BlockPos nidus = land.ground(0, 0);
            level.setBlockAndUpdate(nidus, RemissionBlocks.NIDUS.get().defaultBlockState());
            List<Consumer<?>> listeners = new ArrayList<>();
            try {
                List<SurgeStartedEvent> started = listen(SurgeStartedEvent.class, listeners);
                List<SurgeEndedEvent> ended = listen(SurgeEndedEvent.class, listeners);
                List<HostCorruptedEvent> hosts = listen(HostCorruptedEvent.class, listeners);
                List<ServerPlayer> players = new ArrayList<>();
                SurgeController surges = controller(level, SETTINGS, players);
                ServerPlayer player = player(helper, land.ground(2, 2));
                AdvancementHolder any = gate(helper, player, "{\"trigger\":\"remission:surge_survived\"}");
                AdvancementHolder strong = gate(helper, player,
                        "{\"trigger\":\"remission:surge_survived\",\"conditions\":{\"intensity\":{\"min\":2}}}");

                long base = 1_000_000L;
                surges.tick(base);
                helper.assertValueEqual(surges.data().nextDue(), base + SETTINGS.intervalTicks(), "first surge due");
                long due = base + SETTINGS.intervalTicks();
                for (long t = base + 1; t <= due + 40; t++) {
                    surges.tick(t);
                }
                helper.assertTrue(surges.current() == null, "a due surge started with nobody in a nidus's area");

                players.add(player);
                long now = due + 41;
                while (surges.current() == null && now < due + 100) {
                    surges.tick(now++);
                }
                Surge surge = surges.current();
                helper.assertTrue(surge != null, "a due surge did not start with a player in the area");
                helper.assertValueEqual(now - 1, due + 60, "the surge waits for the next area check");
                helper.assertValueEqual(surge.origin(), nidus, "surge origin");
                helper.assertFalse(surge.started(), "surge started without its warning");
                helper.assertTrue(started.isEmpty(), "started event during the warning");

                long start = surge.startTime();
                helper.assertValueEqual(start, due + 60 + SETTINGS.warningTicks(), "start time");
                for (; now <= start; now++) {
                    surges.tick(now);
                }
                helper.assertTrue(surge.started(), "surge did not start after its warning");
                helper.assertValueEqual(started.size(), 1, "started events");
                SurgeStartedEvent event = started.getFirst();
                helper.assertValueEqual(event.getOrigin(), nidus, "started origin");
                helper.assertValueEqual(event.getIntensity(), 1, "started intensity");
                helper.assertValueEqual(event.getEndTime(), start + SETTINGS.durationTicks(), "started end time");
                helper.assertValueEqual(surge.participants(), List.of(player.getUUID()), "players caught");
                List<MiasmaCloud> clouds = level.getEntities(RemissionEntityTypes.MIASMA_CLOUD.get(),
                        new AABB(land.ground(0, 0)).inflate(48, 64, 48), cloud -> true);
                helper.assertValueEqual(clouds.size(), SETTINGS.clouds(), "clouds released (all nearby: " + level.getEntities(
                        RemissionEntityTypes.MIASMA_CLOUD.get(), new AABB(nidus).inflate(256), cloud -> true).stream()
                        .map(Entity::position).toList() + ")");
                helper.assertValueEqual(surges.spreadFactor(new Nidus(nidus, 0L)), SETTINGS.spreadMultiplier(),
                        "spread factor of the surging nidus");
                helper.assertValueEqual(surges.spreadFactor(new Nidus(nidus.east(), 0L)), 1, "spread factor of another nidus");

                // The first wave spawned its hosts on the surface around the player, as surge hosts.
                List<HostCorruptedEvent> surgeHosts = hosts.stream().filter(e -> e.getCause() == CorruptionCause.SURGE).toList();
                helper.assertValueEqual(surgeHosts.size(), SETTINGS.hostsPerWave(), "surge hosts in the first wave");
                for (HostCorruptedEvent host : surgeHosts) {
                    helper.assertValueEqual(host.getNewLevel(), SETTINGS.hostLevel(), "surge host level");
                    helper.assertTrue(SurgeHosts.types().contains(host.getEntity().getType()), "surge host type not in the tag");
                    double distance = Math.sqrt(host.getEntity().distanceToSqr(player.getX(), host.getEntity().getY(), player.getZ()));
                    helper.assertTrue(distance >= SurgeHosts.MIN_DISTANCE - 2 && distance <= SurgeHosts.MAX_DISTANCE + 2,
                            "surge host " + distance + " blocks from the player");
                }

                long end = surge.endTime();
                for (; now < end; now++) {
                    surges.tick(now);
                }
                helper.assertTrue(ended.isEmpty(), "ended before its duration");
                helper.assertValueEqual(stat(player), 0, "surges survived before the end");
                surges.tick(now);
                helper.assertTrue(surges.current() == null, "surge still current after its end");
                helper.assertValueEqual(ended.size(), 1, "ended events");
                helper.assertValueEqual(ended.getFirst().getReason(), SurgeEndedEvent.Reason.ELAPSED, "ended reason");
                helper.assertValueEqual(ended.getFirst().getSurvivors(), List.of(player), "survivors");
                helper.assertValueEqual(stat(player), 1, "surges survived");
                helper.assertTrue(done(player, any), "surge_survived did not fire");
                helper.assertFalse(done(player, strong), "surge_survived for intensity 2 fired for intensity 1");
                helper.assertValueEqual(surges.data().nextDue(), end + SETTINGS.intervalTicks(), "next surge due");
                helper.assertValueEqual(surges.spreadFactor(new Nidus(nidus, 0L)), 1, "spread factor after the end");
            } finally {
                unlisten(listeners);
                level.setBlockAndUpdate(nidus, Blocks.AIR.defaultBlockState());
                land.release();
            }
            helper.succeed();
        });
    }

    /** A player who dies during a surge is not a survivor, even after coming back into the area. */
    @GameTest(template = "empty", batch = BATCH)
    public static void deadPlayersDoNotSurvive(GameTestHelper helper) {
        Land land = land(helper, 1, LAND_LEVEL);
        ServerLevel level = land.level();
        BlockPos nidus = land.ground(0, 0);
        level.setBlockAndUpdate(nidus, RemissionBlocks.NIDUS.get().defaultBlockState());
        List<Consumer<?>> listeners = new ArrayList<>();
        try {
            List<SurgeEndedEvent> ended = listen(SurgeEndedEvent.class, listeners);
            ServerPlayer survivor = player(helper, land.ground(3, 0));
            ServerPlayer fallen = player(helper, land.ground(-3, 0));
            SurgeController surges = controller(level, new SurgeSettings(false, 100, 0, 40, 4, 0, 50, 4, 0),
                    new ArrayList<>(List.of(survivor, fallen)));
            long now = 2_000_000L;
            helper.assertTrue(surges.start(nidus, 3, now), "surge did not start");
            helper.assertFalse(surges.start(nidus, 1, now), "a second surge started in the same level");
            surges.tick(++now);
            helper.assertValueEqual(surges.current().participants().size(), 2, "players caught");
            surges.playerDied(fallen);
            for (int i = 0; i < 40; i++) {
                surges.tick(++now);
            }
            helper.assertValueEqual(ended.size(), 1, "ended events");
            helper.assertValueEqual(ended.getFirst().getIntensity(), 3, "intensity");
            helper.assertValueEqual(ended.getFirst().getSurvivors(), List.of(survivor), "survivors");
            helper.assertValueEqual(stat(survivor), 1, "survivor's stat");
            helper.assertValueEqual(stat(fallen), 0, "fallen player's stat");
        } finally {
            unlisten(listeners);
            level.setBlockAndUpdate(nidus, Blocks.AIR.defaultBlockState());
            land.release();
        }
        helper.succeed();
    }

    /** Stopped in its warning, a surge posts nothing; destroying its nidus ends a running one. */
    @GameTest(template = "empty", batch = BATCH)
    public static void stoppedAndDestroyedSurges(GameTestHelper helper) {
        Land land = land(helper, 2, LAND_LEVEL);
        ServerLevel level = land.level();
        BlockPos nidus = land.ground(0, 0);
        level.setBlockAndUpdate(nidus, RemissionBlocks.NIDUS.get().defaultBlockState());
        List<Consumer<?>> listeners = new ArrayList<>();
        try {
            List<SurgeStartedEvent> started = listen(SurgeStartedEvent.class, listeners);
            List<SurgeEndedEvent> ended = listen(SurgeEndedEvent.class, listeners);
            SurgeController surges = controller(level, new SurgeSettings(false, 100, 20, 200, 4, 0, 50, 4, 0),
                    new ArrayList<>());
            long now = 3_000_000L;
            helper.assertFalse(surges.start(nidus.above(), 1, now), "a surge started without a nidus");
            helper.assertTrue(surges.start(nidus, 1, now), "surge did not start");
            surges.tick(++now);
            helper.assertTrue(surges.stop(now), "stop found no surge");
            helper.assertFalse(surges.stop(now), "stop found a surge after stopping");
            helper.assertTrue(started.isEmpty() && ended.isEmpty(), "events for a surge stopped in its warning");
            helper.assertValueEqual(surges.data().nextDue(), now + 100, "next due after a stop");

            helper.assertTrue(surges.start(nidus, 1, now), "surge did not start again");
            for (int i = 0; i <= 20; i++) {
                surges.tick(++now);
            }
            helper.assertValueEqual(started.size(), 1, "started events");
            level.setBlockAndUpdate(nidus, Blocks.AIR.defaultBlockState());
            surges.tick(++now);
            helper.assertValueEqual(ended.size(), 1, "ended events");
            helper.assertValueEqual(ended.getFirst().getReason(), SurgeEndedEvent.Reason.NIDUS_DESTROYED, "ended reason");
            helper.assertTrue(surges.current() == null, "surge still current after its nidus was destroyed");
        } finally {
            unlisten(listeners);
            level.setBlockAndUpdate(nidus, Blocks.AIR.defaultBlockState());
            land.release();
        }
        helper.succeed();
    }

    // ---- spread acceleration -----------------------------------------------------------------------------------------

    /**
     * A surge multiplies the surging nidus's live steps and its own chunk's growth; a cancelled step stays cancelled,
     * other nidi are untouched, and catch-up ignores surges.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void surgeAcceleratesLiveSpread(GameTestHelper helper) {
        SpreadSettings settings = FesteringLandTests.SETTINGS;
        FesteringLandTests.MapLand calm = FesteringLandTests.threeNidi();
        FesteringLandTests.MapLand surging = FesteringLandTests.threeNidi();
        Nidus origin = surging.nidi.getFirst();
        surging.surge = nidus -> nidus.equals(origin) ? 3 : 1;
        long blocked = ChunkPos.asLong(0, -1);
        calm.blocked = chunk -> chunk == blocked;
        surging.blocked = chunk -> chunk == blocked;
        // Bring the nidus chunks over the threshold first, so the next pass steps their neighbours.
        for (FesteringLandTests.MapLand land : List.of(calm, surging)) {
            land.set(origin.chunk(), new ChunkCorruption(50, 0L));
            FesteringLandTests.run(new SpreadEngine(land, () -> settings), 0, 1);
        }
        helper.assertValueEqual(FesteringLandTests.level(calm, 0, 0), 50 + settings.nidusGrowth(), "calm nidus chunk");
        helper.assertValueEqual(FesteringLandTests.level(surging, 0, 0), 50 + 3 * settings.nidusGrowth(), "surging nidus chunk");
        helper.assertValueEqual(FesteringLandTests.level(calm, 1, 0), settings.spreadStep(), "calm neighbour");
        helper.assertValueEqual(FesteringLandTests.level(surging, 1, 0), 3 * settings.spreadStep(), "surging neighbour");
        helper.assertValueEqual(FesteringLandTests.level(surging, 0, -1), 0, "cancelled step under a surge");
        helper.assertValueEqual(FesteringLandTests.level(surging, 3, 1), FesteringLandTests.level(calm, 3, 1),
                "another nidus's chunk");

        // Catch-up of the same chunk gives the same level with or without a surge.
        long chunk = ChunkPos.asLong(1, 1);
        for (FesteringLandTests.MapLand land : List.of(calm, surging)) {
            land.set(chunk, ChunkCorruption.NONE);
            land.set(ChunkPos.asLong(0, 1), new ChunkCorruption(90, 0L));
            new SpreadEngine(land, () -> settings).catchUp(chunk, 10L * settings.intervalTicks(), settings);
        }
        helper.assertValueEqual(FesteringLandTests.level(surging, 1, 1), FesteringLandTests.level(calm, 1, 1),
                "catch-up under a surge");
        helper.assertTrue(FesteringLandTests.level(calm, 1, 1) > 0, "catch-up did nothing");
        helper.succeed();
    }

    // ---- host spawning -----------------------------------------------------------------------------------------------

    /** On open festering land a surge host stands on the surface; clean land and other dimensions' chunks get none. */
    @GameTest(template = "empty", batch = BATCH)
    public static void hostsSpawnOnTheSurface(GameTestHelper helper) {
        Land land = land(helper, 3, LAND_LEVEL);
        whenTicking(helper, land, () -> {
            Land clean = land(helper, 4, 0);
            ServerLevel level = land.level();
            try {
                BlockPos ground = land.ground(0, 0);
                RandomSource random = RandomSource.create(42L);
                for (EntityType<?> type : SurgeHosts.types()) {
                    helper.assertValueEqual(SurgeHosts.findSpot(level, ground.getX(), ground.getZ(), ground.getY() + 5, type,
                            random, chunk -> true).orElse(null), ground, "surface spot for " + type);
                }
                helper.assertTrue(SurgeHosts.findSpot(level, ground.getX(), ground.getZ(), ground.getY(), EntityType.COW, random,
                        chunk -> false).isEmpty(), "a spot outside the surge's area");
                BlockPos cleanGround = clean.ground(0, 0);
                helper.assertTrue(SurgeHosts.findSpot(level, cleanGround.getX(), cleanGround.getZ(), cleanGround.getY(),
                        EntityType.COW, random, chunk -> true).isEmpty(), "a spot on clean land");

                List<Mob> hosts = SurgeHosts.spawnAround(level, ground, 6, 4, 12, 50, random, true, chunk -> true);
                helper.assertValueEqual(hosts.size(), 6, "surface hosts spawned");
                for (Mob host : hosts) {
                    helper.assertTrue(Corruption.isHost(host), "spawned mob is not a host");
                    helper.assertTrue(host.isAddedToLevel(), "spawned host is not in the level");
                    helper.assertValueEqual(host.blockPosition().getY(), ground.getY(), "surface host height");
                    helper.assertTrue(level.canSeeSky(host.blockPosition()), "surface host under cover");
                }
            } finally {
                land.release();
                clean.release();
            }
            helper.succeed();
        });
    }

    /**
     * Under a festering chunk, a player in a dark cave gets surge hosts on the cave floor beside them, not on the
     * surface far above, and only types whose spawn rules allow the spot: monsters, never cows or sheep.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 100)
    public static void hostsSpawnUnderground(GameTestHelper helper) {
        Land land = land(helper, 5, LAND_LEVEL);
        ServerLevel level = land.level();
        BlockPos ground = land.ground(0, 0);
        // The flat test world has no rock to dig into, so a sealed 13x26x13 stone block high above the festering ground
        // stands in for it, with a dark 9x3x9 cave inside whose floor is 20 blocks below the top: further than the spot
        // search reaches, so the open top of the block is out of range as the surface would be.
        BlockPos top = ground.above(50);
        BlockPos min = top.offset(-6, -25, -6);
        BlockPos max = top.offset(6, 0, 6);
        BlockPos.betweenClosed(min, max).forEach(pos -> level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState()));
        BlockPos floor = top.below(20);
        BlockPos.betweenClosed(floor.offset(-4, 0, -4), floor.offset(4, 2, 4))
                .forEach(pos -> level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
        // Light settles over the next ticks.
        helper.runAfterDelay(40, () -> {
            try {
                RandomSource random = RandomSource.create(7L);
                helper.assertFalse(level.canSeeSky(floor), "cave floor sees the sky");
                helper.assertValueEqual(SurgeHosts.findSpot(level, floor.getX() + 2, floor.getZ(), floor.getY(),
                        EntityType.ZOMBIE, random, chunk -> true).orElse(null), floor.east(2), "cave spot for a zombie");
                helper.assertTrue(SurgeHosts.findSpot(level, floor.getX() + 2, floor.getZ(), floor.getY(), EntityType.COW,
                        random, chunk -> true).isEmpty(), "a cow found a spot in a dark stone cave");
                helper.assertTrue(SurgeHosts.findSpot(level, floor.getX() + 2, floor.getZ(), floor.getY(), EntityType.SHEEP,
                        random, chunk -> true).isEmpty(), "a sheep found a spot in a dark stone cave");

                List<Mob> hosts = SurgeHosts.spawnAround(level, floor, 8, 1, 4, 50, random, true, chunk -> true);
                helper.assertTrue(!hosts.isEmpty(), "no hosts spawned in the cave");
                for (Mob host : hosts) {
                    BlockPos at = host.blockPosition();
                    helper.assertTrue(host instanceof Monster, "a non-monster spawned in the cave: " + host.getType());
                    helper.assertValueEqual(at.getY(), floor.getY(), "cave host height");
                    helper.assertFalse(level.canSeeSky(at), "cave host sees the sky");
                    helper.assertTrue(Corruption.isHost(host), "cave mob is not a host");
                }
            } finally {
                BlockPos.betweenClosed(min, max).forEach(pos -> level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
                land.release();
            }
            helper.succeed();
        });
    }

    // ---- miasma clouds -----------------------------------------------------------------------------------------------

    /** Clouds are released over festering chunks only, at their altitude above the ground. */
    @GameTest(template = "empty", batch = BATCH)
    public static void cloudsReleaseOverFesteringLand(GameTestHelper helper) {
        Land land = land(helper, 6, LAND_LEVEL);
        whenTicking(helper, land, () -> {
            ServerLevel level = land.level();
            try {
                LevelChunk cleanChunk = land.chunks().getFirst();
                cleanChunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
                List<MiasmaCloud> clouds = SurgeController.releaseClouds(level, List.of(land.centre()), 12,
                        RandomSource.create(3L), land::contains);
                helper.assertValueEqual(clouds.size(), 12, "clouds released");
                for (MiasmaCloud cloud : clouds) {
                    ChunkPos chunk = cloud.chunkPosition();
                    helper.assertFalse(chunk.equals(cleanChunk.getPos()), "cloud released over clean land");
                    helper.assertTrue(land.chunks().stream().anyMatch(c -> c.getPos().equals(chunk)), "cloud outside the land: "
                            + chunk + " at level " + CorruptionQueries.chunkLevel(level, chunk)
                            + ", land centre " + land.centre() + " at " + cloud.position());
                    int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, cloud.getBlockX(), cloud.getBlockZ());
                    helper.assertValueEqual((int) cloud.getY(), ground + MiasmaCloud.ALTITUDE, "cloud altitude");
                    helper.assertTrue(cloud.getRadius() >= MiasmaCloud.DEFAULT_RADIUS
                            && cloud.getRadius() <= MiasmaCloud.DEFAULT_RADIUS + 2, "cloud radius " + cloud.getRadius());
                    helper.assertValueEqual(cloud.getBbWidth(), cloud.getRadius() * 2, "cloud area width");
                }
                helper.assertTrue(SurgeController.releaseClouds(level, List.of(land.centre()), 3, RandomSource.create(3L),
                        chunk -> false).isEmpty(), "clouds released outside the area");
            } finally {
                land.release();
            }
            helper.succeed();
        });
    }

    /**
     * A cloud drifts along its heading and never over clean land: at the edge it turns and keeps drifting above the
     * festering chunks. It keeps its altitude, saves its state, and dissipates at the end of its lifetime.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void cloudsDriftAboveFesteringLand(GameTestHelper helper) {
        Land land = land(helper, 7, LAND_LEVEL);
        ServerLevel level = land.level();
        try {
            // Only the middle row of chunks (west to east) festers.
            for (LevelChunk chunk : land.chunks()) {
                if (chunk.getPos().z != land.centre().z) {
                    chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
                }
            }
            BlockPos ground = land.ground(0, 0);
            // Heading 270 is east (+x).
            MiasmaCloud cloud = MiasmaCloud.create(level, ground.getX() + 0.5, ground.getY() + MiasmaCloud.ALTITUDE,
                    ground.getZ() + 0.5, 270.0F, 4.0F, 100_000);
            double startX = cloud.getX();
            double startZ = cloud.getZ();
            for (int i = 0; i < 40; i++) {
                cloud.tick();
            }
            helper.assertTrue(cloud.getX() - startX > 40 * MiasmaCloud.SPEED * 0.99, "cloud did not drift east: " + cloud.getX());
            helper.assertTrue(Math.abs(cloud.getZ() - startZ) < 1e-6, "cloud drifted off its heading");

            boolean turned = false;
            for (int i = 0; i < 2400; i++) {
                cloud.tick();
                helper.assertTrue(land.chunks().stream().anyMatch(c -> c.getPos().equals(cloud.chunkPosition())
                                && c.getPos().z == land.centre().z),
                        "cloud left the festering chunks at " + cloud.position());
                turned |= cloud.getDeltaMovement().x < 0;
            }
            helper.assertTrue(turned, "cloud never turned back at the edge of festering land");
            helper.assertTrue(Math.abs(cloud.getY() - (ground.getY() + MiasmaCloud.ALTITUDE)) < 0.2, "cloud altitude drifted");

            CompoundTag saved = new CompoundTag();
            cloud.saveWithoutId(saved);
            MiasmaCloud loaded = new MiasmaCloud(RemissionEntityTypes.MIASMA_CLOUD.get(), level);
            loaded.load(saved);
            helper.assertValueEqual(loaded.getRadius(), 4.0F, "saved radius");
            helper.assertValueEqual(loaded.getHeading(), cloud.getHeading(), "saved heading");
            helper.assertValueEqual(loaded.getAge(), cloud.getAge(), "saved age");
            helper.assertValueEqual(loaded.getLifetime(), 100_000, "saved lifetime");

            MiasmaCloud brief = MiasmaCloud.create(level, ground.getX() + 0.5, ground.getY() + 8, ground.getZ() + 0.5, 0.0F,
                    4.0F, 5);
            for (int i = 0; i < 4; i++) {
                brief.tick();
            }
            helper.assertFalse(brief.isRemoved(), "cloud dissipated early");
            brief.tick();
            helper.assertTrue(brief.isRemoved(), "cloud outlived its lifetime");
        } finally {
            land.release();
        }
        helper.succeed();
    }

    // ---- commands and registration -----------------------------------------------------------------------------------

    /** {@code /remission surge start|status|stop} on the level's own controller. */
    @GameTest(template = "empty", batch = BATCH)
    public static void surgeCommands(GameTestHelper helper) {
        Land land = land(helper, 8, LAND_LEVEL);
        ServerLevel level = land.level();
        BlockPos nidus = land.ground(0, 0);
        SurgeController surges = Surges.controller(level);
        try {
            helper.assertTrue(surges.current() == null, "a surge is already running in the test level");
            String far = land.ground(16 * 12, 0).toShortString().replace(",", "");
            assertFails(helper, "remission surge start " + far, "commands.remission.surge.failed.no_nidus");
            assertFails(helper, "remission surge stop", "commands.remission.surge.failed.none");
            helper.assertValueEqual(run(helper, "remission surge status"), 0, "status without a surge");

            level.setBlockAndUpdate(nidus, RemissionBlocks.NIDUS.get().defaultBlockState());
            String near = land.ground(20, 5).toShortString().replace(",", "");
            helper.assertValueEqual(run(helper, "remission surge start " + near + " 3"), 1, "start result");
            helper.assertValueEqual(surges.current().origin(), nidus, "command surge origin");
            helper.assertValueEqual(surges.current().intensity(), 3, "command surge intensity");
            assertFails(helper, "remission surge start " + near, "commands.remission.surge.failed.running");
            helper.assertValueEqual(run(helper, "remission surge status"), 3, "status during the warning");
            helper.assertValueEqual(run(helper, "remission surge stop"), 1, "stop result");
            helper.assertTrue(surges.current() == null, "surge still current after stop");
        } finally {
            surges.stop(level.getGameTime());
            level.setBlockAndUpdate(nidus, Blocks.AIR.defaultBlockState());
            land.release();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void surgeTagAndCause(GameTestHelper helper) {
        List<EntityType<?>> types = SurgeHosts.types();
        helper.assertValueEqual(types, List.of(EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.SPIDER,
                EntityType.ZOMBIE), "default #remission:surge_hosts in id order");
        // Later causes (CONTACT, P4.8) are inserted after SURGE, still before OTHER.
        helper.assertTrue(List.of(CorruptionCause.values()).indexOf(CorruptionCause.SURGE)
                < List.of(CorruptionCause.values()).indexOf(CorruptionCause.OTHER), "SURGE comes before OTHER");
        helper.succeed();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    /** A survival server player at {@code pos} with a connection that drops what it is sent (see QuestTriggerTests). */
    static ServerPlayer player(GameTestHelper helper, BlockPos pos) {
        MinecraftServer server = helper.getLevel().getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "surge-test"), false);
        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        new ServerGamePacketListenerImpl(server, connection, player, cookie) {
            @Override
            public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
            }
        };
        player.setGameMode(GameType.SURVIVAL);
        player.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
        return player;
    }

    private static AdvancementHolder gate(GameTestHelper helper, ServerPlayer player, String json) {
        Criterion<?> criterion = Criterion.CODEC.parse(
                helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(json)).getOrThrow();
        AdvancementHolder holder = Advancement.Builder.advancement().addCriterion("gate", criterion)
                .build(Remission.id("test/" + UUID.randomUUID()));
        listen(player, criterion, holder);
        return holder;
    }

    private static <T extends CriterionTriggerInstance> void listen(ServerPlayer player, Criterion<T> criterion, AdvancementHolder holder) {
        criterion.trigger().addPlayerListener(player.getAdvancements(),
                new CriterionTrigger.Listener<>(criterion.triggerInstance(), holder, "gate"));
    }

    private static boolean done(ServerPlayer player, AdvancementHolder holder) {
        return player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    private static int stat(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(RemissionStats.SURGES_SURVIVED.get()));
    }

    private static int run(GameTestHelper helper, String command) {
        try {
            return execute(helper, command);
        } catch (CommandSyntaxException e) {
            throw new AssertionError("/" + command + " failed: " + e.getMessage(), e);
        }
    }

    private static void assertFails(GameTestHelper helper, String command, String key) {
        try {
            execute(helper, command);
            helper.fail("/" + command + " succeeded");
        } catch (CommandSyntaxException e) {
            Component message = e.getRawMessage() instanceof Component c ? c : null;
            helper.assertTrue(message != null && message.getContents() instanceof
                    TranslatableContents contents && contents.getKey().equals(key),
                    "/" + command + " error: " + e.getMessage());
        }
    }

    private static int execute(GameTestHelper helper, String command) throws CommandSyntaxException {
        CommandSourceStack source = new CommandSourceStack(CommandSource.NULL, Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO)),
                Vec2.ZERO, helper.getLevel(), RemissionCommands.PERMISSION_LEVEL, "gametest", Component.literal("gametest"),
                helper.getLevel().getServer(), null);
        return helper.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
    }
}
