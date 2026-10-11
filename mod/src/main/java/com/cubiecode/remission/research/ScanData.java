package com.cubiecode.remission.research;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

/**
 * What a scan recorded: a host's entity type and host stage, or a block id. Stored on scan items as the
 * {@code remission:scan_data} data component. Ids stay ids (not holders) so a scan of a since-removed mod's content
 * still loads and displays.
 */
public record ScanData(Kind kind, ResourceLocation id, int stage) {
    public enum Kind {
        HOST,
        BLOCK
    }

    private static final Codec<ScanData> HOST_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("entity").forGetter(ScanData::id),
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("stage").forGetter(ScanData::stage)
    ).apply(instance, ScanData::host));

    private static final Codec<ScanData> BLOCK_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("block").forGetter(ScanData::id)
    ).apply(instance, ScanData::block));

    /** {@code {"entity": <entity type id>, "stage": <host stage>}} or {@code {"block": <block id>}}. */
    public static final Codec<ScanData> CODEC = Codec.either(HOST_CODEC, BLOCK_CODEC).xmap(
            Either::unwrap, data -> data.kind() == Kind.HOST ? Either.left(data) : Either.right(data));

    public static final StreamCodec<ByteBuf, ScanData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(i -> Kind.values()[i], Kind::ordinal), ScanData::kind,
            ResourceLocation.STREAM_CODEC, ScanData::id,
            ByteBufCodecs.VAR_INT, ScanData::stage,
            ScanData::new);

    public ScanData {
        if (kind == Kind.HOST ? stage < 1 : stage != 0) {
            throw new IllegalArgumentException("Invalid scan data: " + kind + " " + id + " at stage " + stage);
        }
    }

    public static ScanData host(ResourceLocation entityType, int stage) {
        return new ScanData(Kind.HOST, entityType, stage);
    }

    public static ScanData block(ResourceLocation block) {
        return new ScanData(Kind.BLOCK, block, 0);
    }

    /** The scanned entity type's or block's name, or its id when it is no longer registered. */
    public Component subjectName() {
        return switch (kind) {
            case HOST -> BuiltInRegistries.ENTITY_TYPE.getOptional(id).map(EntityType::getDescription)
                    .orElseGet(() -> Component.literal(id.toString()));
            case BLOCK -> BuiltInRegistries.BLOCK.getOptional(id).map(Block::getName)
                    .orElseGet(() -> Component.literal(id.toString()));
        };
    }
}
