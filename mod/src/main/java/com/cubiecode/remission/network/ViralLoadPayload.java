package com.cubiecode.remission.network;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.viralload.ViralLoadState;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * {@code remission:viral_load}, server to client: the receiving player's own viral load and trend, for the HUD meter. Sent
 * only to the player it belongs to, when either changes (at most once per update) and on login, respawn and dimension
 * change. Wire format: the load as a float, then the trend ordinal as a var-int.
 */
public record ViralLoadPayload(float load, ViralLoadState.Trend trend) implements CustomPacketPayload {
    public static final Type<ViralLoadPayload> TYPE = new Type<>(Remission.id("viral_load"));
    public static final StreamCodec<ByteBuf, ViralLoadPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, ViralLoadPayload::load,
            ByteBufCodecs.VAR_INT.map(ViralLoadState.Trend::byId, ViralLoadState.Trend::ordinal), ViralLoadPayload::trend,
            ViralLoadPayload::new);

    public static ViralLoadPayload of(ViralLoadState state) {
        return new ViralLoadPayload(state.load(), state.trend());
    }

    @Override
    public Type<ViralLoadPayload> type() {
        return TYPE;
    }

    /** Client side, on the main thread: stores the state on the local player, where the HUD and the API query read it. */
    static void handle(ViralLoadPayload payload, IPayloadContext context) {
        ViralLoadState state = new ViralLoadState(payload.load(), payload.trend());
        if (state.equals(ViralLoadState.NONE)) {
            context.player().removeData(RemissionAttachments.VIRAL_LOAD);
        } else {
            context.player().setData(RemissionAttachments.VIRAL_LOAD, state);
        }
    }
}
