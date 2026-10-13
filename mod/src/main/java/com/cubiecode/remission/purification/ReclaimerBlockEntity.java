package com.cubiecode.remission.purification;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.PurificationCause;
import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.registry.RemissionBlockEntities;
import com.cubiecode.remission.world.Nidus;
import com.cubiecode.remission.world.NidusIndex;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * State and work of a reclaimer (P7.4): a purifier fuel slot, a fuel tank, an energy buffer and the fuel left from the
 * last item or bucket burned. Every {@code intervalTicks} it runs one <em>operation</em>: it surveys the chunks within
 * {@code radius} of its own (a square) and, if it has the energy and fuel, lowers the nearest loaded festering chunk by
 * {@code drainStep} through {@link Purification#lowerLevel} with {@link PurificationCause#RECLAIMER}, so a drained chunk
 * enters remission and gets P5.2's chunk cleanse. A chunk holding a live (indexed) nidus is never drained, and the
 * reclaimer does not ward, so the nidus keeps spreading against it. An operation with nothing to drain uses nothing.
 *
 * <p>Other blocks reach it through standard capabilities, the same on every side: items in {@code #remission:purifier_fuel}
 * go into the fuel slot, fluids in the fluid tag {@code #remission:purifier_fuel} fill the tank, and energy is received.
 * Nothing can be taken out, except a fluid in the tank that is no longer fuel ({@link #isFluidFuel}). A comparator
 * reads how much of its area is in remission.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class ReclaimerBlockEntity extends BlockEntity implements MenuProvider {
    /** Fluid tag {@code #remission:purifier_fuel}: fluids a reclaimer burns as fuel. Empty by default; packs fill it. */
    public static final TagKey<Fluid> FLUID_FUEL = TagKey.create(Registries.FLUID, Remission.id("purifier_fuel"));
    public static final int TANK_CAPACITY = 4 * FluidType.BUCKET_VOLUME;
    /** Side of the survey map, which is sized for the largest radius. */
    public static final int GRID = 2 * ReclaimerSettings.MAX_RADIUS + 1;
    private static final List<List<int[]>> ORDERS = new ArrayList<>();

    /** What the last operation did. */
    public enum Status {
        /** It drained a chunk. */
        RUNNING,
        /** Nothing in reach is festering. */
        CLEAR,
        /** The only festering chunks in reach hold a live nidus. */
        HELD,
        /** There is land to drain but not enough energy. */
        NO_ENERGY,
        /** There is land to drain but no fuel. */
        NO_FUEL
    }

    private final ItemStackHandler items = new ItemStackHandler(1) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return stack.is(HandheldPurifierItem.FUEL);
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };
    private final FluidTank tank = new FluidTank(TANK_CAPACITY, ReclaimerBlockEntity::isFluidFuel) {
        @Override
        protected void onContentsChanged() {
            setChanged();
        }
    };
    private final Energy energy = new Energy();
    private final IItemHandler automationItems = new AutomationItems();
    private final IFluidHandler automationFluids = new AutomationFluids();
    private final ContainerData data = new ReclaimerData();
    /** Survey map, {@link ReclaimerMenu.Cell} codes, row by row (z) then column (x), centred on the reclaimer's chunk. */
    private final byte[] map = new byte[GRID * GRID];
    /** Operations left from the last fuel item or bucket burned. */
    private int fuel;
    private int radius = -1;
    private int reclaimed;
    private int festering;
    private int signal;
    /** Whether a survey has run since it was created or loaded; the first tick runs one for the comparator and screen. */
    private boolean surveyed;
    private Status status = Status.CLEAR;

    public ReclaimerBlockEntity(BlockPos pos, BlockState state) {
        super(RemissionBlockEntities.RECLAIMER.get(), pos, state);
    }

    /** The fuel slot, for the menu and internal use. */
    public ItemStackHandler items() {
        return items;
    }

    public FluidTank tank() {
        return tank;
    }

    public IEnergyStorage energy() {
        return energy;
    }

    /** Item handler other blocks see (capability): purifier fuel in, nothing out. */
    public IItemHandler automationItems() {
        return automationItems;
    }

    /** Fluid handler other blocks see (capability): purifier fuel fluids in, nothing out. */
    public IFluidHandler automationFluids() {
        return automationFluids;
    }

    /** Operations left from the last fuel item or bucket burned. */
    public int fuel() {
        return fuel;
    }

    public Status status() {
        return status;
    }

    /** Chunks in reach that were in remission at the last survey. */
    public int reclaimed() {
        return reclaimed;
    }

    /** Chunks in reach that were festering (live nidus chunks included) at the last survey. */
    public int festering() {
        return festering;
    }

    /**
     * Whether the tank accepts {@code stack} as fuel: a fluid in {@link #FLUID_FUEL} while {@code [reclaimers]
     * fuelPerBucket} is above 0. Any other fluid left in the tank (after a reload drops it from the tag, or the config
     * turns fluid fuel off) is never burned and can be drained out, so the tank never jams.
     */
    public static boolean isFluidFuel(FluidStack stack) {
        return stack.is(FLUID_FUEL) && RemissionCommonConfig.RECLAIMER_FUEL_PER_BUCKET.get() > 0;
    }

    /**
     * Comparator output: 0 while none of its area is in remission, then 1 to 15 with the share in remission (15 when all
     * of it is). Saved, recomputed by a survey on the first tick after loading, and updated by each operation.
     */
    public int signal() {
        return signal;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ReclaimerBlockEntity reclaimer) {
        ServerLevel serverLevel = (ServerLevel) level;
        if (!reclaimer.surveyed) {
            reclaimer.survey(serverLevel, RemissionCommonConfig.RECLAIMER_RADIUS.get());
            reclaimer.updateSignal(serverLevel);
        }
        // Staggered by position, so reclaimers loaded together do not all run on the same tick.
        int interval = Math.max(1, RemissionCommonConfig.RECLAIMER_INTERVAL_TICKS.get());
        if (Math.floorMod(level.getGameTime() + pos.asLong(), interval) == 0) {
            reclaimer.operate(serverLevel, ReclaimerSettings.fromConfig());
        }
    }

    /**
     * One operation now: surveys its area and drains the nearest festering chunk without a live nidus if it has the
     * energy and fuel ({@link #status()} says what happened). Called on its interval by the ticker, and directly by
     * tests and the harness.
     */
    public Status operate(ServerLevel level, ReclaimerSettings settings) {
        LevelChunk next = survey(level, settings.radius());
        Status result;
        if (next == null) {
            result = festering > 0 ? Status.HELD : Status.CLEAR;
        } else if (energy.stored < settings.energyPerOperation()) {
            result = Status.NO_ENERGY;
        } else if (!takeFuel(settings)) {
            result = Status.NO_FUEL;
        } else {
            energy.stored -= settings.energyPerOperation();
            fuel--;
            Purification.lowerLevel(level, next, settings.drainStep(), PurificationCause.RECLAIMER, null);
            setChanged();
            // Again, so the comparator and screen see the chunk this operation drained.
            survey(level, settings.radius());
            result = Status.RUNNING;
        }
        status = result;
        updateSignal(level);
        return result;
    }

    private void updateSignal(ServerLevel level) {
        int strength = reclaimed == 0 ? 0 : 1 + 14 * reclaimed / area();
        if (strength != signal) {
            signal = strength;
            setChanged();
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
        }
    }

    /** Chunks in its area at the last survey's radius. */
    public int area() {
        int side = 2 * Math.max(radius, 0) + 1;
        return side * side;
    }

    /**
     * Records every chunk within {@code radius} in the map and counts those in remission and festering. Returns the
     * nearest loaded festering chunk without a live nidus (by squared distance between chunk positions, then z, then x),
     * or null.
     */
    @Nullable
    private LevelChunk survey(ServerLevel level, int radius) {
        this.radius = radius;
        surveyed = true;
        ChunkPos centre = new ChunkPos(worldPosition);
        LongOpenHashSet nidi = new LongOpenHashSet();
        for (Nidus nidus : NidusIndex.get(level).nidi()) {
            long chunk = nidus.chunk();
            if (Math.abs(ChunkPos.getX(chunk) - centre.x) <= radius && Math.abs(ChunkPos.getZ(chunk) - centre.z) <= radius) {
                nidi.add(chunk);
            }
        }
        Arrays.fill(map, (byte) ReclaimerMenu.Cell.OUTSIDE);
        reclaimed = 0;
        festering = 0;
        LevelChunk next = null;
        for (int[] offset : order(radius)) {
            int x = centre.x + offset[0];
            int z = centre.z + offset[1];
            LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
            int cell;
            if (chunk == null) {
                cell = ReclaimerMenu.Cell.UNLOADED;
            } else {
                int chunkLevel = CorruptionQueries.chunkLevel(level, chunk.getPos());
                boolean nidus = nidi.contains(ChunkPos.asLong(x, z));
                if (chunkLevel > 0) {
                    festering++;
                    if (nidus) {
                        cell = ReclaimerMenu.Cell.NIDUS;
                    } else {
                        cell = ReclaimerMenu.Cell.stage(CorruptionQueries.chunkStageFor(chunkLevel));
                        if (next == null) {
                            next = chunk;
                        }
                    }
                } else if (CorruptionQueries.isInRemission(level, chunk.getPos())) {
                    reclaimed++;
                    cell = nidus ? ReclaimerMenu.Cell.NIDUS : ReclaimerMenu.Cell.REMISSION;
                } else {
                    cell = nidus ? ReclaimerMenu.Cell.NIDUS : ReclaimerMenu.Cell.CLEAN;
                }
            }
            map[ReclaimerMenu.Cell.index(offset[0], offset[1])] = (byte) cell;
        }
        return next;
    }

    /** Chunk offsets within {@code radius} (a square), nearest first; ties by z, then x. Shared, do not change. */
    static List<int[]> order(int radius) {
        synchronized (ORDERS) {
            while (ORDERS.size() <= radius) {
                int r = ORDERS.size();
                List<int[]> offsets = new ArrayList<>((2 * r + 1) * (2 * r + 1));
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        offsets.add(new int[] {dx, dz});
                    }
                }
                offsets.sort(Comparator.<int[]>comparingInt(o -> o[0] * o[0] + o[1] * o[1])
                        .thenComparingInt(o -> o[1]).thenComparingInt(o -> o[0]));
                ORDERS.add(List.copyOf(offsets));
            }
            return ORDERS.get(radius);
        }
    }

    /** Makes sure at least one operation of fuel is left, burning a fuel item first, then a bucket of fuel fluid. */
    private boolean takeFuel(ReclaimerSettings settings) {
        if (fuel > 0) {
            return true;
        }
        if (items.getStackInSlot(0).is(HandheldPurifierItem.FUEL)) {
            items.extractItem(0, 1, false);
            fuel += settings.fuelPerItem();
        } else if (settings.fuelPerBucket() > 0 && tank.getFluidAmount() >= FluidType.BUCKET_VOLUME
                && isFluidFuel(tank.getFluid())) {
            tank.drain(FluidType.BUCKET_VOLUME, IFluidHandler.FluidAction.EXECUTE);
            fuel += settings.fuelPerBucket();
        }
        return fuel > 0;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("items", items.serializeNBT(registries));
        tag.put("tank", tank.writeToNBT(registries, new CompoundTag()));
        tag.putInt("energy", energy.stored);
        tag.putInt("fuel", fuel);
        tag.putInt("signal", signal);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.deserializeNBT(registries, tag.getCompound("items"));
        tank.readFromNBT(registries, tag.getCompound("tank"));
        energy.stored = Math.max(0, tag.getInt("energy"));
        fuel = Math.max(0, tag.getInt("fuel"));
        signal = Math.clamp(tag.getInt("signal"), 0, 15);
    }

    @Override
    public Component getDisplayName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new ReclaimerMenu(containerId, inventory, items, data, ContainerLevelAccess.create(player.level(), getBlockPos()));
    }

    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, RemissionBlockEntities.RECLAIMER.get(),
                (reclaimer, side) -> reclaimer.automationItems());
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, RemissionBlockEntities.RECLAIMER.get(),
                (reclaimer, side) -> reclaimer.automationFluids());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, RemissionBlockEntities.RECLAIMER.get(),
                (reclaimer, side) -> reclaimer.energy());
    }

    /** Values the menu syncs to the client; see {@link ReclaimerMenu.Data}. */
    private final class ReclaimerData implements ContainerData {
        @Override
        public int get(int index) {
            if (index >= ReclaimerMenu.Data.MAP) {
                return ReclaimerMenu.Data.packCells(map, index - ReclaimerMenu.Data.MAP);
            }
            return ReclaimerMenu.Data.half(index, value(index / 2));
        }

        private int value(int field) {
            return switch (field) {
                case ReclaimerMenu.Data.ENERGY -> energy.stored;
                case ReclaimerMenu.Data.CAPACITY -> energy.getMaxEnergyStored();
                case ReclaimerMenu.Data.FUEL -> fuel;
                case ReclaimerMenu.Data.FLUID -> BuiltInRegistries.FLUID.getId(tank.getFluid().getFluid());
                case ReclaimerMenu.Data.FLUID_AMOUNT -> tank.getFluidAmount();
                case ReclaimerMenu.Data.RADIUS -> Math.max(radius, 0);
                case ReclaimerMenu.Data.RECLAIMED -> reclaimed;
                case ReclaimerMenu.Data.FESTERING -> festering;
                case ReclaimerMenu.Data.STATUS -> status.ordinal();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return ReclaimerMenu.Data.COUNT;
        }
    }

    /** Energy buffer sized by {@code [reclaimers] energyCapacity}: other blocks may only insert. */
    private final class Energy implements IEnergyStorage {
        private int stored;

        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            int room = Math.max(0, getMaxEnergyStored() - stored);
            int received = Math.min(room, Math.min(toReceive,
                    RemissionCommonConfig.RECLAIMER_MAX_ENERGY_INPUT.get()));
            if (received > 0 && !simulate) {
                stored += received;
                setChanged();
            }
            return Math.max(0, received);
        }

        @Override
        public int extractEnergy(int toExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return stored;
        }

        @Override
        public int getMaxEnergyStored() {
            return RemissionCommonConfig.RECLAIMER_ENERGY_CAPACITY.get();
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    }

    /** The fuel slot accepts purifier fuel; nothing comes out. */
    private final class AutomationItems implements IItemHandler {
        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return items.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return items.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return items.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return items.isItemValid(slot, stack);
        }
    }

    /** Fuel fluids fill the tank; only a fluid that is no longer fuel ({@link #isFluidFuel}) drains from it. */
    private final class AutomationFluids implements IFluidHandler {
        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(int index) {
            return tank.getFluid();
        }

        @Override
        public int getTankCapacity(int index) {
            return TANK_CAPACITY;
        }

        @Override
        public boolean isFluidValid(int index, FluidStack stack) {
            return tank.isFluidValid(stack);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            return tank.fill(resource, action);
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            return isFluidFuel(tank.getFluid()) ? FluidStack.EMPTY : tank.drain(resource, action);
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            return isFluidFuel(tank.getFluid()) ? FluidStack.EMPTY : tank.drain(maxDrain, action);
        }
    }
}
