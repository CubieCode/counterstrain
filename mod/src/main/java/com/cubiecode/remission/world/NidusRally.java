package com.cubiecode.remission.world;

import com.cubiecode.remission.api.CorruptionQueries;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Rallying (P4.13): hosts in festering land converging on one nidus while it is under attack. Held by the nidus's block
 * entity; transient (a reload leaves the hosts where they are, and the next batch sends for them again).
 *
 * <p>Every {@link #RALLY_TICKS} while the nidus is under attack (damaged within {@code contributorWindowTicks}), it may
 * search at most {@code rallyBatch} paths: first for rallying hosts that stopped short (their navigation finished away
 * from the nidus and they have no target), then for new hosts, nearest first, among the living hosts in loaded
 * festering chunks (stage 1 or higher) within {@code rallyRadius} blocks. A host whose path reaches the nidus, or at
 * least ends nearer to it, is sent along it; one that cannot get closer is left alone for the rest of the fight. A
 * host that picks a target fights it with its usual goals and is sent on again once it has none. When the attack ends
 * the rally ends: hosts keep their last path and go back to their own goals.
 */
public final class NidusRally {
    /** Ticks between rally batches. */
    public static final int RALLY_TICKS = 20;
    /** Blocks from the nidus within which a rallying host has arrived and is not sent on again. */
    public static final double ARRIVED = 4.0;
    /** Speed modifier of a rallying host's path. */
    public static final double SPEED = 1.15;
    /** Blocks a path that does not reach the nidus must end nearer to it than the host is, to count as getting closer. */
    private static final double PROGRESS = 2.0;
    /** Blocks added to the distance to the nidus for a path search's range. */
    private static final int RANGE_MARGIN = 8;

    private final BlockPos nidus;
    private final Set<UUID> rallying = new LinkedHashSet<>();
    private final Set<UUID> leftAlone = new LinkedHashSet<>();
    private int searches;
    private long batchNanos;

    public NidusRally(BlockPos nidus) {
        this.nidus = nidus.immutable();
    }

    /** Hosts rallying to the nidus (sent for and not since dead, unloaded or out of the rally). */
    public List<UUID> rallying() {
        return new ArrayList<>(rallying);
    }

    /** Hosts in range that could not get closer and are left alone for the rest of the fight. */
    public List<UUID> leftAlone() {
        return new ArrayList<>(leftAlone);
    }

    /** Path searches made in the last batch. */
    public int lastSearches() {
        return searches;
    }

    /** Wall time the last batch took, in nanoseconds (for the tick cost benchmark). */
    public long lastBatchNanos() {
        return batchNanos;
    }

    /**
     * One tick at game time {@code now}: with {@code underAttack} false (or rallying off) the rally ends; otherwise every
     * {@link #RALLY_TICKS} a batch runs.
     */
    public void tick(ServerLevel level, boolean underAttack, NidusDefenceSettings settings, long now) {
        if (!underAttack || !settings.rally()) {
            if (!rallying.isEmpty() || !leftAlone.isEmpty()) {
                rallying.clear();
                leftAlone.clear();
            }
            return;
        }
        if (now % RALLY_TICKS == 0) {
            batch(level, settings);
        }
    }

    /** One batch: at most {@code rallyBatch} path searches, for hosts that stopped short first, then new hosts. */
    public void batch(ServerLevel level, NidusDefenceSettings settings) {
        long start = System.nanoTime();
        try {
            search(level, settings);
        } finally {
            batchNanos = System.nanoTime() - start;
        }
    }

    private void search(ServerLevel level, NidusDefenceSettings settings) {
        Vec3 centre = Vec3.atCenterOf(nidus);
        int budget = settings.rallyBatch();
        searches = 0;
        for (Iterator<UUID> it = rallying.iterator(); it.hasNext(); ) {
            Entity entity = level.getEntity(it.next());
            if (!(entity instanceof Mob host) || !host.isAlive() || !CorruptionQueries.isHost(host)) {
                it.remove();
                continue;
            }
            if (budget > 0 && host.getTarget() == null && host.getNavigation().isDone()
                    && host.distanceToSqr(centre) > ARRIVED * ARRIVED) {
                Sent sent = send(host, centre);
                if (sent != Sent.NOT_YET) {
                    budget--;
                    searches++;
                }
                if (sent == Sent.CANNOT) {
                    it.remove();
                    leftAlone.add(host.getUUID());
                }
            }
        }
        if (budget <= 0) {
            return;
        }
        double radius = settings.rallyRadius();
        List<Mob> candidates = level.getEntitiesOfClass(Mob.class, new AABB(nidus).inflate(radius), host -> host.isAlive()
                && CorruptionQueries.isHost(host) && !rallying.contains(host.getUUID()) && !leftAlone.contains(host.getUUID())
                && host.distanceToSqr(centre) <= radius * radius && host.distanceToSqr(centre) > ARRIVED * ARRIVED
                && festering(level, host.chunkPosition()));
        candidates.sort(Comparator.<Mob>comparingDouble(host -> host.distanceToSqr(centre)).thenComparing(Entity::getUUID));
        for (Mob host : candidates) {
            if (budget <= 0) {
                break;
            }
            Sent sent = send(host, centre);
            if (sent == Sent.NOT_YET) {
                continue;
            }
            budget--;
            searches++;
            if (sent == Sent.SENT) {
                rallying.add(host.getUUID());
            } else {
                leftAlone.add(host.getUUID());
            }
        }
    }

    private static boolean festering(ServerLevel level, ChunkPos chunk) {
        return level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null && CorruptionQueries.chunkStage(level, chunk) >= 1;
    }

    /** What became of a host sent for. */
    private enum Sent {
        /** On its way. */
        SENT,
        /** It cannot get closer: left alone. */
        CANNOT,
        /** It cannot search a path now (a walker in the air): no search was made; it is tried again next batch. */
        NOT_YET
    }

    /** Searches a path from {@code host} to the nidus and sends it along if it reaches or gets closer. */
    private Sent send(Mob host, Vec3 centre) {
        double distance = Math.sqrt(host.distanceToSqr(centre));
        // createPath(pos, regionOffset, accuracy) passes its last argument on as the search range (follow range).
        Path path = host.getNavigation().createPath(nidus, 1, (int) Math.ceil(distance) + RANGE_MARGIN);
        if (path == null) {
            // Null before any search when the navigation cannot path from where the host is (a walker not on the ground).
            return host.onGround() || host.isInLiquid() ? Sent.CANNOT : Sent.NOT_YET;
        }
        Node end = path.getEndNode();
        boolean closer = end != null && Math.sqrt(end.asVec3().add(0.5, 0.0, 0.5).distanceToSqr(centre)) <= distance - PROGRESS;
        if (!path.canReach() && !closer) {
            return Sent.CANNOT;
        }
        return host.getNavigation().moveTo(path, SPEED) ? Sent.SENT : Sent.CANNOT;
    }
}
