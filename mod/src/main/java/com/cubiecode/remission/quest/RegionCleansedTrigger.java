package com.cubiecode.remission.quest;

import com.cubiecode.remission.api.CorruptionQueries;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Optional;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * {@code remission:region_cleansed}: a chunk near the player entered remission. Conditions: {@code player};
 * {@code radius} (chunks, 0-16, default 4), the square of chunks around the player's chunk the region covers;
 * {@code share}, the share (0-1) of the region's affected chunks (in remission or festering) that are in remission;
 * {@code chunks}, how many of its chunks are in remission. Only loaded chunks count.
 */
public final class RegionCleansedTrigger extends SimpleCriterionTrigger<RegionCleansedTrigger.TriggerInstance> {
    /** Largest region radius a condition may ask for. */
    public static final int MAX_RADIUS = 16;
    public static final int DEFAULT_RADIUS = 4;

    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    /** Fires for {@code player}, measuring each condition's region around the player's chunk now. */
    public void trigger(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        ChunkPos centre = player.chunkPosition();
        Int2ObjectOpenHashMap<Region> regions = new Int2ObjectOpenHashMap<>();
        trigger(player, instance -> instance.matches(regions.computeIfAbsent(instance.radius(), radius -> region(level, centre, radius))));
    }

    /** The loaded chunks within {@code radius} of {@code centre}: how many are in remission and how many festering. */
    public static Region region(ServerLevel level, ChunkPos centre, int radius) {
        int remission = 0;
        int festering = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos chunk = new ChunkPos(centre.x + dx, centre.z + dz);
                if (CorruptionQueries.isInRemission(level, chunk)) {
                    remission++;
                } else if (CorruptionQueries.chunkLevel(level, chunk) > 0) {
                    festering++;
                }
            }
        }
        return new Region(remission, festering);
    }

    /** A measured region: chunks in remission and chunks festering. */
    public record Region(int remission, int festering) {
        /** Chunks in remission over affected chunks (in remission or festering); 0 when none are affected. */
        public double share() {
            int affected = remission + festering;
            return affected == 0 ? 0.0 : (double) remission / affected;
        }
    }

    public record TriggerInstance(Optional<ContextAwarePredicate> player, int radius, MinMaxBounds.Doubles share,
                                  MinMaxBounds.Ints chunks) implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                Codec.intRange(0, MAX_RADIUS).optionalFieldOf("radius", DEFAULT_RADIUS).forGetter(TriggerInstance::radius),
                MinMaxBounds.Doubles.CODEC.optionalFieldOf("share", MinMaxBounds.Doubles.ANY).forGetter(TriggerInstance::share),
                MinMaxBounds.Ints.CODEC.optionalFieldOf("chunks", MinMaxBounds.Ints.ANY).forGetter(TriggerInstance::chunks)
        ).apply(instance, TriggerInstance::new));

        public boolean matches(Region region) {
            return share.matches(region.share()) && chunks.matches(region.remission());
        }
    }
}
