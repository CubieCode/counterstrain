package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Sound events; their files and subtitles are in {@code assets/remission/sounds.json}. */
public final class RemissionSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, Remission.MOD_ID);

    /** The {@code remission:fester} biome's ambient loop (P4.11). */
    public static final DeferredHolder<SoundEvent, SoundEvent> FESTER_LOOP = register("ambient.fester.loop");
    /** The fester biome's mood sound, played like cave sounds in dark spots (P4.11). */
    public static final DeferredHolder<SoundEvent, SoundEvent> FESTER_MOOD = register("ambient.fester.mood");
    /** The fester biome's occasional wet, organic noises (P4.11). */
    public static final DeferredHolder<SoundEvent, SoundEvent> FESTER_ADDITIONS = register("ambient.fester.additions");
    /** The fester biome's music (P4.11). */
    public static final DeferredHolder<SoundEvent, SoundEvent> FESTER_MUSIC = register("music.fester");

    private RemissionSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(Remission.id(name)));
    }
}
