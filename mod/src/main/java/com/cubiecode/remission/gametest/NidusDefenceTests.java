package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.NidusDamagedEvent;
import com.cubiecode.remission.api.SurgeStartedEvent;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.surge.Surge;
import com.cubiecode.remission.surge.SurgeController;
import com.cubiecode.remission.surge.SurgeData;
import com.cubiecode.remission.surge.SurgeHosts;
import com.cubiecode.remission.surge.SurgeSettings;
import com.cubiecode.remission.surge.Surges;
import com.cubiecode.remission.surge.SympatheticRequest;
import com.cubiecode.remission.world.ChunkCorruption;
import com.cubiecode.remission.world.NidusBlockEntity;
import com.cubiecode.remission.world.NidusDefenceSettings;
import com.cubiecode.remission.world.NidusEncounters;
import com.cubiecode.remission.world.NidusIndex;
import com.cubiecode.remission.world.NidusRally;
import com.cubiecode.remission.world.NidusSettings;
import com.cubiecode.remission.world.SpreadSettings;
import com.cubiecode.remission.world.SympatheticSurges;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Nidus defence (P4.13): hosts in festering land within the rally radius path toward a struck nidus and those outside
 * (or not hosts, or unable to get there) do not; each batch makes at most {@code rallyBatch} path searches; crossing a
 * threshold queues a sympathetic surge at the nearest nidus in range and none out of range, which warns once the slot is
 * free and starts with the sympathetic cause and the attacked nidus; both switches work. Land tests work in force-loaded
 * festering squares far from the test grid. Batch {@code nidus_defence} runs on {@link NidusEncounterTests#SETTINGS} and
 * {@link #SETTINGS}; only {@link #thresholdQueuesSympatheticSurge} uses the level's own surge controller.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NidusDefenceTests {
    private static final String BATCH = "nidus_defence";
    /** Chunks east of the test grid where each test's square starts, and south of it; squares are {@link #SQUARE} apart. */
    private static final int FAR = 160;
    private static final int SOUTH = 320;
    private static final int SQUARE = 24;
    /** Level 30: festering stage 2 with the default thresholds. */
    private static final int LAND_LEVEL = 30;
    static final NidusDefenceSettings SETTINGS = new NidusDefenceSettings(true, 16, 4, true, 64, 1.0, 0.5);
    /** Both switches off: what the other nidus tests run with. */
    static final NidusDefenceSettings OFF = new NidusDefenceSettings(false, 16, 4, false, 64, 1.0, 0.5);
    private static final SurgeSettings SURGES = new SurgeSettings(false, 100000, 20, 60, 4, 0, 50, 0, 0);
    private static final SpreadSettings SPREAD = new SpreadSettings(400, 5, 2, 10, 40, 256, 32);

    private NidusDefenceTests() {
    }

    @BeforeBatch(batch = BATCH)
    public static void installSettings(ServerLevel level) {
        NidusSettings.override(NidusEncounterTests.SETTINGS);
        NidusDefenceSettings.override(SETTINGS);
    }

    @AfterBatch(batch = BATCH)
    public static void restoreSettings(ServerLevel level) {
        NidusSettings.override(null);
        NidusDefenceSettings.override(null);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    /** A 3x3 square of force-loaded festering chunks with a nidus in the middle; cleaned up after. */
    private static final class Land implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final ChunkPos centre;
        final List<LevelChunk> chunks = new ArrayList<>();
        final List<BlockPos> nidi = new ArrayList<>();
        final List<Consumer<?>> listeners = new ArrayList<>();
        final BlockPos nidus;
        final ServerPlayer player;

        Land(GameTestHelper helper, int square) {
            this.helper = helper;
            this.level = helper.getLevel();
            ChunkPos own = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
            centre = new ChunkPos(own.x + FAR + square * SQUARE, own.z + SOUTH);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setChunkForced(centre.x + dx, centre.z + dz, true);
                    LevelChunk chunk = level.getChunk(centre.x + dx, centre.z + dz);
                    chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(LAND_LEVEL, level.getGameTime()));
                    chunks.add(chunk);
                }
            }
            nidus = nidus(0, 0);
            // A player far from the land (on the test grid), so no host targets it; strikes need a player to count.
            player = SurgeTests.player(helper, helper.absolutePos(BlockPos.ZERO));
            NidusEncounters.addTestPlayer(nidus, player);
        }

        BlockPos ground(int dx, int dz) {
            int x = centre.getMiddleBlockX() + dx;
            int z = centre.getMiddleBlockZ() + dz;
            return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
        }

        BlockPos nidus(int dx, int dz) {
            BlockPos pos = ground(dx, dz);
            level.setBlockAndUpdate(pos, RemissionBlocks.NIDUS.get().defaultBlockState());
            nidi.add(pos);
            return pos;
        }

        NidusBlockEntity entity() {
            return (NidusBlockEntity) level.getBlockEntity(nidus);
        }

        /** A husk host (husks do not burn in daylight) standing at {@code dx, dz} from the middle of the square. */
        Mob host(int dx, int dz) {
            Mob host = SurgeHosts.spawn(level, EntityType.HUSK, ground(dx, dz), 50, level.getRandom(), false);
            host.setPersistenceRequired();
            return host;
        }

        float strike(float amount) {
            return entity().damage(level, NidusDamagedEvent.Kind.STRIKE, player, null, amount, NidusSettings.current(),
                    level.getGameTime());
        }

        <E extends Event> List<E> listen(Class<E> type) {
            List<E> events = new ArrayList<>();
            Consumer<E> listener = events::add;
            NeoForge.EVENT_BUS.addListener(type, listener);
            listeners.add(listener);
            return events;
        }

        /** Runs {@code test} once every chunk is entity-ticking (entities added before that miss area queries). */
        void whenTicking(Runnable test) {
            helper.startSequence()
                    .thenWaitUntil(() -> chunks.forEach(chunk -> helper.assertTrue(
                            level.isPositionEntityTicking(chunk.getPos().getWorldPosition()), "chunk is not entity-ticking")))
                    .thenExecute(test);
        }

        @Override
        public void close() {
            listeners.forEach(NeoForge.EVENT_BUS::unregister);
            NidusEncounters.removeTestPlayers(nidus);
            AABB box = new AABB(centre.getMinBlockX() - 16, level.getMinBuildHeight(), centre.getMinBlockZ() - 16,
                    centre.getMaxBlockX() + 17, level.getMaxBuildHeight(), centre.getMaxBlockZ() + 17);
            level.getEntities((Entity) null, box, entity -> !(entity instanceof ServerPlayer)).forEach(Entity::discard);
            for (BlockPos pos : nidi) {
                if (level.getBlockState(pos).is(RemissionBlocks.NIDUS.get())) {
                    level.removeBlock(pos, false);
                }
                NidusIndex.get(level).remove(pos);
            }
            for (LevelChunk chunk : chunks) {
                chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
                level.setChunkForced(chunk.getPos().x, chunk.getPos().z, false);
            }
        }
    }

    private static boolean headingFor(Mob host, BlockPos nidus) {
        BlockPos target = host.getNavigation().getTargetPos();
        return target != null && target.closerThan(nidus, 2.0);
    }

    private static double distance(Entity entity, BlockPos pos) {
        return Math.sqrt(entity.distanceToSqr(Vec3.atCenterOf(pos)));
    }

    // ---- rallying ---------------------------------------------------------------------------------------------------

    /**
     * Struck, the nidus sends for the hosts within the rally radius, and they close in on it; a host outside the radius
     * is left where it is.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 600)
    public static void hostsWithinRadiusRally(GameTestHelper helper) {
        Land land = new Land(helper, 0);
        land.whenTicking(() -> {
            List<Mob> inside = List.of(land.host(10, 0), land.host(-9, 4), land.host(3, -11));
            Mob outside = land.host(22, 0);
            double outsideStart = distance(outside, land.nidus);
            helper.assertTrue(land.strike(1.0F) > 0, "the strike dealt nothing");
            helper.assertTrue(land.entity().underAttack(NidusSettings.current(), land.level.getGameTime()), "not under attack");
            helper.onEachTick(() -> {
                if (land.level.getGameTime() % 20 == 10) {
                    land.strike(1.0F); // keep it under attack (it regenerates after 40 ticks without damage)
                }
            });
            helper.succeedWhen(() -> {
                NidusRally rally = land.entity().rally();
                for (Mob host : inside) {
                    helper.assertTrue(rally.rallying().contains(host.getUUID()), "a host within the radius is not rallying: left alone "
                            + rally.leftAlone().contains(host.getUUID()) + ", alive " + host.isAlive() + ", at "
                            + distance(host, land.nidus) + ", under attack "
                            + land.entity().underAttack(NidusSettings.current(), land.level.getGameTime()));
                    helper.assertTrue(distance(host, land.nidus) <= NidusRally.ARRIVED + 1.5,
                            "a rallying host is still " + distance(host, land.nidus) + " blocks away");
                }
                helper.assertFalse(rally.rallying().contains(outside.getUUID()), "a host outside the radius rallied");
                helper.assertFalse(headingFor(outside, land.nidus), "a host outside the radius is heading for the nidus");
                helper.assertTrue(distance(outside, land.nidus) > outsideStart - 3.0, "a host outside the radius closed in");
                land.close();
            });
        });
    }

    /** Each batch searches at most {@code rallyBatch} paths, one batch a second, so the hosts come in waves. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 400)
    public static void rallyBatchesRespectBudget(GameTestHelper helper) {
        Land land = new Land(helper, 1);
        land.whenTicking(() -> {
            List<Mob> hosts = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                hosts.add(land.host(-10 + 2 * i, i % 2 == 0 ? 9 : -9));
            }
            land.strike(1.0F);
            int[] last = {0};
            int[] batches = {0};
            helper.onEachTick(() -> {
                long now = land.level.getGameTime();
                if (now % 20 == 10) {
                    land.strike(1.0F);
                }
                NidusRally rally = land.entity().rally();
                int count = rally.rallying().size() + rally.leftAlone().size();
                if (count != last[0]) {
                    helper.assertTrue(now % NidusRally.RALLY_TICKS == 0, "hosts joined between batches");
                    helper.assertTrue(count - last[0] <= SETTINGS.rallyBatch(), "a batch sent for " + (count - last[0]) + " hosts");
                    batches[0]++;
                    last[0] = count;
                }
                helper.assertTrue(rally.lastSearches() <= SETTINGS.rallyBatch(), "a batch searched " + rally.lastSearches() + " paths");
            });
            helper.succeedWhen(() -> {
                NidusRally rally = land.entity().rally();
                for (Mob host : hosts) {
                    // A host that strolled out of the radius (or up to the nidus) before its batch came is rightly not sent for.
                    double at = distance(host, land.nidus);
                    helper.assertTrue(rally.rallying().contains(host.getUUID()) || at > SETTINGS.rallyRadius() || at <= NidusRally.ARRIVED,
                            "a host within the radius is not rallying: at " + distance(host, land.nidus) + ", on ground "
                                    + host.onGround() + ", left alone " + rally.leftAlone().contains(host.getUUID()));
                }
                helper.assertTrue(rally.rallying().size() >= hosts.size() - 2, "only " + rally.rallying().size() + " hosts rallying");
                helper.assertTrue(batches[0] >= 3, "10 hosts came in " + batches[0] + " batches of at most 4");
                land.close();
            });
        });
    }

    /** A host sealed in a box of stone cannot get closer: it is left alone, and never searched again in the fight. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 300)
    public static void unreachableHostLeftAlone(GameTestHelper helper) {
        Land land = new Land(helper, 2);
        land.whenTicking(() -> {
            BlockPos at = land.ground(10, 0);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = -1; dy <= 2; dy++) {
                        if (dx != 0 || dz != 0 || dy == -1 || dy == 2) {
                            land.level.setBlockAndUpdate(at.offset(dx, dy, dz), Blocks.STONE.defaultBlockState());
                        }
                    }
                }
            }
            Mob sealed = SurgeHosts.spawn(land.level, EntityType.HUSK, at, 50, land.level.getRandom(), false);
            sealed.setPersistenceRequired();
            land.strike(1.0F);
            helper.onEachTick(() -> {
                if (land.level.getGameTime() % 20 == 10) {
                    land.strike(1.0F);
                }
            });
            helper.succeedWhen(() -> {
                NidusRally rally = land.entity().rally();
                helper.assertTrue(rally.leftAlone().contains(sealed.getUUID()), "the sealed host was not left alone");
                helper.assertFalse(rally.rallying().contains(sealed.getUUID()), "the sealed host is rallying");
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dy = 0; dy <= 2; dy++) {
                            land.level.removeBlock(at.offset(dx, dy, dz), false);
                        }
                    }
                }
                land.close();
            });
        });
    }

    /** With rallying off, a struck nidus sends for nobody. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 200)
    public static void rallySwitchOff(GameTestHelper helper) {
        Land land = new Land(helper, 3);
        land.whenTicking(() -> {
            Mob host = land.host(8, 0);
            // A walker can search a path only once it has landed.
            helper.runAfterDelay(10, () -> switchOff(helper, land, host));
        });
    }

    private static void switchOff(GameTestHelper helper, Land land, Mob host) {
        land.strike(1.0F);
        NidusRally rally = land.entity().rally();
        NidusDefenceSettings off = new NidusDefenceSettings(false, 16, 4, true, 64, 1.0, 0.5);
        long now = land.level.getGameTime();
        for (int i = 0; i < 3; i++) {
            rally.tick(land.level, true, off, (now / NidusRally.RALLY_TICKS + i + 1) * NidusRally.RALLY_TICKS);
        }
        helper.assertTrue(rally.rallying().isEmpty(), "hosts rallied with rallying off");
        // Settings on: the same host is sent for at the next batch.
        rally.batch(land.level, SETTINGS);
        helper.assertTrue(rally.rallying().contains(host.getUUID()), "the host did not rally with rallying on: left alone "
                + rally.leftAlone().contains(host.getUUID()) + ", on ground " + host.onGround() + ", searches " + rally.lastSearches());
        // The attack over: the rally ends.
        rally.tick(land.level, false, SETTINGS, now);
        helper.assertTrue(rally.rallying().isEmpty(), "the rally outlived the attack");
        land.close();
        helper.succeed();
    }

    // ---- sympathetic surges -----------------------------------------------------------------------------------------

    /**
     * The nidus answering a threshold is the nearest other nidus within the sympathetic radius; with none in range, a
     * chance of 0 or the switch off, nothing is queued.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void sympatheticChoosesNearestInRange(GameTestHelper helper) {
        Land land = new Land(helper, 4);
        BlockPos near = land.nidus(20, 0);
        BlockPos nearer = land.nidus(0, -12);
        BlockPos far = land.nidus(0, 22);
        NidusIndex index = NidusIndex.get(land.level);
        helper.assertValueEqual(SympatheticSurges.answering(index, land.nidus, 30), Optional.of(nearer), "nearest in range");
        land.level.removeBlock(nearer, false);
        helper.assertValueEqual(SympatheticSurges.answering(index, land.nidus, 30), Optional.of(near), "nearest after removal");
        helper.assertValueEqual(SympatheticSurges.answering(index, land.nidus, 18), Optional.empty(), "none in range");
        helper.assertValueEqual(SympatheticSurges.answering(index, land.nidus, 22), Optional.of(near), "nearest of two in range");
        land.level.removeBlock(near, false);
        helper.assertValueEqual(SympatheticSurges.answering(index, land.nidus, 22), Optional.of(far), "edge of the radius");
        helper.assertValueEqual(SympatheticSurges.answering(index, land.nidus, 21), Optional.empty(), "just out of range");
        long now = land.level.getGameTime();
        helper.assertValueEqual(SympatheticSurges.thresholdPassed(land.level, land.nidus, 2,
                new NidusDefenceSettings(true, 16, 4, true, 18, 1.0, 0.5), now), Optional.empty(), "queued out of range");
        helper.assertValueEqual(SympatheticSurges.thresholdPassed(land.level, land.nidus, 2,
                new NidusDefenceSettings(true, 16, 4, true, 30, 0.0, 0.5), now), Optional.empty(), "queued at chance 0");
        helper.assertValueEqual(SympatheticSurges.thresholdPassed(land.level, land.nidus, 2,
                new NidusDefenceSettings(true, 16, 4, false, 30, 1.0, 0.5), now), Optional.empty(), "queued with the switch off");
        helper.assertValueEqual(SETTINGS.sympatheticIntensityFor(1), 1, "intensity floor");
        helper.assertValueEqual(SETTINGS.sympatheticIntensityFor(6), 3, "reduced intensity");
        land.close();
        helper.succeed();
    }

    /**
     * Crossing a threshold starts the nidus's own surge (cause DIRECT) and queues a sympathetic surge at the nidus in
     * range, at reduced intensity, and none at the one out of range.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void thresholdQueuesSympatheticSurge(GameTestHelper helper) {
        Land land = new Land(helper, 5);
        BlockPos inRange = land.nidus(30, 0);
        List<SurgeStartedEvent> started = land.listen(SurgeStartedEvent.class);
        SurgeController surges = Surges.controller(land.level);
        try {
            land.strike(30.0F); // 100 vitality: past 75%
            helper.assertValueEqual(land.entity().thresholdsPassed(), 1, "thresholds passed");
            List<SurgeStartedEvent> own = started.stream().filter(event -> event.getOrigin().equals(land.nidus)).toList();
            helper.assertValueEqual(own.size(), 1, "threshold surges");
            helper.assertValueEqual(own.getFirst().getCause(), SurgeStartedEvent.Cause.DIRECT, "threshold surge cause");
            helper.assertTrue(own.getFirst().getAttackedNidus().isEmpty(), "threshold surge has an attacked nidus");
            helper.assertValueEqual(surges.data().sympathetic(), new SympatheticRequest(inRange, 1, land.nidus), "queued request");
            // The fight's own surge holds the slot; the request waits.
            helper.assertValueEqual(surges.current().origin(), land.nidus, "running surge");
        } finally {
            surges.stop(land.level.getGameTime());
            helper.assertTrue(surges.data().sympathetic() == null, "stop kept the request");
            land.close();
        }
        helper.succeed();
    }

    /**
     * A queued sympathetic surge waits for the running surge, then warns as usual and starts with the sympathetic cause
     * and the attacked nidus; a later fight threshold supersedes it and it does not come back.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 300)
    public static void sympatheticSurgeWaitsWarnsAndStarts(GameTestHelper helper) {
        Land land = new Land(helper, 6);
        BlockPos other = land.nidus(30, 0);
        List<SurgeStartedEvent> started = land.listen(SurgeStartedEvent.class);
        SurgeController surges = new SurgeController(land.level, new SurgeData(), () -> SURGES, () -> SPREAD,
                new SurgeController.Players() {
                    @Override
                    public List<ServerPlayer> inLevel() {
                        return List.of();
                    }

                    @Override
                    public ServerPlayer byId(UUID id) {
                        return null;
                    }
                });
        long now = land.level.getGameTime();
        surges.startNow(land.nidus, 2, now);
        surges.queueSympathetic(other, 1, land.nidus);
        long t = now;
        // The fight's surge runs its 60 ticks; the request waits.
        while (t < now + 60) {
            surges.tick(++t);
            BlockPos running = surges.current() == null ? null : surges.current().origin();
            helper.assertTrue(java.util.Objects.equals(running, t < now + 60 ? land.nidus : null), "surge at tick " + (t - now) + ": " + running);
        }
        surges.tick(++t);
        Surge sympathetic = surges.current();
        helper.assertTrue(sympathetic != null && sympathetic.origin().equals(other), "the sympathetic surge did not take the slot");
        helper.assertFalse(sympathetic.started(), "the sympathetic surge skipped its warning");
        helper.assertValueEqual(sympathetic.sympatheticTo(), Optional.of(land.nidus), "attacked nidus on the surge");
        helper.assertTrue(surges.data().sympathetic() == null, "request left queued");
        long warned = t;
        while (t < warned + SURGES.warningTicks()) {
            surges.tick(++t);
        }
        List<SurgeStartedEvent> fromOther = started.stream().filter(event -> event.getOrigin().equals(other)).toList();
        helper.assertValueEqual(fromOther.size(), 1, "sympathetic surges started");
        helper.assertValueEqual(fromOther.getFirst().getCause(), SurgeStartedEvent.Cause.SYMPATHETIC, "cause");
        helper.assertValueEqual(fromOther.getFirst().getAttackedNidus(), Optional.of(land.nidus), "attacked nidus");
        helper.assertValueEqual(fromOther.getFirst().getIntensity(), 1, "intensity");
        // A later threshold of the fight supersedes it; with nothing queued it does not come back.
        surges.startNow(land.nidus, 3, ++t);
        helper.assertValueEqual(surges.current().origin(), land.nidus, "the threshold surge took the slot");
        for (int i = 0; i < 61; i++) {
            surges.tick(++t);
        }
        helper.assertTrue(surges.current() == null, "a superseded sympathetic surge came back");
        // A request whose origin is gone is dropped too.
        surges.queueSympathetic(other, 1, land.nidus);
        land.level.removeBlock(other, false);
        surges.tick(++t);
        helper.assertTrue(surges.current() == null, "a sympathetic surge started from a destroyed nidus");
        surges.stop(t);
        land.close();
        helper.succeed();
    }

    /** The queued request and a sympathetic surge's attacked nidus are saved. */
    @GameTest(template = "empty", batch = BATCH)
    public static void sympatheticStateSaved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos attacked = helper.absolutePos(new BlockPos(3, 2, 3));
        SurgeData data = new SurgeData();
        SurgeController surges = new SurgeController(level, data, () -> SURGES, () -> SPREAD, SurgeController.levelPlayers(level));
        surges.queueSympathetic(origin, 2, attacked);
        CompoundTag tag = data.save(new CompoundTag(), level.registryAccess());
        SurgeData loaded = SurgeData.load(tag, level.registryAccess());
        helper.assertValueEqual(loaded.sympathetic(), new SympatheticRequest(origin, 2, attacked), "loaded request");
        CompoundTag none = new SurgeData().save(new CompoundTag(), level.registryAccess());
        helper.assertTrue(SurgeData.load(none, level.registryAccess()).sympathetic() == null, "request from nothing");
        helper.succeed();
    }
}
