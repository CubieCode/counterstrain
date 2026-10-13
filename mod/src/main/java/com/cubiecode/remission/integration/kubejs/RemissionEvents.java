package com.cubiecode.remission.integration.kubejs;

import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.BlockFestered;
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.BlockPurified;
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.ChunkEnteredRemission;
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.ChunkStageChanged;
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.CountermeasureGranted;
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.HostCleansed;
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.HostCorrupted;
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.NidusDamaged;
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.NidusDestroyed;
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.RevenantRisen;
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.RevenantSlain;
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.StrainHazard;
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.StrainSpreadAttempt;
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.SurgeEnded;
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.SurgeStarted;
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.TeamLookup;
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.ViralLoadChanged;
import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.event.EventTargetType;
import dev.latvian.mods.kubejs.event.TargetedEventHandler;
import net.minecraft.resources.ResourceLocation;

/**
 * The {@code RemissionEvents} KubeJS event group (P8.3): one server-script handler per public API event in
 * {@code com.cubiecode.remission.api}. Handlers marked cancellable take {@code event.cancel()}; the hazard events take an
 * optional hazard ID target ({@code RemissionEvents.strainHazard('remission:contact', event => ...)}). Contract:
 * remission-data-formats, "KubeJS event bindings".
 */
public interface RemissionEvents {
    EventGroup GROUP = EventGroup.of("RemissionEvents");

    EventHandler HOST_CORRUPTED = GROUP.server("hostCorrupted", () -> HostCorrupted.class);
    EventHandler HOST_CLEANSED = GROUP.server("hostCleansed", () -> HostCleansed.class);
    EventHandler BLOCK_FESTERED = GROUP.server("blockFestered", () -> BlockFestered.class);
    EventHandler BLOCK_PURIFIED = GROUP.server("blockPurified", () -> BlockPurified.class);
    EventHandler CHUNK_STAGE_CHANGED = GROUP.server("chunkStageChanged", () -> ChunkStageChanged.class);
    EventHandler CHUNK_ENTERED_REMISSION = GROUP.server("chunkEnteredRemission", () -> ChunkEnteredRemission.class);
    /** Cancellable: cancelling stops this spread step. */
    EventHandler STRAIN_SPREAD_ATTEMPT = GROUP.server("strainSpreadAttempt", () -> StrainSpreadAttempt.class).hasResult();
    /** Cancellable: cancelling means the nidus takes no damage. */
    EventHandler NIDUS_DAMAGED = GROUP.server("nidusDamaged", () -> NidusDamaged.class).hasResult();
    EventHandler NIDUS_DESTROYED = GROUP.server("nidusDestroyed", () -> NidusDestroyed.class);
    EventHandler SURGE_STARTED = GROUP.server("surgeStarted", () -> SurgeStarted.class);
    EventHandler SURGE_ENDED = GROUP.server("surgeEnded", () -> SurgeEnded.class);
    EventHandler REVENANT_RISEN = GROUP.server("revenantRisen", () -> RevenantRisen.class);
    EventHandler REVENANT_SLAIN = GROUP.server("revenantSlain", () -> RevenantSlain.class);
    EventHandler VIRAL_LOAD_CHANGED = GROUP.server("viralLoadChanged", () -> ViralLoadChanged.class);
    /** Cancellable: cancelling holds the hazard back. Optional target: the hazard ID. */
    TargetedEventHandler<ResourceLocation> STRAIN_HAZARD = GROUP.server("strainHazard", () -> StrainHazard.class)
            .hasResult()
            .supportsTarget(EventTargetType.ID);
    /** Optional target: the hazard ID. */
    TargetedEventHandler<ResourceLocation> COUNTERMEASURE_GRANTED = GROUP
            .server("countermeasureGranted", () -> CountermeasureGranted.class)
            .supportsTarget(EventTargetType.ID);
    /** Answer with {@code event.setTeammates(...)}. */
    EventHandler TEAM_LOOKUP = GROUP.server("teamLookup", () -> TeamLookup.class);
}
