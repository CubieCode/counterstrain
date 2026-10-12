package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CountermeasureGrantedEvent;
import com.cubiecode.remission.api.StrainHazardEvent;
import com.cubiecode.remission.api.StrainHazards;
import com.cubiecode.remission.api.TeamLookupEvent;
import com.cubiecode.remission.api.TeamQueries;
import com.cubiecode.remission.countermeasure.BlockHazardMode;
import com.cubiecode.remission.countermeasure.CountermeasureProtocolItem;
import com.cubiecode.remission.countermeasure.CountermeasureSet;
import com.cubiecode.remission.countermeasure.Countermeasures;
import com.cubiecode.remission.infection.Contact;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionDataComponents;
import com.cubiecode.remission.registry.RemissionFluids;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.registry.RemissionStats;
import com.cubiecode.remission.tech.MachineKind;
import com.cubiecode.remission.viralload.ViralLoad;
import com.cubiecode.remission.viralload.ViralLoadSettings;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Countermeasures (P5.7): contact viral load as a hazard, the protocol, team sharing on grant, login and team change,
 * the hazard event, the block hazard presence rule in each mode, save and sync, the command, trigger and stat, and the
 * default protocol recipes. Players are never added to the server's player list: tests that need online players pass
 * them to {@link Countermeasures} explicitly, and teams come from a test listener on fresh random UUIDs.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CountermeasureTests {
    /** A hazard id no gameplay asks for, so these tests never change what other tests see. */
    private static final ResourceLocation TEST_HAZARD = Remission.id("test_hazard");
    /** Each default protocol's recipe inputs besides the strain sequence and paper. */
    private static final Map<ResourceLocation, ItemLike> DEFAULT_PROTOCOLS = Map.of(
            StrainHazards.CONTACT, RemissionItems.FESTERED_SOIL.get());

    private CountermeasureTests() {
    }

    @GameTest(template = "empty")
    public static void contactStopsForHolder(GameTestHelper helper) {
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.getLevel().setBlockAndUpdate(feet.below(), RemissionBlocks.FESTERING_GRASS.get().defaultBlockState());
        ServerPlayer holder = SurgeTests.player(helper, feet);
        ServerPlayer other = SurgeTests.player(helper, feet);
        helper.assertValueEqual(Contact.of(holder), Contact.Kind.BLOCK, "holder in contact");
        ViralLoadSettings settings = ViralLoadSettings.fromConfig();
        double before = ViralLoad.gainPerMinute(holder, settings);
        helper.assertTrue(before >= settings.contactGain() && settings.contactGain() > 0, "contact gain before " + before);

        Countermeasures.grant(holder, StrainHazards.CONTACT, CountermeasureGrantedEvent.Cause.OTHER, List.of(holder, other));
        helper.assertFalse(StrainHazards.applies(holder, StrainHazards.CONTACT), "contact applies to its holder");
        helper.assertTrue(StrainHazards.applies(other, StrainHazards.CONTACT), "contact stopped for another player");
        helper.assertValueEqual(ViralLoad.gainPerMinute(holder, settings), before - settings.contactGain(), "holder's gain");
        helper.assertValueEqual(ViralLoad.gainPerMinute(other, settings), before, "other player's gain");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void protocolGrantsAndIsConsumed(GameTestHelper helper) {
        ServerPlayer player = SurgeTests.player(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        ItemStack protocols = CountermeasureProtocolItem.of(RemissionItems.COUNTERMEASURE_PROTOCOL.get(), TEST_HAZARD);
        protocols.setCount(2);
        player.setItemInHand(InteractionHand.MAIN_HAND, protocols);
        InteractionResult first = protocols.use(helper.getLevel(), player, InteractionHand.MAIN_HAND).getResult();
        helper.assertTrue(first.consumesAction(), "first use: " + first);
        helper.assertTrue(StrainHazards.holds(player, TEST_HAZARD), "protocol did not grant its countermeasure");
        helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "protocols left after one use");

        // Already held: nothing happens and nothing is used up.
        InteractionResult again = player.getMainHandItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND).getResult();
        helper.assertFalse(again.consumesAction(), "second use: " + again);
        helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "protocols left after a held use");

        // A protocol naming no hazard does nothing.
        ItemStack blank = new ItemStack(RemissionItems.COUNTERMEASURE_PROTOCOL.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, blank);
        helper.assertValueEqual(blank.use(helper.getLevel(), player, InteractionHand.MAIN_HAND).getResult(),
                InteractionResult.PASS, "blank protocol");
        helper.assertValueEqual(blank.getCount(), 1, "blank protocol used up");
        helper.assertValueEqual(StrainHazards.countermeasures(player), Set.of(TEST_HAZARD), "countermeasures after a blank");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void teamSharesCountermeasures(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer a = SurgeTests.player(helper, pos);
        ServerPlayer b = SurgeTests.player(helper, pos);
        ServerPlayer stranger = SurgeTests.player(helper, pos);
        ServerPlayer late = SurgeTests.player(helper, pos);
        ServerPlayer joiner = SurgeTests.player(helper, pos);
        Set<UUID> team = new HashSet<>(Set.of(a.getUUID(), b.getUUID(), late.getUUID()));
        List<CountermeasureGrantedEvent> events = new ArrayList<>();
        withTeam(team, () -> {
            Consumer<CountermeasureGrantedEvent> listener = events::add;
            NeoForge.EVENT_BUS.addListener(CountermeasureGrantedEvent.class, listener);
            try {
                // A grant reaches online teammates, not a player on another team or a teammate who is offline.
                int gained = Countermeasures.grant(a, TEST_HAZARD, CountermeasureGrantedEvent.Cause.PROTOCOL,
                        List.of(a, b, stranger));
                helper.assertValueEqual(gained, 2, "players who gained it");
                helper.assertTrue(StrainHazards.holds(b, TEST_HAZARD), "online teammate did not gain it");
                helper.assertFalse(StrainHazards.holds(stranger, TEST_HAZARD), "player on another team gained it");
                helper.assertFalse(StrainHazards.holds(late, TEST_HAZARD), "offline teammate gained it");
                helper.assertValueEqual(events.stream().map(CountermeasureGrantedEvent::getCause).toList(),
                        List.of(CountermeasureGrantedEvent.Cause.PROTOCOL, CountermeasureGrantedEvent.Cause.TEAM), "event causes");
                helper.assertTrue(StrainHazards.applies(stranger, TEST_HAZARD), "hazard stopped for a stranger");
                helper.assertFalse(StrainHazards.applies(late, TEST_HAZARD), "hazard applies to the offline teammate's team");

                // The teammate logs in later and gains it.
                Countermeasures.shareWithTeam(late, List.of(a, b, stranger, late));
                helper.assertTrue(StrainHazards.holds(late, TEST_HAZARD), "teammate logging in later did not gain it");

                // A player joining the team pools theirs with the team's, both ways.
                ResourceLocation brought = Remission.id("test_hazard_brought");
                Countermeasures.grant(joiner, brought, CountermeasureGrantedEvent.Cause.OTHER, List.of(joiner));
                team.add(joiner.getUUID());
                TeamQueries.invalidate(joiner.getUUID());
                Countermeasures.shareWithTeam(joiner, List.of(a, b, stranger, late, joiner));
                helper.assertTrue(StrainHazards.holds(joiner, TEST_HAZARD), "joiner did not gain the team's countermeasure");
                helper.assertTrue(StrainHazards.holds(a, brought) && StrainHazards.holds(b, brought) && StrainHazards.holds(late, brought),
                        "the team did not gain the joiner's countermeasure");
                helper.assertFalse(StrainHazards.holds(stranger, brought), "the joiner's countermeasure reached a stranger");

                // Revoke takes it from the online team.
                helper.assertValueEqual(Countermeasures.revoke(a, brought, List.of(a, b, late, joiner, stranger)), 4, "revoked");
                helper.assertFalse(StrainHazards.holds(joiner, brought), "revoke left it with a teammate");
            } finally {
                NeoForge.EVENT_BUS.unregister(listener);
            }
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void hazardEventHoldsBackAndCountermeasureOverrides(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer held = SurgeTests.player(helper, pos);
        ServerPlayer other = SurgeTests.player(helper, pos);
        AtomicInteger asked = new AtomicInteger();
        Consumer<StrainHazardEvent> listener = event -> {
            if (event.getHazard().equals(TEST_HAZARD) && event.getPlayer().filter(p -> p == held).isPresent()) {
                asked.incrementAndGet();
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            helper.assertFalse(StrainHazards.applies(held, TEST_HAZARD), "the event did not hold the hazard back");
            helper.assertTrue(StrainHazards.applies(other, TEST_HAZARD), "the event held the hazard back for another player");
            helper.assertValueEqual(asked.get(), 1, "events for the held-back player");

            // A countermeasure decides before any listener is asked.
            Countermeasures.grant(other, TEST_HAZARD, CountermeasureGrantedEvent.Cause.OTHER, List.of(other));
            Countermeasures.grant(held, TEST_HAZARD, CountermeasureGrantedEvent.Cause.OTHER, List.of(held));
            helper.assertFalse(StrainHazards.applies(other, TEST_HAZARD), "the countermeasure did not stop the hazard");
            helper.assertFalse(StrainHazards.applies(held, TEST_HAZARD), "the hazard applies to a holder");
            helper.assertValueEqual(asked.get(), 1, "the event was posted for a holder");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void blockHazardsFollowPresenceRule(GameTestHelper helper) {
        BlockPos block = helper.absolutePos(new BlockPos(1, 1, 1));
        ServerPlayer placer = SurgeTests.player(helper, block.above());
        ServerPlayer teammate = SurgeTests.player(helper, block.above());
        ServerPlayer stranger = SurgeTests.player(helper, block.above());
        ServerPlayer farTeammate = SurgeTests.player(helper, block.above().east(Countermeasures.NEARBY_RADIUS + 16));
        ServerPlayer farStranger = SurgeTests.player(helper, block.above().east(Countermeasures.NEARBY_RADIUS + 16));
        UUID id = placer.getUUID();
        ResourceLocation hazard = Remission.id("test_block_hazard_" + UUID.randomUUID().toString().substring(0, 8));
        withTeam(Set.of(id, teammate.getUUID(), farTeammate.getUUID()), () -> {
            // TEAM_ONLINE (the default): the placer's team must be online, anywhere.
            helper.assertFalse(at(helper, block, id, hazard, BlockHazardMode.TEAM_ONLINE), "team_online, nobody online");
            helper.assertFalse(at(helper, block, id, hazard, BlockHazardMode.TEAM_ONLINE, stranger), "team_online, a stranger");
            helper.assertTrue(at(helper, block, id, hazard, BlockHazardMode.TEAM_ONLINE, farTeammate), "team_online, a teammate far away");
            helper.assertTrue(at(helper, block, id, hazard, BlockHazardMode.TEAM_ONLINE, placer), "team_online, the placer");

            // PLAYER_NEARBY: someone from the placer's team near the block.
            helper.assertFalse(at(helper, block, id, hazard, BlockHazardMode.PLAYER_NEARBY, farTeammate, stranger),
                    "player_nearby, teammate far and stranger near");
            helper.assertTrue(at(helper, block, id, hazard, BlockHazardMode.PLAYER_NEARBY, teammate), "player_nearby, teammate near");

            // ALWAYS: no presence rule.
            helper.assertTrue(at(helper, block, id, hazard, BlockHazardMode.ALWAYS), "always, nobody online");

            // No known placer: any player nearby, whatever the mode.
            helper.assertFalse(at(helper, block, null, hazard, BlockHazardMode.ALWAYS), "no placer, nobody online");
            helper.assertFalse(at(helper, block, null, hazard, BlockHazardMode.TEAM_ONLINE, farStranger), "no placer, nobody near");
            helper.assertTrue(at(helper, block, null, hazard, BlockHazardMode.TEAM_ONLINE, stranger), "no placer, a player near");

            // The placer's team holds the countermeasure (known from the records while all of them are offline).
            Countermeasures.grant(teammate, hazard, CountermeasureGrantedEvent.Cause.OTHER, List.of(teammate));
            helper.assertFalse(at(helper, block, id, hazard, BlockHazardMode.ALWAYS), "always, the team holds it");
            helper.assertFalse(at(helper, block, id, hazard, BlockHazardMode.TEAM_ONLINE, placer), "team_online, the team holds it");
            helper.assertTrue(at(helper, block, null, hazard, BlockHazardMode.TEAM_ONLINE, stranger), "no placer, a stranger near");
            helper.assertFalse(at(helper, block, null, hazard, BlockHazardMode.TEAM_ONLINE, teammate), "no placer, a holder near");
        });

        // The event holds a block hazard back, with the block's position and placer.
        List<StrainHazardEvent> seen = new ArrayList<>();
        Consumer<StrainHazardEvent> listener = event -> {
            if (event.getHazard().equals(hazard)) {
                seen.add(event);
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            helper.assertFalse(at(helper, block, stranger.getUUID(), hazard, BlockHazardMode.TEAM_ONLINE, stranger),
                    "the event did not hold the block hazard back");
            helper.assertValueEqual(seen.size(), 1, "block hazard events");
            StrainHazardEvent event = seen.getFirst();
            helper.assertTrue(event.isBlockHazard() && event.getPos().orElseThrow().equals(block)
                    && event.getPlacer().orElseThrow().equals(stranger.getUUID())
                    && event.getPlayer().orElseThrow() == stranger, "block hazard event fields");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void attachmentSavesAndSyncs(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer player = SurgeTests.player(helper, pos);
        ServerPlayer other = SurgeTests.player(helper, pos);
        ServerPlayer fresh = SurgeTests.player(helper, pos);
        Countermeasures.grant(player, TEST_HAZARD, CountermeasureGrantedEvent.Cause.OTHER, List.of(player));
        Countermeasures.grant(player, StrainHazards.CONTACT, CountermeasureGrantedEvent.Cause.OTHER, List.of(player));

        CompoundTag saved = player.saveWithoutId(new CompoundTag());
        ServerPlayer loaded = SurgeTests.player(helper, pos);
        loaded.load(saved);
        helper.assertValueEqual(StrainHazards.countermeasures(loaded), Set.of(StrainHazards.CONTACT, TEST_HAZARD),
                "countermeasures after save and load");
        helper.assertFalse(fresh.saveWithoutId(new CompoundTag()).getCompound("neoforge:attachments")
                .contains(Remission.id("countermeasures").toString()), "an empty set was saved");

        // Updates sync to the owner only, in this wire format.
        helper.assertTrue(Countermeasures.syncsTo(player, player), "owner not synced");
        helper.assertFalse(Countermeasures.syncsTo(player, other), "update synced to another player");
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CountermeasureSet set = player.getData(RemissionAttachments.COUNTERMEASURES);
            CountermeasureSet.STREAM_CODEC.encode(buf, set);
            helper.assertValueEqual(CountermeasureSet.STREAM_CODEC.decode(buf), set, "decoded countermeasures");
        } finally {
            buf.release();
        }

        // Kept through death (the respawn clone).
        ServerPlayer respawned = SurgeTests.player(helper, pos);
        NeoForge.EVENT_BUS.post(new PlayerEvent.Clone(respawned, player, true));
        helper.assertValueEqual(StrainHazards.countermeasures(respawned), Set.of(StrainHazards.CONTACT, TEST_HAZARD),
                "countermeasures after death");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void commandTriggerAndStat(GameTestHelper helper) throws CommandSyntaxException {
        ServerPlayer player = SurgeTests.player(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        MinecraftServer server = helper.getLevel().getServer();
        AdvancementHolder any = gate(helper, player, "{\"trigger\": \"remission:countermeasure_gained\"}");
        AdvancementHolder contact = gate(helper, player,
                "{\"trigger\": \"remission:countermeasure_gained\", \"conditions\": {\"hazard\": \"remission:contact\"}}");
        CommandSourceStack source = server.createCommandSourceStack().withEntity(player).withLevel(helper.getLevel())
                .withPermission(2).withSuppressedOutput();
        int before = player.getStats().getValue(Stats.CUSTOM.get(RemissionStats.COUNTERMEASURES_GAINED.get()));

        helper.assertValueEqual(run(server, source, "remission countermeasure grant @s " + TEST_HAZARD), 1, "grant result");
        helper.assertTrue(StrainHazards.holds(player, TEST_HAZARD), "grant did not grant");
        helper.assertTrue(done(player, any), "trigger without conditions did not fire");
        helper.assertFalse(done(player, contact), "contact trigger fired for another hazard");
        helper.assertValueEqual(run(server, source, "remission countermeasure grant @s " + TEST_HAZARD), 0, "grant again");

        helper.assertValueEqual(run(server, source, "remission countermeasure grant @s remission:contact"), 1, "grant contact");
        helper.assertTrue(done(player, contact), "contact trigger did not fire");
        helper.assertValueEqual(player.getStats().getValue(Stats.CUSTOM.get(RemissionStats.COUNTERMEASURES_GAINED.get())),
                before + 2, "countermeasures gained stat");

        helper.assertValueEqual(run(server, source, "remission countermeasure list @s"), 2, "list result");
        helper.assertValueEqual(run(server, source, "remission countermeasure revoke @s " + TEST_HAZARD), 1, "revoke result");
        helper.assertValueEqual(StrainHazards.countermeasures(player), Set.of(StrainHazards.CONTACT), "after revoke");
        helper.assertValueEqual(run(server, source, "remission countermeasure revoke @s " + TEST_HAZARD), 0, "revoke again");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void synthesizerMakesEachDefaultProtocol(GameTestHelper helper) {
        List<BlockPos> machines = new ArrayList<>();
        int x = 1;
        for (Map.Entry<ResourceLocation, ItemLike> protocol : DEFAULT_PROTOCOLS.entrySet()) {
            BlockPos pos = new BlockPos(x, 1, 1);
            x += 2;
            machines.add(pos);
            helper.setBlock(pos, RemissionBlocks.SYNTHESIZER.get());
            BlockPos at = helper.absolutePos(pos);
            IEnergyStorage energy = helper.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, at, Direction.DOWN);
            int left = MachineKind.ENERGY_CAPACITY;
            while (left > 0) {
                int received = energy.receiveEnergy(left, false);
                if (received == 0) {
                    break;
                }
                left -= received;
            }
            IItemHandler items = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, at, Direction.UP);
            items.insertItem(0, new ItemStack(RemissionItems.STRAIN_SEQUENCE.get()), false);
            items.insertItem(1, new ItemStack(protocol.getValue()), false);
            items.insertItem(2, new ItemStack(Items.PAPER), false);
            helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, at, Direction.NORTH)
                    .fill(new FluidStack(RemissionFluids.MIASMA.get(), 1000), IFluidHandler.FluidAction.EXECUTE);
        }
        List<ResourceLocation> hazards = List.copyOf(DEFAULT_PROTOCOLS.keySet());
        helper.succeedWhen(() -> {
            for (int i = 0; i < machines.size(); i++) {
                IItemHandler items = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                        helper.absolutePos(machines.get(i)), Direction.UP);
                ItemStack out = items.extractItem(3, 64, true);
                helper.assertTrue(out.is(RemissionItems.COUNTERMEASURE_PROTOCOL.get())
                        && hazards.get(i).equals(out.get(RemissionDataComponents.HAZARD)), "protocol for " + hazards.get(i) + ", got " + out);
            }
        });
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    /** The block hazard query in {@code mode} with {@code online} as the online players. */
    private static boolean at(GameTestHelper helper, BlockPos block, UUID placer, ResourceLocation hazard,
            BlockHazardMode mode, ServerPlayer... online) {
        return Countermeasures.appliesAtBlock(helper.getLevel(), block, placer, hazard, mode, List.of(online));
    }

    /** Runs {@code body} with every pair of {@code members} answered as teammates (read live, so it can grow). */
    private static void withTeam(Set<UUID> members, Runnable body) {
        Consumer<TeamLookupEvent> listener = event -> {
            if (members.contains(event.getFirst()) && members.contains(event.getSecond())) {
                event.setTeammates(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            members.forEach(TeamQueries::invalidate);
            body.run();
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            Set.copyOf(members).forEach(TeamQueries::invalidate);
        }
    }

    private static int run(MinecraftServer server, CommandSourceStack source, String command) throws CommandSyntaxException {
        return server.getCommands().getDispatcher().execute(command, source);
    }

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
}
