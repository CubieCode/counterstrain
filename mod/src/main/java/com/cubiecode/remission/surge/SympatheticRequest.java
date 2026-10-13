package com.cubiecode.remission.surge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

/**
 * A sympathetic surge waiting for the level's surge slot (P4.13): it warns from {@code origin} at {@code intensity} once
 * no surge is running, set off by {@code attacked}, the nidus under attack.
 */
public record SympatheticRequest(BlockPos origin, int intensity, BlockPos attacked) {
    public static final Codec<SympatheticRequest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("origin").forGetter(SympatheticRequest::origin),
            Codec.intRange(1, Surge.MAX_INTENSITY).fieldOf("intensity").forGetter(SympatheticRequest::intensity),
            BlockPos.CODEC.fieldOf("attacked").forGetter(SympatheticRequest::attacked)
    ).apply(instance, SympatheticRequest::new));

    public SympatheticRequest {
        origin = origin.immutable();
        attacked = attacked.immutable();
    }
}
