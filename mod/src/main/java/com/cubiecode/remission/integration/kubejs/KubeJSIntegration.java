package com.cubiecode.remission.integration.kubejs;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.BlockFesteredEvent;
import com.cubiecode.remission.api.BlockPurifiedEvent;
import com.cubiecode.remission.api.ChunkEnteredRemissionEvent;
import com.cubiecode.remission.api.ChunkStageChangedEvent;
import com.cubiecode.remission.api.CountermeasureGrantedEvent;
import com.cubiecode.remission.api.HostCleansedEvent;
import com.cubiecode.remission.api.HostCorruptedEvent;
import com.cubiecode.remission.api.NidusDamagedEvent;
import com.cubiecode.remission.api.NidusDestroyedEvent;
import com.cubiecode.remission.api.RevenantRisenEvent;
import com.cubiecode.remission.api.RevenantSlainEvent;
import com.cubiecode.remission.api.StrainHazardEvent;
import com.cubiecode.remission.api.StrainSpreadAttemptEvent;
import com.cubiecode.remission.api.SurgeEndedEvent;
import com.cubiecode.remission.api.SurgeStartedEvent;
import com.cubiecode.remission.api.TeamLookupEvent;
import com.cubiecode.remission.api.VariantEmergedEvent;
import com.cubiecode.remission.api.ViralLoadChangedEvent;
import com.cubiecode.remission.gametest.KubeJSBindingTests;
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
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.VariantEmerged;
import com.cubiecode.remission.integration.kubejs.RemissionKubeEvents.ViralLoadChanged;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.event.KubeEvent;
import dev.latvian.mods.kubejs.script.ScriptType;
import java.util.function.Function;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Optional KubeJS integration (P8.3): forwards every public API event to its {@link RemissionEvents} handler, so server
 * scripts can listen, cancel and change them. KubeJS is {@code compileOnly}; this class is loaded only from
 * {@link Remission}'s constructor after a {@code ModList} check, and {@link RemissionKubeJSPlugin} only by KubeJS. An
 * event is wrapped only when a script listens to it.
 */
public final class KubeJSIntegration {
    public static final String MOD_ID = "kubejs";

    private KubeJSIntegration() {
    }

    public static void register(IEventBus modBus) {
        forward(HostCorruptedEvent.class, RemissionEvents.HOST_CORRUPTED, HostCorrupted::new);
        forward(HostCleansedEvent.class, RemissionEvents.HOST_CLEANSED, HostCleansed::new);
        forward(BlockFesteredEvent.class, RemissionEvents.BLOCK_FESTERED, BlockFestered::new);
        forward(BlockPurifiedEvent.class, RemissionEvents.BLOCK_PURIFIED, BlockPurified::new);
        forward(ChunkStageChangedEvent.class, RemissionEvents.CHUNK_STAGE_CHANGED, ChunkStageChanged::new);
        forward(ChunkEnteredRemissionEvent.class, RemissionEvents.CHUNK_ENTERED_REMISSION, ChunkEnteredRemission::new);
        forward(StrainSpreadAttemptEvent.class, RemissionEvents.STRAIN_SPREAD_ATTEMPT, StrainSpreadAttempt::new);
        forward(NidusDamagedEvent.class, RemissionEvents.NIDUS_DAMAGED, NidusDamaged::new);
        forward(NidusDestroyedEvent.class, RemissionEvents.NIDUS_DESTROYED, NidusDestroyed::new);
        forward(SurgeStartedEvent.class, RemissionEvents.SURGE_STARTED, SurgeStarted::new);
        forward(SurgeEndedEvent.class, RemissionEvents.SURGE_ENDED, SurgeEnded::new);
        forward(RevenantRisenEvent.class, RemissionEvents.REVENANT_RISEN, RevenantRisen::new);
        forward(RevenantSlainEvent.class, RemissionEvents.REVENANT_SLAIN, RevenantSlain::new);
        forward(ViralLoadChangedEvent.class, RemissionEvents.VIRAL_LOAD_CHANGED, ViralLoadChanged::new);
        forward(TeamLookupEvent.class, RemissionEvents.TEAM_LOOKUP, TeamLookup::new);
        forward(VariantEmergedEvent.class, RemissionEvents.VARIANT_EMERGED, VariantEmerged::new);
        NeoForge.EVENT_BUS.addListener(StrainHazardEvent.class, event -> {
            if (RemissionEvents.STRAIN_HAZARD.hasListeners()) {
                RemissionEvents.STRAIN_HAZARD.post(ScriptType.SERVER, event.getHazard(), new StrainHazard(event))
                        .applyCancel(event);
            }
        });
        NeoForge.EVENT_BUS.addListener(CountermeasureGrantedEvent.class, event -> {
            if (RemissionEvents.COUNTERMEASURE_GRANTED.hasListeners()) {
                RemissionEvents.COUNTERMEASURE_GRANTED.post(ScriptType.SERVER, event.getHazard(),
                        new CountermeasureGranted(event));
            }
        });
        KubeJSBindingTests.register(modBus);
        Remission.LOGGER.info("KubeJS present: posting Remission API events to RemissionEvents");
    }

    /** Posts {@code type} to {@code handler}'s server scripts, applying a script's cancellation to cancellable events. */
    private static <E extends Event> void forward(Class<E> type, EventHandler handler, Function<E, KubeEvent> wrapper) {
        NeoForge.EVENT_BUS.addListener(type, event -> {
            if (handler.hasListeners()) {
                var result = handler.post(ScriptType.SERVER, wrapper.apply(event));
                if (event instanceof ICancellableEvent cancellable) {
                    result.applyCancel(cancellable);
                }
            }
        });
    }
}
