package com.cubiecode.remission.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code remission:surge_survived}: a surge the player was caught in ended and the player did not die during it.
 * Conditions: {@code player}; {@code intensity}, the surge's intensity.
 */
public final class SurgeSurvivedTrigger extends SimpleCriterionTrigger<SurgeSurvivedTrigger.TriggerInstance> {
    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player, int intensity) {
        trigger(player, instance -> instance.matches(intensity));
    }

    public record TriggerInstance(Optional<ContextAwarePredicate> player, MinMaxBounds.Ints intensity)
            implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                MinMaxBounds.Ints.CODEC.optionalFieldOf("intensity", MinMaxBounds.Ints.ANY).forGetter(TriggerInstance::intensity)
        ).apply(instance, TriggerInstance::new));

        public boolean matches(int surgeIntensity) {
            return intensity.matches(surgeIntensity);
        }
    }
}
