package com.cubiecode.remission.surge;

import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.SurgeEndedEvent;
import com.cubiecode.remission.api.SurgeStartedEvent;
import com.cubiecode.remission.quest.QuestProgress;
import com.cubiecode.remission.world.Nidus;
import com.cubiecode.remission.world.NidusIndex;
import com.cubiecode.remission.world.SpreadSettings;
import it.unimi.dsi.fastutil.HashCommon;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Surges in one level. Ticked by {@link Surges} on the server thread.
 *
 * <p>Schedule: the first tick sets the next surge due {@code intervalTicks} later; the end of each surge sets it again.
 * When a surge is due (and {@code scheduled} is on) it starts as soon as a player is inside some nidus's area, checked
 * every second; the origin is picked among those nidi by a hash of the game time. At most one surge runs per level.
 *
 * <p>A surge's area is the spread reach of its origin nidus ({@link SpreadSettings#radius()} chunks, Manhattan). It
 * warns for {@code warningTicks}, then starts: it releases its miasma clouds, posts {@link SurgeStartedEvent} and, until
 * it ends, multiplies its nidus's spread steps ({@link #spreadFactor}) and spawns a wave of hosts near each player in
 * the area every {@link #WAVE_TICKS} (not on Peaceful; at most {@link #HOSTS_PER_TICK} per tick, and none while
 * {@code maxHosts} of its hosts are alive and loaded). Players in the area while it runs are caught in it; one who dies drops out for
 * good. At the end every caught player still online earns {@code remission:surge_survived}, then
 * {@link SurgeEndedEvent} is posted. Destroying the origin nidus ends it at once; a surge stopped or destroyed during
 * its warning never started and posts nothing. A nidus under attack starts its surges with no warning
 * ({@link #startNow}), replacing any surge in the level.
 */
public final class SurgeController {
    /** Ticks between host waves while a surge runs; the first wave comes when it starts. */
    public static final int WAVE_TICKS = 200;
    /** Ticks between area checks (players caught, boss bar, a due surge finding an origin). */
    public static final int CHECK_TICKS = 20;
    /** Clouds are released in festering chunks within this many chunks (each axis) of a player in the area. */
    public static final int CLOUD_RANGE = 3;
    /** Most clouds one surge releases, whatever its intensity. */
    public static final int MAX_CLOUDS = 32;
    /** Queued wave hosts spawned per tick, at most; the rest wait for later ticks. */
    public static final int HOSTS_PER_TICK = 2;

    private record HostRequest(BlockPos centre, int remaining) {
    }

    /** The players a controller sees. */
    public interface Players {
        /** Players in the controller's level. */
        List<ServerPlayer> inLevel();

        /** An online player by id, in any level, or null. */
        @Nullable
        ServerPlayer byId(UUID id);
    }

    private final ServerLevel level;
    private final SurgeData data;
    private final Supplier<SurgeSettings> settings;
    private final Supplier<SpreadSettings> spread;
    private final Players players;
    private final SurgeWarnings warnings = new SurgeWarnings();
    /** Wave hosts still to spawn; transient (a reload drops the rest of a wave). */
    private final Deque<HostRequest> pendingHosts = new ArrayDeque<>();

    public SurgeController(ServerLevel level, SurgeData data, Supplier<SurgeSettings> settings,
                           Supplier<SpreadSettings> spread, Players players) {
        this.level = level;
        this.data = data;
        this.settings = settings;
        this.spread = spread;
        this.players = players;
    }

    /** The players of a server level, as the live controllers see them. */
    public static Players levelPlayers(ServerLevel level) {
        return new Players() {
            @Override
            public List<ServerPlayer> inLevel() {
                return level.players();
            }

            @Override
            public ServerPlayer byId(UUID id) {
                return level.getServer().getPlayerList().getPlayer(id);
            }
        };
    }

    public SurgeData data() {
        return data;
    }

    /** The surge warning or running in this level, or null. */
    @Nullable
    public Surge current() {
        return data.current();
    }

    public void tick(long now) {
        SurgeSettings current = settings.get();
        Surge surge = data.current();
        if (surge == null) {
            if (data.nextDue() == SurgeData.UNSCHEDULED) {
                data.setNextDue(now + current.intervalTicks());
            } else if (current.scheduled() && now >= data.nextDue() && now % CHECK_TICKS == 0) {
                BlockPos origin = pickOrigin(now);
                if (origin != null) {
                    start(origin, 1, now);
                }
            }
            return;
        }
        if (!NidusIndex.get(level).contains(surge.origin())) {
            end(surge, SurgeEndedEvent.Reason.NIDUS_DESTROYED, now);
            return;
        }
        if (!surge.started()) {
            if (now >= surge.startTime()) {
                begin(surge, now);
            } else if ((now - surge.warningTime()) % CHECK_TICKS == 0) {
                warnings.update(inArea(surge.origin()), progress(now, surge.warningTime(), surge.startTime()));
            }
            return;
        }
        if (now >= surge.endTime()) {
            end(surge, SurgeEndedEvent.Reason.ELAPSED, now);
            return;
        }
        long running = now - surge.startTime();
        if (running % CHECK_TICKS == 0) {
            catchPlayers(surge, now);
        }
        if (running % WAVE_TICKS == 0) {
            wave(surge);
        }
        spawnPending(surge);
    }

    /**
     * Begins a surge's warning at {@code origin} (an indexed nidus) now. Returns false, changing nothing, if a surge is
     * already warning or running here or {@code origin} is not an indexed nidus.
     */
    public boolean start(BlockPos origin, int intensity, long now) {
        if (data.current() != null || !NidusIndex.get(level).contains(origin)) {
            return false;
        }
        SurgeSettings current = settings.get();
        Surge surge = Surge.warn(origin, Math.clamp(intensity, 1, Surge.MAX_INTENSITY), now, current.warningTicks(),
                current.durationTicks());
        data.setCurrent(surge);
        List<ServerPlayer> inArea = inArea(origin);
        warnings.warn(inArea);
        warnings.update(inArea, 1.0F);
        return true;
    }

    /**
     * Starts a surge from {@code origin} (an indexed nidus) now, with no warning: a nidus under attack fighting back
     * (P4.10). A surge already warning or running in this level, from any nidus, ends first
     * ({@link SurgeEndedEvent.Reason#SUPERSEDED}; a started one awards its survivors). Returns false, changing nothing, if
     * {@code origin} is not an indexed nidus.
     */
    public boolean startNow(BlockPos origin, int intensity, long now) {
        if (!NidusIndex.get(level).contains(origin)) {
            return false;
        }
        Surge current = data.current();
        if (current != null) {
            end(current, SurgeEndedEvent.Reason.SUPERSEDED, now);
        }
        Surge surge = Surge.warn(origin, Math.clamp(intensity, 1, Surge.MAX_INTENSITY), now, 0, settings.get().durationTicks());
        data.setCurrent(surge);
        begin(surge, now);
        return true;
    }

    /** Ends the current surge now ({@link SurgeEndedEvent.Reason#STOPPED}). Returns false if there was none. */
    public boolean stop(long now) {
        Surge surge = data.current();
        if (surge == null) {
            return false;
        }
        end(surge, SurgeEndedEvent.Reason.STOPPED, now);
        return true;
    }

    /** A player died: if they were caught in the current surge, they drop out of it for good. */
    public void playerDied(ServerPlayer player) {
        Surge surge = data.current();
        if (surge != null && surge.fall(player.getUUID())) {
            data.setDirty();
        }
    }

    /**
     * The factor on spread steps from {@code nidus} now: {@code spreadMultiplier * intensity} while a started surge from
     * that nidus runs, else 1.
     */
    public int spreadFactor(Nidus nidus) {
        Surge surge = data.current();
        if (surge == null || !surge.started() || !surge.origin().equals(nidus.pos())) {
            return 1;
        }
        return settings.get().spreadMultiplier() * surge.intensity();
    }

    /** Whether {@code chunk} is in the area of a surge from {@code origin}. */
    public boolean inArea(BlockPos origin, ChunkPos chunk) {
        ChunkPos from = new ChunkPos(origin);
        return Math.abs(chunk.x - from.x) + Math.abs(chunk.z - from.z) <= spread.get().radius();
    }

    /** Players inside the area of a surge from {@code origin} who can be caught in it (alive, not spectators). */
    public List<ServerPlayer> inArea(BlockPos origin) {
        List<ServerPlayer> found = new ArrayList<>();
        for (ServerPlayer player : players.inLevel()) {
            if (player.isAlive() && !player.isSpectator() && player.level() == level
                    && inArea(origin, player.chunkPosition())) {
                found.add(player);
            }
        }
        return found;
    }

    /** A nidus with a player in its area, chosen by a hash of {@code now}, or null if none has one. */
    @Nullable
    private BlockPos pickOrigin(long now) {
        List<BlockPos> candidates = new ArrayList<>();
        for (Nidus nidus : NidusIndex.get(level).nidi()) {
            if (!inArea(nidus.pos()).isEmpty()) {
                candidates.add(nidus.pos());
            }
        }
        return candidates.isEmpty() ? null : candidates.get(Math.floorMod(HashCommon.mix(now), candidates.size()));
    }

    private void begin(Surge surge, long now) {
        surge.markStarted();
        data.setDirty();
        List<ServerPlayer> inArea = catchPlayers(surge, now);
        warnings.started(inArea);
        releaseClouds(level, centres(surge, inArea), Math.min(MAX_CLOUDS, settings.get().clouds() * surge.intensity()), level.getRandom(),
                chunk -> inArea(surge.origin(), chunk));
        NeoForge.EVENT_BUS.post(new SurgeStartedEvent(level, surge.origin(), surge.intensity(), surge.endTime()));
        wave(surge);
        spawnPending(surge);
    }

    /** Adds the players in the area to the surge and refreshes the boss bar; returns those players. */
    private List<ServerPlayer> catchPlayers(Surge surge, long now) {
        List<ServerPlayer> inArea = inArea(surge.origin());
        boolean changed = false;
        for (ServerPlayer player : inArea) {
            changed |= surge.addParticipant(player.getUUID());
        }
        if (changed) {
            data.setDirty();
        }
        warnings.update(inArea, progress(now, surge.startTime(), surge.endTime()));
        return inArea;
    }

    /**
     * Queues a wave: {@code hostsPerWave * intensity} hosts around each player in the area. Nothing on Peaceful. The
     * queue is worked off at {@link #HOSTS_PER_TICK} hosts per tick.
     */
    private void wave(Surge surge) {
        int perWave = settings.get().hostsPerWave() * surge.intensity();
        if (perWave == 0 || level.getDifficulty() == Difficulty.PEACEFUL) {
            return;
        }
        for (ServerPlayer player : inArea(surge.origin())) {
            pendingHosts.addLast(new HostRequest(player.blockPosition(), perWave));
        }
    }

    /** Spawns up to {@link #HOSTS_PER_TICK} queued hosts while the surge has fewer than {@code maxHosts} alive. */
    private void spawnPending(Surge surge) {
        if (pendingHosts.isEmpty()) {
            return;
        }
        SurgeSettings current = settings.get();
        if (surge.pruneHosts(id -> {
            Entity host = level.getEntity(id);
            return host == null || !host.isAlive();
        })) {
            data.setDirty();
        }
        for (int budget = HOSTS_PER_TICK; budget > 0 && !pendingHosts.isEmpty(); budget--) {
            if (surge.hosts().size() >= current.maxHosts() || level.getDifficulty() == Difficulty.PEACEFUL) {
                pendingHosts.clear();
                return;
            }
            HostRequest request = pendingHosts.pollFirst();
            if (request.remaining() > 1) {
                pendingHosts.addFirst(new HostRequest(request.centre(), request.remaining() - 1));
            }
            for (Mob host : SurgeHosts.spawnAround(level, request.centre(), 1, SurgeHosts.MIN_DISTANCE,
                    SurgeHosts.MAX_DISTANCE, current.hostLevel(), level.getRandom(), true,
                    chunk -> inArea(surge.origin(), chunk))) {
                surge.addHost(host.getUUID());
                data.setDirty();
            }
        }
    }

    private void end(Surge surge, SurgeEndedEvent.Reason reason, long now) {
        pendingHosts.clear();
        data.setCurrent(null);
        data.setNextDue(now + settings.get().intervalTicks());
        warnings.ended(surge.started());
        if (!surge.started()) {
            return;
        }
        List<ServerPlayer> survivors = new ArrayList<>();
        for (UUID id : surge.participants()) {
            ServerPlayer player = players.byId(id);
            if (player != null && player.isAlive() && !(player instanceof FakePlayer)) {
                QuestProgress.surgeSurvived(player, surge.intensity());
                survivors.add(player);
            }
        }
        NeoForge.EVENT_BUS.post(new SurgeEndedEvent(level, surge.origin(), surge.intensity(), reason, survivors));
    }

    /** Clears the boss bar (the level is unloading). */
    void discard() {
        warnings.clear();
    }

    /** The chunks clouds are released around: those of the players in the area, else the origin's. */
    private static List<ChunkPos> centres(Surge surge, List<ServerPlayer> inArea) {
        List<ChunkPos> centres = new ArrayList<>();
        inArea.forEach(player -> centres.add(player.chunkPosition()));
        if (centres.isEmpty()) {
            centres.add(new ChunkPos(surge.origin()));
        }
        return centres;
    }

    private static float progress(long now, long from, long to) {
        return to <= from ? 0.0F : (float) (to - now) / (to - from);
    }

    /**
     * Releases {@code count} miasma clouds in loaded chunks at festering stage 1 or higher that {@code area} accepts,
     * within {@link #CLOUD_RANGE} chunks of any of {@code centres}: each over a random column of a random such chunk,
     * {@link MiasmaCloud#ALTITUDE} blocks above the ground, with a random heading and a radius of 4 to 6 blocks. Returns
     * the clouds released (none if no chunk qualifies).
     */
    public static List<MiasmaCloud> releaseClouds(ServerLevel level, List<ChunkPos> centres, int count, RandomSource random,
                                                  Predicate<ChunkPos> area) {
        List<MiasmaCloud> clouds = new ArrayList<>();
        if (count <= 0) {
            return clouds;
        }
        TreeSet<Long> chunks = new TreeSet<>();
        for (ChunkPos centre : centres) {
            for (int dx = -CLOUD_RANGE; dx <= CLOUD_RANGE; dx++) {
                for (int dz = -CLOUD_RANGE; dz <= CLOUD_RANGE; dz++) {
                    ChunkPos chunk = new ChunkPos(centre.x + dx, centre.z + dz);
                    if (level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null && area.test(chunk)
                            && CorruptionQueries.chunkStage(level, chunk) >= 1) {
                        chunks.add(chunk.toLong());
                    }
                }
            }
        }
        if (chunks.isEmpty()) {
            return clouds;
        }
        List<Long> candidates = new ArrayList<>(chunks);
        for (int i = 0; i < count; i++) {
            ChunkPos chunk = new ChunkPos(candidates.get(random.nextInt(candidates.size())));
            int x = chunk.getMinBlockX() + random.nextInt(16);
            int z = chunk.getMinBlockZ() + random.nextInt(16);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + MiasmaCloud.ALTITUDE;
            MiasmaCloud cloud = MiasmaCloud.create(level, x + 0.5, y, z + 0.5, random.nextFloat() * 360.0F,
                    MiasmaCloud.DEFAULT_RADIUS + random.nextFloat() * 2.0F, MiasmaCloud.DEFAULT_LIFETIME);
            level.addFreshEntity(cloud);
            clouds.add(cloud);
        }
        return clouds;
    }
}
