package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.ChunkStageChangedEvent;
import com.cubiecode.remission.api.CorruptionCause;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.HostCleansedEvent;
import com.cubiecode.remission.api.HostCorruptedEvent;
import com.cubiecode.remission.api.HostEvent;
import com.cubiecode.remission.api.NidusDestroyedEvent;
import com.cubiecode.remission.api.StrainSpreadAttemptEvent;
import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.cubiecode.remission.infection.Infection;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.world.ChunkCorruption;
import com.cubiecode.remission.world.FesteringLand;
import com.cubiecode.remission.world.NidusIndex;
import com.cubiecode.remission.world.SpreadEngine;
import com.cubiecode.remission.world.SpreadSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The public API (P4.4): every event is posted with the right payload, the spread attempt can cancel and scale spread,
 * and the queries match the stored state. Land tests run a separate engine synchronously over the test chunk and its
 * neighbours, and restore every chunk they touch.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PublicApiTests {
    // Pass every 20 ticks, nidus chunk +30 per pass; reach 3 chunks (targets 70, 40, 10), so spreadStep 10 is exact.
    private static final SpreadSettings SETTINGS = new SpreadSettings(20, 30, 10, 30, 40, 4096, 64);

    private PublicApiTests() {
    }

    /** Records events of {@code type} matching {@code filter} while {@code body} runs; always unregisters. */
    private static <T extends Event> List<T> capture(Class<T> type, java.util.function.Predicate<T> filter,
                                                     Consumer<T> react, Runnable body) {
        List<T> events = new ArrayList<>();
        Consumer<T> listener = event -> {
            if (filter.test(event)) {
                events.add(event);
                react.accept(event);
            }
        };
        NeoForge.EVENT_BUS.addListener(type, listener);
        try {
            body.run();
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        return events;
    }

    private static <T extends Event> List<T> capture(Class<T> type, java.util.function.Predicate<T> filter, Runnable body) {
        return capture(type, filter, event -> {
        }, body);
    }

    /** Host events are abstract on the bus, so this listens to both subclasses into one list, in posting order. */
    private static List<HostEvent> captureHost(java.util.function.Predicate<HostEvent> filter, Consumer<HostEvent> react,
                                               Runnable body) {
        List<HostEvent> events = new ArrayList<>();
        Consumer<HostCorruptedEvent> corrupted = event -> {
            if (filter.test(event)) {
                events.add(event);
                react.accept(event);
            }
        };
        Consumer<HostCleansedEvent> cleansed = event -> {
            if (filter.test(event)) {
                events.add(event);
                react.accept(event);
            }
        };
        NeoForge.EVENT_BUS.addListener(HostCorruptedEvent.class, corrupted);
        NeoForge.EVENT_BUS.addListener(HostCleansedEvent.class, cleansed);
        try {
            body.run();
        } finally {
            NeoForge.EVENT_BUS.unregister(corrupted);
            NeoForge.EVENT_BUS.unregister(cleansed);
        }
        return events;
    }

    private static void assertHostEvent(GameTestHelper helper, HostEvent event, Class<?> type, int oldLevel, int newLevel,
                                        CorruptionCause cause) {
        List<? extends Integer> thresholds = RemissionCommonConfig.STAGE_THRESHOLDS.get();
        String what = type.getSimpleName() + " " + oldLevel + " -> " + newLevel;
        helper.assertTrue(type.isInstance(event), what + ": got " + event.getClass().getSimpleName());
        helper.assertValueEqual(event.getOldLevel(), oldLevel, what + " old level");
        helper.assertValueEqual(event.getNewLevel(), newLevel, what + " new level");
        helper.assertValueEqual(event.getOldStage(), CorruptionState.stageFor(oldLevel, thresholds), what + " old stage");
        helper.assertValueEqual(event.getNewStage(), CorruptionState.stageFor(newLevel, thresholds), what + " new stage");
        helper.assertValueEqual(event.getCause(), cause, what + " cause");
    }

    @GameTest(template = "empty")
    public static void hostEventsFireAfterTheChange(GameTestHelper helper) {
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1);
        List<Integer> seenLevels = new ArrayList<>();
        List<HostEvent> events = captureHost(event -> event.getEntity() == pig,
                event -> seenLevels.add(CorruptionQueries.hostLevel(event.getEntity())), () -> {
                    Corruption.set(pig, 50, CorruptionCause.COMMAND);
                    Corruption.set(pig, 80);
                    Corruption.set(pig, 80);
                    Corruption.set(pig, 30, CorruptionCause.COMMAND);
                    Corruption.set(pig, 0, CorruptionCause.COMMAND);
                    Corruption.set(pig, 0);
                });
        helper.assertValueEqual(events.size(), 4, "host events for 4 changes and 2 no-ops");
        assertHostEvent(helper, events.get(0), HostCorruptedEvent.class, 0, 50, CorruptionCause.COMMAND);
        helper.assertTrue(((HostCorruptedEvent) events.get(0)).becameHost(), "first infection did not become a host");
        assertHostEvent(helper, events.get(1), HostCorruptedEvent.class, 50, 80, CorruptionCause.OTHER);
        helper.assertFalse(((HostCorruptedEvent) events.get(1)).becameHost(), "a host became a host again");
        assertHostEvent(helper, events.get(2), HostCleansedEvent.class, 80, 30, CorruptionCause.COMMAND);
        helper.assertFalse(((HostCleansedEvent) events.get(2)).isFullyCleansed(), "partial cleanse reported full");
        assertHostEvent(helper, events.get(3), HostCleansedEvent.class, 30, 0, CorruptionCause.COMMAND);
        helper.assertTrue(((HostCleansedEvent) events.get(3)).isFullyCleansed(), "cleanse to 0 not reported full");
        helper.assertValueEqual(seenLevels, List.of(50, 80, 30, 0), "levels listeners saw (posted after the change)");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void hostEventsCarryTheCause(GameTestHelper helper) {
        Mob victim = helper.spawnWithNoFreeWill(EntityType.SHEEP, 1, 1, 1);
        LivingEntity boss = helper.spawnWithNoFreeWill(EntityType.WITHER, 3, 1, 3);
        List<HostEvent> events = captureHost(event -> event.getEntity() == victim || event.getEntity() == boss, event -> {
                }, () -> {
                    Infection.infectOnKill(victim, Optional.empty());
                    Corruption.set(boss, 90, CorruptionCause.COMMAND);
                    helper.getLevel().getServer().getCommands().performPrefixedCommand(
                            helper.getLevel().getServer().createCommandSourceStack().withSuppressedOutput()
                                    .withPosition(victim.position()),
                            "remission cleanse @e[type=minecraft:sheep,distance=..0.5]");
                });
        int initial = Infection.initialLevel();
        helper.assertValueEqual(events.size(), 2, "events (immune boss posts none)");
        assertHostEvent(helper, events.get(0), HostCorruptedEvent.class, 0, initial, CorruptionCause.INFECTION);
        assertHostEvent(helper, events.get(1), HostCleansedEvent.class, initial, 0, CorruptionCause.COMMAND);
        boss.discard();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void hostQueries(GameTestHelper helper) {
        LivingEntity cow = helper.spawnWithNoFreeWill(EntityType.COW, 1, 1, 1);
        ItemEntity item = helper.spawnItem(Items.STICK, 2, 1, 2);
        List<? extends Integer> thresholds = RemissionCommonConfig.STAGE_THRESHOLDS.get();
        helper.assertFalse(CorruptionQueries.isHost(cow), "clean cow is a host");
        helper.assertFalse(CorruptionQueries.isHost(item), "item entity is a host");
        helper.assertValueEqual(CorruptionQueries.hostLevel(item), 0, "item entity level");
        helper.assertValueEqual(CorruptionQueries.hostStage(item), 0, "item entity stage");
        int[] seen = new int[thresholds.size() + 1];
        for (int level = 0; level <= CorruptionQueries.MAX_LEVEL; level++) {
            Corruption.set(cow, level);
            int stage = CorruptionState.stageFor(level, thresholds);
            seen[stage]++;
            helper.assertValueEqual(CorruptionQueries.hostLevel(cow), level, "cow level");
            helper.assertValueEqual(CorruptionQueries.hostStage(cow), stage, "cow stage at level " + level);
            helper.assertValueEqual(CorruptionQueries.isHost(cow), stage > 0, "cow isHost at level " + level);
        }
        for (int stage = 0; stage < seen.length; stage++) {
            helper.assertTrue(seen[stage] > 0, "host stage " + stage + " never reached");
        }
        helper.succeed();
    }

    /** Saves the chunk_corruption attachment of every chunk within {@code radius} of {@code centre}; returns a restorer. */
    private static Runnable saveLand(ServerLevel level, ChunkPos centre, int radius) {
        Map<ChunkPos, Optional<ChunkCorruption>> saved = new HashMap<>();
        List<ChunkPos> forced = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(centre.x + dx, centre.z + dz);
                if (Math.abs(dx) + Math.abs(dz) <= 2 && level.setChunkForced(pos.x, pos.z, true)) {
                    forced.add(pos);
                }
                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk == null && forced.contains(pos)) {
                    chunk = level.getChunk(pos.x, pos.z);
                }
                if (chunk != null) {
                    saved.put(pos, Optional.ofNullable(chunk.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION)));
                }
            }
        }
        return () -> {
            saved.forEach((pos, state) -> {
                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk != null) {
                    state.ifPresentOrElse(s -> chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, s),
                            () -> chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION));
                }
            });
            forced.forEach(pos -> level.setChunkForced(pos.x, pos.z, false));
        };
    }

    private static void setLevel(ServerLevel level, ChunkPos pos, int value, long time) {
        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        if (value == 0) {
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
        } else {
            chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(value, time));
        }
    }

    /** First game time at or after {@code time} that starts a pass. */
    private static long aligned(long time) {
        return (Math.floorDiv(time, SETTINGS.intervalTicks()) + 1) * SETTINGS.intervalTicks();
    }

    private static void runPasses(SpreadEngine engine, long base, int passes) {
        for (long t = base; t < base + (long) passes * SETTINGS.intervalTicks(); t++) {
            engine.tick(t);
        }
    }

    /** Runs {@code body} with a nidus in the test area and its land saved; always removes the nidus and restores land. */
    private static void withNidus(GameTestHelper helper, java.util.function.BiConsumer<BlockPos, ChunkPos> body) {
        ServerLevel level = helper.getLevel();
        BlockPos nidus = helper.absolutePos(new BlockPos(1, 1, 1));
        ChunkPos origin = new ChunkPos(nidus);
        Runnable restore = saveLand(level, origin, SETTINGS.radius());
        try {
            helper.setBlock(1, 1, 1, RemissionBlocks.NIDUS.get());
            body.accept(nidus, origin);
        } finally {
            helper.setBlock(1, 1, 1, Blocks.AIR);
            restore.run();
        }
    }

    @GameTest(template = "empty")
    public static void chunkStageChangedFiresPerStage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        withNidus(helper, (nidus, origin) -> {
            setLevel(level, origin, 0, 0L);
            SpreadEngine engine = FesteringLand.newEngine(level, () -> SETTINGS);
            long base = aligned(level.getGameTime() + 100_000L);
            List<ChunkStageChangedEvent> events = capture(ChunkStageChangedEvent.class,
                    event -> event.getChunk().equals(origin), () -> runPasses(engine, base, 5));
            // 0 -> 30 -> 60 -> 90 -> 100 -> 100: stages 0 -> 2 -> 3 -> 4, then no change within stage 4.
            int[][] expected = {{0, 30}, {30, 60}, {60, 90}};
            helper.assertValueEqual(events.size(), expected.length, "stage changes of the nidus chunk");
            for (int i = 0; i < expected.length; i++) {
                ChunkStageChangedEvent event = events.get(i);
                helper.assertTrue(event.getLevel() == level, "event level");
                helper.assertValueEqual(event.getOldLevel(), expected[i][0], "old level of change " + i);
                helper.assertValueEqual(event.getNewLevel(), expected[i][1], "new level of change " + i);
                helper.assertValueEqual(event.getOldStage(), CorruptionQueries.chunkStageFor(expected[i][0]), "old stage " + i);
                helper.assertValueEqual(event.getNewStage(), CorruptionQueries.chunkStageFor(expected[i][1]), "new stage " + i);
            }
            helper.assertValueEqual(CorruptionQueries.chunkLevel(level, origin), 100, "nidus chunk level");

            // Catch-up posts one event per chunk however many passes it applies.
            ChunkPos neighbour = new ChunkPos(origin.x + 1, origin.z);
            setLevel(level, neighbour, 0, 0L);
            long later = base + 1000L * SETTINGS.intervalTicks();
            List<ChunkStageChangedEvent> catchUp = capture(ChunkStageChangedEvent.class,
                    event -> event.getChunk().equals(neighbour), () -> engine.catchUp(neighbour.toLong(), later, SETTINGS));
            helper.assertValueEqual(catchUp.size(), 1, "stage events for one catch-up");
            helper.assertValueEqual(catchUp.getFirst().getNewLevel(), SETTINGS.target(1), "catch-up level");
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nidusDestroyedFires(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos edited = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.setBlock(1, 1, 1, RemissionBlocks.NIDUS.get());
        List<NidusDestroyedEvent> events = capture(NidusDestroyedEvent.class,
                event -> event.getPos().equals(pos) || event.getPos().equals(edited), () -> {
                    helper.destroyBlock(new BlockPos(1, 1, 1));
                    // An index entry whose block went away without onRemove is dropped (and reported) by the next pass.
                    NidusIndex.get(level).add(edited, level.getGameTime());
                    SpreadEngine engine = FesteringLand.newEngine(level, () -> SETTINGS);
                    engine.tick(aligned(level.getGameTime()));
                });
        helper.assertValueEqual(events.stream().map(NidusDestroyedEvent::getPos).toList(), List.of(pos, edited), "destroyed nidi");
        helper.assertTrue(events.stream().allMatch(event -> event.getLevel() == level), "event level");
        helper.assertFalse(NidusIndex.get(level).contains(edited), "edited-away nidus still indexed");
        helper.succeed();
    }

    /** Level the chunk east of the nidus reaches after {@code passes} live passes with {@code listener} reacting. */
    private static int spreadEast(GameTestHelper helper, int passes, Consumer<StrainSpreadAttemptEvent> listener,
                                  List<StrainSpreadAttemptEvent> seen) {
        ServerLevel level = helper.getLevel();
        int[] result = new int[1];
        withNidus(helper, (nidus, origin) -> {
            ChunkPos east = new ChunkPos(origin.x + 1, origin.z);
            ChunkPos further = new ChunkPos(origin.x + 2, origin.z);
            long base = aligned(level.getGameTime() + 100_000L);
            setLevel(level, origin, 100, base);
            setLevel(level, east, 0, 0L);
            setLevel(level, further, 0, 0L);
            SpreadEngine engine = FesteringLand.newEngine(level, () -> SETTINGS);
            seen.addAll(capture(StrainSpreadAttemptEvent.class, event -> event.getTo().equals(east), listener,
                    () -> runPasses(engine, base, passes)));
            result[0] = CorruptionQueries.chunkLevel(level, east);
            if (seen.stream().anyMatch(StrainSpreadAttemptEvent::isCanceled)) {
                helper.assertValueEqual(CorruptionQueries.chunkLevel(level, further), 0, "spread past a cancelled chunk");
            }
        });
        return result[0];
    }

    @GameTest(template = "empty")
    public static void spreadAttemptPayloadAndCancel(GameTestHelper helper) {
        List<StrainSpreadAttemptEvent> seen = new ArrayList<>();
        int open = spreadEast(helper, 4, event -> {
        }, seen);
        helper.assertValueEqual(open, 4 * SETTINGS.spreadStep(), "level after 4 open passes");
        helper.assertValueEqual(seen.size(), 4, "spread attempts into the chunk");
        StrainSpreadAttemptEvent first = seen.getFirst();
        ChunkPos origin = new ChunkPos(helper.absolutePos(new BlockPos(1, 1, 1)));
        helper.assertValueEqual(first.getFrom(), origin, "from chunk");
        helper.assertValueEqual(first.getTo(), new ChunkPos(origin.x + 1, origin.z), "to chunk");
        helper.assertValueEqual(first.getAmount(), SETTINGS.spreadStep(), "amount");
        helper.assertFalse(first.isCatchUp(), "live step reported as catch-up");
        helper.assertValueEqual(first.getRateMultiplier(), 1.0, "default multiplier");
        helper.assertTrue(first.getLevel() == helper.getLevel(), "event level");

        seen.clear();
        int cancelled = spreadEast(helper, 4, event -> event.setCanceled(true), seen);
        helper.assertValueEqual(cancelled, 0, "level after 4 cancelled passes");
        helper.assertValueEqual(seen.size(), 4, "cancelled attempts");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void spreadAttemptMultiplierScalesRate(GameTestHelper helper) {
        List<StrainSpreadAttemptEvent> seen = new ArrayList<>();
        int step = SETTINGS.spreadStep();
        helper.assertValueEqual(spreadEast(helper, 4, event -> event.setRateMultiplier(0.5), seen), 4 * step / 2, "half rate");
        helper.assertValueEqual(spreadEast(helper, 2, event -> event.setRateMultiplier(2.0), seen), 4 * step, "double rate");
        helper.assertValueEqual(spreadEast(helper, 3, event -> event.setRateMultiplier(0), seen), 0, "zero rate");
        // Listeners compose by multiplying: 0.5 * 0.5 = a quarter of 10 per pass, the fraction resolved per pass.
        Consumer<StrainSpreadAttemptEvent> quarter = event -> event.setRateMultiplier(event.getRateMultiplier() * 0.5 * 0.5);
        int slow = spreadEast(helper, 20, quarter, seen);
        helper.assertTrue(slow > 20 * 2 && slow < 20 * 3, "quarter rate over 20 passes (2.5 per pass): " + slow);
        helper.assertValueEqual(spreadEast(helper, 20, quarter, seen), slow, "fractional rate is deterministic");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void spreadAttemptDuringCatchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        withNidus(helper, (nidus, origin) -> {
            ChunkPos east = new ChunkPos(origin.x + 1, origin.z);
            setLevel(level, origin, 100, 0L);
            setLevel(level, east, 0, 0L);
            long since = NidusIndex.get(level).nidi().stream().filter(n -> n.pos().equals(nidus)).findFirst().orElseThrow().since();
            long now = since + 3L * SETTINGS.intervalTicks();
            SpreadEngine engine = FesteringLand.newEngine(level, () -> SETTINGS);
            List<StrainSpreadAttemptEvent> events = capture(StrainSpreadAttemptEvent.class,
                    event -> event.getTo().equals(east), event -> event.setRateMultiplier(0.5),
                    () -> engine.catchUp(east.toLong(), now, SETTINGS));
            helper.assertValueEqual(events.size(), 1, "one attempt per nidus in a catch-up");
            helper.assertTrue(events.getFirst().isCatchUp(), "catch-up step not flagged");
            helper.assertValueEqual(events.getFirst().getAmount(), 3 * SETTINGS.spreadStep(), "catch-up amount for 3 passes");
            helper.assertValueEqual(CorruptionQueries.chunkLevel(level, east), 3 * SETTINGS.spreadStep() / 2, "halved catch-up");
        });
        helper.succeed();
    }

    /** Cancelling every step into the chunk east of a nidus keeps the chunk behind it clean after it reloads. */
    @GameTest(template = "empty")
    public static void cancelledSpreadHoldsThroughCatchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        withNidus(helper, (nidus, origin) -> {
            ChunkPos east = new ChunkPos(origin.x + 1, origin.z);
            ChunkPos behind = new ChunkPos(origin.x + 2, origin.z);
            long base = aligned(level.getGameTime() + 100_000L);
            setLevel(level, origin, 100, base);
            setLevel(level, east, 0, 0L);
            setLevel(level, behind, 0, 0L);
            SpreadEngine engine = FesteringLand.newEngine(level, () -> SETTINGS);
            List<StrainSpreadAttemptEvent> attempts = capture(StrainSpreadAttemptEvent.class,
                    event -> event.getTo().equals(east) || event.getTo().equals(behind),
                    event -> {
                        if (event.getTo().equals(east)) {
                            event.setCanceled(true);
                        }
                    }, () -> {
                        runPasses(engine, base, 4);
                        // The chunk behind the firebreak reloads after a long absence.
                        engine.catchUp(behind.toLong(), base + 1000L * SETTINGS.intervalTicks(), SETTINGS);
                    });
            helper.assertValueEqual(CorruptionQueries.chunkLevel(level, east), 0, "firebreak chunk");
            helper.assertValueEqual(CorruptionQueries.chunkLevel(level, behind), 0, "chunk behind the firebreak after catch-up");
            helper.assertTrue(attempts.stream().allMatch(event -> event.getTo().equals(east)),
                    "a step from the held firebreak chunk was attempted");
            helper.assertValueEqual(attempts.size(), 4, "cancelled live attempts into the firebreak chunk");
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void landQueries(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        ChunkPos chunk = new ChunkPos(pos);
        Runnable restore = saveLand(level, chunk, 0);
        List<? extends Integer> thresholds = RemissionCommonConfig.FESTERING_STAGE_THRESHOLDS.get();
        Supplier<String> where = () -> " at " + chunk;
        try {
            int[] seen = new int[thresholds.size() + 1];
            for (int value = 0; value <= CorruptionQueries.MAX_LEVEL; value++) {
                setLevel(level, chunk, value, level.getGameTime());
                int stage = CorruptionState.stageFor(value, thresholds);
                seen[stage]++;
                helper.assertValueEqual(CorruptionQueries.chunkLevel(level, chunk), value, "chunk level" + where.get());
                helper.assertValueEqual(CorruptionQueries.levelAt(level, pos), value, "level at pos");
                helper.assertValueEqual(CorruptionQueries.chunkStage(level, chunk), stage, "chunk stage at level " + value);
                helper.assertValueEqual(CorruptionQueries.stageAt(level, pos), stage, "stage at pos at level " + value);
                helper.assertValueEqual(CorruptionQueries.chunkStageFor(value), stage, "stage for level " + value);
            }
            for (int stage = 0; stage < seen.length; stage++) {
                helper.assertTrue(seen[stage] > 0, "chunk stage " + stage + " never reached");
            }
            helper.assertValueEqual(CorruptionQueries.maxChunkStage(), thresholds.size(), "max chunk stage");
            helper.assertValueEqual(CorruptionQueries.chunkStage(level, new ChunkPos(1_000_000, 1_000_000)), 0, "unloaded chunk");
        } finally {
            restore.run();
        }
        helper.succeed();
    }
}
