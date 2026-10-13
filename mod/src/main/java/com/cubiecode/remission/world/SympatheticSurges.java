package com.cubiecode.remission.world;

import com.cubiecode.remission.surge.Surges;
import it.unimi.dsi.fastutil.HashCommon;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Sympathetic surges (P4.13): a nidus passing a vitality threshold may set off a surge at another nidus nearby.
 *
 * <p>When a threshold is passed (after the nidus's own surge has started), with {@code sympathetic} on, a roll against
 * {@code sympatheticChance} decides whether one is set off; the roll is a hash of the game time and the attacked nidus's
 * position, so the same fight replays the same way. The nidus that answers is the nearest other indexed nidus within
 * {@code sympatheticRadius} blocks (horizontal), ties broken by position. Its surge is queued with the surge controller
 * ({@link com.cubiecode.remission.surge.SurgeController#queueSympathetic}) at the threshold surge's intensity times
 * {@code sympatheticIntensity} (at least 1); it warns as usual once no surge is running in the level.
 */
public final class SympatheticSurges {
    private SympatheticSurges() {
    }

    /**
     * Nidus {@code attacked} passed a threshold at {@code now}, starting its own surge at {@code intensity}: queues a
     * sympathetic surge if the roll and a nidus in range allow. Returns the nidus queued, if any.
     */
    public static Optional<BlockPos> thresholdPassed(ServerLevel level, BlockPos attacked, int intensity,
                                                     NidusDefenceSettings settings, long now) {
        if (!settings.sympathetic() || !rolls(attacked, settings.sympatheticChance(), now)) {
            return Optional.empty();
        }
        Optional<BlockPos> origin = answering(NidusIndex.get(level), attacked, settings.sympatheticRadius());
        origin.ifPresent(pos -> Surges.controller(level).queueSympathetic(pos, settings.sympatheticIntensityFor(intensity),
                attacked));
        return origin;
    }

    /** Whether the roll at {@code now} for {@code attacked} is under {@code chance}. */
    static boolean rolls(BlockPos attacked, double chance, long now) {
        if (chance <= 0.0) {
            return false;
        }
        long hash = HashCommon.mix(attacked.asLong() ^ HashCommon.mix(now));
        return (hash >>> 11) * 0x1.0p-53 < chance;
    }

    /** The nearest indexed nidus other than {@code attacked} within {@code radius} blocks (horizontal), by position on a tie. */
    public static Optional<BlockPos> answering(NidusIndex index, BlockPos attacked, int radius) {
        BlockPos best = null;
        long bestDistance = Long.MAX_VALUE;
        long limit = (long) radius * radius;
        for (Nidus nidus : index.nidi()) {
            BlockPos pos = nidus.pos();
            if (pos.equals(attacked)) {
                continue;
            }
            long dx = pos.getX() - attacked.getX();
            long dz = pos.getZ() - attacked.getZ();
            long distance = dx * dx + dz * dz;
            // nidi() iterates in asLong order, so the first at the least distance wins the tie.
            if (distance <= limit && distance < bestDistance) {
                best = pos;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }
}
