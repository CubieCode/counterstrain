package com.cubiecode.remission.loot;

import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.registry.RemissionLootConditions;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParam;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

/**
 * Loot condition {@code remission:is_corrupted}: true when the {@code entity} loot target is a living host at host stage
 * {@code min_stage} or higher. False when the target is absent or not living.
 */
public record IsCorruptedCondition(LootContext.EntityTarget entity, int minStage) implements LootItemCondition {
    public static final MapCodec<IsCorruptedCondition> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            LootContext.EntityTarget.CODEC.optionalFieldOf("entity", LootContext.EntityTarget.THIS)
                    .forGetter(IsCorruptedCondition::entity),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("min_stage", 1).forGetter(IsCorruptedCondition::minStage)
    ).apply(instance, IsCorruptedCondition::new));

    @Override
    public LootItemConditionType getType() {
        return RemissionLootConditions.IS_CORRUPTED.get();
    }

    @Override
    public Set<LootContextParam<?>> getReferencedContextParams() {
        return Set.of(entity.getParam());
    }

    @Override
    public boolean test(LootContext context) {
        Entity target = context.getParamOrNull(entity.getParam());
        return target instanceof LivingEntity living && Corruption.get(living).stage() >= minStage;
    }
}
