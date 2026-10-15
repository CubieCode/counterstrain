package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.world.FesterMaps;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.Biomes;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The fester biome's atmosphere as loaded from data (P4.11): its own fog colour, unlike the Deep Dark's, and no sounds or
 * music until the art pass (P12.2) supplies them.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FesterAtmosphereTests {
    private FesterAtmosphereTests() {
    }

    @GameTest(template = "empty")
    public static void festerBiomeIsSilentUntilTheArtPass(GameTestHelper helper) {
        var biomes = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        BiomeSpecialEffects fester = biomes.getOrThrow(FesterMaps.FESTER).getSpecialEffects();
        BiomeSpecialEffects deepDark = biomes.getOrThrow(Biomes.DEEP_DARK).getSpecialEffects();

        helper.assertTrue(fester.getAmbientLoopSoundEvent().isEmpty(), "ambient loop");
        helper.assertTrue(fester.getAmbientMoodSettings().isEmpty(), "mood sound");
        helper.assertTrue(fester.getAmbientAdditionsSettings().isEmpty(), "additions sound");
        helper.assertTrue(fester.getBackgroundMusic().isEmpty(), "music");
        helper.assertFalse(fester.getFogColor() == deepDark.getFogColor(), "fog colour unlike the Deep Dark");
        helper.succeed();
    }
}
