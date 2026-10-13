package com.cubiecode.remission.network;

import com.cubiecode.remission.Remission;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * {@code remission:hazard_status}, server to client (P5.8): the receiving player's active hazards and their team's
 * countermeasures, each sorted, for the bestiary's hazards tab. Sent in answer to {@link HazardStatusRequestPayload}.
 * Wire format: two lists (var-int length, then hazard ids).
 */
public record HazardStatusPayload(List<ResourceLocation> active, List<ResourceLocation> held)
        implements CustomPacketPayload {
    public static final Type<HazardStatusPayload> TYPE = new Type<>(Remission.id("hazard_status"));
    public static final StreamCodec<ByteBuf, HazardStatusPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), HazardStatusPayload::active,
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), HazardStatusPayload::held,
            HazardStatusPayload::new);

    public HazardStatusPayload {
        active = List.copyOf(active);
        held = List.copyOf(held);
    }

    @Override
    public Type<HazardStatusPayload> type() {
        return TYPE;
    }

    /** Client side, on the main thread: stores the status for the hazards tab. */
    static void handle(HazardStatusPayload payload, IPayloadContext context) {
        HazardClientCache.received(payload);
    }
}
