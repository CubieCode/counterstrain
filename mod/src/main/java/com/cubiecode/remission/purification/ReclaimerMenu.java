package com.cubiecode.remission.purification;

import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionMenus;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * A reclaimer's menu (P7.4): the fuel slot and the player inventory. Energy, fuel, radius, progress and the survey map
 * sync through {@link ContainerData}, so the reclaimer adds no network payloads, and the client copy needs no open data.
 */
public final class ReclaimerMenu extends AbstractContainerMenu {
    // Layout shared with the screen, in GUI pixels from the top-left of the 176x166 background.
    public static final int FUEL_X = 44;
    public static final int FUEL_Y = 53;
    public static final int INVENTORY_Y = 84;

    private final ContainerData data;
    private final ContainerLevelAccess access;

    public ReclaimerMenu(int containerId, Inventory inventory, IItemHandler items, ContainerData data, ContainerLevelAccess access) {
        super(RemissionMenus.RECLAIMER.get(), containerId);
        this.data = data;
        this.access = access;
        checkContainerDataCount(data, Data.COUNT);
        addSlot(new SlotItemHandler(items, 0, FUEL_X, FUEL_Y));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column, 8 + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, INVENTORY_Y + 58));
        }
        addDataSlots(data);
    }

    /** Client side: an empty slot and data, filled by vanilla menu sync. */
    public static ReclaimerMenu client(int containerId, Inventory inventory) {
        ItemStackHandler items = new ItemStackHandler(1) {
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return stack.is(HandheldPurifierItem.FUEL);
            }
        };
        return new ReclaimerMenu(containerId, inventory, items, new SimpleContainerData(Data.COUNT), ContainerLevelAccess.NULL);
    }

    public int energy() {
        return Data.read(data, Data.ENERGY);
    }

    public int capacity() {
        return Data.read(data, Data.CAPACITY);
    }

    /** Operations left from the last fuel burned. */
    public int fuel() {
        return Data.read(data, Data.FUEL);
    }

    public FluidStack fluid() {
        int amount = Data.read(data, Data.FLUID_AMOUNT);
        Fluid fluid = BuiltInRegistries.FLUID.byId(Data.read(data, Data.FLUID));
        return amount <= 0 ? FluidStack.EMPTY : new FluidStack(fluid, amount);
    }

    public int radius() {
        return Data.read(data, Data.RADIUS);
    }

    public int reclaimed() {
        return Data.read(data, Data.RECLAIMED);
    }

    public int festering() {
        return Data.read(data, Data.FESTERING);
    }

    public int area() {
        int side = 2 * radius() + 1;
        return side * side;
    }

    public ReclaimerBlockEntity.Status status() {
        ReclaimerBlockEntity.Status[] values = ReclaimerBlockEntity.Status.values();
        int index = Data.read(data, Data.STATUS);
        return index >= 0 && index < values.length ? values[index] : ReclaimerBlockEntity.Status.CLEAR;
    }

    /** The {@link Cell} code of the chunk {@code (dx, dz)} from the reclaimer's own. */
    public int cell(int dx, int dz) {
        int index = Cell.index(dx, dz);
        return data.get(Data.MAP + index / Data.CELLS_PER_SLOT) >> (index % Data.CELLS_PER_SLOT * 4) & 0xF;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, RemissionBlocks.RECLAIMER.get());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index == 0) {
            if (!moveItemStackTo(stack, 1, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, 1, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return original;
    }

    /** Survey map codes, one per chunk, 4 bits each. */
    public static final class Cell {
        /** Beyond the configured radius. */
        public static final int OUTSIDE = 0;
        /** Not loaded, so not drained. */
        public static final int UNLOADED = 1;
        /** Never festered, or clean without a remission mark. */
        public static final int CLEAN = 2;
        public static final int REMISSION = 3;
        /** Festering stage 1; stages 2 to 4 follow, higher stages count as 4. */
        public static final int STAGE_1 = 4;
        public static final int STAGE_4 = 7;
        /** Holds a live nidus: never drained. */
        public static final int NIDUS = 8;

        private Cell() {
        }

        static int stage(int stage) {
            return Math.min(STAGE_4, STAGE_1 + Math.max(stage, 1) - 1);
        }

        /** Map index of the chunk {@code (dx, dz)} from the centre. */
        public static int index(int dx, int dz) {
            int r = ReclaimerSettings.MAX_RADIUS;
            return (dz + r) * ReclaimerBlockEntity.GRID + dx + r;
        }
    }

    /**
     * Synced values. Vanilla sends container data as shorts, so each value is two slots holding its low and high
     * 16 bits; the map follows, four 4-bit cells per slot. The fluid is sent as its registry id (the registry is synced).
     */
    public static final class Data {
        public static final int ENERGY = 0;
        public static final int CAPACITY = 1;
        public static final int FUEL = 2;
        public static final int FLUID = 3;
        public static final int FLUID_AMOUNT = 4;
        public static final int RADIUS = 5;
        public static final int RECLAIMED = 6;
        public static final int FESTERING = 7;
        public static final int STATUS = 8;
        static final int FIELDS = 9;
        static final int CELLS_PER_SLOT = 4;
        /** First map slot. */
        public static final int MAP = FIELDS * 2;
        public static final int COUNT = MAP + (ReclaimerBlockEntity.GRID * ReclaimerBlockEntity.GRID + CELLS_PER_SLOT - 1) / CELLS_PER_SLOT;

        private Data() {
        }

        /** The data slot {@code index} of a value: its low 16 bits in even slots, its high 16 bits in odd ones. */
        static int half(int index, int value) {
            return index % 2 == 0 ? value & 0xFFFF : value >>> 16;
        }

        static int read(ContainerData data, int field) {
            return (data.get(field * 2) & 0xFFFF) | (data.get(field * 2 + 1) & 0xFFFF) << 16;
        }

        /** Map slot {@code slot}: four cells from {@code cells}, lowest bits first. */
        static int packCells(byte[] cells, int slot) {
            int packed = 0;
            for (int i = 0; i < CELLS_PER_SLOT; i++) {
                int index = slot * CELLS_PER_SLOT + i;
                if (index < cells.length) {
                    packed |= (cells[index] & 0xF) << (i * 4);
                }
            }
            return packed;
        }
    }
}
