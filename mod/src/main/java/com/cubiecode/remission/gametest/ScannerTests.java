package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.cubiecode.remission.registry.RemissionDataComponents;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.research.ScanData;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The scanner: host scans, block scans, non-hosts, cooldown, and the {@code scan_data} component's formats. */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ScannerTests {
    private ScannerTests() {
    }

    @GameTest(template = "empty")
    public static void scanningHostGivesHostScan(GameTestHelper helper) {
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1);
        CorruptionState state = Corruption.set(pig, 50);
        Player player = playerWithScanner(helper);

        InteractionResult result = player.interactOn(pig, InteractionHand.MAIN_HAND);
        helper.assertTrue(result.consumesAction(), "scan did not consume the interaction: " + result);
        List<ItemStack> scans = scans(player, RemissionItems.HOST_SCAN.get());
        helper.assertValueEqual(scans.size(), 1, "host scans");
        helper.assertValueEqual(scans.getFirst().get(RemissionDataComponents.SCAN_DATA.get()),
                ScanData.host(ResourceLocation.withDefaultNamespace("pig"), state.stage()), "scan data");
        helper.assertTrue(player.getCooldowns().isOnCooldown(RemissionItems.SCANNER.get()), "no cooldown after scan");
        helper.assertValueEqual(Corruption.get(pig), state, "scanning changed the host");
        helper.succeed();
    }

    // Villagers trade with any held item from their own interact; the scanner must get there first.
    @GameTest(template = "empty")
    public static void scanningHostVillagerSkipsTrading(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        Corruption.set(villager, 90);
        Player player = playerWithScanner(helper);

        player.interactOn(villager, InteractionHand.MAIN_HAND);
        helper.assertFalse(villager.isTrading(), "villager opened trading");
        helper.assertValueEqual(scans(player, RemissionItems.HOST_SCAN.get()).size(), 1, "host scans");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void scanningNonHostGivesNothing(GameTestHelper helper) {
        LivingEntity cow = helper.spawnWithNoFreeWill(EntityType.COW, 1, 1, 1);
        Player player = playerWithScanner(helper);

        InteractionResult result = player.interactOn(cow, InteractionHand.MAIN_HAND);
        helper.assertTrue(result.consumesAction(), "non-host scan did not consume the interaction: " + result);
        helper.assertValueEqual(scans(player, RemissionItems.HOST_SCAN.get()).size(), 0, "host scans");
        helper.assertTrue(player.getCooldowns().isOnCooldown(RemissionItems.SCANNER.get()), "no cooldown after scan");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void scanningBlockGivesBlockScan(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.CHEST);
        Player player = playerWithScanner(helper);

        InteractionResult result = useOnBlock(helper, player, pos);
        helper.assertTrue(result.consumesAction(), "block scan did not consume the interaction: " + result);
        helper.assertTrue(player.containerMenu == player.inventoryMenu, "scanning opened the chest");
        List<ItemStack> scans = scans(player, RemissionItems.BLOCK_SCAN.get());
        helper.assertValueEqual(scans.size(), 1, "block scans");
        helper.assertValueEqual(scans.getFirst().get(RemissionDataComponents.SCAN_DATA.get()),
                ScanData.block(ResourceLocation.withDefaultNamespace("chest")), "scan data");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cooldownBlocksRepeatScans(GameTestHelper helper) {
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1);
        Corruption.set(pig, 50);
        helper.setBlock(new BlockPos(0, 1, 0), Blocks.STONE);
        Player player = playerWithScanner(helper);

        player.interactOn(pig, InteractionHand.MAIN_HAND);
        player.interactOn(pig, InteractionHand.MAIN_HAND);
        useOnBlock(helper, player, new BlockPos(0, 1, 0));
        helper.assertValueEqual(scans(player, RemissionItems.HOST_SCAN.get()).stream().mapToInt(ItemStack::getCount).sum(), 1,
                "host scans");
        helper.assertValueEqual(scans(player, RemissionItems.BLOCK_SCAN.get()).size(), 0, "block scans");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void scanDataFormats(GameTestHelper helper) {
        ScanData host = ScanData.host(ResourceLocation.withDefaultNamespace("cow"), 2);
        ScanData block = ScanData.block(ResourceLocation.fromNamespaceAndPath("othermod", "gone"));
        JsonElement hostJson = JsonParser.parseString("{\"entity\":\"minecraft:cow\",\"stage\":2}");
        JsonElement blockJson = JsonParser.parseString("{\"block\":\"othermod:gone\"}");

        helper.assertValueEqual(ScanData.CODEC.encodeStart(JsonOps.INSTANCE, host).getOrThrow(), hostJson, "host json");
        helper.assertValueEqual(ScanData.CODEC.encodeStart(JsonOps.INSTANCE, block).getOrThrow(), blockJson, "block json");
        helper.assertValueEqual(ScanData.CODEC.parse(JsonOps.INSTANCE, hostJson).getOrThrow(), host, "host parse");
        helper.assertValueEqual(ScanData.CODEC.parse(JsonOps.INSTANCE, blockJson).getOrThrow(), block, "block parse");
        helper.assertTrue(ScanData.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"entity\":\"minecraft:cow\",\"stage\":0}"))
                .isError(), "stage 0 host scan parsed");
        helper.assertTrue(ScanData.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}")).isError(), "empty scan parsed");
        helper.assertValueEqual(block.subjectName().getString(), "othermod:gone", "unregistered subject name");

        ByteBuf buf = Unpooled.buffer();
        try {
            ScanData.STREAM_CODEC.encode(buf, host);
            helper.assertValueEqual(ScanData.STREAM_CODEC.decode(buf), host, "decoded scan");
            helper.assertFalse(buf.isReadable(), "trailing bytes");
        } finally {
            buf.release();
        }

        ItemStack stack = RemissionItems.HOST_SCAN.get().getDefaultInstance();
        stack.set(RemissionDataComponents.SCAN_DATA.get(), host);
        var registries = helper.getLevel().registryAccess();
        Tag saved = stack.save(registries);
        ItemStack loaded = ItemStack.parse(registries, saved).orElseThrow();
        helper.assertValueEqual(loaded.get(RemissionDataComponents.SCAN_DATA.get()), host, "scan after save and load");

        // The recipe ingredient documented in remission-data-formats.
        Ingredient cowStage2 = Ingredient.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(
                "{\"type\":\"neoforge:components\",\"items\":\"remission:host_scan\","
                        + "\"components\":{\"remission:scan_data\":{\"entity\":\"minecraft:cow\",\"stage\":2}}}")).getOrThrow();
        helper.assertTrue(cowStage2.test(stack), "ingredient missed a matching scan");
        ItemStack stage3 = stack.copy();
        stage3.set(RemissionDataComponents.SCAN_DATA.get(), ScanData.host(ResourceLocation.withDefaultNamespace("cow"), 3));
        helper.assertFalse(cowStage2.test(stage3), "ingredient matched another stage");
        helper.succeed();
    }

    private static Player playerWithScanner(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, RemissionItems.SCANNER.get().getDefaultInstance());
        return player;
    }

    // The scanner acts in onItemUseFirst, which the game calls before the block's own use.
    private static InteractionResult useOnBlock(GameTestHelper helper, Player player, BlockPos relative) {
        BlockPos pos = helper.absolutePos(relative);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        return stack.onItemUseFirst(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }

    private static List<ItemStack> scans(Player player, Item item) {
        List<ItemStack> found = new ArrayList<>();
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                found.add(stack);
            }
        }
        return found;
    }
}
