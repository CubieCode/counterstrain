package com.cubiecode.remission.infection;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.behaviour.MobProfiles;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Runs one {@link ExposureTracker} over each server level's mobs: festering land infection (P4.7). */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class FesteringExposure {
    private static final Map<ServerLevel, ExposureTracker> TRACKERS = new IdentityHashMap<>();

    private FesteringExposure() {
    }

    /** The tracker that runs on {@code level}'s ticks. */
    public static ExposureTracker tracker(ServerLevel level) {
        synchronized (TRACKERS) {
            return TRACKERS.computeIfAbsent(level, l -> new ExposureTracker(l, ExposureSettings::fromConfig, l::getAllEntities,
                    MobProfiles::find));
        }
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            tracker(level).tick(level.getGameTime());
        }
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            synchronized (TRACKERS) {
                TRACKERS.remove(level);
            }
        }
    }
}
