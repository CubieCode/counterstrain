package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.world.FesterMaps;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.worldgen.BiomeDefaultFeatures;
import net.minecraft.world.level.biome.AmbientMoodSettings;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;

/**
 * The {@code remission:fester} biome. Land only flips to it after it generated, so it has no features or carvers; its
 * colours and spawns are what change. Packs override the file or add their own variants ({@code fester_variant}).
 */
public final class RemissionBiomeProvider extends DatapackBuiltinEntriesProvider {
    private static final RegistrySetBuilder BIOMES = new RegistrySetBuilder().add(Registries.BIOME, context -> {
        MobSpawnSettings.Builder spawns = new MobSpawnSettings.Builder();
        BiomeDefaultFeatures.farmAnimals(spawns);
        BiomeDefaultFeatures.commonSpawns(spawns);
        context.register(FesterMaps.FESTER, new Biome.BiomeBuilder()
                .hasPrecipitation(true)
                .temperature(0.8F)
                .downfall(0.4F)
                .specialEffects(new BiomeSpecialEffects.Builder()
                        .grassColorOverride(0xB0A07E)
                        .foliageColorOverride(0xA08898)
                        .waterColor(0x5E4A6E)
                        .waterFogColor(0x2B1F36)
                        .skyColor(0x8F84A3)
                        .fogColor(0xA597B0)
                        .ambientMoodSound(AmbientMoodSettings.LEGACY_CAVE_SETTINGS)
                        .build())
                .mobSpawnSettings(spawns.build())
                .generationSettings(BiomeGenerationSettings.EMPTY)
                .build());
    });

    public RemissionBiomeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
        super(output, lookup, BIOMES, Set.of(Remission.MOD_ID));
    }

    @Override
    public String getName() {
        return "Remission biomes";
    }
}
