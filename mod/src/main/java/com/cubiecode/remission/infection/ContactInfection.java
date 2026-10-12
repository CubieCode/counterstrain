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

/** Runs one {@link ContactTracker} over each server level's mobs: contact infection (P4.8). */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class ContactInfection {
    private static final Map<ServerLevel, ContactTracker> TRACKERS = new IdentityHashMap<>();

    private ContactInfection() {
    }

    /** The tracker that runs on {@code level}'s ticks. */
    public static ContactTracker tracker(ServerLevel level) {
        synchronized (TRACKERS) {
            return TRACKERS.computeIfAbsent(level, l -> new ContactTracker(l, ContactSettings::fromConfig, l::getAllEntities,
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
