package com.cubiecode.remission.infection;

import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.behaviour.MobProfile;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.registry.RemissionAttachments;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * Festering land exposure for one level's mobs. Every {@link ExposureSettings#intervalTicks} a pass snapshots the mobs
 * and checks up to {@link ExposureSettings#mobBudget} of them per tick, stopping early once
 * {@link ExposureSettings#infectionBudget} have become hosts. Each mob checked gains (in festering land at or
 * above the minimum stage, at any height) or loses (elsewhere) the game ticks since the previous pass started (nothing on the
 * first pass), so a pass spread over several ticks still counts real time. Mobs in chunks that are not entity-ticking are skipped. A mob whose
 * exposure reaches {@link ExposureSettings#exposureTicks} becomes a host through {@link Infection#infectFromFesteringLand}.
 */
public final class ExposureTracker {
    private static final long NEVER = Long.MIN_VALUE;

    private final ServerLevel level;
    private final Supplier<ExposureSettings> settings;
    private final Supplier<? extends Iterable<? extends Entity>> source;
    private final Function<Mob, Optional<MobProfile>> profiles;
    private final ArrayDeque<Mob> pass = new ArrayDeque<>();
    private long passStart = NEVER;
    private int credit;
    private int minLevel;
    private int exposureTicks;

    /**
     * A tracker over the mobs that {@code source} yields when each pass starts, finding each mob's profile with
     * {@code profiles} ({@code MobProfiles::find} in play).
     */
    public ExposureTracker(ServerLevel level, Supplier<ExposureSettings> settings,
                           Supplier<? extends Iterable<? extends Entity>> source, Function<Mob, Optional<MobProfile>> profiles) {
        this.level = level;
        this.settings = settings;
        this.source = source;
        this.profiles = profiles;
    }

    /** Mobs still waiting in the current pass. */
    public int pending() {
        return pass.size();
    }

    /** Runs one server tick at game time {@code now}. */
    public TickWork tick(long now) {
        ExposureSettings current = settings.get();
        if (!current.enabled()) {
            pass.clear();
            passStart = NEVER;
            return TickWork.NONE;
        }
        if (pass.isEmpty()) {
            if (passStart != NEVER && now - passStart < current.intervalTicks()) {
                return TickWork.NONE;
            }
            credit = passStart == NEVER ? 0 : (int) Math.min(now - passStart, Integer.MAX_VALUE);
            passStart = now;
            minLevel = current.minLevel();
            exposureTicks = current.exposureTicks();
            for (Entity entity : source.get()) {
                if (entity instanceof Mob mob) {
                    pass.add(mob);
                }
            }
        }
        int checked = 0;
        int infected = 0;
        while (checked < current.mobBudget() && infected < current.infectionBudget() && !pass.isEmpty()) {
            checked++;
            if (check(pass.poll())) {
                infected++;
            }
        }
        return new TickWork(checked, infected);
    }

    /** Applies this pass's exposure change to one mob; returns whether it became a host. */
    private boolean check(Mob mob) {
        if (!mob.isAlive() || mob.level() != level || !level.isPositionEntityTicking(mob.blockPosition())) {
            return false;
        }
        int exposure = exposure(mob);
        if (CorruptionQueries.chunkLevel(level, mob.chunkPosition()) < minLevel) {
            if (exposure > 0) {
                setExposure(mob, exposure - credit);
            }
            return false;
        }
        if (Corruption.isImmune(mob) || Corruption.isHost(mob)) {
            setExposure(mob, 0);
            return false;
        }
        Optional<MobProfile> profile = profiles.apply(mob);
        if (!Infection.canInfectFromFesteringLand(mob, profile)) {
            setExposure(mob, 0);
            return false;
        }
        exposure = (int) Math.min((long) exposure + credit, Integer.MAX_VALUE);
        if (exposure < exposureTicks) {
            setExposure(mob, exposure);
            return false;
        }
        setExposure(mob, 0);
        return Infection.infectFromFesteringLand(mob, profile) != null;
    }

    /** A mob's festering land exposure in ticks; 0 if it has none. */
    public static int exposure(Mob mob) {
        Integer exposure = mob.getExistingDataOrNull(RemissionAttachments.FESTERING_EXPOSURE);
        return exposure == null ? 0 : exposure;
    }

    /** Sets a mob's exposure, removing the attachment at 0 or below. Server only. */
    public static void setExposure(Mob mob, int ticks) {
        if (ticks <= 0) {
            mob.removeData(RemissionAttachments.FESTERING_EXPOSURE);
        } else {
            mob.setData(RemissionAttachments.FESTERING_EXPOSURE, ticks);
        }
    }

    /** What one tick did: mobs checked and mobs that became hosts. */
    public record TickWork(int checked, int infected) {
        public static final TickWork NONE = new TickWork(0, 0);
    }
}
