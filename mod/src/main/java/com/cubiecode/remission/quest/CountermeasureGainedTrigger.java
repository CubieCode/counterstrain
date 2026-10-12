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
 * {@code remission:countermeasure_gained}: the player gained a countermeasure, by protocol, from a teammate or by
 * command (P5.7). Conditions: {@code player}; {@code hazard}, the hazard id (absent matches every hazard).
 */
public final class CountermeasureGainedTrigger extends SimpleCriterionTrigger<CountermeasureGainedTrigger.TriggerInstance> {
    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player, ResourceLocation hazard) {
        trigger(player, instance -> instance.matches(hazard));
    }

    public record TriggerInstance(Optional<ContextAwarePredicate> player, Optional<ResourceLocation> hazard)
            implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                ResourceLocation.CODEC.optionalFieldOf("hazard").forGetter(TriggerInstance::hazard)
        ).apply(instance, TriggerInstance::new));

        public boolean matches(ResourceLocation gained) {
            return hazard.map(gained::equals).orElse(true);
        }
    }
}
