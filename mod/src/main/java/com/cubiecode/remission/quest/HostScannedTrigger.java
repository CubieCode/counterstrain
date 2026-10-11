package com.cubiecode.remission.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.CriterionValidator;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.loot.LootContext;

/**
 * {@code remission:host_scanned}: the player took a host scan. Conditions: {@code player}; {@code entity}, the scanned
 * host; {@code stage}, its host stage when scanned; {@code bestiary_types}, how many host types the player's bestiary
 * holds after the scan.
 */
public final class HostScannedTrigger extends SimpleCriterionTrigger<HostScannedTrigger.TriggerInstance> {
    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player, LivingEntity host, int stage, int bestiaryTypes) {
        LootContext context = EntityPredicate.createContext(player, host);
        trigger(player, instance -> instance.matches(context, stage, bestiaryTypes));
    }

    public record TriggerInstance(Optional<ContextAwarePredicate> player, Optional<ContextAwarePredicate> entity,
            MinMaxBounds.Ints stage, MinMaxBounds.Ints bestiaryTypes) implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("entity").forGetter(TriggerInstance::entity),
                MinMaxBounds.Ints.CODEC.optionalFieldOf("stage", MinMaxBounds.Ints.ANY).forGetter(TriggerInstance::stage),
                MinMaxBounds.Ints.CODEC.optionalFieldOf("bestiary_types", MinMaxBounds.Ints.ANY).forGetter(TriggerInstance::bestiaryTypes)
        ).apply(instance, TriggerInstance::new));

        public boolean matches(LootContext host, int hostStage, int types) {
            return (entity.isEmpty() || entity.get().matches(host)) && stage.matches(hostStage) && bestiaryTypes.matches(types);
        }

        @Override
        public void validate(CriterionValidator validator) {
            SimpleCriterionTrigger.SimpleInstance.super.validate(validator);
            validator.validateEntity(entity, ".entity");
        }
    }
}
