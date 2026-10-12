package com.cubiecode.remission.tech;

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
 * A machine's menu. Slots sync through vanilla slot updates and the progress, energy and tanks through
 * {@link ContainerData}, so machines add no network payloads. The client's copy needs no extra open data, so a
 * spectator (who opens menus without it) can look inside too.
 */
public final class MachineMenu extends AbstractContainerMenu {
    // Layout shared with the screen, in GUI pixels from the top-left of the 176x166 background.
    public static final int INPUT_Y = 35;
    public static final int OUTPUT_X = 129;
    public static final int[] OUTPUT_Y = {26, 44};
    public static final int INVENTORY_Y = 84;

    private final MachineKind kind;
    private final ContainerData data;
    private final ContainerLevelAccess access;

    public MachineMenu(MachineKind kind, int containerId, Inventory inventory, IItemHandler items, ContainerData data,
                       ContainerLevelAccess access) {
        super(kind.menuType(), containerId);
        this.kind = kind;
        this.data = data;
        this.access = access;
        checkContainerDataCount(data, Data.COUNT);
        for (int i = 0; i < kind.inputSlots(); i++) {
            addSlot(new SlotItemHandler(items, i, inputX(kind, i), INPUT_Y));
        }
        for (int i = 0; i < MachineKind.OUTPUT_SLOTS; i++) {
            addSlot(new SlotItemHandler(items, kind.inputSlots() + i, OUTPUT_X, OUTPUT_Y[i]) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }
            });
        }
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

    /** Client side: empty slots and data, filled by vanilla menu sync. */
    public static MachineMenu client(MachineKind kind, int containerId, Inventory inventory) {
        return new MachineMenu(kind, containerId, inventory, new ItemStackHandler(kind.slots()),
                new SimpleContainerData(Data.COUNT), ContainerLevelAccess.NULL);
    }

    /** X of input slot {@code index}: the inputs sit centred between the input tank and the progress arrow. */
    public static int inputX(MachineKind kind, int index) {
        return 71 - kind.inputSlots() * 9 + index * 18;
    }

    public MachineKind kind() {
        return kind;
    }

    public int progress() {
        return Data.read(data, Data.PROGRESS);
    }

    public int time() {
        return Data.read(data, Data.TIME);
    }

    public int energy() {
        return Data.read(data, Data.ENERGY);
    }

    public FluidStack inputFluid() {
        return Data.fluid(data, Data.INPUT_FLUID, Data.INPUT_AMOUNT);
    }

    public FluidStack outputFluid() {
        return Data.fluid(data, Data.OUTPUT_FLUID, Data.OUTPUT_AMOUNT);
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, kind.block());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int machineSlots = kind.slots();
        if (index < machineSlots) {
            if (!moveItemStackTo(stack, machineSlots, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, kind.inputSlots(), false)) {
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

    /**
     * Synced values. Vanilla sends container data as shorts, so each value is two slots holding its low and high
     * 16 bits. Fluids are sent as their registry id (the fluid registry is synced) and amount.
     */
    public static final class Data {
        public static final int PROGRESS = 0;
        public static final int TIME = 1;
        public static final int ENERGY = 2;
        public static final int INPUT_FLUID = 3;
        public static final int INPUT_AMOUNT = 4;
        public static final int OUTPUT_FLUID = 5;
        public static final int OUTPUT_AMOUNT = 6;
        static final int FIELDS = 7;
        public static final int COUNT = FIELDS * 2;

        private Data() {
        }

        /** The data slot {@code index} of a value: its low 16 bits in even slots, its high 16 bits in odd ones. */
        static int half(int index, int value) {
            return index % 2 == 0 ? value & 0xFFFF : value >>> 16;
        }

        static int read(ContainerData data, int field) {
            return (data.get(field * 2) & 0xFFFF) | (data.get(field * 2 + 1) & 0xFFFF) << 16;
        }

        static FluidStack fluid(ContainerData data, int fluidField, int amountField) {
            int amount = read(data, amountField);
            Fluid fluid = BuiltInRegistries.FLUID.byId(read(data, fluidField));
            return amount <= 0 ? FluidStack.EMPTY : new FluidStack(fluid, amount);
        }
    }
}
