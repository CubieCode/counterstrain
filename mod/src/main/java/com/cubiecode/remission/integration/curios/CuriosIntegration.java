package com.cubiecode.remission.integration.curios;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;
import top.theillusivec4.curios.api.type.ISlotType;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.common.data.CuriosEntityManager;
import top.theillusivec4.curios.common.data.CuriosSlotManager;

/**
 * Optional Curios integration (P5.3). Revenants need no Curios hook to hold Curios items: Curios adds a dying player's
 * slot items to NeoForge's {@code LivingDropsEvent} at its highest priority, honouring each item's drop rule and
 * {@code keepInventory}, and {@code Revenants} takes the drops at the lowest priority, so they are among the items a
 * revenant holds. This class is the only code that touches Curios types: it gives the gametests a slot to fill. Curios is
 * {@code compileOnly}; callers check {@link #isLoaded()} ({@code ModList}) first, so nothing here loads without it.
 */
public final class CuriosIntegration {
    public static final String MOD_ID = "curios";

    private CuriosIntegration() {
    }

    public static boolean isLoaded() {
        return net.neoforged.fml.ModList.get().isLoaded(MOD_ID);
    }

    /**
     * Gametests only: gives players the Curios slot type {@code slot} (such as {@code ring}) until the returned handle is
     * closed. Curios gives players no slots unless a datapack or its config assigns them, and Remission ships none, so the
     * test adds one to Curios' loaded entity slot map (a private field of its entity manager) and restores the map after.
     */
    public static AutoCloseable assignPlayerSlot(String slot) throws ReflectiveOperationException {
        ISlotType type = CuriosSlotManager.SERVER.getSlot(slot).orElseThrow(() -> new IllegalArgumentException(slot));
        Field field = CuriosEntityManager.class.getDeclaredField("entitySlots");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<EntityType<?>, Map<String, ISlotType>> before = (Map<EntityType<?>, Map<String, ISlotType>>) field.get(CuriosEntityManager.SERVER);
        Map<EntityType<?>, Map<String, ISlotType>> with = new HashMap<>(before);
        Map<String, ISlotType> playerSlots = new HashMap<>(before.getOrDefault(EntityType.PLAYER, Map.of()));
        playerSlots.put(slot, type);
        with.put(EntityType.PLAYER, playerSlots);
        field.set(CuriosEntityManager.SERVER, with);
        return () -> field.set(CuriosEntityManager.SERVER, before);
    }

    /** Puts {@code stack} into the first slot of type {@code slot} the entity has. Returns whether it was equipped. */
    public static boolean equip(LivingEntity entity, String slot, ItemStack stack) {
        ICuriosItemHandler handler = CuriosApi.getCuriosInventory(entity).orElse(null);
        if (handler == null) {
            return false;
        }
        if (handler.getStacksHandler(slot).isEmpty()) {
            // An entity that never joined a level (a gametest player) has not built its slots yet.
            handler.reset();
        }
        return handler.getStacksHandler(slot).filter(stacks -> stacks.getSlots() > 0).map(stacks -> {
            stacks.getStacks().setStackInSlot(0, stack.copy());
            return true;
        }).orElse(false);
    }

    /** Copies of every non-empty item in the entity's Curios slots. */
    public static List<ItemStack> equipped(LivingEntity entity) {
        return CuriosApi.getCuriosInventory(entity)
                .map(handler -> handler.findCurios(stack -> !stack.isEmpty()).stream()
                        .map(SlotResult::stack).map(ItemStack::copy).toList())
                .orElse(List.of());
    }
}
