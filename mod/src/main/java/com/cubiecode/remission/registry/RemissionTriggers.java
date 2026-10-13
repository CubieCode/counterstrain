package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.quest.CountermeasureGainedTrigger;
import com.cubiecode.remission.quest.HostScannedTrigger;
import com.cubiecode.remission.quest.LocationTrigger;
import com.cubiecode.remission.quest.RegionCleansedTrigger;
import com.cubiecode.remission.quest.RevenantReclaimedTrigger;
import com.cubiecode.remission.quest.SurgeSurvivedTrigger;
import com.cubiecode.remission.quest.VariantTrigger;
import com.cubiecode.remission.quest.ViralLoadTrigger;
import java.util.function.Supplier;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Advancement criterion triggers that quest mods gate on (schemas: remission-data-formats). */
public final class RemissionTriggers {
    public static final DeferredRegister<CriterionTrigger<?>> TRIGGER_TYPES =
            DeferredRegister.create(Registries.TRIGGER_TYPE, Remission.MOD_ID);

    public static final Supplier<LocationTrigger> NIDUS_DESTROYED =
            TRIGGER_TYPES.register("nidus_destroyed", LocationTrigger::new);
    public static final Supplier<HostScannedTrigger> HOST_SCANNED =
            TRIGGER_TYPES.register("host_scanned", HostScannedTrigger::new);
    public static final Supplier<LocationTrigger> BLOCK_SCANNED =
            TRIGGER_TYPES.register("block_scanned", LocationTrigger::new);
    public static final Supplier<SurgeSurvivedTrigger> SURGE_SURVIVED =
            TRIGGER_TYPES.register("surge_survived", SurgeSurvivedTrigger::new);
    public static final Supplier<ViralLoadTrigger> VIRAL_LOAD_CHANGED =
            TRIGGER_TYPES.register("viral_load_changed", ViralLoadTrigger::new);
    public static final Supplier<RevenantReclaimedTrigger> REVENANT_RECLAIMED =
            TRIGGER_TYPES.register("revenant_reclaimed", RevenantReclaimedTrigger::new);

    public static final Supplier<RegionCleansedTrigger> REGION_CLEANSED =
            TRIGGER_TYPES.register("region_cleansed", RegionCleansedTrigger::new);

    public static final Supplier<CountermeasureGainedTrigger> COUNTERMEASURE_GAINED =
            TRIGGER_TYPES.register("countermeasure_gained", CountermeasureGainedTrigger::new);

    /** A variant emerged at a nidus whose land the player is in (P7.5). */
    public static final Supplier<VariantTrigger> VARIANT_EMERGED =
            TRIGGER_TYPES.register("variant_emerged", VariantTrigger::new);
    /** A sequencer near the player finished a strain sequence of a variant (P7.5). */
    public static final Supplier<VariantTrigger> VARIANT_SEQUENCED =
            TRIGGER_TYPES.register("variant_sequenced", VariantTrigger::new);

    private RemissionTriggers() {
    }
}
