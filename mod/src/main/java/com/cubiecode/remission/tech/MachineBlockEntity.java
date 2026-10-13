package com.cubiecode.remission.tech;

import com.cubiecode.remission.quest.QuestProgress;
import com.cubiecode.remission.registry.RemissionDataComponents;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

/**
 * State and work of a machine: input and output slots, an input and an output tank, an energy buffer and the current
 * craft. The server ticks it; clients see it only through {@link MachineMenu} (vanilla slot and data sync).
 *
 * <p>Other blocks reach it through standard capabilities, the same on every side ({@link MachineCapabilities}): items
 * go into the input slots and come out of the output slots, fluid fills the input tank and drains from the output tank,
 * and energy is received only.
 */
public class MachineBlockEntity extends BlockEntity implements MenuProvider {
    private final MachineKind kind;
    private final RecipeManager.CachedCheck<MachineInput, MachineRecipe> recipeCheck;
    private final ItemStackHandler items;
    private final FluidTank inputTank = new FluidTank(MachineKind.TANK_CAPACITY) {
        @Override
        protected void onContentsChanged() {
            inputsChanged();
        }
    };
    private final FluidTank outputTank = new FluidTank(MachineKind.TANK_CAPACITY) {
        @Override
        protected void onContentsChanged() {
            setChanged();
        }
    };
    private final Energy energy = new Energy();
    private final IItemHandler automationItems;
    private final IFluidHandler automationFluids;
    private final ContainerData data = new MachineData();
    /** Current recipe, or {@code null} when none matches; re-resolved after the inputs change. */
    private @Nullable RecipeHolder<MachineRecipe> recipe;
    private @Nullable ResourceLocation savedRecipe;
    private boolean inputsDirty = true;
    private int progress;

    public MachineBlockEntity(MachineKind kind, BlockPos pos, BlockState state) {
        super(kind.blockEntityType(), pos, state);
        this.kind = kind;
        this.recipeCheck = RecipeManager.createCheck(kind.recipeType());
        this.items = new ItemStackHandler(kind.slots()) {
            @Override
            protected void onContentsChanged(int slot) {
                if (slot < kind.inputSlots()) {
                    inputsChanged();
                } else {
                    setChanged();
                }
            }
        };
        this.automationItems = new AutomationItems();
        this.automationFluids = new AutomationFluids();
    }

    public MachineKind kind() {
        return kind;
    }

    /** All slots, inputs first, for the menu and internal use. */
    public ItemStackHandler items() {
        return items;
    }

    public FluidTank inputTank() {
        return inputTank;
    }

    public FluidTank outputTank() {
        return outputTank;
    }

    public IEnergyStorage energy() {
        return energy;
    }

    public int progress() {
        return progress;
    }

    /** Item handler other blocks see (capability). */
    public IItemHandler automationItems() {
        return automationItems;
    }

    /** Fluid handler other blocks see (capability): tank 0 is the input, tank 1 the output. */
    public IFluidHandler automationFluids() {
        return automationFluids;
    }

    private void inputsChanged() {
        inputsDirty = true;
        setChanged();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, MachineBlockEntity machine) {
        machine.tick((ServerLevel) level);
    }

    private void tick(ServerLevel level) {
        if (inputsDirty) {
            inputsDirty = false;
            // The recipe in progress: the current one, or after a load the saved one.
            ResourceLocation previous = recipe != null ? recipe.id() : savedRecipe;
            RecipeHolder<MachineRecipe> found = findRecipe(level);
            if (found == null || !found.id().equals(previous)) {
                progress = 0;
            }
            recipe = found;
        }
        if (recipe == null) {
            return;
        }
        MachineRecipe current = recipe.value();
        if (!canOutput(current)) {
            return;
        }
        int needed = current.energyForTick(progress);
        if (energy.getEnergyStored() < needed) {
            return;
        }
        energy.use(needed);
        progress++;
        if (progress >= current.time()) {
            progress = 0;
            craft(current);
        }
        setChanged();
    }

    private @Nullable RecipeHolder<MachineRecipe> findRecipe(ServerLevel level) {
        MachineInput input = input();
        // A recipe saved mid-craft is tried first, so a reload keeps its progress (even if another recipe matches too).
        if (savedRecipe != null) {
            ResourceLocation id = savedRecipe;
            savedRecipe = null;
            Optional<RecipeHolder<?>> saved = level.getRecipeManager().byKey(id);
            if (saved.isPresent() && saved.get().value() instanceof MachineRecipe found && found.kind() == kind
                    && found.matches(input, level)) {
                return new RecipeHolder<>(id, found);
            }
        }
        if (input.items().stream().allMatch(ItemStack::isEmpty)) {
            return null;
        }
        return recipeCheck.getRecipeFor(input, level).orElse(null);
    }

    /** The input slots and input tank, as copies. */
    public MachineInput input() {
        List<ItemStack> stacks = new ArrayList<>(kind.inputSlots());
        for (int slot = 0; slot < kind.inputSlots(); slot++) {
            stacks.add(items.getStackInSlot(slot));
        }
        return new MachineInput(stacks, inputTank.getFluid());
    }

    private boolean canOutput(MachineRecipe current) {
        if (!current.fluidResult().isEmpty()
                && outputTank.fill(current.fluidResult(), IFluidHandler.FluidAction.SIMULATE) < current.fluidResult().getAmount()) {
            return false;
        }
        // Simulate on a copy so two results never count the same free space.
        ItemStackHandler outputs = new ItemStackHandler(MachineKind.OUTPUT_SLOTS);
        for (int i = 0; i < MachineKind.OUTPUT_SLOTS; i++) {
            outputs.setStackInSlot(i, items.getStackInSlot(kind.inputSlots() + i).copy());
        }
        for (ItemStack result : current.results()) {
            if (!ItemHandlerHelper.insertItemStacked(outputs, result.copy(), false).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void craft(MachineRecipe current) {
        MachineInput input = input();
        int[] slots = current.slotsFor(input);
        if (slots == null || !current.fluidMatches(input.fluid())) {
            // Inputs were taken out between the last check and now; look again next tick.
            inputsDirty = true;
            return;
        }
        for (int i = 0; i < slots.length; i++) {
            items.extractItem(slots[i], current.ingredients().get(i).count(), false);
        }
        current.fluidIngredient().ifPresent(fluid -> inputTank.drain(fluid.amount(), IFluidHandler.FluidAction.EXECUTE));
        for (ItemStack result : current.results()) {
            ItemStack rest = result.copy();
            for (int i = 0; i < MachineKind.OUTPUT_SLOTS && !rest.isEmpty(); i++) {
                rest = items.insertItem(kind.inputSlots() + i, rest, false);
            }
            // A sequenced variant (P7.5) credits the players running the machine.
            ResourceLocation variant = result.get(RemissionDataComponents.VARIANT);
            if (variant != null && kind == MachineKind.SEQUENCER && level instanceof ServerLevel serverLevel) {
                QuestProgress.variantSequenced(serverLevel, worldPosition, variant);
            }
        }
        if (!current.fluidResult().isEmpty()) {
            outputTank.fill(current.fluidResult().copy(), IFluidHandler.FluidAction.EXECUTE);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("items", items.serializeNBT(registries));
        tag.put("input_tank", inputTank.writeToNBT(registries, new CompoundTag()));
        tag.put("output_tank", outputTank.writeToNBT(registries, new CompoundTag()));
        tag.put("energy", energy.serializeNBT(registries));
        tag.putInt("progress", progress);
        ResourceLocation id = recipe != null ? recipe.id() : savedRecipe;
        if (id != null && progress > 0) {
            tag.putString("recipe", id.toString());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.deserializeNBT(registries, tag.getCompound("items"));
        inputTank.readFromNBT(registries, tag.getCompound("input_tank"));
        outputTank.readFromNBT(registries, tag.getCompound("output_tank"));
        if (tag.contains("energy")) {
            energy.deserializeNBT(registries, tag.get("energy"));
        }
        progress = tag.getInt("progress");
        savedRecipe = tag.contains("recipe") ? ResourceLocation.tryParse(tag.getString("recipe")) : null;
        recipe = null;
        inputsDirty = true;
    }

    @Override
    public Component getDisplayName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new MachineMenu(kind, containerId, inventory, items, data,
                ContainerLevelAccess.create(player.level(), getBlockPos()));
    }

    /** Values the menu syncs to the client; see {@link MachineMenu.Data}. */
    private final class MachineData implements ContainerData {
        @Override
        public int get(int index) {
            return MachineMenu.Data.half(index, value(index / 2));
        }

        private int value(int field) {
            return switch (field) {
                case MachineMenu.Data.PROGRESS -> progress;
                case MachineMenu.Data.TIME -> recipe == null ? 0 : recipe.value().time();
                case MachineMenu.Data.ENERGY -> energy.getEnergyStored();
                case MachineMenu.Data.INPUT_FLUID -> fluidId(inputTank.getFluid());
                case MachineMenu.Data.INPUT_AMOUNT -> inputTank.getFluidAmount();
                case MachineMenu.Data.OUTPUT_FLUID -> fluidId(outputTank.getFluid());
                case MachineMenu.Data.OUTPUT_AMOUNT -> outputTank.getFluidAmount();
                default -> 0;
            };
        }

        private static int fluidId(FluidStack stack) {
            Fluid fluid = stack.getFluid();
            return BuiltInRegistries.FLUID.getId(fluid);
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return MachineMenu.Data.COUNT;
        }
    }

    /** Energy buffer: other blocks may only insert; the machine uses it through {@link #use}. */
    private final class Energy extends EnergyStorage {
        Energy() {
            super(MachineKind.ENERGY_CAPACITY, MachineKind.MAX_ENERGY_INPUT, 0);
        }

        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            int received = super.receiveEnergy(toReceive, simulate);
            if (received > 0 && !simulate) {
                setChanged();
            }
            return received;
        }

        void use(int amount) {
            energy -= amount;
        }
    }

    /** Inputs accept items, outputs give them; nothing else. */
    private final class AutomationItems implements IItemHandler {
        @Override
        public int getSlots() {
            return items.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return items.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return slot < kind.inputSlots() ? items.insertItem(slot, stack, simulate) : stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return slot >= kind.inputSlots() ? items.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return items.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot < kind.inputSlots();
        }
    }

    /** Filling goes to the input tank and draining comes from the output tank. */
    private final class AutomationFluids implements IFluidHandler {
        @Override
        public int getTanks() {
            return 2;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            return (tank == 0 ? inputTank : outputTank).getFluid();
        }

        @Override
        public int getTankCapacity(int tank) {
            return MachineKind.TANK_CAPACITY;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return tank == 0;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            return inputTank.fill(resource, action);
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            return outputTank.drain(resource, action);
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            return outputTank.drain(maxDrain, action);
        }
    }
}
