package com.cubiecode.remission.network;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.variant.Variants;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * {@code remission:variant_land}, server to client (P7.5): the variant of chunks in one dimension, so the client can tint
 * festering land of a variant. {@link VariantLandSync} sends a chunk's variant with the chunk when it has one, and the
 * new variant (or none) of every chunk of a nidus's land the player watches when the nidus's variant changes. Wire
 * format: the dimension key, a var-int entry count, then per entry the chunk position as a long and the optional variant
 * id (a boolean, then the id).
 */
public record VariantLandPayload(ResourceKey<Level> dimension, Map<Long, Optional<ResourceLocation>> chunks)
        implements CustomPacketPayload {
    public static final Type<VariantLandPayload> TYPE = new Type<>(Remission.id("variant_land"));
    public static final StreamCodec<FriendlyByteBuf, VariantLandPayload> STREAM_CODEC =
            StreamCodec.of(VariantLandPayload::write, VariantLandPayload::read);
    /** Entries per payload; a larger batch is split. */
    static final int MAX_ENTRIES = 4096;

    private static final StreamCodec<FriendlyByteBuf, ResourceKey<Level>> DIMENSION_CODEC =
            ResourceKey.streamCodec(Registries.DIMENSION).cast();
    private static final StreamCodec<FriendlyByteBuf, Optional<ResourceLocation>> VARIANT_CODEC =
            ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).cast();

    public VariantLandPayload {
        chunks = Collections.unmodifiableMap(new LinkedHashMap<>(chunks));
    }

    @Override
    public Type<VariantLandPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buf, VariantLandPayload payload) {
        DIMENSION_CODEC.encode(buf, payload.dimension());
        buf.writeVarInt(payload.chunks().size());
        payload.chunks().forEach((chunk, variant) -> {
            buf.writeLong(chunk);
            VARIANT_CODEC.encode(buf, variant);
        });
    }

    private static VariantLandPayload read(FriendlyByteBuf buf) {
        ResourceKey<Level> dimension = DIMENSION_CODEC.decode(buf);
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new IllegalArgumentException("variant land payload with " + size + " entries");
        }
        Map<Long, Optional<ResourceLocation>> chunks = new LinkedHashMap<>(size);
        for (int i = 0; i < size; i++) {
            chunks.put(buf.readLong(), VARIANT_CODEC.decode(buf));
        }
        return new VariantLandPayload(dimension, chunks);
    }

    /** Client side, on the main thread: stores the chunks' variants and tints for the player's current dimension. */
    static void handle(VariantLandPayload payload, IPayloadContext context) {
        Level level = context.player().level();
        if (!level.dimension().equals(payload.dimension())) {
            return;
        }
        VariantLandCache.apply(payload.chunks(), id -> Variants.get(level.registryAccess(), id)
                .map(variant -> OptionalInt.of(variant.tint())).orElse(OptionalInt.empty()));
    }
}
