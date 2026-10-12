package com.cubiecode.remission.counterstrain;

import com.cubiecode.remission.api.PurificationCause;
import com.cubiecode.remission.purification.Cleansing;
import com.cubiecode.remission.purification.Purification;
import com.cubiecode.remission.surge.MiasmaCloud;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * Unstable Counterstrain prototypes (P7.3): what a thrown {@code remission:counterstrain_prototype} does where it lands.
 * Usually it cleanses the festered blocks and veins around it and lowers its chunk's Strain level, through the same
 * purification path as the handheld purifier (cause {@link PurificationCause#PROTOTYPE}); sometimes it is unstable and
 * backfires, releasing a miasma cloud (P4.3's {@link MiasmaCloud}) instead.
 */
public final class CounterstrainPrototypes {
    /** Lifetime of the cloud a backfire releases, in ticks. */
    public static final int BACKFIRE_CLOUD_TICKS = 1200;

    private CounterstrainPrototypes() {
    }

    /** What a prototype did where it landed. */
    public enum Outcome {
        /** It cleansed, or found nothing to cleanse. */
        CLEANSED,
        /** It backfired and released a miasma cloud. */
        BACKFIRED
    }

    /**
     * A prototype landing at {@code at}: backfires with {@code backfireChance}, else cleanses the box of
     * {@code radius} around the block it landed in and lowers that block's chunk by {@code step}, crediting
     * {@code thrower} if that brings the chunk into remission. Plays the sound and particles either way.
     */
    public static Outcome land(ServerLevel level, Vec3 at, @Nullable ServerPlayer thrower, PrototypeSettings settings,
                               RandomSource random) {
        BlockPos pos = BlockPos.containing(at);
        if (settings.backfireChance() > 0 && random.nextDouble() < settings.backfireChance()) {
            MiasmaCloud cloud = MiasmaCloud.create(level, at.x, at.y, at.z, random.nextFloat() * 360.0F,
                    MiasmaCloud.DEFAULT_RADIUS, BACKFIRE_CLOUD_TICKS);
            level.addFreshEntity(cloud);
            level.playSound(null, pos, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.NEUTRAL, 1.0F, 0.6F);
            return Outcome.BACKFIRED;
        }
        Cleansing.cleanseBox(level, pos, settings.radius(), PurificationCause.PROTOTYPE);
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null) {
            Purification.lowerLevel(level, chunk, settings.step(), PurificationCause.PROTOTYPE, thrower);
        }
        level.levelEvent(LevelEvent.PARTICLES_INSTANT_POTION_SPLASH, pos, PrototypeItem.COLOR);
        return Outcome.CLEANSED;
    }
}
