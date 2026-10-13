package com.cubiecode.remission.network;

import com.cubiecode.remission.Remission;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * {@code remission:hazard_notice}, server to client (P5.8): {@code count} strikes of {@code hazard} in {@code areas}
 * areas, one of them near {@code pos} in {@code dimension}; one grouped notice for the receiving player (sent by
 * {@code notice.HazardNoticeTracker}). {@code areas} is 1 except for a summary of several groups. Wire format: the
 * hazard id, the dimension key, the position as a packed long, then the count and the areas as var-ints.
 */
public record HazardNoticePayload(ResourceLocation hazard, ResourceKey<Level> dimension, BlockPos pos, int count,
        int areas) implements CustomPacketPayload {
    public static final Type<HazardNoticePayload> TYPE = new Type<>(Remission.id("hazard_notice"));
    public static final StreamCodec<ByteBuf, HazardNoticePayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, HazardNoticePayload::hazard,
            ResourceKey.streamCodec(Registries.DIMENSION), HazardNoticePayload::dimension,
            BlockPos.STREAM_CODEC, HazardNoticePayload::pos,
            ByteBufCodecs.VAR_INT, HazardNoticePayload::count,
            ByteBufCodecs.VAR_INT, HazardNoticePayload::areas,
            HazardNoticePayload::new);

    /** One group's notice: a single area. */
    public HazardNoticePayload(ResourceLocation hazard, ResourceKey<Level> dimension, BlockPos pos, int count) {
        this(hazard, dimension, pos, count, 1);
    }

    @Override
    public Type<HazardNoticePayload> type() {
        return TYPE;
    }

    /** Client side, on the main thread: stores the notice and tells the client's listeners (chat, map). */
    static void handle(HazardNoticePayload payload, IPayloadContext context) {
        HazardClientCache.received(payload);
    }
}
