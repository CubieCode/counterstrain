package com.cubiecode.remission.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.LocationPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A trigger for a player acting on one block position: {@code remission:nidus_destroyed} (after the nidus was removed,
 * so a {@code block} or {@code fluid} predicate sees what replaced it) and {@code remission:block_scanned} (the scanned
 * block is still there). Conditions: {@code player}, and {@code location} matched at the centre of the block.
 */
public final class LocationTrigger extends SimpleCriterionTrigger<LocationTrigger.TriggerInstance> {
    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player, BlockPos pos) {
        trigger(player, player.serverLevel(), pos);
    }

    /** Fires for a position in {@code level}, which need not be the player's level. */
    public void trigger(ServerPlayer player, ServerLevel level, BlockPos pos) {
        trigger(player, instance -> instance.matches(level, pos));
    }

    public record TriggerInstance(Optional<ContextAwarePredicate> player, Optional<LocationPredicate> location)
            implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                LocationPredicate.CODEC.optionalFieldOf("location").forGetter(TriggerInstance::location)
        ).apply(instance, TriggerInstance::new));

        public boolean matches(ServerLevel level, BlockPos pos) {
            return location.isEmpty() || location.get().matches(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
    }
}
