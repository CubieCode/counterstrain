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

    /**
     * Wet, organic noises (P4.11), which the nidus makes now and then (its heartbeat). The fester biome no longer plays
     * them; its sounds wait for the art pass.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> FESTER_ADDITIONS = register("ambient.fester.additions");

    private RemissionSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(Remission.id(name)));
    }
}
