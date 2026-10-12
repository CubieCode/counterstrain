package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionDataComponents;
import com.cubiecode.remission.registry.RemissionFluids;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.research.ScanData;
import com.cubiecode.remission.tech.MachineBlockEntity;
import com.cubiecode.remission.tech.MachineKind;
import com.cubiecode.remission.tech.MachineRecipe;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The analyzer, sequencer and synthesizer (P7.2): each runs its default recipe fed only through the standard item,
 * fluid and energy capabilities, as a pipe or storage network would; plus power, output and side rules, save and load,
 * and the recipe schema.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MachineTests {
    private static final BlockPos MACHINE = new BlockPos(1, 1, 1);

    private MachineTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void analyzerTurnsTissueIntoMiasma(GameTestHelper helper) {
        BlockPos pos = place(helper, RemissionBlocks.ANALYZER.get());
        power(helper, pos, MachineKind.ENERGY_CAPACITY);
        helper.assertValueEqual(items(helper, pos).insertItem(0, new ItemStack(RemissionItems.HOST_TISSUE.get(), 2), false).getCount(), 0,
                "items left after insert");
        helper.succeedWhen(() -> {
            FluidStack out = fluids(helper, pos).drain(1000, IFluidHandler.FluidAction.SIMULATE);
            helper.assertTrue(out.is(RemissionFluids.MIASMA.get()) && out.getAmount() == 200,
                    "two tissues give 200 mB miasma, got " + out.getAmount() + " " + out.getFluid());
            helper.assertTrue(items(helper, pos).getStackInSlot(0).isEmpty(), "input used up");
            // 2 crafts of the default 2000 FE recipe.
            helper.assertValueEqual(energy(helper, pos).getEnergyStored(), MachineKind.ENERGY_CAPACITY - 4000, "energy used");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void analyzerScansNidusCore(GameTestHelper helper) {
        BlockPos pos = place(helper, RemissionBlocks.ANALYZER.get());
        power(helper, pos, MachineKind.ENERGY_CAPACITY);
        items(helper, pos).insertItem(0, new ItemStack(RemissionItems.NIDUS_CORE.get()), false);
        helper.succeedWhen(() -> {
            ItemStack scan = items(helper, pos).extractItem(1, 64, true);
            helper.assertTrue(scan.is(RemissionItems.BLOCK_SCAN.get()), "block scan in the first output slot, got " + scan);
            helper.assertValueEqual(scan.get(RemissionDataComponents.SCAN_DATA.get()),
                    ScanData.block(Remission.id("nidus")), "scan data");
            helper.assertValueEqual(fluids(helper, pos).getFluidInTank(1).getAmount(), 1000, "miasma from a core");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void sequencerMakesStrainSequence(GameTestHelper helper) {
        BlockPos pos = place(helper, RemissionBlocks.SEQUENCER.get());
        power(helper, pos, MachineKind.ENERGY_CAPACITY);
        IItemHandler items = items(helper, pos);
        // Shapeless: tissue in the first slot, the scan in the second.
        items.insertItem(0, new ItemStack(RemissionItems.HOST_TISSUE.get()), false);
        items.insertItem(1, blockScan(), false);
        helper.assertValueEqual(fluids(helper, pos).fill(new FluidStack(RemissionFluids.MIASMA.get(), 1000), IFluidHandler.FluidAction.EXECUTE),
                1000, "miasma accepted");
        helper.succeedWhen(() -> {
            helper.assertTrue(items(helper, pos).extractItem(2, 64, true).is(RemissionItems.STRAIN_SEQUENCE.get()), "strain sequence made");
            helper.assertValueEqual(fluids(helper, pos).getFluidInTank(0).getAmount(), 750, "250 mB miasma used");
            helper.assertTrue(items(helper, pos).getStackInSlot(0).isEmpty() && items(helper, pos).getStackInSlot(1).isEmpty(),
                    "inputs used up");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void synthesizerCulturesHostTissue(GameTestHelper helper) {
        BlockPos pos = place(helper, RemissionBlocks.SYNTHESIZER.get());
        power(helper, pos, MachineKind.ENERGY_CAPACITY);
        IItemHandler items = items(helper, pos);
        items.insertItem(2, new ItemStack(RemissionItems.STRAIN_SEQUENCE.get()), false);
        items.insertItem(0, new ItemStack(RemissionItems.HOST_TISSUE.get()), false);
        fluids(helper, pos).fill(new FluidStack(RemissionFluids.MIASMA.get(), 500), IFluidHandler.FluidAction.EXECUTE);
        helper.succeedWhen(() -> {
            ItemStack out = items(helper, pos).extractItem(3, 64, true);
            helper.assertTrue(out.is(RemissionItems.HOST_TISSUE.get()) && out.getCount() == 2, "two host tissue, got " + out);
            helper.assertTrue(fluids(helper, pos).getFluidInTank(0).isEmpty(), "500 mB miasma used");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void machineWaitsForEnergyAndFluid(GameTestHelper helper) {
        BlockPos pos = place(helper, RemissionBlocks.SEQUENCER.get());
        IItemHandler items = items(helper, pos);
        items.insertItem(0, blockScan(), false);
        items.insertItem(1, new ItemStack(RemissionItems.HOST_TISSUE.get()), false);
        // No energy and no miasma: nothing happens.
        helper.runAfterDelay(60, () -> {
            helper.assertValueEqual(machine(helper, pos).progress(), 0, "progress without energy or fluid");
            power(helper, pos, MachineKind.ENERGY_CAPACITY);
        });
        // Energy but too little miasma: still nothing.
        helper.runAfterDelay(80, () -> {
            fluids(helper, pos).fill(new FluidStack(RemissionFluids.MIASMA.get(), 100), IFluidHandler.FluidAction.EXECUTE);
        });
        helper.runAfterDelay(120, () -> {
            helper.assertValueEqual(machine(helper, pos).progress(), 0, "progress with too little miasma");
            helper.assertValueEqual(energy(helper, pos).getEnergyStored(), MachineKind.ENERGY_CAPACITY, "no energy used");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void fullOutputTankHoldsTheCraft(GameTestHelper helper) {
        BlockPos pos = place(helper, RemissionBlocks.ANALYZER.get());
        power(helper, pos, MachineKind.ENERGY_CAPACITY);
        machine(helper, pos).outputTank().fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);
        items(helper, pos).insertItem(0, new ItemStack(RemissionItems.HOST_TISSUE.get()), false);
        helper.runAfterDelay(150, () -> {
            helper.assertValueEqual(items(helper, pos).getStackInSlot(0).getCount(), 1, "tissue not used");
            helper.assertValueEqual(machine(helper, pos).progress(), 0, "no progress while the output is blocked");
            helper.assertValueEqual(energy(helper, pos).getEnergyStored(), MachineKind.ENERGY_CAPACITY, "no energy used");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void capabilitiesKeepInputsAndOutputsApart(GameTestHelper helper) {
        BlockPos pos = place(helper, RemissionBlocks.SYNTHESIZER.get());
        for (Direction side : new Direction[] {null, Direction.UP, Direction.NORTH, Direction.DOWN}) {
            helper.assertTrue(helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos), side) != null,
                    "item handler on side " + side);
        }
        IItemHandler items = items(helper, pos);
        helper.assertValueEqual(items.getSlots(), 5, "3 inputs and 2 outputs");
        ItemStack stone = new ItemStack(Items.STONE);
        helper.assertValueEqual(items.insertItem(3, stone.copy(), false).getCount(), 1, "outputs refuse insertion");
        helper.assertValueEqual(items.insertItem(0, stone.copy(), false).getCount(), 0, "inputs accept items");
        helper.assertTrue(items.extractItem(0, 1, false).isEmpty(), "inputs cannot be emptied from outside");
        IFluidHandler fluids = fluids(helper, pos);
        helper.assertValueEqual(fluids.getTanks(), 2, "input and output tank");
        fluids.fill(new FluidStack(Fluids.WATER, 500), IFluidHandler.FluidAction.EXECUTE);
        helper.assertTrue(fluids.drain(500, IFluidHandler.FluidAction.EXECUTE).isEmpty(), "input tank cannot be drained from outside");
        IEnergyStorage energy = energy(helper, pos);
        helper.assertValueEqual(energy.receiveEnergy(5000, false), MachineKind.MAX_ENERGY_INPUT, "energy input per call");
        helper.assertValueEqual(energy.extractEnergy(100, false), 0, "energy cannot be taken out");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void machineStateSurvivesSaveAndLoad(GameTestHelper helper) {
        BlockPos pos = place(helper, RemissionBlocks.ANALYZER.get());
        MachineBlockEntity machine = machine(helper, pos);
        power(helper, pos, 3000);
        machine.items().setStackInSlot(0, new ItemStack(RemissionItems.HOST_TISSUE.get(), 5));
        machine.items().setStackInSlot(2, new ItemStack(RemissionItems.STRAIN_SEQUENCE.get(), 3));
        machine.inputTank().fill(new FluidStack(Fluids.WATER, 700), IFluidHandler.FluidAction.EXECUTE);
        machine.outputTank().fill(new FluidStack(RemissionFluids.MIASMA.get(), 300), IFluidHandler.FluidAction.EXECUTE);
        CompoundTag saved = machine.saveWithFullMetadata(helper.getLevel().registryAccess());

        MachineBlockEntity copy = new MachineBlockEntity(MachineKind.ANALYZER, machine.getBlockPos(), machine.getBlockState());
        copy.loadWithComponents(saved, helper.getLevel().registryAccess());
        helper.assertValueEqual(copy.items().getStackInSlot(0).getCount(), 5, "input stack");
        helper.assertValueEqual(copy.items().getStackInSlot(2).getCount(), 3, "output stack");
        helper.assertTrue(copy.inputTank().getFluid().is(Fluids.WATER) && copy.inputTank().getFluidAmount() == 700, "input tank");
        helper.assertValueEqual(copy.outputTank().getFluidAmount(), 300, "output tank");
        helper.assertValueEqual(copy.energy().getEnergyStored(), 3000, "energy");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void craftProgressSurvivesReload(GameTestHelper helper) {
        BlockPos pos = place(helper, RemissionBlocks.ANALYZER.get());
        power(helper, pos, MachineKind.ENERGY_CAPACITY);
        items(helper, pos).insertItem(0, new ItemStack(RemissionItems.NIDUS_CORE.get()), false);
        helper.runAfterDelay(40, () -> {
            MachineBlockEntity machine = machine(helper, pos);
            int progress = machine.progress();
            helper.assertTrue(progress > 0, "crafting before the reload");
            CompoundTag saved = machine.saveWithFullMetadata(helper.getLevel().registryAccess());
            // A fresh block entity loading the save, as after a chunk reload.
            helper.setBlock(pos, Blocks.AIR);
            helper.setBlock(pos, RemissionBlocks.ANALYZER.get());
            machine(helper, pos).loadWithComponents(saved, helper.getLevel().registryAccess());
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(machine(helper, pos).progress() > progress,
                        "progress " + machine(helper, pos).progress() + " continues from " + progress);
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty")
    public static void recipeSchemaRules(GameTestHelper helper) {
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        DataResult<Recipe<?>> ok = Recipe.CODEC.parse(ops, JsonParser.parseString("""
                {"type": "remission:synthesizing", "ingredients": [{"item": "minecraft:apple", "count": 2}, {"tag": "remission:scans"},
                 {"item": "remission:host_tissue"}], "fluid_ingredient": {"fluid": "remission:miasma", "amount": 100},
                 "results": [{"id": "minecraft:stone"}, {"id": "minecraft:dirt", "count": 3}],
                 "fluid_result": {"id": "minecraft:water", "amount": 10}, "energy": 50, "time": 7}"""));
        helper.assertTrue(ok.isSuccess(), "full recipe parses: " + ok);
        MachineRecipe recipe = (MachineRecipe) ok.getOrThrow();
        helper.assertValueEqual(recipe.kind(), MachineKind.SYNTHESIZER, "kind");
        helper.assertValueEqual(recipe.ingredients().getFirst().count(), 2, "ingredient count");
        // Energy is spread over the ticks and adds up to the total.
        int total = 0;
        for (int tick = 0; tick < recipe.time(); tick++) {
            total += recipe.energyForTick(tick);
        }
        helper.assertValueEqual(total, 50, "energy over a craft");

        assertRejected(helper, ops, """
                {"type": "remission:analyzing", "ingredients": [{"item": "minecraft:apple"}, {"item": "minecraft:stone"}],
                 "results": [{"id": "minecraft:stone"}], "time": 10}""", "two ingredients for the one-slot analyzer");
        assertRejected(helper, ops, """
                {"type": "remission:analyzing", "ingredients": [{"item": "minecraft:apple"}], "time": 10}""", "no results");
        assertRejected(helper, ops, """
                {"type": "remission:sequencing", "ingredients": [{"item": "minecraft:apple"}],
                 "results": [{"id": "minecraft:stone"}, {"id": "minecraft:stone"}, {"id": "minecraft:stone"}], "time": 10}""",
                "three results");
        assertRejected(helper, ops, """
                {"type": "remission:sequencing", "ingredients": [{"item": "minecraft:apple"}], "results": [{"id": "minecraft:stone"}]}""",
                "no time");

        var recipes = helper.getLevel().getRecipeManager();
        for (String id : new String[] {"analyzing/host_tissue", "analyzing/festered_soil", "analyzing/nidus_core",
                "sequencing/strain_sequence", "synthesizing/host_tissue"}) {
            helper.assertTrue(recipes.byKey(Remission.id(id)).map(h -> h.value() instanceof MachineRecipe).orElse(false),
                    "default recipe remission:" + id);
        }
        helper.succeed();
    }

    private static void assertRejected(GameTestHelper helper, DynamicOps<JsonElement> ops,
                                       String json, String what) {
        helper.assertTrue(Recipe.CODEC.parse(ops, JsonParser.parseString(json)).isError(), "rejected: " + what);
    }

    private static BlockPos place(GameTestHelper helper, Block block) {
        helper.setBlock(MACHINE, block);
        return MACHINE;
    }

    private static MachineBlockEntity machine(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos);
    }

    private static IItemHandler items(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos), Direction.UP);
    }

    private static IFluidHandler fluids(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, helper.absolutePos(pos), Direction.NORTH);
    }

    private static IEnergyStorage energy(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, helper.absolutePos(pos), Direction.DOWN);
    }

    /** Fills the buffer through the energy capability, a cable's worth per call. */
    private static void power(GameTestHelper helper, BlockPos pos, int amount) {
        IEnergyStorage storage = energy(helper, pos);
        int left = amount;
        while (left > 0) {
            int received = storage.receiveEnergy(left, false);
            if (received == 0) {
                break;
            }
            left -= received;
        }
    }

    private static ItemStack blockScan() {
        ItemStack scan = new ItemStack(RemissionItems.BLOCK_SCAN.get());
        scan.set(RemissionDataComponents.SCAN_DATA.get(), ScanData.block(Remission.id("nidus")));
        return scan;
    }
}
