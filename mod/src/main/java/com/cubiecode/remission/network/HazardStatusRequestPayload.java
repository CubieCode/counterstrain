package com.cubiecode.remission.network;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.notice.HazardStatus;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * {@code remission:hazard_status_request}, client to server (P5.8): the bestiary's hazards tab opened; the server
 * answers with {@link HazardStatusPayload} (at most every {@code HazardStatus.REQUEST_MILLIS} milliseconds per player). No body.
 */
public record HazardStatusRequestPayload() implements CustomPacketPayload {
    public static final HazardStatusRequestPayload INSTANCE = new HazardStatusRequestPayload();
    public static final Type<HazardStatusRequestPayload> TYPE = new Type<>(Remission.id("hazard_status_request"));
    public static final StreamCodec<ByteBuf, HazardStatusRequestPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<HazardStatusRequestPayload> type() {
        return TYPE;
    }

    /** Server side, on the main thread. */
    static void handle(HazardStatusRequestPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HazardStatus.request(player);
        }
    }
}
