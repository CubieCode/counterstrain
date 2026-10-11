package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.behaviour.MobBehaviour;
import com.cubiecode.remission.behaviour.MobProfile;
import com.cubiecode.remission.behaviour.MobProfiles;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;

/**
 * Remission's default {@code remission:mob_profile} entries. They sit at {@link #DEFAULT_PRIORITY}, so any pack profile
 * for the same mob at the default priority 0 replaces them.
 */
public final class RemissionMobProfileProvider extends DatapackBuiltinEntriesProvider {
    public static final int DEFAULT_PRIORITY = -1000;

    private static final RegistrySetBuilder PROFILES = new RegistrySetBuilder().add(MobProfiles.KEY, context -> {
        // Brain-based mobs whose brains cannot fight a chosen target: villagers and allays have no fight activity, and a
        // frog's tongue only takes frog food. They still become hosts, but keep their own AI.
        context.register(ResourceKey.create(MobProfiles.KEY, Remission.id("unsupported")), new MobProfile(
                HolderSet.direct(EntityType.VILLAGER.builtInRegistryHolder(), EntityType.ALLAY.builtInRegistryHolder(),
                        EntityType.FROG.builtInRegistryHolder()),
                DEFAULT_PRIORITY, Optional.of(MobBehaviour.NONE), Map.of(), Optional.empty(),
                MobProfile.Conversion.DEFAULT));
    });

    public RemissionMobProfileProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
        super(output, lookup, PROFILES, Set.of(Remission.MOD_ID));
    }

    @Override
    public String getName() {
        return "Remission mob profiles";
    }
}
