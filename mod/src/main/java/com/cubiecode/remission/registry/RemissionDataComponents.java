package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.research.ScanData;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Item data component types. */
public final class RemissionDataComponents {
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Remission.MOD_ID);

    /** What a host scan or block scan recorded (research, P6.1). Saved with the stack and synced for tooltips. */
    public static final Supplier<DataComponentType<ScanData>> SCAN_DATA = DATA_COMPONENTS.registerComponentType(
            "scan_data", builder -> builder.persistent(ScanData.CODEC).networkSynchronized(ScanData.STREAM_CODEC));

    private RemissionDataComponents() {
    }
}
