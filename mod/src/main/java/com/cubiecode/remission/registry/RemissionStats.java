package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Custom stats under {@code minecraft:custom} that quest mods read (schemas: remission-data-formats). */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class RemissionStats {
    public static final DeferredRegister<ResourceLocation> CUSTOM_STATS =
            DeferredRegister.create(Registries.CUSTOM_STAT, Remission.MOD_ID);

    /** Nidi a player broke (P8.1). */
    public static final Supplier<ResourceLocation> NIDI_DESTROYED = register("nidi_destroyed");
    /** Host scans a player took with the scanner (P8.1). */
    public static final Supplier<ResourceLocation> HOSTS_SCANNED = register("hosts_scanned");
    /** Block scans a player took with the scanner (P8.1). */
    public static final Supplier<ResourceLocation> BLOCKS_SCANNED = register("blocks_scanned");
    /** Surges a player survived (P4.3). */
    public static final Supplier<ResourceLocation> SURGES_SURVIVED = register("surges_survived");

    private RemissionStats() {
    }

    private static Supplier<ResourceLocation> register(String name) {
        ResourceLocation id = Remission.id(name);
        return CUSTOM_STATS.register(name, () -> id);
    }

    // Vanilla creates each custom stat with its formatter as it registers it; ours are created once registries are frozen.
    @SubscribeEvent
    static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> CUSTOM_STATS.getEntries().forEach(stat -> Stats.CUSTOM.get(stat.get(), StatFormatter.DEFAULT)));
    }
}
