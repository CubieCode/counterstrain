package com.cubiecode.remission.world;

import com.cubiecode.remission.Remission;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/**
 * The data that says what festering land turns into (P4.2). Both are NeoForge data maps, server side only, reloaded
 * with {@code /reload}; contracts in the remission-data-formats skill.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class FesterMaps {
    /** The biome festering land flips to when its source biome has no {@link #FESTER_VARIANT} entry. */
    public static final ResourceKey<Biome> FESTER = ResourceKey.create(Registries.BIOME, Remission.id("fester"));

    /**
     * {@code data/<ns>/data_maps/block/festers_into.json}: the block a block turns into, and the festering stage its chunk
     * needs first. Keys are block ids or block tags.
     */
    public static final DataMapType<Block, FesterBlock> FESTERS_INTO = DataMapType.builder(
            Remission.id("festers_into"), Registries.BLOCK, FesterBlock.CODEC).build();

    /**
     * {@code data/<ns>/data_maps/worldgen/biome/fester_variant.json}: the biome a source biome flips to at the last
     * festering stage, instead of {@link #FESTER}. Keys are biome ids or biome tags.
     */
    public static final DataMapType<Biome, FesterVariant> FESTER_VARIANT = DataMapType.builder(
            Remission.id("fester_variant"), Registries.BIOME, FesterVariant.CODEC).build();

    private FesterMaps() {
    }

    /**
     * One {@code festers_into} value.
     *
     * @param block the block it turns into; state properties both blocks share (axis, snowy, leaf distance) carry over
     * @param stage the chunk festering stage at which it turns, at least 1 (default 1)
     */
    public record FesterBlock(Block block, int stage) {
        public static final Codec<FesterBlock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BuiltInRegistries.BLOCK.byNameCodec().fieldOf("block").forGetter(FesterBlock::block),
                Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("stage", 1).forGetter(FesterBlock::stage)
        ).apply(instance, FesterBlock::new));
    }

    /**
     * One {@code fester_variant} value.
     *
     * @param biome the fester biome this source biome flips to; a missing biome falls back to {@link #FESTER}
     */
    public record FesterVariant(ResourceKey<Biome> biome) {
        public static final Codec<FesterVariant> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceKey.codec(Registries.BIOME).fieldOf("biome").forGetter(FesterVariant::biome)
        ).apply(instance, FesterVariant::new));
    }

    @SubscribeEvent
    static void register(RegisterDataMapTypesEvent event) {
        event.register(FESTERS_INTO);
        event.register(FESTER_VARIANT);
    }
}
