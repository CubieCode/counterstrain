package com.cubiecode.remission.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code remission:revenant_reclaimed}: the player slew a revenant whose items they may pick up, their own or one the
 * revenant pickup event allows them to collect (a pack's teammates). Conditions: {@code player}; {@code own}, whether the
 * revenant was the player's own (absent matches both).
 */
public final class RevenantReclaimedTrigger extends SimpleCriterionTrigger<RevenantReclaimedTrigger.TriggerInstance> {
    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player, boolean own) {
        trigger(player, instance -> instance.matches(own));
    }

    public record TriggerInstance(Optional<ContextAwarePredicate> player, Optional<Boolean> own)
            implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                Codec.BOOL.optionalFieldOf("own").forGetter(TriggerInstance::own)
        ).apply(instance, TriggerInstance::new));

        public boolean matches(boolean wasOwn) {
            return own.map(value -> value == wasOwn).orElse(true);
        }
    }
}
