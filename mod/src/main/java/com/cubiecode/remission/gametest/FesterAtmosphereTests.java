package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.registry.RemissionSounds;
import com.cubiecode.remission.world.FesterMaps;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.biome.AmbientAdditionsSettings;
import net.minecraft.world.level.biome.AmbientMoodSettings;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.Biomes;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The fester biome's atmosphere as loaded from data (P4.11): its own sounds and music, none shared with the Deep Dark. */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FesterAtmosphereTests {
    private FesterAtmosphereTests() {
    }

    @GameTest(template = "empty")
    public static void festerBiomeHasItsOwnSounds(GameTestHelper helper) {
        var biomes = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        BiomeSpecialEffects fester = biomes.getOrThrow(FesterMaps.FESTER).getSpecialEffects();
        BiomeSpecialEffects deepDark = biomes.getOrThrow(Biomes.DEEP_DARK).getSpecialEffects();

        helper.assertTrue(is(fester.getAmbientLoopSoundEvent(), RemissionSounds.FESTER_LOOP), "ambient loop");
        helper.assertTrue(is(fester.getAmbientMoodSettings().map(AmbientMoodSettings::getSoundEvent),
                RemissionSounds.FESTER_MOOD), "mood sound");
        helper.assertTrue(is(fester.getAmbientAdditionsSettings().map(AmbientAdditionsSettings::getSoundEvent),
                RemissionSounds.FESTER_ADDITIONS), "additions sound");
        Optional<Music> music = fester.getBackgroundMusic();
        helper.assertTrue(is(music.map(Music::getEvent), RemissionSounds.FESTER_MUSIC), "music");
        helper.assertTrue(music.get().replaceCurrentMusic(), "music replaces what plays on entering");

        helper.assertFalse(fester.getAmbientLoopSoundEvent().equals(deepDark.getAmbientLoopSoundEvent()), "loop unlike the Deep Dark");
        helper.assertFalse(fester.getBackgroundMusic().map(Music::getEvent)
                .equals(deepDark.getBackgroundMusic().map(Music::getEvent)), "music unlike the Deep Dark");
        helper.assertFalse(fester.getFogColor() == deepDark.getFogColor(), "fog colour unlike the Deep Dark");
        helper.succeed();
    }

    private static boolean is(Optional<Holder<SoundEvent>> sound, Holder<SoundEvent> expected) {
        return sound.isPresent() && sound.get().is(expected.unwrapKey().orElseThrow());
    }
}
