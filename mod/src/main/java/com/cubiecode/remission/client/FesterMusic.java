package com.cubiecode.remission.client;

import com.cubiecode.remission.registry.RemissionSounds;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.Music;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Stops the fester music once the player is out of festering land. The fester biome's music replaces whatever plays
 * when a player walks in, but most other music never replaces the current track, so without this a fester track
 * played to its end after the player left, or after the land around them was purified. While a fester track plays
 * and the situational music is something else, that music is made to replace it; the music manager then stops the
 * track and waits its usual short delay before the next one.
 */
public final class FesterMusic {
    private FesterMusic() {
    }

    /** From the client entrypoint. */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(FesterMusic::onSelectMusic);
    }

    private static void onSelectMusic(SelectMusicEvent event) {
        SoundInstance playing = event.getPlayingMusic();
        Music music = event.getMusic();
        if (playing == null || music == null || music.replaceCurrentMusic() || !isFester(playing)
                || music.getEvent().is(RemissionSounds.FESTER_MUSIC.getKey())) {
            return;
        }
        event.setMusic(new Music(music.getEvent(), music.getMinDelay(), music.getMaxDelay(), true));
    }

    private static boolean isFester(SoundInstance sound) {
        return sound.getLocation().equals(RemissionSounds.FESTER_MUSIC.getId());
    }
}
