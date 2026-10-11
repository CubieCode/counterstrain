package com.cubiecode.remission.loot;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.registry.RemissionLootConditions;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParam;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import net.minecraft.world.phys.Vec3;

/**
 * Loot condition {@code remission:is_festering}: true when the chunk at the loot {@code origin} is at festering stage
 * {@code min_stage} or higher and, if {@code blocks} is set, the loot's {@code block_state} is one of those blocks. False
 * without an origin, or with {@code blocks} set and no block state. Chunk stages are read from loaded chunks only.
 */
public record IsFesteringCondition(int minStage, Optional<HolderSet<Block>> blocks) implements LootItemCondition {
    /** Blocks that drop festered soil in festering chunks (the default {@code remission:festered_soil_samples}). */
    public static final TagKey<Block> FESTERED_SOIL_SOURCES =
            TagKey.create(Registries.BLOCK, Remission.id("festered_soil_sources"));

    public static final MapCodec<IsFesteringCondition> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("min_stage", 1).forGetter(IsFesteringCondition::minStage),
            RegistryCodecs.homogeneousList(Registries.BLOCK).optionalFieldOf("blocks")
                    .forGetter(IsFesteringCondition::blocks)
    ).apply(instance, IsFesteringCondition::new));

    @Override
    public LootItemConditionType getType() {
        return RemissionLootConditions.IS_FESTERING.get();
    }

    @Override
    public Set<LootContextParam<?>> getReferencedContextParams() {
        return blocks.isPresent() ? Set.of(LootContextParams.ORIGIN, LootContextParams.BLOCK_STATE)
                : Set.of(LootContextParams.ORIGIN);
    }

    @Override
    public boolean test(LootContext context) {
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        if (origin == null) {
            return false;
        }
        if (blocks.isPresent()) {
            BlockState state = context.getParamOrNull(LootContextParams.BLOCK_STATE);
            if (state == null || !state.is(blocks.get())) {
                return false;
            }
        }
        return CorruptionQueries.stageAt(context.getLevel(), BlockPos.containing(origin)) >= minStage;
    }
}
