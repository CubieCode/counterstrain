package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.GlobalLootModifierProvider;

/** Global loot modifiers (planned: host drops gated on {@code remission:is_corrupted}). */
public final class RemissionLootModifierProvider extends GlobalLootModifierProvider {
    public RemissionLootModifierProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
        super(output, lookup, Remission.MOD_ID);
    }

    @Override
    protected void start() {
    }
}
