package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.BestiaryQueries;
import com.cubiecode.remission.api.TeamLookupEvent;
import com.cubiecode.remission.api.TeamQueries;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
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
import net.minecraft.server.level.ServerPlayer;
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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The bestiary: host types from the registry, per-player scan progress recorded, kept, saved, copied and synced, and
 * team-shared progress through the team query (P6.4).
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BestiaryTests {
    private static final ResourceLocation PIG = ResourceLocation.withDefaultNamespace("pig");
    private static final ResourceLocation COW = ResourceLocation.withDefaultNamespace("cow");
    private static final ResourceLocation SHEEP = ResourceLocation.withDefaultNamespace("sheep");

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

    // ---- team-shared research (P6.4) ---------------------------------------------------------------------------------

    @GameTest(template = "empty")
    public static void teammateScanAppearsInBestiaryAndTotal(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer a = SurgeTests.player(helper, pos);
        ServerPlayer b = SurgeTests.player(helper, pos);
        ServerPlayer offline = SurgeTests.player(helper, pos);
        List<ServerPlayer> online = List.of(a, b);
        withTeam(new HashSet<>(Set.of(a.getUUID(), b.getUUID(), offline.getUUID())), () -> {
            // A scan by one member reaches the other's synced bestiary and total; their own record is untouched.
            Bestiary.recordScan(a, COW, 2, online);
            helper.assertValueEqual(Bestiary.team(b).highestStages(), Map.of(COW, 2), "teammate's bestiary");
            helper.assertValueEqual(b.getExistingDataOrNull(RemissionAttachments.TEAM_BESTIARY), Bestiary.team(b),
                    "synced team progress");
            helper.assertValueEqual(BestiaryQueries.teamHostTypesScanned(b), 1, "teammate's total");
            helper.assertTrue(Bestiary.get(b).isEmpty(), "teammate's own record changed");
            helper.assertValueEqual(BestiaryQueries.ownHostTypesScanned(b), 0, "teammate's own total");

            // Each type at the highest stage any member scanned.
            Bestiary.recordScan(b, COW, 3, online);
            Bestiary.recordScan(b, PIG, 1, online);
            Map<ResourceLocation, Integer> union = Map.of(COW, 3, PIG, 1);
            helper.assertValueEqual(BestiaryQueries.teamProgress(a), union, "scanner's team progress");
            helper.assertValueEqual(a.getExistingDataOrNull(RemissionAttachments.TEAM_BESTIARY).highestStages(), union,
                    "scanner's synced team progress");
            helper.assertValueEqual(BestiaryQueries.teamHighestStage(a, COW), 3, "highest cow stage");
            helper.assertValueEqual(Bestiary.get(a).highestStages(), Map.of(COW, 2), "scanner's own record");
            // b's own record is now the whole union, so b needs no separate copy.
            helper.assertFalse(b.hasData(RemissionAttachments.TEAM_BESTIARY), "team copy equal to the own record");

            // An offline teammate's team total counts the online members' scans, and their scans count once they join.
            helper.assertValueEqual(BestiaryQueries.teamHostTypesScanned(helper.getLevel().getServer(), offline.getUUID()),
                    2, "offline teammate's total");
            offline.setData(RemissionAttachments.BESTIARY, BestiaryProgress.EMPTY.withScan(SHEEP, 1));
            Bestiary.logIn(offline, List.of(a, b, offline));
            helper.assertValueEqual(Bestiary.team(a).highestStages(), Map.of(COW, 3, PIG, 1, SHEEP, 1),
                    "progress after a teammate with older scans logs in");
            helper.assertValueEqual(BestiaryQueries.teamHostTypesScanned(b), 3, "total after the login");
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void differentTeamsShareNothing(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer a = SurgeTests.player(helper, pos);
        ServerPlayer b = SurgeTests.player(helper, pos);
        ServerPlayer c = SurgeTests.player(helper, pos);
        ServerPlayer d = SurgeTests.player(helper, pos);
        List<ServerPlayer> online = List.of(a, b, c, d);
        withTeam(new HashSet<>(Set.of(a.getUUID(), b.getUUID())), () -> withTeam(new HashSet<>(Set.of(c.getUUID(), d.getUUID())), () -> {
            Bestiary.recordScan(a, COW, 2, online);
            Bestiary.recordScan(c, PIG, 1, online);
            helper.assertValueEqual(Bestiary.team(b).highestStages(), Map.of(COW, 2), "first team's progress");
            helper.assertValueEqual(Bestiary.team(d).highestStages(), Map.of(PIG, 1), "second team's progress");
            helper.assertFalse(Bestiary.team(c).isScanned(COW) || Bestiary.team(b).isScanned(PIG), "progress crossed teams");
            helper.assertValueEqual(BestiaryQueries.teamHostTypesScanned(helper.getLevel().getServer(), d.getUUID()), 1,
                    "second team's total");
        }));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void leavingTeamKeepsOwnScans(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer a = SurgeTests.player(helper, pos);
        ServerPlayer b = SurgeTests.player(helper, pos);
        List<ServerPlayer> online = List.of(a, b);
        Set<UUID> team = new HashSet<>(Set.of(a.getUUID(), b.getUUID()));
        withTeam(team, () -> {
            Bestiary.recordScan(a, COW, 2, online);
            Bestiary.recordScan(b, PIG, 1, online);
            helper.assertValueEqual(Bestiary.team(b).highestStages(), Map.of(COW, 2, PIG, 1), "progress in the team");

            team.remove(b.getUUID());
            TeamQueries.invalidate(b.getUUID());
            Bestiary.refreshAll(online);
            helper.assertValueEqual(Bestiary.team(b).highestStages(), Map.of(PIG, 1), "leaver's bestiary");
            helper.assertFalse(b.hasData(RemissionAttachments.TEAM_BESTIARY), "leaver kept a team copy");
            helper.assertValueEqual(BestiaryQueries.teamHostTypesScanned(b), 1, "leaver's total");
            helper.assertValueEqual(Bestiary.team(a).highestStages(), Map.of(COW, 2), "former teammate's bestiary");
            helper.assertFalse(a.hasData(RemissionAttachments.TEAM_BESTIARY), "former teammate kept a team copy");
        });
        helper.succeed();
    }

    // P6.2 behaviour: each player is their own team, so the bestiary shows their own record and nothing extra is synced.
    @GameTest(template = "empty")
    public static void withoutTeamSourceBestiaryIsOwnProgress(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer a = SurgeTests.player(helper, pos);
        ServerPlayer b = SurgeTests.player(helper, pos);
        List<ServerPlayer> online = List.of(a, b);
        Bestiary.recordScan(a, COW, 2, online);
        Bestiary.recordScan(a, PIG, 1, online);
        helper.assertValueEqual(Bestiary.team(a), Bestiary.get(a), "bestiary differs from own progress");
        helper.assertValueEqual(BestiaryQueries.teamHostTypesScanned(a), 2, "total");
        helper.assertFalse(a.hasData(RemissionAttachments.TEAM_BESTIARY), "team copy synced without a team");
        helper.assertTrue(Bestiary.team(b).isEmpty(), "another player's scans shown");
        helper.assertFalse(b.hasData(RemissionAttachments.TEAM_BESTIARY), "team copy for another player");
        Bestiary.refreshAll(online);
        helper.assertFalse(a.hasData(RemissionAttachments.TEAM_BESTIARY) || b.hasData(RemissionAttachments.TEAM_BESTIARY),
                "team copy after a refresh");
        helper.succeed();
    }

    /** Runs {@code body} with every pair of {@code members} answered as teammates (read live, so it can shrink). */
    private static void withTeam(Set<UUID> members, Runnable body) {
        Consumer<TeamLookupEvent> listener = event -> {
            if (members.contains(event.getFirst()) && members.contains(event.getSecond())) {
                event.setTeammates(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        Set<UUID> invalidated = Set.copyOf(members);
        try {
            invalidated.forEach(TeamQueries::invalidate);
            body.run();
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            invalidated.forEach(TeamQueries::invalidate);
        }
    }

    private static Player playerWithScanner(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, RemissionItems.SCANNER.get().getDefaultInstance());
        return player;
    }
}
