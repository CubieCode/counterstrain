package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.BlockPurifiedEvent;
import com.cubiecode.remission.api.ChunkEnteredRemissionEvent;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.PurificationCause;
import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.purification.Cleansing;
import com.cubiecode.remission.purification.Purification;
import com.cubiecode.remission.purification.ReclaimerBlockEntity;
import com.cubiecode.remission.purification.ReclaimerMenu;
import com.cubiecode.remission.purification.ReclaimerSettings;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.world.ChunkCorruption;
import com.cubiecode.remission.world.FesteringLand;
import com.cubiecode.remission.world.NidusIndex;
import com.cubiecode.remission.world.SpreadEngine;
import com.cubiecode.remission.world.SpreadSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
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
 * Reclaimers (P7.4): nearest-first drain of their reach, stopping without energy or fuel, remission entry with the
 * reclaimer as the event cause, a live nidus holding its land and its own chunk, the item, fluid and energy capabilities,
 * and the comparator. Each test runs alone in its own batch in its own row of chunks far east of the test grid (beyond
 * {@link SerumTests}' chunks), force-loads every chunk of its reach, calls operations directly within one tick, and
 * removes its blocks and land afterwards.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReclaimerTests {
    private static final String BATCH = "reclaimer";
    /** Chunks east of the test's own chunk to the first test area. */
    private static final int FAR = 560;
    /** Chunks between test areas. */
    private static final int SPACING = 16;
    private static final int BASE_Y = 100;
    /** Radius 2 (5 by 5 chunks), 1000 FE and 10 levels per operation, two operations per fuel item or bucket. */
    private static final ReclaimerSettings SETTINGS = new ReclaimerSettings(2, 20, 10, 1000, 200_000, 2000, 2, 2);

    private ReclaimerTests() {
    }

    @GameTest(template = "empty", batch = BATCH + "_drain")
    public static void drainsNearestFirstAndStopsWithoutEnergyOrFuel(GameTestHelper helper) {
        inArea(helper, 0, area -> {
            helper.assertValueEqual(RemissionCommonConfig.RECLAIMER_RADIUS.get(), 4, "default reclaimer radius");
            ServerLevel level = helper.getLevel();
            ReclaimerBlockEntity reclaimer = area.reclaimer();
            LevelChunk near = area.chunk(1, 0);
            LevelChunk diagonal = area.chunk(-1, 1);
            LevelChunk far = area.chunk(0, -2);
            LevelChunk outside = area.chunk(3, 0);
            for (LevelChunk chunk : List.of(near, diagonal, far, outside)) {
                setLevel(chunk, 20);
            }

            // Nothing in the tank or the buffer: there is land to drain, so it says why it stops.
            helper.assertValueEqual(reclaimer.operate(level, SETTINGS), ReclaimerBlockEntity.Status.NO_ENERGY, "no energy");
            reclaimer.energy().receiveEnergy(2000, false);
            helper.assertValueEqual(reclaimer.operate(level, SETTINGS), ReclaimerBlockEntity.Status.NO_FUEL, "no fuel");
            helper.assertValueEqual(reclaimer.energy().getEnergyStored(), 2000, "energy used without fuel");
            helper.assertValueEqual(levelOf(near), 20, "drained without fuel");

            // One fuel item runs two operations: the nearest chunk twice, so it reaches 0 first.
            reclaimer.items().setStackInSlot(0, new ItemStack(RemissionItems.PURIFIER_FUEL.get()));
            fill(reclaimer, 100_000);
            helper.assertValueEqual(reclaimer.operate(level, SETTINGS), ReclaimerBlockEntity.Status.RUNNING, "first operation");
            helper.assertTrue(reclaimer.items().getStackInSlot(0).isEmpty(), "fuel item not burned");
            helper.assertValueEqual(reclaimer.fuel(), 1, "operations left from the item");
            helper.assertValueEqual(levelOf(near), 10, "nearest chunk after one operation");
            helper.assertValueEqual(levelOf(diagonal), 20, "diagonal chunk drained before the nearest");
            reclaimer.operate(level, SETTINGS);
            helper.assertValueEqual(levelOf(near), 0, "nearest chunk after two operations");
            helper.assertTrue(CorruptionQueries.isInRemission(level, near.getPos()), "drained chunk not in remission");

            // Fuel spent: it stops with land left and keeps its energy.
            int energy = reclaimer.energy().getEnergyStored();
            helper.assertValueEqual(reclaimer.operate(level, SETTINGS), ReclaimerBlockEntity.Status.NO_FUEL, "fuel spent");
            helper.assertValueEqual(reclaimer.energy().getEnergyStored(), energy, "energy used without fuel");
            helper.assertValueEqual(levelOf(diagonal), 20, "drained after the fuel ran out");

            // Then the diagonal (distance squared 2) before the far chunk (4); never past the radius.
            reclaimer.items().setStackInSlot(0, new ItemStack(RemissionItems.PURIFIER_FUEL.get(), 4));
            reclaimer.operate(level, SETTINGS);
            reclaimer.operate(level, SETTINGS);
            helper.assertValueEqual(levelOf(diagonal), 0, "diagonal chunk after two more operations");
            helper.assertValueEqual(levelOf(far), 20, "far chunk drained before the diagonal");
            reclaimer.operate(level, SETTINGS);
            reclaimer.operate(level, SETTINGS);
            helper.assertValueEqual(levelOf(far), 0, "far chunk last");
            helper.assertValueEqual(reclaimer.operate(level, SETTINGS), ReclaimerBlockEntity.Status.CLEAR, "reach clear");
            helper.assertValueEqual(levelOf(outside), 20, "chunk past the radius drained");
            helper.assertValueEqual(reclaimer.energy().getEnergyStored(), 94_000, "energy after six operations");
            helper.assertValueEqual(reclaimer.items().getStackInSlot(0).getCount(), 2, "fuel items left");

            // Energy spent: stops with fuel left.
            setLevel(near, 30);
            drain(reclaimer);
            helper.assertValueEqual(reclaimer.operate(level, SETTINGS), ReclaimerBlockEntity.Status.NO_ENERGY, "energy spent");
            helper.assertValueEqual(levelOf(near), 30, "drained without energy");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_remission")
    public static void drainedChunksEnterRemissionWithTheReclaimerAsCause(GameTestHelper helper) {
        inArea(helper, 1, area -> {
            ServerLevel level = helper.getLevel();
            ReclaimerBlockEntity reclaimer = area.reclaimer();
            LevelChunk chunk = area.chunk(1, 0);
            setLevel(chunk, 10);
            // A festered floor and a vein in the drained chunk, cleansed by P5.2's chunk cleanse.
            area.fill(1, 0, 4, BASE_Y, 4, 6, BASE_Y, 6, RemissionBlocks.FESTERED_STONE.get().defaultBlockState());
            fill(reclaimer, 10_000);
            reclaimer.items().setStackInSlot(0, new ItemStack(RemissionItems.PURIFIER_FUEL.get()));

            helper.assertValueEqual(reclaimer.signal(), 0, "comparator before any remission");
            List<ChunkEnteredRemissionEvent> entered = capture(ChunkEnteredRemissionEvent.class,
                    () -> reclaimer.operate(level, SETTINGS));
            helper.assertValueEqual(entered.size(), 1, "remission events");
            ChunkEnteredRemissionEvent event = entered.getFirst();
            helper.assertTrue(event.getCause() == PurificationCause.RECLAIMER && event.getPlayer().isEmpty()
                    && event.getChunk().equals(chunk.getPos()) && event.getLevel() == level, "remission event payload");
            helper.assertTrue(CorruptionQueries.isInRemission(level, chunk.getPos()), "chunk not in remission");
            helper.assertTrue(Purification.engine(level).busy(), "remission cleanse not queued");

            List<BlockPurifiedEvent> purified = capture(BlockPurifiedEvent.class,
                    () -> Purification.engine(level).tick(Integer.MAX_VALUE));
            helper.assertValueEqual(purified.size(), 9, "blocks cleansed");
            helper.assertTrue(purified.stream().allMatch(e -> e.getCause() == PurificationCause.RECLAIMER), "cleanse cause");
            helper.assertTrue(level.getBlockState(area.at(1, 0, 5, BASE_Y, 5)).is(Blocks.STONE), "festered stone left");

            // One chunk of 25 in remission: the lowest non-zero signal; all 25: 15.
            helper.assertValueEqual(reclaimer.signal(), 1, "comparator with 1 of 25 chunks in remission");
            helper.assertValueEqual(reclaimer.getBlockState().getAnalogOutputSignal(level, area.reclaimerPos()), 1,
                    "block comparator output");
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    area.chunk(dx, dz).setData(RemissionAttachments.CHUNK_REMISSION, level.getGameTime());
                }
            }
            helper.assertValueEqual(reclaimer.operate(level, SETTINGS), ReclaimerBlockEntity.Status.CLEAR, "all in remission");
            helper.assertValueEqual(reclaimer.signal(), 15, "comparator with all of its reach in remission");
            helper.assertValueEqual(reclaimer.reclaimed(), 25, "chunks counted in remission");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_nidus")
    public static void liveNidusHoldsItsLandAndChunk(GameTestHelper helper) {
        inArea(helper, 2, area -> {
            ServerLevel level = helper.getLevel();
            ReclaimerBlockEntity reclaimer = area.reclaimer();
            LevelChunk nidusChunk = area.chunk(2, 0);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    area.chunk(dx, dz);
                }
            }
            setLevel(nidusChunk, 60);
            area.nidus(2, 0);
            helper.assertTrue(NidusIndex.get(level).contains(area.at(2, 0, 8, BASE_Y + 8, 8)), "nidus not indexed");
            fill(reclaimer, 200_000);
            reclaimer.items().setStackInSlot(0, new ItemStack(RemissionItems.PURIFIER_FUEL.get(), 64));

            // Its own chunk is never drained, and an operation with only it festering uses nothing.
            helper.assertValueEqual(reclaimer.operate(level, SETTINGS), ReclaimerBlockEntity.Status.HELD, "only the nidus chunk");
            helper.assertValueEqual(levelOf(nidusChunk), 60, "nidus chunk drained");
            helper.assertValueEqual(reclaimer.energy().getEnergyStored(), 200_000, "energy used with nothing to drain");
            helper.assertValueEqual(reclaimer.items().getStackInSlot(0).getCount(), 64, "fuel burned with nothing to drain");

            // It does not ward: the nidus keeps spreading against it, one pass per operation, and the land holds.
            SpreadSettings spread = new SpreadSettings(20, 10, 10, 10, 20, 4096, 32);
            SpreadEngine engine = FesteringLand.newEngine(level, () -> spread);
            long pass = (level.getGameTime() / 20 + 1) * 20;
            int lowestNidus = Integer.MAX_VALUE;
            for (int i = 0; i < 30; i++) {
                engine.tick(pass + i * 20L);
                reclaimer.operate(level, SETTINGS);
                lowestNidus = Math.min(lowestNidus, levelOf(nidusChunk));
            }
            helper.assertTrue(lowestNidus >= 60, "nidus chunk lost level: " + lowestNidus);
            helper.assertFalse(CorruptionQueries.isInRemission(level, nidusChunk.getPos()), "nidus chunk in remission");
            helper.assertTrue(reclaimer.festering() > 1, "land around the nidus cleared: " + reclaimer.festering());
            helper.assertValueEqual(reclaimer.status(), ReclaimerBlockEntity.Status.RUNNING, "still draining against the nidus");

            // With the nidus gone (no more spread) the reclaimer clears its chunk too; one operation per chunk here.
            area.removeNidi();
            ReclaimerSettings strong = new ReclaimerSettings(2, 20, CorruptionQueries.MAX_LEVEL, 1, 200_000, 2000, 100, 2);
            for (int i = 0; i < 50 && reclaimer.operate(level, strong) == ReclaimerBlockEntity.Status.RUNNING; i++) {
                helper.assertTrue(i < 49, "reach not cleared in 50 operations");
            }
            helper.assertValueEqual(levelOf(nidusChunk), 0, "former nidus chunk after the nidus was destroyed");
            helper.assertTrue(CorruptionQueries.isInRemission(level, nidusChunk.getPos()), "former nidus chunk not in remission");
            helper.succeed();
        });
    }

    // The fuel fluid tag is empty by default; bound to water here as a pack's tag would be after a reload, and restored
    // in the same call, so no other test sees the change (its own batch keeps it apart from tests reading fluid tags).
    @GameTest(template = "empty", batch = BATCH + "_capabilities")
    public static void capabilitiesAcceptEnergyItemsAndFluids(GameTestHelper helper) {
        inArea(helper, 3, area -> {
            ServerLevel level = helper.getLevel();
            ReclaimerBlockEntity reclaimer = area.reclaimer();
            BlockPos pos = area.reclaimerPos();
            setLevel(area.chunk(1, 0), 50);

            IEnergyStorage energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.NORTH);
            IItemHandler items = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
            IFluidHandler fluids = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
            helper.assertTrue(energy != null && items != null && fluids != null, "a capability is missing");

            int input = RemissionCommonConfig.RECLAIMER_MAX_ENERGY_INPUT.get();
            helper.assertValueEqual(energy.receiveEnergy(1_000_000, false), input, "energy accepted per insert");
            helper.assertValueEqual(energy.getMaxEnergyStored(), RemissionCommonConfig.RECLAIMER_ENERGY_CAPACITY.get(), "capacity");
            helper.assertValueEqual(energy.extractEnergy(100, false), 0, "energy extracted");
            helper.assertFalse(energy.canExtract(), "energy extractable");

            ItemStack dirt = new ItemStack(Items.DIRT);
            helper.assertValueEqual(items.insertItem(0, dirt, false).getCount(), 1, "dirt accepted as fuel");
            helper.assertTrue(items.insertItem(0, new ItemStack(RemissionItems.PURIFIER_FUEL.get(), 3), false).isEmpty(),
                    "purifier fuel refused");
            helper.assertTrue(items.extractItem(0, 1, false).isEmpty(), "fuel extracted");
            helper.assertValueEqual(reclaimer.items().getStackInSlot(0).getCount(), 3, "fuel in the slot");

            helper.assertValueEqual(fluids.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE), 0,
                    "water accepted while the fuel tag is empty");
            Registry<Fluid> registry = level.registryAccess().registryOrThrow(Registries.FLUID);
            Map<TagKey<Fluid>, List<Holder<Fluid>>> original = new HashMap<>();
            registry.getTags().forEach(pair -> original.put(pair.getFirst(), pair.getSecond().stream().toList()));
            Map<TagKey<Fluid>, List<Holder<Fluid>>> packTags = new HashMap<>(original);
            packTags.put(ReclaimerBlockEntity.FLUID_FUEL, List.of(Fluids.WATER.builtInRegistryHolder()));
            try {
                registry.bindTags(packTags);
                helper.assertValueEqual(fluids.fill(new FluidStack(Fluids.LAVA, 1000), IFluidHandler.FluidAction.EXECUTE), 0,
                        "untagged fluid accepted");
                helper.assertValueEqual(fluids.fill(new FluidStack(Fluids.WATER, 3000), IFluidHandler.FluidAction.EXECUTE), 3000,
                        "tagged fuel fluid refused");
                helper.assertTrue(fluids.drain(1000, IFluidHandler.FluidAction.EXECUTE).isEmpty(), "fuel drained");

                // The item burns first; with the slot empty a bucket of fuel fluid runs fuelPerBucket operations.
                reclaimer.items().setStackInSlot(0, ItemStack.EMPTY);
                fill(reclaimer, 10_000);
                helper.assertValueEqual(reclaimer.operate(level, SETTINGS), ReclaimerBlockEntity.Status.RUNNING, "fluid fuel");
                helper.assertValueEqual(reclaimer.tank().getFluidAmount(), 2000, "fuel fluid left after one bucket");
                helper.assertValueEqual(reclaimer.fuel(), 1, "operations left from the bucket");
            } finally {
                registry.bindTags(original);
            }
            helper.assertValueEqual(fluids.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE), 0,
                    "water accepted after restoring the tags");

            // Saved and loaded: slot, tank, energy and fuel left.
            var saved = reclaimer.saveWithoutMetadata(level.registryAccess());
            ReclaimerBlockEntity copy = new ReclaimerBlockEntity(pos, reclaimer.getBlockState());
            copy.loadWithComponents(saved, level.registryAccess());
            helper.assertValueEqual(copy.tank().getFluidAmount(), 2000, "tank after a reload");
            helper.assertValueEqual(copy.energy().getEnergyStored(), reclaimer.energy().getEnergyStored(), "energy after a reload");
            helper.assertValueEqual(copy.fuel(), 1, "fuel after a reload");
            helper.succeed();
        });
    }

    // P7.4 review: the comparator reads the saved signal straight after a load, and the first tick's survey refreshes it.
    @GameTest(template = "empty", batch = BATCH + "_reload")
    public static void comparatorSignalSurvivesAReload(GameTestHelper helper) {
        inArea(helper, 4, area -> {
            ServerLevel level = helper.getLevel();
            ReclaimerBlockEntity reclaimer = area.reclaimer();
            BlockPos pos = area.reclaimerPos();
            area.chunk(1, 0).setData(RemissionAttachments.CHUNK_REMISSION, level.getGameTime());
            area.chunk(-1, 0).setData(RemissionAttachments.CHUNK_REMISSION, level.getGameTime());
            helper.assertValueEqual(reclaimer.operate(level, SETTINGS), ReclaimerBlockEntity.Status.CLEAR, "nothing to drain");
            helper.assertValueEqual(reclaimer.signal(), 2, "comparator with 2 of 25 chunks in remission");

            var saved = reclaimer.saveWithoutMetadata(level.registryAccess());
            ReclaimerBlockEntity loaded = new ReclaimerBlockEntity(pos, reclaimer.getBlockState());
            loaded.loadWithComponents(saved, level.registryAccess());
            helper.assertValueEqual(loaded.signal(), 2, "comparator straight after a load");

            // Without a saved signal (or with a stale one), the first tick surveys at the configured radius.
            saved.remove("signal");
            ReclaimerBlockEntity stale = new ReclaimerBlockEntity(pos, reclaimer.getBlockState());
            stale.loadWithComponents(saved, level.registryAccess());
            stale.setLevel(level);
            helper.assertValueEqual(stale.signal(), 0, "signal before the first tick");
            area.chunk(0, 1).setData(RemissionAttachments.CHUNK_REMISSION, level.getGameTime());
            ReclaimerBlockEntity.serverTick(level, pos, stale.getBlockState(), stale);
            helper.assertTrue(stale.reclaimed() >= 3, "first tick did not survey: " + stale.reclaimed());
            helper.assertValueEqual(stale.signal(), 1 + 14 * stale.reclaimed() / stale.area(), "signal after the first tick");
            helper.succeed();
        });
    }

    // P7.4 review: fuelPerBucket 0 refuses fluid fuel, and a fluid that stops being fuel drains out instead of jamming the
    // tank. Binds water into the fuel tag and restores the tags and the config in the same call, in its own batch.
    @GameTest(template = "empty", batch = BATCH + "_fluid_rules")
    public static void fluidFuelFollowsConfigAndTags(GameTestHelper helper) {
        inArea(helper, 5, area -> {
            ServerLevel level = helper.getLevel();
            ReclaimerBlockEntity reclaimer = area.reclaimer();
            setLevel(area.chunk(1, 0), 50);
            IFluidHandler fluids = level.getCapability(Capabilities.FluidHandler.BLOCK, area.reclaimerPos(), null);
            Registry<Fluid> registry = level.registryAccess().registryOrThrow(Registries.FLUID);
            Map<TagKey<Fluid>, List<Holder<Fluid>>> original = new HashMap<>();
            registry.getTags().forEach(pair -> original.put(pair.getFirst(), pair.getSecond().stream().toList()));
            Map<TagKey<Fluid>, List<Holder<Fluid>>> packTags = new HashMap<>(original);
            packTags.put(ReclaimerBlockEntity.FLUID_FUEL, List.of(Fluids.WATER.builtInRegistryHolder()));
            int perBucket = RemissionCommonConfig.RECLAIMER_FUEL_PER_BUCKET.get();
            try {
                registry.bindTags(packTags);
                RemissionCommonConfig.RECLAIMER_FUEL_PER_BUCKET.set(0);
                helper.assertValueEqual(fluids.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE), 0,
                        "fuel fluid accepted with fuelPerBucket 0");
                RemissionCommonConfig.RECLAIMER_FUEL_PER_BUCKET.set(perBucket);
                helper.assertValueEqual(fluids.fill(new FluidStack(Fluids.WATER, 3000), IFluidHandler.FluidAction.EXECUTE), 3000,
                        "fuel fluid refused");
                helper.assertTrue(fluids.drain(1000, IFluidHandler.FluidAction.EXECUTE).isEmpty(), "valid fuel drained");
                RemissionCommonConfig.RECLAIMER_FUEL_PER_BUCKET.set(0);
                helper.assertValueEqual(fluids.drain(1000, IFluidHandler.FluidAction.EXECUTE).getAmount(), 1000,
                        "fluid fuel turned off but the tank does not drain");
                RemissionCommonConfig.RECLAIMER_FUEL_PER_BUCKET.set(perBucket);
            } finally {
                RemissionCommonConfig.RECLAIMER_FUEL_PER_BUCKET.set(perBucket);
                registry.bindTags(original);
            }
            // Water is no longer fuel (as after a /reload): never burned, refused when filling, and drainable.
            fill(reclaimer, 10_000);
            helper.assertValueEqual(reclaimer.operate(level, SETTINGS), ReclaimerBlockEntity.Status.NO_FUEL, "stale fluid burned");
            helper.assertValueEqual(reclaimer.tank().getFluidAmount(), 2000, "stale fluid used");
            helper.assertValueEqual(fluids.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE), 0,
                    "non-fuel fluid accepted");
            helper.assertValueEqual(fluids.drain(new FluidStack(Fluids.WATER, 1500), IFluidHandler.FluidAction.EXECUTE).getAmount(),
                    1500, "stale fluid not drained by stack");
            helper.assertValueEqual(fluids.drain(1000, IFluidHandler.FluidAction.EXECUTE).getAmount(), 500, "stale fluid left");
            helper.assertTrue(reclaimer.tank().isEmpty(), "tank not emptied");
            helper.succeed();
        });
    }

    // Container data travels as shorts: values above 65535 and the 4-bit map cells (the top one sets the sign bit) must
    // come through the client menu as the server has them.
    @GameTest(template = "empty", batch = BATCH + "_menu")
    public static void menuDataSurvivesShortSync(GameTestHelper helper) {
        inArea(helper, 6, area -> {
            ServerLevel level = helper.getLevel();
            ReclaimerBlockEntity reclaimer = area.reclaimer();
            setLevel(area.chunk(1, 0), 90);
            setLevel(area.chunk(-1, 0), 10);
            setLevel(area.chunk(2, 1), 60);
            area.nidus(2, 1);
            area.chunk(0, -1).setData(RemissionAttachments.CHUNK_REMISSION, level.getGameTime());
            fill(reclaimer, 130_000);
            reclaimer.items().setStackInSlot(0, new ItemStack(RemissionItems.PURIFIER_FUEL.get(), 7));
            reclaimer.operate(level, new ReclaimerSettings(2, 20, 10, 1_000_000, 200_000, 2000, 2, 2));

            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            ReclaimerMenu server = (ReclaimerMenu) reclaimer.createMenu(1, player.getInventory(), player);
            ReclaimerMenu client = ReclaimerMenu.client(1, player.getInventory());
            server.setSynchronizer(new ContainerSynchronizer() {
                @Override
                public void sendInitialData(AbstractContainerMenu menu, NonNullList<ItemStack> items, ItemStack carried, int[] data) {
                    for (int i = 0; i < data.length; i++) {
                        client.setData(i, (short) data[i]);
                    }
                }

                @Override
                public void sendSlotChange(AbstractContainerMenu menu, int slot, ItemStack stack) {
                }

                @Override
                public void sendCarriedChange(AbstractContainerMenu menu, ItemStack stack) {
                }

                @Override
                public void sendDataChange(AbstractContainerMenu menu, int id, int value) {
                    client.setData(id, (short) value);
                }
            });
            helper.assertValueEqual(client.energy(), 130_000, "energy above 65535");
            helper.assertValueEqual(client.capacity(), RemissionCommonConfig.RECLAIMER_ENERGY_CAPACITY.get(), "capacity");
            helper.assertValueEqual(client.status(), ReclaimerBlockEntity.Status.NO_ENERGY, "status");
            helper.assertValueEqual(client.radius(), 2, "radius");
            helper.assertValueEqual(client.reclaimed(), 1, "chunks in remission");
            helper.assertValueEqual(client.festering(), 3, "festering chunks");
            helper.assertValueEqual(client.cell(2, 1), ReclaimerMenu.Cell.NIDUS, "nidus cell (sign bit)");
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    helper.assertValueEqual(client.cell(dx, dz), server.cell(dx, dz), "map cell " + dx + ", " + dz);
                }
            }
            helper.succeed();
        });
    }

    /** Fills the reclaimer's buffer to {@code amount} FE through its capability, insert by insert. */
    private static void fill(ReclaimerBlockEntity reclaimer, int amount) {
        drain(reclaimer);
        while (reclaimer.energy().getEnergyStored() < amount) {
            if (reclaimer.energy().receiveEnergy(amount - reclaimer.energy().getEnergyStored(), false) == 0) {
                break;
            }
        }
    }

    /** Empties the reclaimer's buffer (it never gives energy out, so through a save round trip). */
    private static void drain(ReclaimerBlockEntity reclaimer) {
        var level = reclaimer.getLevel();
        var tag = reclaimer.saveWithoutMetadata(level.registryAccess());
        tag.putInt("energy", 0);
        reclaimer.loadWithComponents(tag, level.registryAccess());
    }

    /** One test's chunks, force-loaded on demand, and everything to put back. */
    private record Area(GameTestHelper helper, ChunkPos origin, List<LevelChunk> chunks, List<BlockPos> blocks,
                        List<BlockPos> nidi) {
        ChunkPos pos(int dx, int dz) {
            return new ChunkPos(origin.x + dx, origin.z + dz);
        }

        LevelChunk chunk(int dx, int dz) {
            ServerLevel level = helper.getLevel();
            ChunkPos pos = pos(dx, dz);
            level.setChunkForced(pos.x, pos.z, true);
            LevelChunk chunk = level.getChunk(pos.x, pos.z);
            if (!chunks.contains(chunk)) {
                chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
                chunk.removeData(RemissionAttachments.CHUNK_REMISSION);
                chunks.add(chunk);
            }
            return chunk;
        }

        BlockPos at(int dx, int dz, int localX, int y, int localZ) {
            ChunkPos pos = pos(dx, dz);
            return new BlockPos(pos.getBlockX(localX), y, pos.getBlockZ(localZ));
        }

        BlockPos reclaimerPos() {
            return at(0, 0, 8, BASE_Y + 4, 8);
        }

        /** Places a reclaimer in the centre chunk after force-loading its whole reach (radius 2). */
        ReclaimerBlockEntity reclaimer() {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    chunk(dx, dz);
                }
            }
            BlockPos at = reclaimerPos();
            helper.getLevel().setBlock(at, RemissionBlocks.RECLAIMER.get().defaultBlockState(), Block.UPDATE_ALL);
            blocks.add(at);
            return (ReclaimerBlockEntity) helper.getLevel().getBlockEntity(at);
        }

        void fill(int dx, int dz, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        BlockPos at = at(dx, dz, x, y, z);
                        helper.getLevel().setBlock(at, state, Block.UPDATE_ALL);
                        blocks.add(at);
                    }
                }
            }
        }

        void nidus(int dx, int dz) {
            BlockPos at = at(dx, dz, 8, BASE_Y + 8, 8);
            helper.getLevel().setBlock(at, RemissionBlocks.NIDUS.get().defaultBlockState(), Block.UPDATE_ALL);
            nidi.add(at);
        }

        void removeNidi() {
            nidi.forEach(pos -> helper.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
            nidi.clear();
        }

        void release() {
            ServerLevel level = helper.getLevel();
            removeNidi();
            blocks.reversed().forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
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

    private static void inArea(GameTestHelper helper, int index, Consumer<Area> body) {
        ChunkPos own = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        Area area = new Area(helper, new ChunkPos(own.x + FAR + index * SPACING, own.z), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>());
        try {
            body.accept(area);
        } finally {
            area.release();
        }
    }

    private static void setLevel(LevelChunk chunk, int value) {
        if (value <= 0) {
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
        } else {
            chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(value, 0L));
        }
    }

    private static int levelOf(LevelChunk chunk) {
        ChunkCorruption state = chunk.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
        return state == null ? 0 : state.level();
    }

    private static <E extends Event> List<E> capture(Class<E> type, Runnable body) {
        List<E> events = new ArrayList<>();
        Consumer<E> listener = events::add;
        NeoForge.EVENT_BUS.addListener(type, listener);
        try {
            body.run();
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        return events;
    }
}
