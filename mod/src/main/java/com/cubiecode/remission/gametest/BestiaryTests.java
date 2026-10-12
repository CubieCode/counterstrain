package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.research.Bestiary;
import com.cubiecode.remission.research.BestiaryProgress;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The bestiary: host types from the registry, and per-player scan progress recorded, kept, saved, copied and synced. */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BestiaryTests {
    private static final ResourceLocation PIG = ResourceLocation.withDefaultNamespace("pig");
    private static final ResourceLocation COW = ResourceLocation.withDefaultNamespace("cow");

    private BestiaryTests() {
    }

    @GameTest(template = "empty")
    public static void scanningHostRecordsProgress(GameTestHelper helper) {
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1);
        CorruptionState state = Corruption.set(pig, 50);
        Player player = playerWithScanner(helper);
        helper.assertTrue(Bestiary.get(player).isEmpty(), "new player has progress");

        player.interactOn(pig, InteractionHand.MAIN_HAND);
        helper.assertValueEqual(Bestiary.get(player).highestStage(PIG), state.stage(), "pig stage");
        helper.assertFalse(Bestiary.get(player).isScanned(COW), "cow scanned without a scan");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nonHostAndBlockScansRecordNothing(GameTestHelper helper) {
        LivingEntity cow = helper.spawnWithNoFreeWill(EntityType.COW, 1, 1, 1);
        BlockPos pos = helper.absolutePos(new BlockPos(0, 1, 0));
        helper.setBlock(new BlockPos(0, 1, 0), Blocks.STONE);
        Player player = playerWithScanner(helper);

        player.interactOn(cow, InteractionHand.MAIN_HAND);
        player.getCooldowns().removeCooldown(RemissionItems.SCANNER.get());
        ItemStack scanner = player.getItemInHand(InteractionHand.MAIN_HAND);
        scanner.onItemUseFirst(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)));
        helper.assertTrue(Bestiary.get(player).isEmpty(), "progress after a non-host and a block scan");
        helper.assertFalse(player.hasData(RemissionAttachments.BESTIARY),
                "attachment created without progress");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void progressKeepsHighestStage(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Bestiary.recordScan(player, COW, 2);
        Bestiary.recordScan(player, COW, 1);
        helper.assertValueEqual(Bestiary.get(player).highestStage(COW), 2, "stage after a lower scan");
        Bestiary.recordScan(player, COW, 3);
        Bestiary.recordScan(player, PIG, 1);
        helper.assertValueEqual(Bestiary.get(player).highestStages(), Map.of(COW, 3, PIG, 1), "progress");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unlistedTypesRecordNothing(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Bestiary.recordScan(player, ResourceLocation.withDefaultNamespace("player"), 2);
        Bestiary.recordScan(player, ResourceLocation.withDefaultNamespace("wither"), 3);
        Bestiary.recordScan(player, ResourceLocation.fromNamespaceAndPath("othermod", "gone"), 1);
        helper.assertTrue(Bestiary.get(player).isEmpty(), "unlisted types recorded: " + Bestiary.get(player));
        helper.assertFalse(player.hasData(RemissionAttachments.BESTIARY), "attachment created without progress");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void progressPersistsAndSurvivesDeath(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Bestiary.recordScan(player, COW, 2);

        CompoundTag saved = player.saveWithoutId(new CompoundTag());
        Player loaded = helper.makeMockPlayer(GameType.SURVIVAL);
        loaded.load(saved);
        helper.assertValueEqual(Bestiary.get(loaded), Bestiary.get(player), "progress after save and load");

        Player respawned = helper.makeMockPlayer(GameType.SURVIVAL);
        EventHooks.onPlayerClone(respawned, player, true);
        helper.assertValueEqual(Bestiary.get(respawned), Bestiary.get(player), "progress after death");

        Player fresh = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.assertFalse(fresh.saveWithoutId(new CompoundTag()).getCompound("neoforge:attachments")
                .contains(Remission.id("bestiary").toString()), "empty progress saved");
        helper.succeed();
    }

    // Only the update predicate: NeoForge's initial tracking sync does not consult it (remission-data-formats).
    @GameTest(template = "empty")
    public static void progressUpdatesSyncOnlyToOwner(GameTestHelper helper) {
        Player owner = helper.makeMockPlayer(GameType.SURVIVAL);
        Player other = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.assertTrue(Bestiary.syncsTo(owner, owner), "owner not synced");
        helper.assertFalse(Bestiary.syncsTo(owner, other), "progress update synced to another player");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void progressFormats(GameTestHelper helper) {
        BestiaryProgress progress = BestiaryProgress.EMPTY.withScan(COW, 2)
                .withScan(ResourceLocation.fromNamespaceAndPath("othermod", "gone"), 1);
        JsonElement json = JsonParser.parseString("{\"minecraft:cow\":2,\"othermod:gone\":1}");
        helper.assertValueEqual(BestiaryProgress.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(), progress, "parse");
        helper.assertValueEqual(BestiaryProgress.CODEC.parse(JsonOps.INSTANCE,
                BestiaryProgress.CODEC.encodeStart(JsonOps.INSTANCE, progress).getOrThrow()).getOrThrow(), progress,
                "json round trip");
        helper.assertTrue(BestiaryProgress.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"minecraft:cow\":0}"))
                .isError(), "stage 0 parsed");

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            BestiaryProgress.STREAM_CODEC.encode(buf, progress);
            helper.assertValueEqual(BestiaryProgress.STREAM_CODEC.decode(buf), progress, "decoded progress");
            helper.assertFalse(buf.isReadable(), "trailing bytes");
        } finally {
            buf.release();
        }
        helper.succeed();
    }

    // Host types come from the entity type registry, so every loaded mod's living types count (mekanism:robit in dev
    // runs) without a list to maintain.
    @GameTest(template = "empty")
    public static void hostTypesFollowRegistry(GameTestHelper helper) {
        List<EntityType<?>> hosts = Bestiary.hostTypes();
        // Living mobs of every kind, including MISC-category ones (villager, iron golem) and non-mob living entities.
        for (EntityType<?> type : List.of(EntityType.COW, EntityType.ZOMBIE, EntityType.VILLAGER, EntityType.ENDERMAN,
                EntityType.IRON_GOLEM, EntityType.SQUID)) {
            helper.assertTrue(hosts.contains(type), "missing host type " + type);
        }
        // The player, immune bosses (#c:bosses), the default #remission:bestiary_hidden types and non-living entities.
        for (EntityType<?> type : List.of(EntityType.PLAYER, EntityType.WITHER, EntityType.ENDER_DRAGON, EntityType.GIANT,
                EntityType.ILLUSIONER, EntityType.ARMOR_STAND, EntityType.ITEM, EntityType.ARROW, EntityType.BOAT,
                EntityType.ITEM_FRAME, EntityType.LIGHTNING_BOLT)) {
            helper.assertFalse(hosts.contains(type), "listed non-host type " + type);
        }
        if (ModList.get().isLoaded("mekanism")) {
            ResourceLocation robit = ResourceLocation.fromNamespaceAndPath("mekanism", "robit");
            helper.assertTrue(hosts.contains(BuiltInRegistries.ENTITY_TYPE.get(robit)), "modded living type missing");
        }
        Remission.LOGGER.info("Bestiary lists {} host types from {} mods", hosts.size(), hosts.stream()
                .map(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace()).distinct().count());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void hiddenTypesRecordNothing(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Bestiary.recordScan(player, BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.ILLUSIONER), 2);
        Bestiary.recordScan(player, BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.ARMOR_STAND), 1);
        helper.assertTrue(Bestiary.get(player).isEmpty(), "hidden types recorded as progress");
        helper.succeed();
    }

    // A pack's tag replaces or extends the default like any tag. Rebinds the entity type tags as a datapack reload
    // would (bestiary_hidden = only the cow, as a "replace": true pack tag), checks, and restores them in the same
    // call, so no other test sees the change; its own batch keeps it apart from tests that read tags while running.
    @GameTest(template = "empty", batch = "bestiary_tags")
    public static void hiddenTagIsReadLive(GameTestHelper helper) {
        Registry<EntityType<?>> registry = helper.getLevel().registryAccess().registryOrThrow(Registries.ENTITY_TYPE);
        Map<TagKey<EntityType<?>>, List<Holder<EntityType<?>>>> original = new HashMap<>();
        registry.getTags().forEach(pair -> original.put(pair.getFirst(), pair.getSecond().stream().toList()));
        Map<TagKey<EntityType<?>>, List<Holder<EntityType<?>>>> packTags = new HashMap<>(original);
        packTags.put(Bestiary.HIDDEN, List.of(EntityType.COW.builtInRegistryHolder()));
        try {
            registry.bindTags(packTags);
            List<EntityType<?>> hosts = Bestiary.hostTypes();
            helper.assertFalse(hosts.contains(EntityType.COW), "pack-hidden cow listed");
            helper.assertTrue(hosts.contains(EntityType.GIANT) && hosts.contains(EntityType.ILLUSIONER)
                    && hosts.contains(EntityType.ARMOR_STAND), "replaced defaults still hidden");
        } finally {
            registry.bindTags(original);
        }
        helper.assertTrue(Bestiary.hostTypes().contains(EntityType.COW), "cow not listed after restoring tags");
        helper.assertFalse(Bestiary.hostTypes().contains(EntityType.GIANT), "giant listed after restoring tags");
        helper.succeed();
    }

    private static Player playerWithScanner(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, RemissionItems.SCANNER.get().getDefaultInstance());
        return player;
    }
}
