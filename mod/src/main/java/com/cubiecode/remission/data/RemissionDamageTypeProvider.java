package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.viralload.ViralLoad;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.world.damagesource.DamageScaling;
import net.minecraft.world.damagesource.DamageType;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;

/**
 * Damage types: {@code remission:viral_load} (P5.1), the damage players take at the top of the viral load meter. Message
 * id {@code remission.viral_load}, no difficulty scaling, no exhaustion.
 */
public final class RemissionDamageTypeProvider extends DatapackBuiltinEntriesProvider {
    private static final RegistrySetBuilder DAMAGE_TYPES = new RegistrySetBuilder().add(Registries.DAMAGE_TYPE, context ->
            context.register(ViralLoad.DAMAGE_TYPE, new DamageType("remission.viral_load", DamageScaling.NEVER, 0.0F)));

    public RemissionDamageTypeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
        super(output, lookup, DAMAGE_TYPES, Set.of(Remission.MOD_ID));
    }

    @Override
    public String getName() {
        return "Remission damage types";
    }
}
