package com.cubiecode.remission.behaviour;

import com.cubiecode.remission.Remission;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

/**
 * The {@code remission:mob_profile} datapack registry ({@code data/<ns>/remission/mob_profile/<name>.json}). It is a
 * server-only datapack registry: it loads when a world (or server) starts, so profile changes need a world reload, not
 * {@code /reload}. Tags named in {@code entities} rebind on {@code /reload} like any tag.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class MobProfiles {
    public static final ResourceKey<Registry<MobProfile>> KEY = ResourceKey.createRegistryKey(Remission.id("mob_profile"));

    private MobProfiles() {
    }

    @SubscribeEvent
    static void newRegistry(DataPackRegistryEvent.NewRegistry event) {
        event.dataPackRegistry(KEY, MobProfile.CODEC);
    }

    /** The highest-priority profile matching the entity's type; ties go to the profile with the smallest id. */
    public static Optional<MobProfile> find(Entity entity) {
        return entity.level().registryAccess().registry(KEY)
                .flatMap(registry -> select(registry.entrySet().stream()
                        .sorted(Comparator.comparing(entry -> entry.getKey().location()))
                        .map(Map.Entry::getValue)
                        .toList(), entity.getType()));
    }

    /** The profile {@link #find} would pick from {@code candidates}, in tie-break order (first wins a tie). */
    public static Optional<MobProfile> select(Iterable<MobProfile> candidates, EntityType<?> type) {
        MobProfile best = null;
        for (MobProfile profile : candidates) {
            if (profile.appliesTo(type) && (best == null || profile.priority() > best.priority())) {
                best = profile;
            }
        }
        return Optional.ofNullable(best);
    }
}
