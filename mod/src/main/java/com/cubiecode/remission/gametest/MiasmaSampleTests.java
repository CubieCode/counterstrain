package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.loot.IsFesteringCondition;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionFluids;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.world.ChunkCorruption;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Miasma, the sample items and their default loot, and the optional Mekanism chemical (P7.1). */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MiasmaSampleTests {
    // Enough rolls that the default 25% chance misses every one with odds of about 1e-25.
    private static final int ROLLS = 200;
    private static final BlockPos SOURCE = new BlockPos(1, 1, 1);

    private MiasmaSampleTests() {
    }

    @GameTest(template = "empty")
    public static void miasmaFlows(GameTestHelper helper) {
        // The empty template is 3x3x3: a floor, then a source in the middle of the layer above it.
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
        helper.setBlock(SOURCE, RemissionBlocks.MIASMA.get());
        helper.assertTrue(helper.getLevel().getFluidState(helper.absolutePos(SOURCE)).isSource(),
                "placed miasma is a source");
        helper.succeedWhen(() -> {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                FluidState flow = helper.getLevel().getFluidState(helper.absolutePos(SOURCE.relative(side)));
                helper.assertTrue(flow.getType() == RemissionFluids.FLOWING_MIASMA.get(), "no flowing miasma to the " + side + ": " + flow + " at tick " + helper.getTick()
                        + ", source " + helper.getLevel().getFluidState(helper.absolutePos(SOURCE)));
            }
        });
    }

    @GameTest(template = "empty")
    public static void miasmaBucketHoldsMiasma(GameTestHelper helper) {
        helper.assertValueEqual(RemissionItems.MIASMA_BUCKET.get().content, RemissionFluids.MIASMA.get(), "bucket fluid");
        helper.assertValueEqual(RemissionFluids.MIASMA.get().getBucket(), RemissionItems.MIASMA_BUCKET.get(), "fluid bucket");
        helper.assertValueEqual(RemissionFluids.MIASMA.get().getFluidType(), RemissionFluids.MIASMA_TYPE.get(), "fluid type");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void brokenNidusDropsNidusCore(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, RemissionBlocks.NIDUS.get());
        helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
        helper.assertItemEntityCountIs(RemissionItems.NIDUS_CORE.get(), pos, 2, 1);
        helper.assertItemEntityNotPresent(RemissionItems.NIDUS.get(), pos, 2);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void festeringSoilDropsFesteredSoil(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCorruption before = chunk.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
        try {
            // Default thresholds [1, 25, 50, 75]: level 10 is stage 1, level 30 is stage 2.
            chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(30, level.getGameTime()));
            helper.assertTrue(festeredSoilRolls(level, pos, Blocks.DIRT.defaultBlockState()) > 0, "dirt at stage 2");
            helper.assertTrue(festeredSoilRolls(level, pos, Blocks.GRASS_BLOCK.defaultBlockState()) > 0, "grass at stage 2");
            helper.assertValueEqual(festeredSoilRolls(level, pos, Blocks.STONE.defaultBlockState()), 0, "stone at stage 2");

            chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(10, level.getGameTime()));
            helper.assertValueEqual(festeredSoilRolls(level, pos, Blocks.DIRT.defaultBlockState()), 0, "dirt at stage 1");
        } finally {
            if (before == null) {
                chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
            } else {
                chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, before);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void isFesteringDecodes(GameTestHelper helper) {
        LootItemCondition parsed = LootItemCondition.DIRECT_CODEC.parse(
                helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE),
                JsonParser.parseString("{\"condition\": \"remission:is_festering\", \"min_stage\": 3, \"blocks\": \"#remission:festered_soil_sources\"}"))
                .getOrThrow();
        helper.assertTrue(parsed instanceof IsFesteringCondition condition && condition.minStage() == 3
                && condition.blocks().isPresent(), "decoded condition");
        LootItemCondition defaults = LootItemCondition.DIRECT_CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"condition\": \"remission:is_festering\"}")).getOrThrow();
        helper.assertValueEqual(defaults, new IsFesteringCondition(1, Optional.empty()), "default fields");
        helper.succeed();
    }

    /** The {@code remission:miasma} chemical exists exactly when Mekanism is loaded; read by key, without Mekanism classes. */
    @GameTest(template = "empty")
    public static void mekanismChemicalOnlyWithMekanism(GameTestHelper helper) {
        boolean mekanism = ModList.get().isLoaded("mekanism");
        ResourceKey<Registry<Object>> chemicals = ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("mekanism", "chemical"));
        Optional<Registry<Object>> registry = helper.getLevel().registryAccess().registry(chemicals);
        helper.assertValueEqual(registry.isPresent(), mekanism, "Mekanism chemical registry present");
        registry.ifPresent(chemical -> helper.assertTrue(chemical.containsKey(Remission.id("miasma")), "remission:miasma chemical"));
        Remission.LOGGER.info("Mekanism loaded: {}; remission:miasma chemical registered: {}", mekanism,
                registry.map(chemical -> chemical.containsKey(Remission.id("miasma"))).orElse(false));
        helper.succeed();
    }

    private static int festeredSoilRolls(ServerLevel level, BlockPos pos, BlockState state) {
        int found = 0;
        for (int roll = 0; roll < ROLLS; roll++) {
            for (ItemStack drop : Block.getDrops(state, level, pos, null)) {
                if (drop.is(RemissionItems.FESTERED_SOIL.get())) {
                    found++;
                }
            }
        }
        return found;
    }
}
