package com.cubiecode.remission.surge;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.world.Nidus;
import com.cubiecode.remission.world.SpreadSettings;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Runs one {@link SurgeController} per server level over its saved {@link SurgeData}, with the config settings. */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class Surges {
    private static final Map<ServerLevel, SurgeController> CONTROLLERS = new IdentityHashMap<>();

    private Surges() {
    }

    /** The controller that runs on {@code level}'s ticks. */
    public static SurgeController controller(ServerLevel level) {
        synchronized (CONTROLLERS) {
            return CONTROLLERS.computeIfAbsent(level, l -> new SurgeController(l, SurgeData.get(l),
                    SurgeSettings::fromConfig, SpreadSettings::fromConfig, SurgeController.levelPlayers(l)));
        }
    }

    /** The factor on spread steps from {@code nidus} in {@code level} now (1 unless a surge from it is running). */
    public static int spreadFactor(ServerLevel level, Nidus nidus) {
        return controller(level).spreadFactor(nidus);
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            controller(level).tick(level.getGameTime());
        }
    }

    // Lowest priority and not cancelled: only deaths that really happen count.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            List<SurgeController> controllers;
            synchronized (CONTROLLERS) {
                controllers = new ArrayList<>(CONTROLLERS.values());
            }
            controllers.forEach(controller -> controller.playerDied(player));
        }
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SurgeController removed;
            synchronized (CONTROLLERS) {
                removed = CONTROLLERS.remove(level);
            }
            if (removed != null) {
                removed.discard();
            }
        }
    }
}
