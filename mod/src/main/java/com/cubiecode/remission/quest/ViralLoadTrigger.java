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
 * {@code remission:viral_load_changed}: the player's viral load changed (at most once a second, plus once per drink).
 * Conditions: {@code player}; {@code viral_load}, the new viral load (0 to 100), for example {@code {"min": 50}} for a
 * player who reached 50, or {@code {"max": 0}} for one whose viral load fell back to 0.
 */
public final class ViralLoadTrigger extends SimpleCriterionTrigger<ViralLoadTrigger.TriggerInstance> {
    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player, float viralLoad) {
        trigger(player, instance -> instance.matches(viralLoad));
    }

    public record TriggerInstance(Optional<ContextAwarePredicate> player, MinMaxBounds.Doubles viralLoad)
            implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                MinMaxBounds.Doubles.CODEC.optionalFieldOf("viral_load", MinMaxBounds.Doubles.ANY).forGetter(TriggerInstance::viralLoad)
        ).apply(instance, TriggerInstance::new));

        public boolean matches(float load) {
            return viralLoad.matches(load);
        }
    }
}
