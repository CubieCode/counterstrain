package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/** Wires every provider into {@code ./gradlew runData}; output goes to {@code src/generated/resources}. */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class RemissionDataGenerators {
    private RemissionDataGenerators() {
    }

    @SubscribeEvent
    static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();
        CompletableFuture<HolderLookup.Provider> lookup = event.getLookupProvider();
        ExistingFileHelper files = event.getExistingFileHelper();

        // Client resources
        generator.addProvider(event.includeClient(), new RemissionBlockStateProvider(output, files));
        generator.addProvider(event.includeClient(), new RemissionItemModelProvider(output, files));

        // Server data
        var blockTags = generator.addProvider(event.includeServer(), new RemissionBlockTagsProvider(output, lookup, files));
        generator.addProvider(event.includeServer(), new RemissionItemTagsProvider(output, lookup, blockTags.contentsGetter(), files));
        generator.addProvider(event.includeServer(), new RemissionEntityTypeTagsProvider(output, lookup, files));
        generator.addProvider(event.includeServer(), new RemissionFluidTagsProvider(output, lookup, files));
        generator.addProvider(event.includeServer(), new RemissionRecipeProvider(output, lookup));
        generator.addProvider(event.includeServer(), new LootTableProvider(output, Set.of(),
                List.of(new LootTableProvider.SubProviderEntry(RemissionBlockLootProvider::new, LootContextParamSets.BLOCK)), lookup));
        generator.addProvider(event.includeServer(), new RemissionLootModifierProvider(output, lookup));
        generator.addProvider(event.includeServer(), new RemissionMobProfileProvider(output, lookup));
        generator.addProvider(event.includeServer(), new RemissionBiomeProvider(output, lookup));
        generator.addProvider(event.includeServer(), new RemissionDataMapProvider(output, lookup));
        var damageTypes = generator.addProvider(event.includeServer(), new RemissionDamageTypeProvider(output, lookup));
        generator.addProvider(event.includeServer(),
                new RemissionDamageTypeTagsProvider(output, damageTypes.getRegistryProvider(), files));
    }
}
