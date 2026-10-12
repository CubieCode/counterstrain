package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.BlockPurifiedEvent;
import com.cubiecode.remission.api.ChunkEnteredRemissionEvent;
import com.cubiecode.remission.api.PurificationCause;
import com.cubiecode.remission.api.ViralLoadChangedEvent;
import com.cubiecode.remission.counterstrain.CounterstrainPrototypes;
import com.cubiecode.remission.counterstrain.PrototypeSettings;
import com.cubiecode.remission.counterstrain.ThrownPrototype;
import com.cubiecode.remission.purification.Cleansing;
import com.cubiecode.remission.purification.HandheldPurifierItem;
import com.cubiecode.remission.purification.Purification;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionEntityTypes;
import com.cubiecode.remission.registry.RemissionFluids;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.registry.RemissionMobEffects;
import com.cubiecode.remission.serum.SerumSettings;
import com.cubiecode.remission.serum.Serums;
import com.cubiecode.remission.surge.MiasmaCloud;
import com.cubiecode.remission.tech.MachineKind;
import com.cubiecode.remission.viralload.ViralLoad;
import com.cubiecode.remission.viralload.ViralLoadSettings;
import com.cubiecode.remission.viralload.ViralLoadState;
import com.cubiecode.remission.world.ChunkCorruption;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.EffectCures;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Serums, purifier fuel and unstable Counterstrain prototypes (P7.3): a serum lowers viral load and inoculates, and
 * inoculation scales viral load gain and is cleared by milk; purifier fuel repairs the handheld purifier; a prototype
 * cleanses or backfires where it lands, thrown or not; the synthesizer makes each product from its default recipe; and
 * the KubeJS recipe schemas match the machine recipe format. Tests that change land run alone in their own batch in
 * their own chunks (far from the test grid where no entities are involved), and put the land back afterwards.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SerumTests {
    private static final String BATCH = "serums";
    private static final BlockPos MACHINE = new BlockPos(1, 1, 1);
    /** Chunks east and south of the test's own chunk to the first prototype test area. */
    private static final int FAR = 480;
    private static final int SOUTH = 96;
    private static final int SPACING = 16;
    private static final int BASE_Y = 100;
    private static final float EPSILON = 1.0E-4F;

    private SerumTests() {
    }

    // ---- serums ------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = BATCH + "_dose")
    public static void serumLowersViralLoadAndInoculates(GameTestHelper helper) {
        SerumSettings settings = SerumSettings.fromConfig();
        helper.assertValueEqual(settings, new SerumSettings(40.0, 3600, 0.5), "serum settings in the test config");
        ServerPlayer player = SurgeTests.player(helper, helper.absolutePos(new BlockPos(1, 1, 1)));
        ViralLoad.set(player, 70.0F, ViralLoadState.Trend.STEADY, ViralLoadChangedEvent.Cause.OTHER);
        ItemStack serums = new ItemStack(RemissionItems.SERUM.get(), 2);

        List<ViralLoadChangedEvent> events = capture(ViralLoadChangedEvent.class,
                () -> serums.finishUsingItem(helper.getLevel(), player));
        assertLoad(helper, player, 30.0F, "after one serum");
        helper.assertValueEqual(ViralLoad.state(player).trend(), ViralLoadState.Trend.FALLING, "trend after a serum");
        helper.assertValueEqual(events.size(), 1, "viral load events for one serum");
        helper.assertValueEqual(events.getFirst().getCause(), ViralLoadChangedEvent.Cause.SERUM, "event cause");
        MobEffectInstance inoculated = player.getEffect(RemissionMobEffects.INOCULATED);
        helper.assertTrue(inoculated != null && inoculated.getDuration() == 3600, "inoculated for 3600 ticks, got " + inoculated);
        helper.assertValueEqual(serums.getCount(), 1, "serums left");
        helper.assertTrue(player.getInventory().contains(new ItemStack(Items.GLASS_BOTTLE)), "glass bottle returned");

        // The last serum turns into the bottle, and viral load never goes below 0.
        ItemStack left = serums.finishUsingItem(helper.getLevel(), player);
        helper.assertTrue(left.is(Items.GLASS_BOTTLE), "last serum leaves a bottle, got " + left);
        assertLoad(helper, player, 0.0F, "after a second serum");
        helper.assertValueEqual(ViralLoad.state(player).trend(), ViralLoadState.Trend.STEADY, "trend at 0");

        // A dose with nothing to take off still inoculates, and fires no viral load event.
        player.removeAllEffects();
        List<ViralLoadChangedEvent> none = capture(ViralLoadChangedEvent.class, () -> Serums.apply(player, settings));
        helper.assertTrue(none.isEmpty(), "no event at 0 viral load");
        helper.assertTrue(Serums.isInoculated(player), "inoculated at 0 viral load");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH + "_inoculation")
    public static void inoculationScalesGainAndMilkClearsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spot = helper.absolutePos(new BlockPos(1, 1, 1));
        LevelChunk chunk = level.getChunkAt(spot);
        ChunkCorruption before = chunk.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
        try {
            chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(75, 0L));
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot.getX(), spot.getZ());
            ServerPlayer player = SurgeTests.player(helper, new BlockPos(spot.getX(), ground, spot.getZ()));
            ViralLoadSettings settings = ViralLoadSettings.fromConfig();
            ViralLoad.update(player, ViralLoadSettings.INTERVAL_TICKS, settings);
            float full = ViralLoad.get(player);
            helper.assertTrue(full > 0, "no gain in a stage 4 chunk");
            helper.assertValueEqual(Serums.gainFactor(player, SerumSettings.fromConfig()), 1.0, "factor without the effect");

            ViralLoad.set(player, 0.0F, ViralLoadState.Trend.STEADY, ViralLoadChangedEvent.Cause.OTHER);
            player.addEffect(new MobEffectInstance(RemissionMobEffects.INOCULATED, 200));
            helper.assertValueEqual(Serums.gainFactor(player, SerumSettings.fromConfig()), 0.5, "factor while inoculated");
            ViralLoad.update(player, ViralLoadSettings.INTERVAL_TICKS, settings);
            assertLoad(helper, player, full * 0.5F, "gain while inoculated");

            player.removeEffectsCuredBy(EffectCures.MILK);
            helper.assertTrue(!Serums.isInoculated(player), "milk clears inoculated");
        } finally {
            restore(chunk, before);
        }
        helper.succeed();
    }

    // ---- purifier fuel -----------------------------------------------------------------------------------------------

    @GameTest(template = "empty")
    public static void purifierFuelRepairsThePurifier(GameTestHelper helper) {
        ItemStack purifier = new ItemStack(RemissionItems.HANDHELD_PURIFIER.get());
        ItemStack fuel = new ItemStack(RemissionItems.PURIFIER_FUEL.get());
        helper.assertTrue(fuel.is(HandheldPurifierItem.FUEL), "purifier fuel is in #remission:purifier_fuel");
        helper.assertTrue(purifier.getItem().isValidRepairItem(purifier, fuel), "fuel repairs the purifier");
        helper.assertTrue(!purifier.getItem().isValidRepairItem(purifier, new ItemStack(Items.IRON_INGOT)), "iron does not");
        helper.succeed();
    }

    // ---- Counterstrain prototypes ------------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = BATCH + "_prototype")
    public static void prototypeCleansesWhereItLands(GameTestHelper helper) {
        inArea(helper, 0, area -> {
            ServerLevel level = helper.getLevel();
            LevelChunk chunk = area.chunk();
            setLevel(chunk, 60);
            BlockPos centre = area.at(8, BASE_Y, 8);
            area.set(centre, RemissionBlocks.FESTERING_GRASS.get().defaultBlockState());
            area.set(centre.east(4), RemissionBlocks.FESTERED_STONE.get().defaultBlockState());
            area.set(centre.east(5), RemissionBlocks.FESTERED_STONE.get().defaultBlockState());
            ServerPlayer player = SurgeTests.player(helper, centre.above());
            PrototypeSettings settings = new PrototypeSettings(4, 40, 0.0);

            List<BlockPurifiedEvent> purified = capture(BlockPurifiedEvent.class, () -> helper.assertValueEqual(
                    CounterstrainPrototypes.land(level, Vec3.atCenterOf(centre.above()), player, settings, RandomSource.create()),
                    CounterstrainPrototypes.Outcome.CLEANSED, "outcome"));
            helper.assertTrue(level.getBlockState(centre).is(Blocks.GRASS_BLOCK), "landing block not cleansed");
            helper.assertTrue(level.getBlockState(centre.east(4)).is(Blocks.STONE), "block 4 away not cleansed");
            helper.assertTrue(level.getBlockState(centre.east(5)).is(RemissionBlocks.FESTERED_STONE.get()), "block 5 away cleansed");
            helper.assertValueEqual(purified.size(), 2, "block purified events");
            helper.assertTrue(purified.stream().allMatch(e -> e.getCause() == PurificationCause.PROTOTYPE), "event causes");
            helper.assertValueEqual(levelOf(chunk), 20, "chunk level after one prototype");

            List<ChunkEnteredRemissionEvent> entered = capture(ChunkEnteredRemissionEvent.class,
                    () -> CounterstrainPrototypes.land(level, Vec3.atCenterOf(centre.above()), player, settings, RandomSource.create()));
            helper.assertValueEqual(levelOf(chunk), 0, "chunk level after a second prototype");
            helper.assertValueEqual(entered.size(), 1, "remission events");
            helper.assertTrue(entered.getFirst().getCause() == PurificationCause.PROTOTYPE
                    && entered.getFirst().getPlayer().orElse(null) == player, "remission event payload");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_backfire")
    public static void unstablePrototypeBackfires(GameTestHelper helper) {
        // In the test's own chunk, whose entities are already loaded, so the cloud can be found at once.
        ServerLevel level = helper.getLevel();
        BlockPos floor = helper.absolutePos(new BlockPos(1, 1, 1));
        LevelChunk chunk = level.getChunkAt(floor);
        ChunkCorruption before = chunk.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
        List<MiasmaCloud> clouds = new ArrayList<>();
        try {
            setLevel(chunk, 60);
            helper.setBlock(new BlockPos(1, 1, 1), RemissionBlocks.FESTERING_GRASS.get());
            helper.assertValueEqual(CounterstrainPrototypes.land(level, Vec3.atCenterOf(floor.above()), null,
                    new PrototypeSettings(4, 40, 1.0), RandomSource.create()), CounterstrainPrototypes.Outcome.BACKFIRED, "outcome");
            clouds.addAll(clouds(level, floor));
            helper.assertValueEqual(clouds.size(), 1, "miasma clouds released");
            helper.assertValueEqual(clouds.getFirst().getLifetime(), CounterstrainPrototypes.BACKFIRE_CLOUD_TICKS, "cloud lifetime");
            helper.assertValueEqual(levelOf(chunk), 60, "chunk level after a backfire");
            helper.assertTrue(level.getBlockState(floor).is(RemissionBlocks.FESTERING_GRASS.get()), "backfire cleansed a block");
        } finally {
            clouds.forEach(MiasmaCloud::discard);
            restore(chunk, before);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH + "_thrown", timeoutTicks = 100)
    public static void thrownPrototypeLands(GameTestHelper helper) {
        // The thrown path uses the configured backfire chance, so either outcome passes; each is checked above.
        ServerLevel level = helper.getLevel();
        BlockPos floor = helper.absolutePos(new BlockPos(1, 1, 1));
        LevelChunk chunk = level.getChunkAt(floor);
        ChunkCorruption before = chunk.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
        setLevel(chunk, 60);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        ThrownPrototype thrown = new ThrownPrototype(level, floor.getX() + 0.5, floor.getY() + 4, floor.getZ() + 0.5);
        thrown.setDeltaMovement(0.0, -0.5, 0.0);
        level.addFreshEntity(thrown);
        int step = PrototypeSettings.fromConfig().step();
        helper.onEachTick(() -> {
            if (!thrown.isRemoved()) {
                return;
            }
            List<MiasmaCloud> clouds = clouds(level, floor);
            int after = levelOf(chunk);
            clouds.forEach(MiasmaCloud::discard);
            restore(chunk, before);
            helper.assertTrue(after == 60 - step || clouds.size() == 1,
                    "prototype landed but neither cleansed nor backfired (level " + after + ", " + clouds.size() + " clouds)");
            helper.succeed();
        });
    }

    // ---- synthesizer recipes -----------------------------------------------------------------------------------------

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void synthesizerMakesSerums(GameTestHelper helper) {
        BlockPos pos = synthesizer(helper);
        IItemHandler items = items(helper, pos);
        items.insertItem(0, new ItemStack(Items.GLASS_BOTTLE, 2), false);
        items.insertItem(1, new ItemStack(RemissionItems.STRAIN_SEQUENCE.get()), false);
        fluids(helper, pos).fill(new FluidStack(RemissionFluids.MIASMA.get(), 250), IFluidHandler.FluidAction.EXECUTE);
        helper.succeedWhen(() -> {
            ItemStack out = items(helper, pos).extractItem(3, 64, true);
            helper.assertTrue(out.is(RemissionItems.SERUM.get()) && out.getCount() == 2, "two serums, got " + out);
            helper.assertTrue(fluids(helper, pos).getFluidInTank(0).isEmpty(), "250 mB miasma used");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void synthesizerMakesPurifierFuel(GameTestHelper helper) {
        BlockPos pos = synthesizer(helper);
        IItemHandler items = items(helper, pos);
        items.insertItem(0, new ItemStack(RemissionItems.STRAIN_SEQUENCE.get()), false);
        items.insertItem(1, new ItemStack(Items.CHARCOAL, 4), false);
        fluids(helper, pos).fill(new FluidStack(RemissionFluids.MIASMA.get(), 250), IFluidHandler.FluidAction.EXECUTE);
        helper.succeedWhen(() -> {
            ItemStack out = items(helper, pos).extractItem(3, 64, true);
            helper.assertTrue(out.is(RemissionItems.PURIFIER_FUEL.get()) && out.getCount() == 4, "four purifier fuel, got " + out);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void synthesizerMakesPrototypes(GameTestHelper helper) {
        BlockPos pos = synthesizer(helper);
        IItemHandler items = items(helper, pos);
        items.insertItem(0, new ItemStack(RemissionItems.NIDUS_CORE.get()), false);
        items.insertItem(1, new ItemStack(Items.GLASS_BOTTLE), false);
        items.insertItem(2, new ItemStack(RemissionItems.STRAIN_SEQUENCE.get()), false);
        fluids(helper, pos).fill(new FluidStack(RemissionFluids.MIASMA.get(), 1000), IFluidHandler.FluidAction.EXECUTE);
        helper.succeedWhen(() -> {
            ItemStack out = items(helper, pos).extractItem(3, 64, true);
            helper.assertTrue(out.is(RemissionItems.COUNTERSTRAIN_PROTOTYPE.get()), "a Counterstrain prototype, got " + out);
            helper.assertValueEqual(energy(helper, pos).getEnergyStored(), MachineKind.ENERGY_CAPACITY - 12000, "energy used");
        });
    }

    // ---- KubeJS recipe schemas ---------------------------------------------------------------------------------------

    @GameTest(template = "empty")
    public static void kubeJsSchemasMatchMachineRecipes(GameTestHelper helper) {
        Set<String> fields = Set.of("ingredients", "results", "fluid_ingredient", "fluid_result", "energy", "time");
        for (MachineKind kind : MachineKind.values()) {
            String name = kind.recipeName();
            JsonObject schema = schema(helper, name);
            Set<String> keys = new LinkedHashSet<>();
            for (JsonElement key : schema.getAsJsonArray("keys")) {
                JsonObject object = key.getAsJsonObject();
                keys.add(object.get("name").getAsString());
                if (object.get("name").getAsString().equals("ingredients")) {
                    JsonObject type = object.getAsJsonObject("type");
                    helper.assertValueEqual(type.get("max").getAsInt(), kind.inputSlots(), name + " schema ingredient limit");
                }
            }
            helper.assertValueEqual(keys, fields, name + " schema keys");
            JsonArray constructors = schema.getAsJsonArray("constructors");
            helper.assertValueEqual(constructors.get(0).getAsJsonObject().getAsJsonArray("keys").get(0).getAsString(), "results",
                    name + " schema takes results first");
        }
        helper.succeed();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static JsonObject schema(GameTestHelper helper, String name) {
        Resource resource = helper.getLevel().getServer().getResourceManager()
                .getResource(Remission.id("kubejs/recipe_schema/" + name + ".json"))
                .orElseThrow(() -> new IllegalStateException("no KubeJS schema for " + name));
        try (Reader reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<MiasmaCloud> clouds(ServerLevel level, BlockPos near) {
        return level.getEntities(RemissionEntityTypes.MIASMA_CLOUD.get(), new AABB(near).inflate(16), cloud -> true);
    }

    private static BlockPos synthesizer(GameTestHelper helper) {
        helper.setBlock(MACHINE, RemissionBlocks.SYNTHESIZER.get());
        IEnergyStorage storage = energy(helper, MACHINE);
        while (storage.receiveEnergy(MachineKind.ENERGY_CAPACITY, false) > 0) {
            // A cable's worth per call until full.
        }
        return MACHINE;
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

    private static void assertLoad(GameTestHelper helper, ServerPlayer player, float expected, String what) {
        float actual = ViralLoad.get(player);
        helper.assertTrue(Math.abs(actual - expected) < EPSILON, what + ": expected viral load " + expected + ", got " + actual);
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

    private static void setLevel(LevelChunk chunk, int value) {
        chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(value, 0L));
    }

    private static int levelOf(LevelChunk chunk) {
        ChunkCorruption state = chunk.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
        return state == null ? 0 : state.level();
    }

    private static void restore(LevelChunk chunk, ChunkCorruption before) {
        if (before == null) {
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
        } else {
            chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, before);
        }
    }

    private static ChunkPos areaOrigin(GameTestHelper helper, int index) {
        ChunkPos own = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        return new ChunkPos(own.x + FAR + index * SPACING, own.z + SOUTH);
    }

    /** One test's chunk, force-loaded, and the blocks to put back. */
    private record Area(GameTestHelper helper, ChunkPos pos, List<Runnable> undo) {
        LevelChunk chunk() {
            ServerLevel level = helper.getLevel();
            level.setChunkForced(pos.x, pos.z, true);
            return level.getChunk(pos.x, pos.z);
        }

        BlockPos at(int localX, int y, int localZ) {
            return new BlockPos(pos.getBlockX(localX), y, pos.getBlockZ(localZ));
        }

        void set(BlockPos at, BlockState state) {
            ServerLevel level = helper.getLevel();
            BlockPos fixed = at.immutable();
            undo.add(() -> level.setBlock(fixed, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
            level.setBlock(fixed, state, Block.UPDATE_ALL);
        }

        void release() {
            ServerLevel level = helper.getLevel();
            undo.reversed().forEach(Runnable::run);
            LevelChunk chunk = level.getChunk(pos.x, pos.z);
            Cleansing.cleanseNow(level, chunk, PurificationCause.OTHER);
            Purification.engine(level).unloaded(pos);
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
            chunk.removeData(RemissionAttachments.CHUNK_REMISSION);
            chunk.removeData(RemissionAttachments.FESTERED_STAGE);
            level.setChunkForced(pos.x, pos.z, false);
        }
    }

    private static void inArea(GameTestHelper helper, int index, Consumer<Area> body) {
        Area area = new Area(helper, areaOrigin(helper, index), new ArrayList<>());
        try {
            body.accept(area);
        } finally {
            area.release();
        }
    }
}
