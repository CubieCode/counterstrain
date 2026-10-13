package com.cubiecode.remission.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The variant triggers (P7.5), {@code remission:variant_emerged} and {@code remission:variant_sequenced}. Conditions:
 * {@code player}; {@code variant}, a {@code remission:variant} id (absent matches every variant).
 */
public final class VariantTrigger extends SimpleCriterionTrigger<VariantTrigger.TriggerInstance> {
    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player, ResourceLocation variant) {
        trigger(player, instance -> instance.matches(variant));
    }

    public record TriggerInstance(Optional<ContextAwarePredicate> player, Optional<ResourceLocation> variant)
            implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                ResourceLocation.CODEC.optionalFieldOf("variant").forGetter(TriggerInstance::variant)
        ).apply(instance, TriggerInstance::new));

        public boolean matches(ResourceLocation variant) {
            return this.variant.map(variant::equals).orElse(true);
        }
    }
}
