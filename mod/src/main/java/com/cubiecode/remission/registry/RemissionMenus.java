package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.tech.MachineKind;
import com.cubiecode.remission.tech.MachineMenu;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Menu type registrations. */
public final class RemissionMenus {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(Registries.MENU, Remission.MOD_ID);

    // Machines (P7.2). No extra open data, so the client menu is built from the type alone.
    public static final Supplier<MenuType<MachineMenu>> ANALYZER = machine(MachineKind.ANALYZER);
    public static final Supplier<MenuType<MachineMenu>> SEQUENCER = machine(MachineKind.SEQUENCER);
    public static final Supplier<MenuType<MachineMenu>> SYNTHESIZER = machine(MachineKind.SYNTHESIZER);

    private RemissionMenus() {
    }

    private static Supplier<MenuType<MachineMenu>> machine(MachineKind kind) {
        return MENU_TYPES.register(kind.blockName(),
                () -> new MenuType<>((id, inventory) -> MachineMenu.client(kind, id, inventory), FeatureFlags.DEFAULT_FLAGS));
    }
}
