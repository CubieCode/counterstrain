package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.ChunkEnteredRemissionEvent;
import com.cubiecode.remission.api.ChunkStageChangedEvent;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.PurificationCause;
import com.cubiecode.remission.network.FesteringMapCache;
import com.cubiecode.remission.commands.RemissionCommands;
import com.cubiecode.remission.network.FesteringMapPayload;
import com.cubiecode.remission.network.RemissionNetwork;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.world.ChunkCorruption;
import com.cubiecode.remission.world.NidusIndex;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Quest-fired events and the map sync (P8.2): {@code /remission nidus seed} and {@code /remission region advance} through
 * the server's command dispatcher, the {@code remission:festering_map} payload's wire format, the server queueing a sent
 * chunk's stage for its player, and the client cache's change reports. Region tests use force-loaded chunks
 * {@link #FAR} or more chunks east of the test grid and restore them.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuestEventTests {
    private static final String BATCH = "quest_events";
    private static final int FAR = 224;

    private QuestEventTests() {
    }

    // ---- /remission nidus seed ---------------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = BATCH)
    public static void nidusSeedPlacesAnIndexedNidus(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos chest = helper.absolutePos(new BlockPos(0, 1, 1));
        BlockPos bedrock = helper.absolutePos(new BlockPos(2, 1, 1));
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(bedrock, Blocks.BEDROCK.defaultBlockState());
        Feedback feedback = new Feedback();
        try {
            helper.assertValueEqual(run(helper, feedback, "remission nidus seed " + coords(pos)), 1, "seed result");
            helper.assertBlockPresent(RemissionBlocks.NIDUS.get(), new BlockPos(1, 1, 1));
            helper.assertTrue(NidusIndex.get(level).contains(pos), "seeded nidus is not indexed");
            feedback.assertOnly(helper, "commands.remission.nidus.seed.success", pos.getX(), pos.getY(), pos.getZ());

            assertFails(helper, feedback, "remission nidus seed " + coords(pos), "commands.remission.nidus.seed.failed.present");
            assertFails(helper, feedback, "remission nidus seed " + coords(chest), "commands.remission.nidus.seed.failed.blocked");
            assertFails(helper, feedback, "remission nidus seed " + coords(bedrock), "commands.remission.nidus.seed.failed.blocked");
            helper.assertBlockPresent(Blocks.CHEST, new BlockPos(0, 1, 1));
            helper.assertBlockPresent(Blocks.BEDROCK, new BlockPos(2, 1, 1));
            // Far outside any loaded chunk: vanilla's unloaded position error, nothing placed or loaded.
            BlockPos unloaded = pos.offset(16 * 4096, 0, 0);
            assertFails(helper, feedback, "remission nidus seed " + coords(unloaded), "argument.pos.unloaded");
            helper.assertTrue(!level.hasChunk(unloaded.getX() >> 4, unloaded.getZ() >> 4), "seed loaded a chunk");
        } finally {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(chest, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(bedrock, Blocks.AIR.defaultBlockState());
        }
        helper.assertTrue(!NidusIndex.get(level).contains(pos), "removed nidus is still indexed");
        helper.succeed();
    }

    // ---- /remission region advance -----------------------------------------------------------------------------------

    /**
     * A 5x5 square of force-loaded chunks: radius 1 reaches the centre and its four edge neighbours (Manhattan), never the
     * diagonals; stages add up, cap at the last stage, and each change posts one stage event; chunks outside the square
     * are not loaded and stay so.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void regionAdvanceRaisesStages(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos centre = new ChunkPos(new ChunkPos(helper.absolutePos(BlockPos.ZERO)).x + FAR, 0);
        List<LevelChunk> chunks = forceLoad(level, centre, 2);
        List<ChunkStageChangedEvent> events = new ArrayList<>();
        Consumer<ChunkStageChangedEvent> listener = events::add;
        NeoForge.EVENT_BUS.addListener(ChunkStageChangedEvent.class, listener);
        Feedback feedback = new Feedback();
        String pos = centre.getMiddleBlockX() + " 64 " + centre.getMiddleBlockZ();
        List<ChunkPos> reach = List.of(centre, offset(centre, 1, 0), offset(centre, -1, 0), offset(centre, 0, 1),
                offset(centre, 0, -1));
        try {
            int max = CorruptionQueries.maxChunkStage();
            helper.assertValueEqual(run(helper, feedback, "remission region advance " + pos + " 1"), 5, "chunks advanced");
            feedback.assertOnly(helper, "commands.remission.region.advance.success", 5, 1, centre.x, centre.z);
            for (ChunkPos chunk : reach) {
                helper.assertValueEqual(CorruptionQueries.chunkStage(level, chunk), 1, "stage after one advance at " + chunk);
                helper.assertValueEqual(CorruptionQueries.chunkLevel(level, chunk),
                        firstLevelOf(1), "lowest level of stage 1 at " + chunk);
            }
            helper.assertValueEqual(CorruptionQueries.chunkLevel(level, offset(centre, 1, 1)), 0, "diagonal neighbour");
            helper.assertValueEqual(events.size(), 5, "stage events");
            helper.assertTrue(events.stream().allMatch(e -> e.getOldStage() == 0 && e.getNewStage() == 1), "event stages");

            events.clear();
            helper.assertValueEqual(run(helper, feedback, "remission region advance " + pos + " 0 2"), 1, "centre only");
            helper.assertValueEqual(CorruptionQueries.chunkStage(level, centre), 3, "centre after two more stages");
            helper.assertValueEqual(events.size(), 1, "stage events for the centre");

            // The largest int: stages are clamped before they are added, so nothing overflows into a skipped chunk.
            helper.assertValueEqual(run(helper, feedback, "remission region advance " + pos + " 2 " + Integer.MAX_VALUE), 13,
                    "chunks raised to the last stage (radius 2 holds 13 chunks)");
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int expected = Math.abs(dx) + Math.abs(dz) <= 2 ? max : 0;
                    helper.assertValueEqual(CorruptionQueries.chunkStage(level, offset(centre, dx, dz)), expected,
                            "stage at offset " + dx + ", " + dz);
                }
            }
            assertFails(helper, feedback, "remission region advance " + pos + " 2", "commands.remission.region.advance.failed");

            // Radius 3 adds the ring at distance 3: the square's eight remaining chunks and whichever chunks just outside it
            // the forced ones keep loaded. Unloaded chunks in the ring are neither loaded nor changed.
            List<ChunkPos> loaded = new ArrayList<>();
            List<ChunkPos> unloaded = new ArrayList<>();
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz : new int[] {3 - Math.abs(dx), Math.abs(dx) - 3}) {
                    ChunkPos chunk = offset(centre, dx, dz);
                    if (!loaded.contains(chunk) && !unloaded.contains(chunk)) {
                        (level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null ? loaded : unloaded).add(chunk);
                    }
                }
            }
            helper.assertTrue(loaded.size() >= 8, "square chunks at distance 3 are not loaded");
            helper.assertValueEqual(run(helper, feedback, "remission region advance " + pos + " 3"), loaded.size(),
                    "loaded chunks at distance 3");
            helper.assertValueEqual(CorruptionQueries.chunkStage(level, offset(centre, 2, 1)), 1, "stage at offset 2, 1");
            helper.assertValueEqual(CorruptionQueries.chunkStage(level, offset(centre, 2, 2)), 0, "stage at offset 2, 2");
            for (ChunkPos chunk : unloaded) {
                helper.assertTrue(level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null, "advance loaded " + chunk);
            }
            loaded.forEach(chunk -> level.getChunkSource().getChunkNow(chunk.x, chunk.z)
                    .removeData(RemissionAttachments.CHUNK_CORRUPTION));
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            release(level, chunks);
        }
        helper.succeed();
    }

    // ---- the festering map payload and sync --------------------------------------------------------------------------

    @GameTest(template = "empty", batch = BATCH)
    public static void festeringMapPayloadRoundTrips(GameTestHelper helper) {
        Long2ByteLinkedOpenHashMap stages = new Long2ByteLinkedOpenHashMap();
        stages.put(ChunkPos.asLong(3, -7), (byte) 2);
        stages.put(ChunkPos.asLong(-100000, 52), (byte) 0);
        stages.put(ChunkPos.asLong(0, 0), (byte) 4);
        stages.put(ChunkPos.asLong(7, 7), (byte) FesteringMapPayload.state(0, true));
        FesteringMapPayload sent = new FesteringMapPayload(Level.NETHER, stages);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        FesteringMapPayload.STREAM_CODEC.encode(buf, sent);
        FesteringMapPayload received = FesteringMapPayload.STREAM_CODEC.decode(buf);
        helper.assertValueEqual(buf.readableBytes(), 0, "bytes left after decoding");
        helper.assertValueEqual(received.dimension(), Level.NETHER, "dimension");
        helper.assertValueEqual(new ArrayList<>(received.states().long2ByteEntrySet()).toString(),
                new ArrayList<>(stages.long2ByteEntrySet()).toString(), "entries in order");
        helper.assertValueEqual(received.type().id(), Remission.id("festering_map"), "payload id");
        helper.assertValueEqual(RemissionNetwork.PROTOCOL_VERSION, "3", "protocol version with the festering map payload");
        helper.succeed();
    }

    /**
     * A chunk sent to a player queues its stage, 0 included, and the next server tick sends one payload with every
     * queued chunk of that dimension to that player only.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void sentChunksReachTheirPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LevelChunk festering = level.getChunkAt(helper.absolutePos(BlockPos.ZERO));
        LevelChunk clean = level.getChunk(festering.getPos().x + 1, festering.getPos().z);
        LevelChunk cleansed = level.getChunk(festering.getPos().x, festering.getPos().z + 1);
        ChunkCorruption before = festering.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
        ChunkCorruption cleanBefore = clean.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
        ChunkCorruption cleansedBefore = cleansed.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
        Long remissionBefore = cleansed.getExistingDataOrNull(RemissionAttachments.CHUNK_REMISSION);
        int stage = 2;
        List<FesteringMapPayload> received = new ArrayList<>();
        ServerPlayer player = player(helper, received);
        List<FesteringMapPayload> other = new ArrayList<>();
        player(helper, other);
        festering.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(firstLevelOf(stage), level.getGameTime()));
        clean.removeData(RemissionAttachments.CHUNK_CORRUPTION);
        cleansed.removeData(RemissionAttachments.CHUNK_CORRUPTION);
        cleansed.setData(RemissionAttachments.CHUNK_REMISSION, level.getGameTime());
        try {
            NeoForge.EVENT_BUS.post(new ChunkWatchEvent.Sent(player, festering, level));
            NeoForge.EVENT_BUS.post(new ChunkWatchEvent.Sent(player, clean, level));
            NeoForge.EVENT_BUS.post(new ChunkWatchEvent.Sent(player, cleansed, level));
        } finally {
            restore(festering, before);
            restore(clean, cleanBefore);
            restore(cleansed, cleansedBefore);
            if (remissionBefore == null) {
                cleansed.removeData(RemissionAttachments.CHUNK_REMISSION);
            } else {
                cleansed.setData(RemissionAttachments.CHUNK_REMISSION, remissionBefore);
            }
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertFalse(received.isEmpty(), "no festering map payload"))
                .thenExecute(() -> {
                    helper.assertValueEqual(received.size(), 1, "payloads");
                    FesteringMapPayload payload = received.getFirst();
                    helper.assertValueEqual(payload.dimension(), level.dimension(), "dimension");
                    helper.assertValueEqual(payload.states().size(), 3, "entries");
                    helper.assertValueEqual((int) payload.states().get(festering.getPos().toLong()), stage, "festering chunk");
                    helper.assertTrue(payload.states().containsKey(clean.getPos().toLong()), "clean chunk not sent");
                    helper.assertValueEqual((int) payload.states().get(clean.getPos().toLong()), 0, "clean chunk");
                    helper.assertValueEqual(payload.states().get(cleansed.getPos().toLong()) & 0xFF,
                            FesteringMapPayload.REMISSION, "chunk in remission");
                    helper.assertTrue(other.isEmpty(), "another player got the payload");
                })
                .thenSucceed();
    }

    /**
     * A stage change and an entry into remission reach a player watching the chunk (in the chunk map, the chunk sent) on
     * the next tick, with the new state, and not a player who does not watch it. The watcher is tracked through the chunk
     * map's own player status (package-private, so by reflection; gametests run with Mojang names) and its chunk sender
     * sends its nearest chunks, the 3x3 around it among them.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void watchersGetStageAndRemissionChanges(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        LevelChunk staged = level.getChunkAt(origin);
        LevelChunk purified = level.getChunk(staged.getPos().x + 1, staged.getPos().z);
        Long remissionBefore = purified.getExistingDataOrNull(RemissionAttachments.CHUNK_REMISSION);
        List<FesteringMapPayload> received = new ArrayList<>();
        ServerPlayer watcher = player(helper, received);
        watcher.moveTo(staged.getPos().getMiddleBlockX() + 0.5, origin.getY(), staged.getPos().getMiddleBlockZ() + 0.5);
        List<FesteringMapPayload> other = new ArrayList<>();
        player(helper, other);
        track(level, watcher, true);
        try {
            watcher.connection.chunkSender.sendNextChunks(watcher);
            helper.assertTrue(level.getChunkSource().chunkMap.getPlayers(staged.getPos(), false).contains(watcher),
                    "watcher does not watch the staged chunk");
            helper.assertTrue(level.getChunkSource().chunkMap.getPlayers(purified.getPos(), false).contains(watcher),
                    "watcher does not watch the purified chunk");
            NeoForge.EVENT_BUS.post(new ChunkStageChangedEvent(level, staged.getPos(), 0, 30, 0, 2));
            purified.setData(RemissionAttachments.CHUNK_REMISSION, level.getGameTime());
            NeoForge.EVENT_BUS.post(new ChunkEnteredRemissionEvent(level, purified.getPos(), PurificationCause.OTHER, null));
        } finally {
            // The events queue for the watcher at once; the payload goes out through its connection next tick.
            track(level, watcher, false);
            if (remissionBefore == null) {
                purified.removeData(RemissionAttachments.CHUNK_REMISSION);
            } else {
                purified.setData(RemissionAttachments.CHUNK_REMISSION, remissionBefore);
            }
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertFalse(received.isEmpty(), "no festering map payload"))
                .thenExecute(() -> {
                    // One payload per tick and dimension; the chunks sent by sendNextChunks queue in the same tick.
                    helper.assertValueEqual(received.size(), 1, "payloads");
                    Long2ByteMap states = received.getFirst().states();
                    helper.assertValueEqual((int) states.get(staged.getPos().toLong()), 2, "staged chunk");
                    helper.assertValueEqual(states.get(purified.getPos().toLong()) & 0xFF, FesteringMapPayload.REMISSION,
                            "purified chunk");
                    helper.assertTrue(other.isEmpty(), "a player not watching got the payload");
                })
                .thenSucceed();
    }

    /** {@code ChunkMap#updatePlayerStatus}: adds the player to (or removes it from) the chunk map's tracking. */
    private static void track(ServerLevel level, ServerPlayer player, boolean track) {
        try {
            Method update = ChunkMap.class.getDeclaredMethod("updatePlayerStatus", ServerPlayer.class, boolean.class);
            update.setAccessible(true);
            update.invoke(level.getChunkSource().chunkMap, player, track);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot change the chunk map's tracking of " + player.getName().getString(), e);
        }
    }

    /** The client cache stores festering chunks and chunks in remission only and reports each real change once. */
    @GameTest(template = "empty", batch = BATCH)
    public static void clientCacheReportsChanges(GameTestHelper helper) {
        ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                Remission.id("test_map_cache"));
        List<String> changes = new ArrayList<>();
        FesteringMapCache.Listener listener = new FesteringMapCache.Listener() {
            @Override
            public void changed(ResourceKey<Level> dim, long chunk, int oldState, int newState) {
                if (dim.equals(dimension)) {
                    changes.add(ChunkPos.getX(chunk) + ":" + oldState + ">" + newState);
                }
            }

            @Override
            public void cleared() {
                changes.add("cleared");
            }
        };
        boolean wasEnabled = FesteringMapCache.enabled();
        FesteringMapCache.enable();
        FesteringMapCache.addListener(listener);
        try {
            cacheChanges(helper, dimension, changes);
        } finally {
            FesteringMapCache.removeListener(listener);
            if (!wasEnabled) {
                FesteringMapCache.disable();
            }
        }
        helper.succeed();
    }

    private static void cacheChanges(GameTestHelper helper, ResourceKey<Level> dimension, List<String> changes) {
        long a = ChunkPos.asLong(1, 0);
        long b = ChunkPos.asLong(2, 0);
        FesteringMapCache.apply(dimension, map(a, 1, b, 0));
        FesteringMapCache.apply(dimension, map(a, 1, b, 3));
        int remission = FesteringMapPayload.state(0, true);
        FesteringMapCache.apply(dimension, map(a, remission, b, 4));
        helper.assertValueEqual(changes, List.of("1:0>1", "2:0>3", "1:1>" + remission, "2:3>4"), "reported changes");
        helper.assertValueEqual(FesteringMapCache.state(dimension, a), remission, "chunk in remission");
        helper.assertTrue(FesteringMapCache.inRemission(remission) && FesteringMapCache.stageOf(remission) == 0,
                "remission state");
        helper.assertValueEqual(FesteringMapCache.stageOf(FesteringMapCache.state(dimension, b)), 4, "festering chunk");
        helper.assertValueEqual(FesteringMapCache.known(dimension).size(), 2, "stored chunks");
        FesteringMapCache.apply(dimension, map(b, 0, a, 0));
        helper.assertTrue(FesteringMapCache.known(dimension).isEmpty(), "dimension not emptied");
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static Long2ByteMap map(long first, int firstStage, long second, int secondStage) {
        Long2ByteLinkedOpenHashMap map = new Long2ByteLinkedOpenHashMap();
        map.put(first, (byte) firstStage);
        map.put(second, (byte) secondStage);
        return map;
    }

    /** The lowest Strain level at festering stage {@code stage} under the current config. */
    private static int firstLevelOf(int stage) {
        for (int value = 0; value <= CorruptionQueries.MAX_LEVEL; value++) {
            if (CorruptionQueries.chunkStageFor(value) >= stage) {
                return value;
            }
        }
        throw new AssertionError("no level reaches stage " + stage);
    }

    private static ChunkPos offset(ChunkPos chunk, int dx, int dz) {
        return new ChunkPos(chunk.x + dx, chunk.z + dz);
    }

    private static List<LevelChunk> forceLoad(ServerLevel level, ChunkPos centre, int radius) {
        List<LevelChunk> chunks = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                level.setChunkForced(centre.x + dx, centre.z + dz, true);
                LevelChunk chunk = level.getChunk(centre.x + dx, centre.z + dz);
                chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    private static void release(ServerLevel level, List<LevelChunk> chunks) {
        for (LevelChunk chunk : chunks) {
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
            level.setChunkForced(chunk.getPos().x, chunk.getPos().z, false);
        }
    }

    private static void restore(LevelChunk chunk, @Nullable ChunkCorruption before) {
        if (before == null) {
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
        } else {
            chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, before);
        }
    }

    /**
     * A server player, never added to the level, whose connection keeps the festering map payloads it is sent and drops
     * everything else (see SurgeTests).
     */
    private static ServerPlayer player(GameTestHelper helper, List<FesteringMapPayload> received) {
        MinecraftServer server = helper.getLevel().getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "map-test"), false);
        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        new ServerGamePacketListenerImpl(server, connection, player, cookie) {
            @Override
            public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
                if (packet instanceof ClientboundCustomPayloadPacket custom
                        && custom.payload() instanceof FesteringMapPayload payload) {
                    received.add(payload);
                }
            }
        };
        return player;
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static int run(GameTestHelper helper, Feedback feedback, String command) {
        try {
            return execute(helper, feedback, command);
        } catch (CommandSyntaxException e) {
            throw new AssertionError("/" + command + " failed: " + e.getMessage(), e);
        }
    }

    private static void assertFails(GameTestHelper helper, Feedback feedback, String command, String key) {
        try {
            execute(helper, feedback, command);
            helper.fail("/" + command + " succeeded");
        } catch (CommandSyntaxException e) {
            helper.assertValueEqual(translationKey(e.getRawMessage() instanceof Component c ? c : null), key,
                    "/" + command + " error");
        }
    }

    private static int execute(GameTestHelper helper, Feedback feedback, String command) throws CommandSyntaxException {
        feedback.messages.clear();
        CommandSourceStack source = new CommandSourceStack(feedback, Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO)),
                Vec2.ZERO, helper.getLevel(), RemissionCommands.PERMISSION_LEVEL, "gametest", Component.literal("gametest"),
                helper.getLevel().getServer(), null);
        return helper.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
    }

    @Nullable
    private static String translationKey(@Nullable Component component) {
        return component != null && component.getContents() instanceof TranslatableContents contents ? contents.getKey() : null;
    }

    /** Collects the command feedback sent to the source. */
    private static final class Feedback implements CommandSource {
        private final List<Component> messages = new ArrayList<>();

        void assertOnly(GameTestHelper helper, String key, Object... args) {
            helper.assertValueEqual(messages.size(), 1, "feedback messages");
            Component message = messages.getFirst();
            helper.assertValueEqual(translationKey(message), key, "feedback key");
            helper.assertValueEqual(List.of(((TranslatableContents) message.getContents()).getArgs()), List.of(args),
                    "feedback args");
        }

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
