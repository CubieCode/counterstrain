package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.registry.RemissionStats;
import com.cubiecode.remission.research.Scanning;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Advancement triggers and stats (P8.1): each trigger fires with the conditions documented in remission-data-formats
 * (parsed from that JSON), conditions that do not match stay open, and each stat counts the action.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuestTriggerTests {
    private QuestTriggerTests() {
    }

    @GameTest(template = "empty")
    public static void breakingNidusFiresTriggerAndStat(GameTestHelper helper) {
        BlockPos nidus = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.setBlock(1, 1, 1, RemissionBlocks.NIDUS.get());
        helper.setBlock(3, 1, 1, RemissionBlocks.NIDUS.get());
        ServerPlayer player = player(helper);
        AdvancementHolder any = gate(helper, player, "{\"trigger\":\"remission:nidus_destroyed\"}");
        AdvancementHolder here = gate(helper, player, "{\"trigger\":\"remission:nidus_destroyed\",\"conditions\":"
                + "{\"location\":{\"dimension\":\"minecraft:overworld\"}}}");
        AdvancementHolder nether = gate(helper, player, "{\"trigger\":\"remission:nidus_destroyed\",\"conditions\":"
                + "{\"location\":{\"dimension\":\"minecraft:the_nether\"}}}");

        // Removal that is not a player's break counts for nobody.
        helper.setBlock(3, 1, 1, Blocks.AIR);
        helper.assertFalse(done(player, any), "nidus edited away fired the trigger");
        helper.assertValueEqual(stat(player, RemissionStats.NIDI_DESTROYED.get()), 0, "nidi destroyed after edit");

        helper.assertTrue(player.gameMode.destroyBlock(nidus), "player could not break the nidus");
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(1, 1, 1));
        helper.assertTrue(done(player, any), "nidus_destroyed without conditions did not fire");
        helper.assertTrue(done(player, here), "nidus_destroyed in the overworld did not fire");
        helper.assertFalse(done(player, nether), "nidus_destroyed for the nether fired");
        helper.assertValueEqual(stat(player, RemissionStats.NIDI_DESTROYED.get()), 1, "nidi destroyed");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void hostScanFiresTriggerAndStat(GameTestHelper helper) {
        LivingEntity cow = helper.spawnWithNoFreeWill(EntityType.COW, 1, 1, 1);
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 3, 1, 1);
        int stage = Corruption.set(pig, 50).stage();
        ServerPlayer player = player(helper);
        AdvancementHolder pigGate = gate(helper, player, "{\"trigger\":\"remission:host_scanned\",\"conditions\":"
                + "{\"entity\":{\"type\":\"minecraft:pig\"},\"stage\":{\"min\":" + stage + "},\"bestiary_types\":1}}");
        AdvancementHolder cowGate = gate(helper, player, "{\"trigger\":\"remission:host_scanned\",\"conditions\":"
                + "{\"entity\":{\"type\":\"minecraft:cow\"}}}");
        AdvancementHolder higherStage = gate(helper, player, "{\"trigger\":\"remission:host_scanned\",\"conditions\":"
                + "{\"stage\":{\"min\":" + (stage + 1) + "}}}");
        AdvancementHolder twoTypes = gate(helper, player, "{\"trigger\":\"remission:host_scanned\",\"conditions\":"
                + "{\"bestiary_types\":{\"min\":2}}}");

        // A living entity that is not a host gives no scan, so no trigger.
        Scanning.scanEntity(player, cow, InteractionHand.MAIN_HAND);
        helper.assertFalse(done(player, cowGate), "scanning a clean cow fired host_scanned");
        helper.assertValueEqual(stat(player, RemissionStats.HOSTS_SCANNED.get()), 0, "hosts scanned after non-host");

        player.getCooldowns().removeCooldown(RemissionItems.SCANNER.get());
        Scanning.scanEntity(player, pig, InteractionHand.MAIN_HAND);
        helper.assertTrue(done(player, pigGate), "host_scanned for the pig did not fire");
        helper.assertFalse(done(player, cowGate), "host_scanned for a cow fired on a pig");
        helper.assertFalse(done(player, higherStage), "host_scanned matched a higher stage");
        helper.assertFalse(done(player, twoTypes), "host_scanned matched two bestiary types after one");
        helper.assertValueEqual(stat(player, RemissionStats.HOSTS_SCANNED.get()), 1, "hosts scanned");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void blockScanFiresTriggerAndStat(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, RemissionBlocks.NIDUS.get());
        ServerPlayer player = player(helper);
        AdvancementHolder nidusGate = gate(helper, player, "{\"trigger\":\"remission:block_scanned\",\"conditions\":"
                + "{\"location\":{\"block\":{\"blocks\":\"remission:nidus\"}}}}");
        AdvancementHolder stoneGate = gate(helper, player, "{\"trigger\":\"remission:block_scanned\",\"conditions\":"
                + "{\"location\":{\"block\":{\"blocks\":\"minecraft:stone\"}}}}");

        Scanning.scanBlock(player, helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)), InteractionHand.MAIN_HAND);
        helper.assertTrue(done(player, nidusGate), "block_scanned for the nidus did not fire");
        helper.assertFalse(done(player, stoneGate), "block_scanned for stone fired on a nidus");
        helper.assertValueEqual(stat(player, RemissionStats.BLOCKS_SCANNED.get()), 1, "blocks scanned");
        helper.setBlock(1, 1, 1, Blocks.AIR); // a nidus left in place would spread into neighbouring tests
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void statsAreRegistered(GameTestHelper helper) {
        for (String name : new String[] {"nidi_destroyed", "hosts_scanned", "blocks_scanned", "surges_survived"}) {
            ResourceLocation id = Remission.id(name);
            helper.assertTrue(BuiltInRegistries.CUSTOM_STAT.containsKey(id), "stat not registered: " + id);
            // Stat types key stats by identity: the registry's own id instance is the one that has the stat.
            helper.assertTrue(Stats.CUSTOM.contains(BuiltInRegistries.CUSTOM_STAT.get(id)), "stat not created: " + id);
        }
        helper.succeed();
    }

    /**
     * A survival server player holding a scanner, with a connection that drops what it is sent (scanner messages,
     * bestiary sync, which a connection without a negotiated channel would reject). It stays out of the level's player
     * list: nothing here needs it found, and login listeners in the dev runtime fail on a fake connection.
     */
    private static ServerPlayer player(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "quest-test-player"), false);
        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        new ServerGamePacketListenerImpl(server, connection, player, cookie) {
            @Override
            public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
            }
        };
        player.setGameMode(GameType.SURVIVAL);
        Vec3 pos = helper.absoluteVec(new Vec3(0.5, 1, 0.5));
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        player.setItemInHand(InteractionHand.MAIN_HAND, RemissionItems.SCANNER.get().getDefaultInstance());
        return player;
    }

    /** An advancement with one criterion parsed from {@code json}, listening on the player as a loaded one would. */
    private static AdvancementHolder gate(GameTestHelper helper, ServerPlayer player, String json) {
        Criterion<?> criterion = Criterion.CODEC.parse(
                helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(json)).getOrThrow();
        AdvancementHolder holder = Advancement.Builder.advancement().addCriterion("gate", criterion)
                .build(Remission.id("test/" + UUID.randomUUID()));
        listen(player, criterion, holder);
        return holder;
    }

    private static <T extends CriterionTriggerInstance> void listen(ServerPlayer player, Criterion<T> criterion, AdvancementHolder holder) {
        criterion.trigger().addPlayerListener(player.getAdvancements(),
                new CriterionTrigger.Listener<>(criterion.triggerInstance(), holder, "gate"));
    }

    private static boolean done(ServerPlayer player, AdvancementHolder holder) {
        return player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    private static int stat(ServerPlayer player, ResourceLocation id) {
        return player.getStats().getValue(Stats.CUSTOM.get(id));
    }
}
