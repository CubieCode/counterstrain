package com.cubiecode.remission.network;

import com.cubiecode.remission.Remission;
import it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMaps;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * {@code remission:festering_map}, server to client (P8.2): the state of chunks in one dimension, for map overlays
 * ({@code integration/journeymap/}): the festering stage in the low seven bits, and the top bit
 * ({@link #REMISSION}) set when the chunk is in remission. {@link FesteringMapSync} sends a chunk's state,
 * 0 included, when the chunk is sent to the player and when its stage changes or it enters remission while the player
 * watches it. Wire format: the dimension key, a var-int entry count, then per entry the chunk position as a long and the
 * state as a byte.
 */
public record FesteringMapPayload(ResourceKey<Level> dimension, Long2ByteMap states) implements CustomPacketPayload {
    public static final Type<FesteringMapPayload> TYPE = new Type<>(Remission.id("festering_map"));
    public static final StreamCodec<FriendlyByteBuf, FesteringMapPayload> STREAM_CODEC =
            StreamCodec.of(FesteringMapPayload::write, FesteringMapPayload::read);
    /** The state bit set when a chunk is in remission (its stage is then 0). */
    public static final int REMISSION = 0x80;
    public static final int STAGE_MASK = 0x7F;
    /** Entries per payload; a larger batch is split. */
    static final int MAX_ENTRIES = 4096;

    private static final StreamCodec<FriendlyByteBuf, ResourceKey<Level>> DIMENSION_CODEC =
            ResourceKey.streamCodec(Registries.DIMENSION).cast();

    public FesteringMapPayload {
        states = Long2ByteMaps.unmodifiable(new Long2ByteLinkedOpenHashMap(states));
    }

    /** A chunk state: {@code stage} in the low bits, plus {@link #REMISSION} when the chunk is in remission. */
    public static int state(int stage, boolean inRemission) {
        return (stage & STAGE_MASK) | (inRemission ? REMISSION : 0);
    }

    @Override
    public Type<FesteringMapPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buf, FesteringMapPayload payload) {
        DIMENSION_CODEC.encode(buf, payload.dimension());
        buf.writeVarInt(payload.states().size());
        for (Long2ByteMap.Entry entry : payload.states().long2ByteEntrySet()) {
            buf.writeLong(entry.getLongKey());
            buf.writeByte(entry.getByteValue());
        }
    }

    private static FesteringMapPayload read(FriendlyByteBuf buf) {
        ResourceKey<Level> dimension = DIMENSION_CODEC.decode(buf);
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new IllegalArgumentException("festering map payload with " + size + " entries");
        }
        Long2ByteLinkedOpenHashMap states = new Long2ByteLinkedOpenHashMap(size);
        for (int i = 0; i < size; i++) {
            states.put(buf.readLong(), buf.readByte());
        }
        return new FesteringMapPayload(dimension, states);
    }

    /** Client side, on the main thread: hands the states to the client cache, which keeps them only while a map uses it. */
    static void handle(FesteringMapPayload payload, IPayloadContext context) {
        FesteringMapCache.apply(payload.dimension(), payload.states());
    }
}
