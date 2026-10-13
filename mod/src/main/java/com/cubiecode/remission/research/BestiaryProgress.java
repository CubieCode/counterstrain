package com.cubiecode.remission.research;

import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * One player's bestiary progress: the highest host stage they have scanned for each host type. Stored as the player's
 * {@code remission:bestiary} attachment. Ids stay ids so progress for a since-removed mod's types survives in the save;
 * the bestiary simply does not list them. A team's progress (P6.4) is the same shape: the {@link #with(BestiaryProgress)}
 * of its members' records.
 */
public record BestiaryProgress(Map<ResourceLocation, Integer> highestStages) {
    public static final BestiaryProgress EMPTY = new BestiaryProgress(Map.of());

    /** {@code {"<entity type id>": <highest host stage scanned>, ...}}. */
    public static final Codec<BestiaryProgress> CODEC = Codec.unboundedMap(ResourceLocation.CODEC,
            Codec.intRange(1, Integer.MAX_VALUE)).xmap(BestiaryProgress::new, BestiaryProgress::highestStages);

    public static final StreamCodec<RegistryFriendlyByteBuf, BestiaryProgress> STREAM_CODEC = ByteBufCodecs
            .<RegistryFriendlyByteBuf, ResourceLocation, Integer, Map<ResourceLocation, Integer>>map(HashMap::new,
                    ResourceLocation.STREAM_CODEC, ByteBufCodecs.VAR_INT)
            .map(BestiaryProgress::new, BestiaryProgress::highestStages);

    public BestiaryProgress {
        highestStages = Map.copyOf(highestStages);
    }

    public boolean isEmpty() {
        return highestStages.isEmpty();
    }

    public boolean isScanned(ResourceLocation type) {
        return highestStages.containsKey(type);
    }

    /** The highest host stage scanned for the type, or 0 if it was never scanned. */
    public int highestStage(ResourceLocation type) {
        return highestStages.getOrDefault(type, 0);
    }

    /** This progress with a host scan of the type at the stage added; the same instance if it adds nothing. */
    public BestiaryProgress withScan(ResourceLocation type, int stage) {
        if (stage < 1 || stage <= highestStage(type)) {
            return this;
        }
        Map<ResourceLocation, Integer> stages = new HashMap<>(highestStages);
        stages.put(type, stage);
        return new BestiaryProgress(stages);
    }

    /** This progress joined with {@code other}: every type at the higher of the two stages; this instance if it adds nothing. */
    public BestiaryProgress with(BestiaryProgress other) {
        BestiaryProgress joined = this;
        for (Map.Entry<ResourceLocation, Integer> entry : other.highestStages.entrySet()) {
            joined = joined.withScan(entry.getKey(), entry.getValue());
        }
        return joined;
    }
}
