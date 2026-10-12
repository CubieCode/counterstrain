package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.registry.RemissionSounds;
import com.cubiecode.remission.world.FesterMaps;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.worldgen.BiomeDefaultFeatures;
import net.minecraft.sounds.Music;
import net.minecraft.world.level.biome.AmbientAdditionsSettings;
import net.minecraft.world.level.biome.AmbientMoodSettings;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;

/**
 * The {@code remission:fester} biome. Land only flips to it after it generated, so it has no features or carvers; its
 * colours, sounds (P4.11) and spawns are what change. Packs override the file or add their own variants
 * ({@code fester_variant}), which inherit none of these effects.
 */
public final class RemissionBiomeProvider extends DatapackBuiltinEntriesProvider {
    /** Ticks between fester music tracks. The music replaces whatever plays when a player walks in. */
    private static final int MUSIC_MIN_DELAY = 2400;
    private static final int MUSIC_MAX_DELAY = 9600;
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
                        .ambientLoopSound(RemissionSounds.FESTER_LOOP)
                        .ambientMoodSound(new AmbientMoodSettings(RemissionSounds.FESTER_MOOD, 3000, 8, 2.0))
                        .ambientAdditionsSound(new AmbientAdditionsSettings(RemissionSounds.FESTER_ADDITIONS, 0.008))
                        .backgroundMusic(new Music(RemissionSounds.FESTER_MUSIC, MUSIC_MIN_DELAY, MUSIC_MAX_DELAY, true))
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
