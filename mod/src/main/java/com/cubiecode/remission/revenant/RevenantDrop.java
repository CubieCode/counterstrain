package com.cubiecode.remission.revenant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

/**
 * Pickup restriction on an item a slain revenant dropped (attachment {@code remission:revenant_drop}): only its owner,
 * and the owner's teammates ({@code TeamQueries}), may pick it up from {@code droppedAt + Revenants.PICKUP_DELAY_TICKS}
 * until {@code freeAt}, when it is freed for anyone. Game times, so time spent unloaded counts.
 */
public record RevenantDrop(UUID owner, long droppedAt, long freeAt) {
    public static final Codec<RevenantDrop> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("owner").forGetter(RevenantDrop::owner),
            Codec.LONG.fieldOf("dropped_at").forGetter(RevenantDrop::droppedAt),
            Codec.LONG.fieldOf("free_at").forGetter(RevenantDrop::freeAt)
    ).apply(instance, RevenantDrop::new));
}
