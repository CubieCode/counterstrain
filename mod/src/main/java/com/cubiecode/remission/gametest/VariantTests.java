package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CorruptionCause;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.PurificationCause;
import com.cubiecode.remission.api.VariantEmergedEvent;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.cubiecode.remission.network.RemissionNetwork;
import com.cubiecode.remission.network.VariantLandCache;
import com.cubiecode.remission.network.VariantLandPayload;
import com.cubiecode.remission.purification.Cleansing;
import com.cubiecode.remission.purification.Purification;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionDataComponents;
import com.cubiecode.remission.registry.RemissionFluids;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.research.ScanData;
import com.cubiecode.remission.tech.MachineKind;
import com.cubiecode.remission.variant.ResistedMethod;
import com.cubiecode.remission.variant.Variant;
import com.cubiecode.remission.variant.VariantCounters;
import com.cubiecode.remission.variant.VariantEffects;
import com.cubiecode.remission.variant.VariantPressure;
import com.cubiecode.remission.variant.VariantRecords;
import com.cubiecode.remission.variant.VariantSettings;
import com.cubiecode.remission.variant.Variants;
import com.cubiecode.remission.world.ChunkCorruption;
import com.cubiecode.remission.world.NidusIndex;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Resistant variants (P7.5): pressure making the matching variant emerge at the overused nidus only, mixing methods
 * delaying it, purification and damage reduced by the resistance, serums and fuels of a variant restoring full effect,
 * the event cancelling emergence, the command, the triggers, the off switch, save and sync, and the defaults with their
 * recipes. The land tests each run alone in their own batch (they replace {@link VariantSettings}) in their own row of
 * chunks far east of the test grid, place their own nidi there, call purification and damage directly within one tick
 * and remove everything afterwards.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VariantTests {
    private static final String BATCH = "variants";
    /** Chunks east of the test's own chunk to the first test area (beyond {@link ReclaimerTests}' areas). */
    private static final int FAR = 900;
    /** Chunks between test areas: room for two nidi 20 chunks apart and their reach. */
    private static final int SPACING = 48;
    /** Chunks between the two nidi of an area, more than twice the default reach (7), so their lands never meet. */
    private static final int APART = 20;
    private static final int BASE_Y = 100;
    /** Thresholds at a tenth (purifier 12, mendroot 15, pylon 150 levels; melee 4 kills), half of the total needed. */
    private static final VariantSettings SETTINGS = new VariantSettings(true, 0.1, 0.5, 200);

    private static final ResourceLocation PURIFIER = Remission.id("purifier_resistant");
    private static final ResourceLocation MELEE = Remission.id("melee_resistant");
    private static final ResourceLocation FIRE = Remission.id("fire_resistant");
    private static final ResourceLocation PYLON = Remission.id("pylon_resistant");

    private VariantTests() {
    }

    @GameTest(template = "empty", batch = BATCH + "_pressure")
    public static void overusedMethodMakesItsVariantOnlyAtThatNidus(GameTestHelper helper) {
        inArea(helper, 0, area -> {
            ServerLevel level = helper.getLevel();
            BlockPos a = area.nidus(0, 0);
            BlockPos b = area.nidus(APART, 0);
            LevelChunk nearA = area.chunk(1, 0);
            LevelChunk nearB = area.chunk(APART + 1, 0);
            setLevel(nearA, 100);
            ServerPlayer inA = area.player(2, 0);
            ServerPlayer inB = area.player(APART + 2, 0);
            AdvancementHolder emergedA = gate(helper, inA, "{\"trigger\": \"remission:variant_emerged\", \"conditions\": {\"variant\": \""
                    + PURIFIER + "\"}}");
            AdvancementHolder emergedB = gate(helper, inB, "{\"trigger\": \"remission:variant_emerged\"}");
            List<VariantEmergedEvent> events = new ArrayList<>();
            listen(VariantEmergedEvent.class, events::add, () -> {
                for (int i = 0; i < 11; i++) {
                    Purification.lowerLevel(level, nearA, 1, PurificationCause.PURIFIER, null);
                }
                helper.assertTrue(VariantRecords.get(level).variant(a).isEmpty(), "variant before its threshold");
                helper.assertValueEqual(pressure(level, a, PURIFIER), 11.0F, "purifier pressure");
                Purification.lowerLevel(level, nearA, 1, PurificationCause.PURIFIER, null);
            });
            helper.assertValueEqual(VariantRecords.get(level).variant(a), Optional.of(PURIFIER), "variant at the overused nidus");
            helper.assertTrue(VariantRecords.get(level).variant(b).isEmpty(), "variant at the other nidus");
            helper.assertValueEqual(events.size(), 1, "emerged events");
            VariantEmergedEvent event = events.getFirst();
            helper.assertTrue(event.getNidus().equals(a) && event.getVariant().equals(PURIFIER)
                    && event.getCause() == VariantEmergedEvent.Cause.PRESSURE && event.getPreviousVariant().isEmpty(), "event fields");
            helper.assertValueEqual(VariantRecords.get(level).get(a).total(), 0.0F, "pressure after emergence");
            helper.assertValueEqual(Variants.variantAt(level, nearA.getPos()), Optional.of(PURIFIER), "land variant");
            helper.assertValueEqual(Variants.variantAt(level, nearB.getPos()), Optional.empty(), "other land's variant");
            helper.assertValueEqual(CorruptionQueries.variantAt(level, nearA.getPos().getWorldPosition()), Optional.of(PURIFIER),
                    "variant query");
            helper.assertTrue(done(inA, emergedA), "variant_emerged did not fire in the land");
            helper.assertFalse(done(inB, emergedB), "variant_emerged fired in another nidus's land");

            // Hosts killed by a player in B's land: the melee variant there, by kill count.
            for (int i = 0; i < 4; i++) {
                helper.assertTrue(VariantRecords.get(level).variant(b).isEmpty(), "melee variant after " + i + " kills");
                Pig host = area.pig(APART + 1, 0);
                Corruption.set(host, 50, CorruptionCause.COMMAND);
                host.hurt(level.damageSources().playerAttack(inB), 1000.0F);
                helper.assertTrue(host.isDeadOrDying(), "host survived");
            }
            helper.assertValueEqual(VariantRecords.get(level).variant(b), Optional.of(MELEE), "variant from kills");
            helper.assertValueEqual(VariantRecords.get(level).variant(a), Optional.of(PURIFIER), "first nidus's variant");
            helper.assertTrue(done(inB, emergedB), "variant_emerged did not fire for the kills");

            // A host killed with no player behind it builds nothing.
            Pig unattributed = area.pig(1, 0);
            Corruption.set(unattributed, 50, CorruptionCause.COMMAND);
            unattributed.hurt(level.damageSources().generic(), 1000.0F);
            helper.assertValueEqual(VariantRecords.get(level).get(a).total(), 0.0F, "pressure from a kill without a player");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_mixing")
    public static void mixingMethodsDelaysVariants(GameTestHelper helper) {
        inArea(helper, 1, area -> {
            ServerLevel level = helper.getLevel();
            BlockPos nidus = area.nidus(0, 0);
            List<LevelChunk> land = List.of(area.chunk(1, 0), area.chunk(0, 1), area.chunk(-1, 0));
            land.forEach(chunk -> setLevel(chunk, 100));
            // Three methods in turn: purifier pressure passes its threshold (12) but holds only a third of the total.
            for (int i = 0; i < 20; i++) {
                Purification.lowerLevel(level, land.get(0), 1, PurificationCause.PURIFIER, null);
                Purification.lowerLevel(level, land.get(1), 1, PurificationCause.MENDROOT, null);
                Purification.lowerLevel(level, land.get(2), 1, PurificationCause.PYLON, null);
            }
            helper.assertTrue(VariantRecords.get(level).variant(nidus).isEmpty(), "a variant emerged from mixed methods");
            helper.assertValueEqual(VariantRecords.get(level).get(nidus).total(), 60.0F, "total pressure");
            // The purifier alone until it holds half of the total: 20 more uses (40 of 80), not 19 (39 of 79).
            for (int i = 0; i < 19; i++) {
                Purification.lowerLevel(level, land.get(0), 1, PurificationCause.PURIFIER, null);
            }
            helper.assertTrue(VariantRecords.get(level).variant(nidus).isEmpty(), "variant below half the total");
            Purification.lowerLevel(level, land.get(0), 1, PurificationCause.PURIFIER, null);
            helper.assertValueEqual(VariantRecords.get(level).variant(nidus), Optional.of(PURIFIER), "variant at half the total");

            // With the rule off, the threshold alone decides.
            VariantSettings.override(new VariantSettings(true, 0.1, 0.0, 200));
            for (int i = 0; i < 14; i++) {
                Purification.lowerLevel(level, land.get(0), 1, PurificationCause.PURIFIER, null);
                Purification.lowerLevel(level, land.get(1), 1, PurificationCause.MENDROOT, null);
            }
            helper.assertValueEqual(VariantRecords.get(level).variant(nidus), Optional.of(PURIFIER), "variant before 15 mendroot");
            Purification.lowerLevel(level, land.get(1), 1, PurificationCause.MENDROOT, null);
            helper.assertValueEqual(VariantRecords.get(level).variant(nidus), Optional.of(Remission.id("mendroot_resistant")),
                    "variant after 15 mendroot with minShare 0");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_resistance")
    public static void purificationAndDamageAreReducedByTheResistance(GameTestHelper helper) {
        inArea(helper, 2, area -> {
            ServerLevel level = helper.getLevel();
            BlockPos a = area.nidus(0, 0);
            BlockPos b = area.nidus(APART, 0);
            helper.assertTrue(VariantPressure.emerge(level, a, PURIFIER, VariantEmergedEvent.Cause.OTHER), "purifier variant");
            helper.assertTrue(VariantPressure.emerge(level, b, MELEE, VariantEmergedEvent.Cause.OTHER), "melee variant");
            LevelChunk land = area.chunk(1, 0);
            setLevel(land, 50);
            Purification.lowerLevel(level, land, 10, PurificationCause.PURIFIER, null);
            helper.assertValueEqual(levelOf(land), 45, "level after a resisted step of 10");
            Purification.lowerLevel(level, land, 10, PurificationCause.PYLON, null);
            helper.assertValueEqual(levelOf(land), 35, "level after a pylon step of 10");
            // A remainder is resolved per chunk and game time, so a step of 3 takes 1 or 2.
            Purification.lowerLevel(level, land, 3, PurificationCause.PURIFIER, null);
            helper.assertTrue(levelOf(land) == 34 || levelOf(land) == 33, "level after a resisted step of 3: " + levelOf(land));
            LevelChunk outside = area.chunk(-9, 0);
            setLevel(outside, 50);
            Purification.lowerLevel(level, outside, 10, PurificationCause.PURIFIER, null);
            helper.assertValueEqual(levelOf(outside), 40, "level outside every nidus's land");

            ServerPlayer player = area.player(APART + 2, 0);
            Pig host = area.pig(APART + 1, 0);
            Corruption.set(host, 50, CorruptionCause.COMMAND);
            helper.assertValueEqual(Corruption.get(host).variant(), Optional.of(MELEE), "host turned in the land");
            helper.assertValueEqual(CorruptionQueries.hostVariant(host), Optional.of(MELEE), "host variant query");
            host.hurt(level.damageSources().playerAttack(player), 4.0F);
            helper.assertValueEqual(host.getHealth(), 8.0F, "health after a resisted hit of 4");
            host.invulnerableTime = 0;
            host.hurt(level.damageSources().source(DamageTypes.IN_FIRE), 2.0F);
            helper.assertValueEqual(host.getHealth(), 6.0F, "health after fire, which melee variants do not resist");
            Pig clean = area.pig(-9, 0);
            Corruption.set(clean, 50, CorruptionCause.COMMAND);
            helper.assertTrue(Corruption.get(clean).variant().isEmpty(), "host turned outside every land has a variant");
            clean.hurt(level.damageSources().playerAttack(player), 4.0F);
            helper.assertValueEqual(clean.getHealth(), 6.0F, "health of a host without a variant");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_counter")
    public static void variantSerumAndFuelRestoreFullEffect(GameTestHelper helper) {
        inArea(helper, 3, area -> {
            ServerLevel level = helper.getLevel();
            BlockPos a = area.nidus(0, 0);
            BlockPos b = area.nidus(APART, 0);
            VariantPressure.emerge(level, a, PURIFIER, VariantEmergedEvent.Cause.OTHER);
            VariantPressure.emerge(level, b, MELEE, VariantEmergedEvent.Cause.OTHER);

            // Serum: its drinker hurts the variant's hosts in full; another player still does not.
            ServerPlayer drinker = area.player(APART + 2, 0);
            ServerPlayer other = area.player(APART + 2, 1);
            ItemStack serum = withVariant(new ItemStack(RemissionItems.SERUM.get()), MELEE);
            RemissionItems.SERUM.get().finishUsingItem(serum, level, drinker);
            helper.assertTrue(VariantEffects.countered(drinker, MELEE), "serum did not counter its variant");
            helper.assertFalse(VariantEffects.countered(drinker, FIRE), "serum countered another variant");
            Pig host = area.pig(APART + 1, 0);
            Corruption.set(host, 50, CorruptionCause.COMMAND);
            host.hurt(level.damageSources().playerAttack(drinker), 4.0F);
            helper.assertValueEqual(host.getHealth(), 6.0F, "health after a countered hit of 4");
            host.invulnerableTime = 0;
            host.hurt(level.damageSources().playerAttack(other), 4.0F);
            helper.assertValueEqual(host.getHealth(), 4.0F, "health after an uncountered hit of 4");
            // The counter runs out.
            drinker.setData(RemissionAttachments.VARIANT_COUNTERS, new VariantCounters(Map.of(MELEE, level.getGameTime())));
            helper.assertFalse(VariantEffects.countered(drinker, MELEE), "counter did not expire");

            // Fuel used on a block in the land counters the nidus for every tool; a fuel of another variant does nothing.
            LevelChunk land = area.chunk(1, 0);
            setLevel(land, 50);
            BlockPos target = area.block(1, 0, Blocks.STONE);
            ServerPlayer user = area.player(1, 0);
            useOn(user, level, withVariant(new ItemStack(RemissionItems.PURIFIER_FUEL.get(), 2), FIRE), target);
            helper.assertValueEqual(user.getMainHandItem().getCount(), 2, "fuel of another variant used up");
            helper.assertFalse(VariantRecords.get(level).get(a).countered(level.getGameTime()), "countered by another variant's fuel");
            useOn(user, level, withVariant(new ItemStack(RemissionItems.PURIFIER_FUEL.get(), 2), PURIFIER), target);
            helper.assertValueEqual(user.getMainHandItem().getCount(), 1, "fuel not used up");
            helper.assertTrue(VariantRecords.get(level).get(a).countered(level.getGameTime()), "fuel did not counter the nidus");
            Purification.lowerLevel(level, land, 10, PurificationCause.PURIFIER, null);
            helper.assertValueEqual(levelOf(land), 40, "level after a countered step of 10");

            // A reclaimer burning a variant's fuel counters that variant's nidi whose land reaches it.
            VariantPressure.clear(level, b);
            VariantPressure.emerge(level, b, PYLON, VariantEmergedEvent.Cause.OTHER);
            BlockPos reclaimer = area.at(APART + 9, 0);
            helper.assertValueEqual(VariantEffects.reclaimerBurned(level, reclaimer, 2,
                    withVariant(new ItemStack(RemissionItems.PURIFIER_FUEL.get()), PYLON)), 1, "nidi countered by a reclaimer");
            helper.assertTrue(VariantRecords.get(level).get(b).countered(level.getGameTime()), "reclaimer fuel did not counter");
            helper.assertValueEqual(VariantEffects.reclaimerBurned(level, area.at(APART + 12, 0), 2,
                    withVariant(new ItemStack(RemissionItems.PURIFIER_FUEL.get()), PYLON)), 0, "reclaimer out of reach countered");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_cancel")
    public static void cancellingTheEventKeepsTheNidus(GameTestHelper helper) {
        inArea(helper, 4, area -> {
            ServerLevel level = helper.getLevel();
            BlockPos nidus = area.nidus(0, 0);
            LevelChunk land = area.chunk(1, 0);
            setLevel(land, 100);
            listen(VariantEmergedEvent.class, event -> event.setCanceled(true), () -> {
                for (int i = 0; i < 12; i++) {
                    Purification.lowerLevel(level, land, 1, PurificationCause.PURIFIER, null);
                }
                helper.assertTrue(VariantRecords.get(level).variant(nidus).isEmpty(), "cancelled variant emerged");
                helper.assertValueEqual(pressure(level, nidus, PURIFIER), 0.0F, "pressure of the cancelled variant");
                helper.assertValueEqual(VariantRecords.get(level).get(nidus).total(), 12.0F, "total kept");
                helper.assertFalse(VariantPressure.emerge(level, nidus, PURIFIER, VariantEmergedEvent.Cause.OTHER),
                        "emergence reported despite the cancel");
            });
            Purification.lowerLevel(level, land, 1, PurificationCause.PURIFIER, null);
            helper.assertTrue(VariantRecords.get(level).variant(nidus).isEmpty(), "variant before the pressure built again");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_command")
    public static void commandSetsClearsAndQueries(GameTestHelper helper) {
        // The host's chunk is new; entity searches find a mob in it only a few ticks after it loads, as in play, where
        // hosts live in loaded chunks. So the area is set up now and checked later.
        ChunkPos own = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        Area area = new Area(helper, new ChunkPos(own.x + FAR + 5 * SPACING, own.z), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        VariantSettings.override(SETTINGS);
        BlockPos nidus = area.nidus(0, 0);
        Pig host = area.pig(1, 0);
        Corruption.set(host, 50, CorruptionCause.COMMAND);
        ServerPlayer player = area.player(2, 0);
        helper.runAfterDelay(20, () -> {
            try {
                commandChecks(helper, area, nidus, host, player);
            } finally {
                VariantSettings.override(null);
                area.release();
            }
        });
    }

    private static void commandChecks(GameTestHelper helper, Area area, BlockPos nidus, Pig host, ServerPlayer player) {
        ServerLevel level = helper.getLevel();
        AdvancementHolder fire = gate(helper, player, "{\"trigger\": \"remission:variant_emerged\", \"conditions\": {\"variant\": \""
                + FIRE + "\"}}");
        MinecraftServer server = level.getServer();
        CommandSourceStack source = server.createCommandSourceStack().withLevel(level).withPermission(2).withSuppressedOutput();
        String at = nidus.getX() + " " + nidus.getY() + " " + nidus.getZ();

        helper.assertValueEqual(run(server, source, "remission variant query " + at), 0, "query without a variant");
        helper.assertValueEqual(run(server, source, "remission variant set " + at + " " + FIRE), 1, "set");
        helper.assertValueEqual(VariantRecords.get(level).variant(nidus), Optional.of(FIRE), "variant after set");
        helper.assertValueEqual(Corruption.get(host).variant(), Optional.of(FIRE), "host after set");
        helper.assertTrue(done(player, fire), "variant_emerged did not fire for the command");
        helper.assertValueEqual(run(server, source, "remission variant query " + at), 1, "query with a variant");
        helper.assertValueEqual(run(server, source, "remission variant clear " + at), 1, "clear");
        helper.assertTrue(VariantRecords.get(level).variant(nidus).isEmpty(), "variant after clear");
        helper.assertValueEqual(Corruption.get(host).variant(), Optional.of(FIRE), "host lost its variant with the nidus");
        assertFails(server, source, "remission variant clear " + at, "clear without a variant");
        assertFails(server, source, "remission variant set " + at + " remission:no_such_variant", "unknown variant");
        BlockPos beside = nidus.east();
        assertFails(server, source, "remission variant set " + beside.getX() + " " + beside.getY() + " " + beside.getZ()
                + " " + FIRE, "set beside a nidus");
        listen(VariantEmergedEvent.class, event -> event.setCanceled(true),
                () -> assertFails(server, source, "remission variant set " + at + " " + FIRE, "set while cancelled"));
        helper.assertTrue(VariantRecords.get(level).variant(nidus).isEmpty(), "cancelled set changed the nidus");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH + "_off")
    public static void offSwitchStopsVariants(GameTestHelper helper) {
        inArea(helper, 6, area -> {
            ServerLevel level = helper.getLevel();
            BlockPos a = area.nidus(0, 0);
            BlockPos b = area.nidus(APART, 0);
            VariantPressure.emerge(level, b, PURIFIER, VariantEmergedEvent.Cause.OTHER);
            VariantSettings.override(new VariantSettings(false, 0.1, 0.5, 200));
            LevelChunk landA = area.chunk(1, 0);
            LevelChunk landB = area.chunk(APART + 1, 0);
            setLevel(landA, 100);
            setLevel(landB, 50);
            for (int i = 0; i < 40; i++) {
                Purification.lowerLevel(level, landA, 1, PurificationCause.PURIFIER, null);
            }
            helper.assertTrue(VariantRecords.get(level).get(a) == null, "pressure counted while off");
            Purification.lowerLevel(level, landB, 10, PurificationCause.PURIFIER, null);
            helper.assertValueEqual(levelOf(landB), 40, "purification resisted while off");
            helper.assertTrue(Variants.variantAt(level, landB.getPos()).isEmpty(), "land variant while off");
            Pig host = area.pig(APART + 1, 0);
            Corruption.set(host, 50, CorruptionCause.COMMAND);
            helper.assertTrue(Corruption.get(host).variant().isEmpty(), "new host took a variant while off");
            Corruption.setVariant(host, Optional.of(MELEE));
            ServerPlayer player = area.player(APART + 2, 0);
            host.hurt(level.damageSources().playerAttack(player), 4.0F);
            helper.assertValueEqual(host.getHealth(), 6.0F, "damage resisted while off");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_state")
    public static void variantSavesAndSyncs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HolderLookup.Provider registries = level.registryAccess();
        CorruptionState state = new CorruptionState(70, 3, Optional.of(FIRE));
        CompoundTag encoded = (CompoundTag) CorruptionState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        helper.assertValueEqual(encoded.getString("variant"), FIRE.toString(), "saved variant key");
        helper.assertValueEqual(CorruptionState.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow(), state, "decoded state");
        CompoundTag old = new CompoundTag();
        old.putInt("level", 40);
        old.putInt("stage", 2);
        helper.assertValueEqual(CorruptionState.CODEC.parse(NbtOps.INSTANCE, old).getOrThrow(), new CorruptionState(40, 2),
                "state saved before variants");
        ByteBuf buf = Unpooled.buffer();
        try {
            CorruptionState.STREAM_CODEC.encode(buf, state);
            helper.assertValueEqual(CorruptionState.STREAM_CODEC.decode(buf), state, "synced state");
            helper.assertFalse(buf.isReadable(), "trailing bytes");
        } finally {
            buf.release();
        }

        // With the entity, through a save and load.
        Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1);
        Corruption.set(pig, 70, CorruptionCause.COMMAND);
        helper.assertTrue(Corruption.setVariant(pig, Optional.of(FIRE)), "variant not set");
        helper.assertFalse(Corruption.setVariant(pig, Optional.of(FIRE)), "same variant changed the state");
        CompoundTag saved = pig.saveWithoutId(new CompoundTag());
        Pig reloaded = EntityType.PIG.create(level);
        helper.assertTrue(reloaded != null, "pig not created");
        reloaded.load(saved);
        helper.assertValueEqual(Corruption.get(reloaded).variant(), Optional.of(FIRE), "variant after reload");
        Corruption.set(pig, 30, CorruptionCause.COMMAND);
        helper.assertValueEqual(Corruption.get(pig).variant(), Optional.of(FIRE), "variant after a level change");
        Corruption.set(pig, 0, CorruptionCause.COMMAND);
        helper.assertTrue(Corruption.get(pig).variant().isEmpty(), "variant after cleansing");

        // The per-nidus records.
        VariantRecords records = new VariantRecords();
        BlockPos nidus = new BlockPos(5, 64, 7);
        CompoundTag recordTag = new CompoundTag();
        records.save(recordTag, registries);
        helper.assertTrue(VariantRecords.load(recordTag, registries).get(nidus) == null, "empty records");
        VariantRecords live = VariantRecords.get(level);
        BlockPos probe = new BlockPos(30_000_000 - 16, 64, 30_000_000 - 16);
        VariantPressure.emerge(level, probe, FIRE, VariantEmergedEvent.Cause.OTHER);
        VariantEffects.counterNidus(level, probe, FIRE);
        CompoundTag liveTag = live.save(new CompoundTag(), registries);
        VariantRecords loaded = VariantRecords.load(liveTag, registries);
        helper.assertValueEqual(loaded.variant(probe), Optional.of(FIRE), "saved nidus variant");
        helper.assertValueEqual(loaded.get(probe).counteredUntil(), live.get(probe).counteredUntil(), "saved counter");
        helper.assertTrue(loaded.anyVariant(), "loaded records lost their variant count");
        VariantPressure.clear(level, probe);
        helper.assertTrue(live.get(probe) == null, "cleared record kept");

        // The land payload and the client cache.
        Map<Long, Optional<ResourceLocation>> chunks = new LinkedHashMap<>();
        chunks.put(new ChunkPos(3, -4).toLong(), Optional.of(FIRE));
        chunks.put(new ChunkPos(-7, 9).toLong(), Optional.empty());
        VariantLandPayload payload = new VariantLandPayload(Level.OVERWORLD, chunks);
        FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            VariantLandPayload.STREAM_CODEC.encode(wire, payload);
            VariantLandPayload decoded = VariantLandPayload.STREAM_CODEC.decode(wire);
            helper.assertValueEqual(decoded.dimension(), Level.OVERWORLD, "payload dimension");
            helper.assertValueEqual(decoded.chunks(), chunks, "payload chunks");
            helper.assertFalse(wire.isReadable(), "trailing payload bytes");
        } finally {
            wire.release();
        }
        helper.assertValueEqual(RemissionNetwork.PROTOCOL_VERSION, "5", "protocol version with the variant land payload");
        long chunk = new ChunkPos(3, -4).toLong();
        VariantLandCache.apply(Map.of(chunk, Optional.of(FIRE)), id -> OptionalInt.of(0x123456));
        helper.assertValueEqual(VariantLandCache.tint(chunk), 0xFF123456, "cached tint");
        helper.assertValueEqual(VariantLandCache.variant(chunk), Optional.of(FIRE), "cached variant");
        VariantLandCache.apply(Map.of(chunk, Optional.empty()), id -> OptionalInt.of(0x123456));
        helper.assertValueEqual(VariantLandCache.tint(chunk), VariantLandCache.NO_TINT, "tint after the variant left");
        VariantLandCache.apply(Map.of(chunk, Optional.of(FIRE)), id -> OptionalInt.empty());
        helper.assertValueEqual(VariantLandCache.tint(chunk), VariantLandCache.NO_TINT, "tint of a variant the client lacks");
        VariantLandCache.forget(chunk);
        helper.assertTrue(VariantLandCache.variant(chunk).isEmpty(), "forgotten chunk");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH + "_defaults")
    public static void defaultsDecodeWithRecipes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Map.Entry<ResourceLocation, Variant>> all = Variants.all(level.registryAccess());
        helper.assertValueEqual(all.stream().map(Map.Entry::getKey).map(ResourceLocation::getPath).toList(), List.of(
                "blast_resistant", "fire_resistant", "magic_resistant", "melee_resistant", "mendroot_resistant",
                "projectile_resistant", "purifier_resistant", "pylon_resistant", "reclaimer_resistant"), "default variants");
        Variant purifier = Variants.get(level.registryAccess(), PURIFIER).orElseThrow();
        helper.assertTrue(purifier.resists().matches(PurificationCause.PURIFIER) && !purifier.resists().matches(PurificationCause.PYLON),
                "purifier variant method");
        helper.assertValueEqual(purifier.resistance(), 0.5F, "default resistance");
        Variant fire = Variants.get(level.registryAccess(), FIRE).orElseThrow();
        helper.assertTrue(fire.resists() instanceof ResistedMethod.Damage
                && fire.resists().matches(level.damageSources().source(DamageTypes.LAVA).typeHolder())
                && !fire.resists().matches(level.damageSources().source(DamageTypes.PLAYER_ATTACK).typeHolder()), "fire variant method");
        // A pack's entry in the JSON shape.
        Variant parsed = Variant.CODEC.parse(level.registryAccess().createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(
                "{\"resists\": {\"type\": \"remission:purification\", \"cause\": \"prototype\"}, \"resistance\": 0.25,"
                        + " \"threshold\": 10, \"tint\": \"#12AB34\"}")).getOrThrow();
        helper.assertTrue(parsed.resists().matches(PurificationCause.PROTOTYPE) && parsed.tint() == 0x12AB34
                && parsed.resistance() == 0.25F, "pack variant decoded");
        helper.assertTrue(Variant.CODEC.parse(level.registryAccess().createSerializationContext(JsonOps.INSTANCE),
                JsonParser.parseString("{\"resists\": {\"type\": \"remission:unknown\"}, \"resistance\": 0.5, \"threshold\": 1,"
                        + " \"tint\": 0}")).isError(), "unknown method type decoded");
        var recipes = level.getRecipeManager();
        for (Map.Entry<ResourceLocation, Variant> entry : all) {
            String path = entry.getKey().getPath();
            helper.assertTrue(recipes.byKey(Remission.id("sequencing/variant/" + path)).isPresent(), "no sequencing recipe for " + path);
            helper.assertTrue(recipes.byKey(Remission.id("synthesizing/variant/" + path)).isPresent(), "no synthesizing recipe for " + path);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH + "_machines", timeoutTicks = 800)
    public static void machinesSequenceAndSynthesizeVariants(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = SurgeTests.player(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        level.players().add(player);
        AdvancementHolder sequenced = gate(helper, player, "{\"trigger\": \"remission:variant_sequenced\", \"conditions\": {\"variant\": \""
                + FIRE + "\"}}");
        AdvancementHolder otherSequenced = gate(helper, player, "{\"trigger\": \"remission:variant_sequenced\", \"conditions\": {\"variant\": \""
                + MELEE + "\"}}");
        BlockPos sequencer = machine(helper, new BlockPos(1, 1, 1), RemissionBlocks.SEQUENCER.get());
        ItemStack scan = new ItemStack(RemissionItems.HOST_SCAN.get());
        scan.set(RemissionDataComponents.SCAN_DATA.get(), ScanData.host(ResourceLocation.withDefaultNamespace("pig"), 2));
        withVariant(scan, FIRE);
        insert(helper, sequencer, scan, new ItemStack(RemissionItems.STRAIN_SEQUENCE.get()));
        BlockPos serum = machine(helper, new BlockPos(3, 1, 1), RemissionBlocks.SYNTHESIZER.get());
        insert(helper, serum, withVariant(new ItemStack(RemissionItems.STRAIN_SEQUENCE.get()), FIRE),
                new ItemStack(Items.GLASS_BOTTLE, 2), new ItemStack(RemissionItems.HOST_TISSUE.get()));
        BlockPos fuel = machine(helper, new BlockPos(5, 1, 1), RemissionBlocks.SYNTHESIZER.get());
        insert(helper, fuel, withVariant(new ItemStack(RemissionItems.STRAIN_SEQUENCE.get()), PYLON),
                new ItemStack(Items.COAL, 4), new ItemStack(RemissionItems.FESTERED_SOIL.get()));
        helper.succeedWhen(() -> {
            ItemStack sequence = output(helper, sequencer, MachineKind.SEQUENCER);
            helper.assertTrue(sequence.is(RemissionItems.STRAIN_SEQUENCE.get()) && FIRE.equals(sequence.get(RemissionDataComponents.VARIANT)),
                    "variant sequence, got " + sequence);
            ItemStack serums = output(helper, serum, MachineKind.SYNTHESIZER);
            helper.assertTrue(serums.is(RemissionItems.SERUM.get()) && serums.getCount() == 2
                    && FIRE.equals(serums.get(RemissionDataComponents.VARIANT)), "variant serum, got " + serums);
            ItemStack fuels = output(helper, fuel, MachineKind.SYNTHESIZER);
            helper.assertTrue(fuels.is(RemissionItems.PURIFIER_FUEL.get()) && fuels.getCount() == 4
                    && PYLON.equals(fuels.get(RemissionDataComponents.VARIANT)), "variant fuel, got " + fuels);
            helper.assertTrue(done(player, sequenced), "variant_sequenced did not fire");
            helper.assertFalse(done(player, otherSequenced), "variant_sequenced fired for another variant");
            level.players().remove(player);
        });
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static BlockPos machine(GameTestHelper helper, BlockPos pos, Block block) {
        helper.setBlock(pos, block);
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
        helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, at, Direction.NORTH)
                .fill(new FluidStack(RemissionFluids.MIASMA.get(), 1000), IFluidHandler.FluidAction.EXECUTE);
        return at;
    }

    private static void insert(GameTestHelper helper, BlockPos machine, ItemStack... stacks) {
        IItemHandler items = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, machine, Direction.UP);
        for (int i = 0; i < stacks.length; i++) {
            items.insertItem(i, stacks[i], false);
        }
    }

    private static ItemStack output(GameTestHelper helper, BlockPos machine, MachineKind kind) {
        IItemHandler items = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, machine, Direction.UP);
        return items.extractItem(kind.inputSlots(), 64, true);
    }

    private static ItemStack withVariant(ItemStack stack, ResourceLocation variant) {
        stack.set(RemissionDataComponents.VARIANT.get(), variant);
        return stack;
    }

    private static void useOn(ServerPlayer player, ServerLevel level, ItemStack stack, BlockPos pos) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private static float pressure(ServerLevel level, BlockPos nidus, ResourceLocation variant) {
        VariantRecords.Entry entry = VariantRecords.get(level).get(nidus);
        return entry == null ? 0.0F : entry.pressure(variant);
    }

    /** Runs {@code body} with {@code listener} on the event bus. */
    private static <E extends Event> void listen(Class<E> type, Consumer<E> listener, Runnable body) {
        Consumer<E> registered = listener::accept;
        NeoForge.EVENT_BUS.addListener(type, registered);
        try {
            body.run();
        } finally {
            NeoForge.EVENT_BUS.unregister(registered);
        }
    }

    private static int run(MinecraftServer server, CommandSourceStack source, String command) {
        try {
            return server.getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            throw new AssertionError("/" + command + " failed: " + e.getMessage(), e);
        }
    }

    private static void assertFails(MinecraftServer server, CommandSourceStack source, String command, String what) {
        try {
            server.getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            return;
        }
        throw new AssertionError(what + ": /" + command + " succeeded");
    }

    private static AdvancementHolder gate(GameTestHelper helper, ServerPlayer player, String json) {
        Criterion<?> criterion = Criterion.CODEC.parse(
                helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(json)).getOrThrow();
        AdvancementHolder holder = Advancement.Builder.advancement().addCriterion("gate", criterion)
                .build(Remission.id("test/" + UUID.randomUUID()));
        addListener(player, criterion, holder);
        return holder;
    }

    private static <T extends CriterionTriggerInstance> void addListener(ServerPlayer player, Criterion<T> criterion,
                                                                         AdvancementHolder holder) {
        criterion.trigger().addPlayerListener(player.getAdvancements(),
                new CriterionTrigger.Listener<>(criterion.triggerInstance(), holder, "gate"));
    }

    private static boolean done(ServerPlayer player, AdvancementHolder holder) {
        return player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    private static void setLevel(LevelChunk chunk, int value) {
        chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(value, 0L));
    }

    private static int levelOf(LevelChunk chunk) {
        ChunkCorruption state = chunk.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
        return state == null ? 0 : state.level();
    }

    private static void inArea(GameTestHelper helper, int index, Consumer<Area> body) {
        ChunkPos own = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        Area area = new Area(helper, new ChunkPos(own.x + FAR + index * SPACING, own.z), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        VariantSettings.override(SETTINGS);
        try {
            body.accept(area);
        } finally {
            VariantSettings.override(null);
            area.release();
        }
    }

    /** One test's chunks, blocks, nidi, mobs and players, all removed afterwards. */
    private record Area(GameTestHelper helper, ChunkPos origin, List<LevelChunk> chunks, List<BlockPos> blocks, List<BlockPos> nidi,
                        List<Pig> pigs, List<ServerPlayer> players) {
        LevelChunk chunk(int dx, int dz) {
            ServerLevel level = helper.getLevel();
            ChunkPos pos = new ChunkPos(origin.x + dx, origin.z + dz);
            level.setChunkForced(pos.x, pos.z, true);
            LevelChunk chunk = level.getChunk(pos.x, pos.z);
            if (!chunks.contains(chunk)) {
                chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
                chunk.removeData(RemissionAttachments.CHUNK_REMISSION);
                chunks.add(chunk);
            }
            return chunk;
        }

        BlockPos at(int dx, int dz) {
            ChunkPos pos = new ChunkPos(origin.x + dx, origin.z + dz);
            return new BlockPos(pos.getBlockX(8), BASE_Y, pos.getBlockZ(8));
        }

        BlockPos nidus(int dx, int dz) {
            chunk(dx, dz);
            BlockPos at = at(dx, dz).above(8);
            helper.getLevel().setBlock(at, RemissionBlocks.NIDUS.get().defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(NidusIndex.get(helper.getLevel()).contains(at), "nidus not indexed");
            nidi.add(at);
            return at;
        }

        BlockPos block(int dx, int dz, Block block) {
            chunk(dx, dz);
            BlockPos at = at(dx, dz).above(2);
            helper.getLevel().setBlock(at, block.defaultBlockState(), Block.UPDATE_ALL);
            blocks.add(at);
            return at;
        }

        Pig pig(int dx, int dz) {
            chunk(dx, dz);
            Pig pig = EntityType.PIG.create(helper.getLevel());
            BlockPos at = at(dx, dz).above(4);
            pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
            pig.setNoAi(true);
            pig.setNoGravity(true);
            helper.getLevel().addFreshEntity(pig);
            pigs.add(pig);
            return pig;
        }

        ServerPlayer player(int dx, int dz) {
            chunk(dx, dz);
            ServerPlayer player = SurgeTests.player(helper, at(dx, dz).above(4));
            helper.getLevel().players().add(player);
            players.add(player);
            return player;
        }

        void release() {
            ServerLevel level = helper.getLevel();
            players.forEach(player -> level.players().remove(player));
            pigs.forEach(Pig::discard);
            nidi.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
            blocks.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
            for (LevelChunk chunk : chunks) {
                Cleansing.cleanseNow(level, chunk, PurificationCause.OTHER);
                Purification.engine(level).unloaded(chunk.getPos());
                chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
                chunk.removeData(RemissionAttachments.CHUNK_REMISSION);
                chunk.removeData(RemissionAttachments.FESTERED_STAGE);
                level.setChunkForced(chunk.getPos().x, chunk.getPos().z, false);
            }
        }
    }
}
