package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.quest.HostScannedTrigger;
import com.cubiecode.remission.quest.LocationTrigger;
import com.cubiecode.remission.quest.RegionCleansedTrigger;
import com.cubiecode.remission.quest.SurgeSurvivedTrigger;
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

    public static final Supplier<RegionCleansedTrigger> REGION_CLEANSED =
            TRIGGER_TYPES.register("region_cleansed", RegionCleansedTrigger::new);

    private RemissionTriggers() {
    }
}
