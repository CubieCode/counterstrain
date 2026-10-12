package com.cubiecode.remission.surge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

/**
 * One surge in a level: its origin nidus, intensity, the game times of its warning, start and end, and the players
 * caught in it so far (in the area while it ran and not dead since) and the hosts it spawned. Mutable only through its
 * started flag, players and hosts.
 */
public final class Surge {
    public static final int MAX_INTENSITY = 10;

    public static final Codec<Surge> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("origin").forGetter(Surge::origin),
            Codec.intRange(1, MAX_INTENSITY).fieldOf("intensity").forGetter(Surge::intensity),
            Codec.LONG.fieldOf("warning_time").forGetter(Surge::warningTime),
            Codec.LONG.fieldOf("start_time").forGetter(Surge::startTime),
            Codec.LONG.fieldOf("end_time").forGetter(Surge::endTime),
            Codec.BOOL.optionalFieldOf("started", false).forGetter(Surge::started),
            UUIDUtil.CODEC.listOf().optionalFieldOf("participants", List.of()).forGetter(surge -> List.copyOf(surge.participants)),
            UUIDUtil.CODEC.listOf().optionalFieldOf("fallen", List.of()).forGetter(surge -> List.copyOf(surge.fallen)),
            UUIDUtil.CODEC.listOf().optionalFieldOf("hosts", List.of()).forGetter(surge -> List.copyOf(surge.hosts))
    ).apply(instance, Surge::new));

    private final BlockPos origin;
    private final int intensity;
    private final long warningTime;
    private final long startTime;
    private final long endTime;
    private boolean started;
    private final Set<UUID> participants;
    private final Set<UUID> fallen;
    private final Set<UUID> hosts;

    private Surge(BlockPos origin, int intensity, long warningTime, long startTime, long endTime, boolean started,
                  List<UUID> participants, List<UUID> fallen, List<UUID> hosts) {
        this.origin = origin.immutable();
        this.intensity = intensity;
        this.warningTime = warningTime;
        this.startTime = startTime;
        this.endTime = endTime;
        this.started = started;
        this.participants = new LinkedHashSet<>(participants);
        this.fallen = new LinkedHashSet<>(fallen);
        this.hosts = new LinkedHashSet<>(hosts);
    }

    /** A surge whose warning begins at {@code now}. */
    public static Surge warn(BlockPos origin, int intensity, long now, int warningTicks, int durationTicks) {
        return new Surge(origin, intensity, now, now + warningTicks, now + warningTicks + durationTicks, false, List.of(),
                List.of(), List.of());
    }

    public BlockPos origin() {
        return origin;
    }

    public int intensity() {
        return intensity;
    }

    public long warningTime() {
        return warningTime;
    }

    public long startTime() {
        return startTime;
    }

    public long endTime() {
        return endTime;
    }

    /** Whether the warning ran out and the surge started (its started event was posted). */
    public boolean started() {
        return started;
    }

    void markStarted() {
        started = true;
    }

    /** Players caught in the surge, in the order they were first in its area. */
    public List<UUID> participants() {
        return new ArrayList<>(participants);
    }

    /** Adds a player found in the area; a player who died during this surge is never added again. */
    boolean addParticipant(UUID player) {
        return !fallen.contains(player) && participants.add(player);
    }

    /** Hosts this surge spawned that were alive and loaded when last counted. */
    public List<UUID> hosts() {
        return new ArrayList<>(hosts);
    }

    void addHost(UUID host) {
        hosts.add(host);
    }

    /** Drops hosts {@code gone} reports (dead or no longer loaded); returns whether any were dropped. */
    boolean pruneHosts(Predicate<UUID> gone) {
        return hosts.removeIf(gone);
    }

    /** Takes over the caught and fallen players and the hosts of {@code previous}, a surge this one replaces. */
    void inherit(Surge previous) {
        fallen.addAll(previous.fallen);
        previous.participants.stream().filter(id -> !fallen.contains(id)).forEach(participants::add);
        hosts.addAll(previous.hosts);
    }

    /** A participant died: they no longer count and cannot rejoin this surge. */
    boolean fall(UUID player) {
        return participants.remove(player) && fallen.add(player);
    }
}
