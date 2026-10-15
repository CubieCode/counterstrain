package com.cubiecode.remission.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The nidus's six tentacles (P4.19): where they rest, which player each one turns toward, and how far it has turned.
 * Server and client run the same code on the same inputs (players in reach, entity ids, game ticks), so the hitboxes the
 * server moves and the tentacles clients draw point the same way.
 *
 * <p>A <i>bearing</i> is a horizontal angle from the nidus's centre in degrees: 0 is north (-z), 90 east (+x). Each
 * tentacle rests at its own bearing; its <i>aim</i> is how far it has turned from that, positive toward east of north.
 */
public final class NidusTentacles {
    /** Tentacles per nidus. */
    public static final int COUNT = 6;
    /** Bone name suffixes, by tentacle index: {@code tentacle_<name>} and its segments {@code tentacle_<name>_1.._7}. */
    public static final List<String> NAMES = List.of("front_right", "front_left", "mid_right", "mid_left", "back_right",
            "back_left");
    /** Rest bearings, by tentacle index, from the model. */
    private static final float[] REST_BEARINGS = {52.0F, -57.0F, 106.0F, -101.0F, 155.0F, -150.0F};
    /**
     * Rest centre of each tentacle's hitbox, in blocks from the block's bottom centre: the middle of the boxes around its
     * lower (segments 1 to 3) and upper (4 to 7) segments, measured from the model at rest.
     */
    private static final double[][] REST_CENTRES = {
            {0.91, 1.49, -0.76}, {-0.96, 1.49, -0.55}, {1.16, 1.57, 0.30},
            {-1.16, 1.56, 0.20}, {0.52, 1.62, 1.05}, {-0.57, 1.61, 1.02}};
    /** Each tentacle's base pivot, in blocks from the block's bottom centre: the hitbox turns around it. */
    private static final double[][] PIVOTS = {
            {0.433, 0.575, -0.339}, {-0.461, 0.575, -0.299}, {0.529, 0.575, 0.152},
            {-0.540, 0.575, 0.105}, {0.233, 0.575, 0.499}, {-0.275, 0.575, 0.476}};

    /** Most a tentacle turns toward a player it is tracking, so tentacles never sweep through the body or each other. */
    public static final float TRACK_LIMIT = 60.0F;
    /** Most a tentacle turns to lash. */
    public static final float LASH_LIMIT = 90.0F;
    /** Fraction of the remaining turn a tracking tentacle makes each tick. */
    public static final float TRACK_EASE = 0.15F;
    /** Fraction of the remaining turn a lashing tentacle makes each tick (it swings round while rearing back). */
    public static final float LASH_EASE = 0.5F;
    /** Ticks from a lash's start until it strikes (the animation's contact at 0.65 s). */
    public static final int LASH_CONTACT_TICKS = 13;
    /** Ticks a lash lasts (the animation's 1.4 s); the tentacle keeps its lash aim until then. */
    public static final int LASH_TICKS = 28;
    /** How far a lash's tip lands from the nidus's centre, in blocks, at the end of its strike. */
    public static final double LASH_REACH = 5.2;
    /** Ticks after a lash's start until its tentacle rises off the ground again (contact is 0.65 to 0.8 s). */
    public static final int LASH_GROUND_TICKS = 16;
    /** How far out along the lash a lashing tentacle's hitbox lies while it is on the ground, in blocks from the centre. */
    public static final double LASH_HITBOX_DISTANCE = 3.0;

    private NidusTentacles() {
    }

    /** One player a tentacle tracks: the player, its tentacle and its bearing from the nidus. */
    public record Assignment(Entity player, int tentacle, float bearing) {
    }

    /** The rest bearing of {@code tentacle}. */
    public static float restBearing(int tentacle) {
        return REST_BEARINGS[tentacle];
    }

    /** Bearing of {@code x, z} from {@code centre}. */
    public static float bearing(Vec3 centre, double x, double z) {
        return (float) Math.toDegrees(Math.atan2(x - centre.x, -(z - centre.z)));
    }

    /** The aim that points {@code tentacle} at {@code bearing}, wrapped and clamped to {@code limit} either way. */
    public static float aimToward(int tentacle, float bearing, float limit) {
        return Mth.clamp(Mth.wrapDegrees(bearing - REST_BEARINGS[tentacle]), -limit, limit);
    }

    /**
     * Gives each of {@code players} (those in reach) a tentacle: nearest player first (then lowest entity id), each the
     * free tentacle whose rest bearing is closest to its own bearing (then the lowest index). With more players than
     * tentacles the furthest go without. The same players in the same places always get the same tentacles.
     */
    public static List<Assignment> assign(Vec3 centre, List<? extends Entity> players) {
        List<? extends Entity> sorted = players.stream()
                .sorted(Comparator.<Entity>comparingDouble(player -> horizontalDistanceSqr(centre, player))
                        .thenComparingInt(Entity::getId))
                .toList();
        boolean[] taken = new boolean[COUNT];
        List<Assignment> assignments = new ArrayList<>();
        for (Entity player : sorted) {
            if (assignments.size() == COUNT) {
                break;
            }
            float bearing = bearing(centre, player.getX(), player.getZ());
            int best = -1;
            float bestTurn = Float.MAX_VALUE;
            for (int tentacle = 0; tentacle < COUNT; tentacle++) {
                float turn = Math.abs(Mth.wrapDegrees(bearing - REST_BEARINGS[tentacle]));
                if (!taken[tentacle] && turn < bestTurn) {
                    best = tentacle;
                    bestTurn = turn;
                }
            }
            taken[best] = true;
            assignments.add(new Assignment(player, best, bearing));
        }
        return assignments;
    }

    private static double horizontalDistanceSqr(Vec3 centre, Entity player) {
        double dx = player.getX() - centre.x;
        double dz = player.getZ() - centre.z;
        return dx * dx + dz * dz;
    }

    /**
     * Where the hitbox of {@code tentacle} turned by {@code aim} degrees sits: its centre, in blocks from the block's bottom
     * centre, turned about the tentacle's base pivot.
     */
    public static Vec3 hitboxCentre(int tentacle, float aim) {
        double[] rest = REST_CENTRES[tentacle];
        double[] pivot = PIVOTS[tentacle];
        double dx = rest[0] - pivot[0];
        double dz = rest[2] - pivot[2];
        // Turning by +aim moves a point clockwise seen from above (north toward east): bearing atan2(x, -z) grows.
        double angle = Math.toRadians(aim);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double x = dx * cos - dz * sin;
        double z = dx * sin + dz * cos;
        return new Vec3(pivot[0] + x, rest[1], pivot[2] + z);
    }

    /**
     * The aims of one nidus's tentacles, eased each game tick toward the players assigned to them and back to rest when
     * they have none; a lash turns its tentacle straight at its target, faster, and holds it there while the lash lasts.
     * A tentacle tracking a player also leans toward it ({@link #lean}, 0 to 1), easing back while it lashes or has no one.
     */
    public static final class Aim {
        private final float[] aim = new float[COUNT];
        private final float[] previous = new float[COUNT];
        private final float[] lean = new float[COUNT];
        private final float[] previousLean = new float[COUNT];
        private final float[] lashAim = new float[COUNT];
        private final float[] lashBearing = new float[COUNT];
        private final long[] lashUntil = new long[COUNT];

        public Aim() {
            Arrays.fill(lashUntil, Long.MIN_VALUE);
        }

        /** One game tick at {@code now}: each tentacle eases toward its target in {@code players} (those in reach). */
        public void tick(Vec3 centre, List<? extends Entity> players, long now) {
            float[] target = new float[COUNT];
            boolean[] tracking = new boolean[COUNT];
            for (Assignment assignment : assign(centre, players)) {
                target[assignment.tentacle()] = aimToward(assignment.tentacle(), assignment.bearing(), TRACK_LIMIT);
                tracking[assignment.tentacle()] = true;
            }
            for (int tentacle = 0; tentacle < COUNT; tentacle++) {
                previous[tentacle] = aim[tentacle];
                previousLean[tentacle] = lean[tentacle];
                float leanTarget = tracking[tentacle] && !lashing(tentacle, now) ? 1.0F : 0.0F;
                lean[tentacle] += (leanTarget - lean[tentacle]) * TRACK_EASE;
                if (lashing(tentacle, now)) {
                    aim[tentacle] += (lashAim[tentacle] - aim[tentacle]) * LASH_EASE;
                } else {
                    aim[tentacle] += (target[tentacle] - aim[tentacle]) * TRACK_EASE;
                }
            }
        }

        /** {@code tentacle} lashes toward {@code bearing} from {@code now}, for {@link #LASH_TICKS}. */
        public void lash(int tentacle, float bearing, long now) {
            lashAim[tentacle] = aimToward(tentacle, bearing, LASH_LIMIT);
            lashBearing[tentacle] = bearing;
            lashUntil[tentacle] = now + LASH_TICKS;
        }

        /** Whether {@code tentacle} is mid-lash at {@code now}. */
        public boolean lashing(int tentacle, long now) {
            return now < lashUntil[tentacle] && now >= lashUntil[tentacle] - LASH_TICKS;
        }

        /**
         * Whether {@code tentacle}'s lash has it lying along the ground at {@code now}: from contact
         * ({@link #LASH_CONTACT_TICKS}) until {@link #LASH_GROUND_TICKS} after the lash started.
         */
        public boolean grounded(int tentacle, long now) {
            long start = lashUntil[tentacle] - LASH_TICKS;
            return lashing(tentacle, now) && now - start >= LASH_CONTACT_TICKS && now - start <= LASH_GROUND_TICKS;
        }

        /** The bearing of {@code tentacle}'s latest lash. */
        public float lashBearing(int tentacle) {
            return lashBearing[tentacle];
        }

        /** The aim of {@code tentacle} after the last tick. */
        public float aim(int tentacle) {
            return aim[tentacle];
        }

        /** How far {@code tentacle} leans toward its player (0 to 1) between the last two ticks. */
        public float lean(int tentacle, float partialTick) {
            return Mth.lerp(partialTick, previousLean[tentacle], lean[tentacle]);
        }

        /** The aim of {@code tentacle} between the last two ticks, {@code partialTick} of the way through. */
        public float aim(int tentacle, float partialTick) {
            return Mth.lerp(partialTick, previous[tentacle], aim[tentacle]);
        }
    }
}
