package com.cubiecode.remission.revenant;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CorruptionCause;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.RevenantPickupEvent;
import com.cubiecode.remission.api.RevenantRisenEvent;
import com.cubiecode.remission.api.RevenantSlainEvent;
import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.cubiecode.remission.quest.QuestProgress;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionEntityTypes;
import com.cubiecode.remission.viralload.ViralLoad;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Revenants (P5.3): which player deaths raise one, the rise itself, the owner-only pickup of what a slain revenant drops
 * and its timeout. A player's Strain death is noted on {@link LivingDeathEvent}, its experience drop and item drops
 * (vanilla inventory, armour and offhand, plus whatever other mods add to {@link LivingDropsEvent}, Curios slots
 * included) are taken at {@link EventPriority#LOWEST}, and a revenant rises with them; other deaths and
 * {@code keepInventory} are left alone.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class Revenants {
    /** Ticks after a slain revenant's drop before even its owner can pick it up (vanilla's pickup delay). */
    public static final int PICKUP_DELAY_TICKS = 10;
    private static final int FREE_CHECK_TICKS = 20;

    /**
     * Strain deaths noted on {@link LivingDeathEvent}, waiting for their drops in the same call stack. An entry is valid
     * only in the game tick it was noted: a death a later listener cancelled leaves one behind, and the player's next
     * death must not use it.
     */
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private Revenants() {
    }

    private static final class Pending {
        final DamageSource source;
        final BlockPos pos;
        final float viralLoad;
        final Map<EquipmentSlot, ItemStack> worn = new EnumMap<>(EquipmentSlot.class);
        final long gameTime;
        int experience;

        Pending(ServerPlayer player, DamageSource source) {
            this.source = source;
            this.gameTime = player.serverLevel().getGameTime();
            this.pos = player.blockPosition();
            this.viralLoad = ViralLoad.get(player);
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (slot.isArmor() || slot == EquipmentSlot.MAINHAND) {
                    ItemStack stack = player.getItemBySlot(slot);
                    if (!stack.isEmpty()) {
                        worn.put(slot, stack.copy());
                    }
                }
            }
        }
    }

    // ---- which deaths ------------------------------------------------------------------------------------------------

    /**
     * Whether {@code player}'s death from {@code source} is a Strain death: the killer (the damage's responsible entity, or
     * the player's kill credit) is a host or a revenant, the damage is viral load, or the player is in a chunk at
     * festering stage 1 or higher, at any height. {@code keepInventory} is not checked here.
     */
    public static boolean isStrainDeath(ServerPlayer player, DamageSource source) {
        if (source.is(ViralLoad.DAMAGE_TYPE)) {
            return true;
        }
        if (isStrainKiller(source.getEntity()) || isStrainKiller(player.getKillCredit())) {
            return true;
        }
        return CorruptionQueries.stageAt(player.serverLevel(), player.blockPosition()) >= 1;
    }

    private static boolean isStrainKiller(@Nullable Entity killer) {
        return killer instanceof Revenant || CorruptionQueries.isHost(killer);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!event.isCanceled() && !player.isSpectator()
                && !player.serverLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)
                && isStrainDeath(player, event.getSource())) {
            PENDING.put(player.getUUID(), new Pending(player, event.getSource()));
        } else {
            PENDING.remove(player.getUUID());
        }
    }

    /** The player's pending Strain death if it was noted this tick; a stale entry is dropped. */
    @Nullable
    private static Pending pending(ServerPlayer player, boolean remove) {
        Pending pending = remove ? PENDING.remove(player.getUUID()) : PENDING.get(player.getUUID());
        if (pending != null && pending.gameTime != player.serverLevel().getGameTime()) {
            PENDING.remove(player.getUUID());
            return null;
        }
        return pending;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onExperienceDrop(LivingExperienceDropEvent event) {
        Pending pending = event.getEntity() instanceof ServerPlayer player ? pending(player, false) : null;
        if (pending != null) {
            pending.experience = event.getDroppedExperience();
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    static void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Pending pending = pending(player, true);
        if (pending == null) {
            return;
        }
        ServerLevel level = player.serverLevel();
        if (event.isCanceled()) {
            // Another mod (a grave) took the items; give the experience back as vanilla would have dropped it.
            ExperienceOrb.award(level, player.position(), pending.experience);
            return;
        }
        List<ItemStack> items = new ArrayList<>();
        for (ItemEntity drop : event.getDrops()) {
            if (!drop.getItem().isEmpty()) {
                items.add(drop.getItem().copy());
            }
        }
        if (items.isEmpty() && pending.experience <= 0) {
            return;
        }
        if (rise(level, player, pending.source, pending.pos, items, pending.experience, pending.viralLoad, pending.worn) != null) {
            event.setCanceled(true);
        } else {
            // No revenant joined the level (a join listener refused it): the items drop as vanilla, and so does the
            // experience already taken from the drop.
            ExperienceOrb.award(level, player.position(), pending.experience);
        }
    }

    /** A death whose drops never came (another mod stopped the loot) leaves nothing behind once the player respawns. */
    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }

    // ---- rising ------------------------------------------------------------------------------------------------------

    /**
     * Raises a revenant of {@code player} at {@code deathPos} holding {@code items} and {@code experience}, wearing copies
     * of the {@code worn} armour and main-hand item that are among {@code items}. Its Strain level is the player's viral
     * load at death (at least the first host level), so a death to viral load rises at the highest host stage. Adds it to
     * the index and posts {@link RevenantRisenEvent}. Returns null, with nothing indexed or posted, if the entity could not
     * be created or did not join the level ({@code addFreshEntity} refused it: a cancelled {@code EntityJoinLevelEvent} or
     * a duplicate UUID).
     */
    @Nullable
    public static Revenant rise(ServerLevel level, ServerPlayer player, DamageSource source, BlockPos deathPos,
            List<ItemStack> items, int experience, float viralLoad, Map<EquipmentSlot, ItemStack> worn) {
        Revenant revenant = RemissionEntityTypes.REVENANT.get().create(level);
        if (revenant == null) {
            return null;
        }
        BlockPos home = riseSpot(level, deathPos);
        revenant.setOwner(player.getUUID(), player.getGameProfile().getName());
        revenant.moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, player.getYRot(), 0.0F);
        revenant.setYHeadRot(player.getYRot());
        revenant.setHome(home);
        revenant.setContents(items, experience);
        for (Map.Entry<EquipmentSlot, ItemStack> entry : worn.entrySet()) {
            if (items.stream().anyMatch(stack -> ItemStack.matches(stack, entry.getValue()))) {
                revenant.setItemSlot(entry.getKey(), entry.getValue().copy());
            }
        }
        if (!level.addFreshEntity(revenant)) {
            return null;
        }
        int strain = Math.max(Math.round(viralLoad), firstHostLevel());
        Corruption.set(revenant, strain, CorruptionCause.REVENANT);
        indexed(level, revenant);
        NeoForge.EVENT_BUS.post(new RevenantRisenEvent(revenant, player, source, deathPos, items, experience));
        return revenant;
    }

    /**
     * Where a revenant of a death at {@code deathPos} rises, always a spot of this level: the death position when it is
     * at or above the level's minimum height; below the world, the top of that column ({@code MOTION_BLOCKING}) when the
     * column has any block; in an empty column, the End's obsidian platform spawn ({@link ServerLevel#END_SPAWN_POINT}) in
     * the End, the world spawn in the overworld, and otherwise one block above the level's minimum height in the death
     * column (a revenant whose home has nothing below it hovers there, {@link Revenant#returnHome}).
     */
    public static BlockPos riseSpot(ServerLevel level, BlockPos deathPos) {
        if (deathPos.getY() >= level.getMinBuildHeight()) {
            return deathPos;
        }
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, deathPos);
        if (surface.getY() > level.getMinBuildHeight()) {
            return surface;
        }
        if (level.dimension() == Level.END) {
            return ServerLevel.END_SPAWN_POINT;
        }
        if (level.dimension() == Level.OVERWORLD) {
            return level.getSharedSpawnPos();
        }
        return new BlockPos(deathPos.getX(), level.getMinBuildHeight() + 1, deathPos.getZ());
    }

    private static int firstHostLevel() {
        List<? extends Integer> thresholds = RemissionCommonConfig.STAGE_THRESHOLDS.get();
        return thresholds.isEmpty() ? 1 : Math.max(1, Math.min(thresholds.getFirst(), CorruptionState.MAX_LEVEL));
    }

    /** Records the revenant's current position in its level's index. */
    static void indexed(ServerLevel level, Revenant revenant) {
        UUID owner = revenant.owner();
        if (owner != null) {
            RevenantIndex.get(level).put(revenant.getUUID(), owner, revenant.ownerName(), revenant.blockPosition());
        }
    }

    // ---- slain -------------------------------------------------------------------------------------------------------

    /** The revenant died and dropped its items and experience; awards a reclaim and posts {@link RevenantSlainEvent}. */
    static void slain(ServerLevel level, Revenant revenant, DamageSource source) {
        RevenantIndex.get(level).remove(revenant.getUUID());
        UUID owner = revenant.owner();
        if (owner == null) {
            return;
        }
        ServerPlayer killer = source.getEntity() instanceof ServerPlayer player ? player : null;
        boolean reclaimed = killer != null && mayReclaim(killer, owner, null);
        if (reclaimed) {
            QuestProgress.revenantReclaimed(killer, killer.getUUID().equals(owner));
        }
        NeoForge.EVENT_BUS.post(new RevenantSlainEvent(revenant, owner, source, killer, reclaimed));
    }

    // ---- pickup ------------------------------------------------------------------------------------------------------

    /** Whether {@code player} may collect what a revenant of {@code owner} holds ({@link RevenantPickupEvent}). */
    public static boolean mayReclaim(Player player, UUID owner, @Nullable ItemEntity item) {
        return NeoForge.EVENT_BUS.post(new RevenantPickupEvent(player, owner, item)).isAllowed();
    }

    /** Restricts a dropped item to {@code owner} until the pickup timeout; mobs never pick it up, and it never despawns. */
    static void restrict(ItemEntity item, UUID owner) {
        long now = item.level().getGameTime();
        int timeout = RemissionCommonConfig.REVENANT_PICKUP_TIMEOUT_TICKS.get();
        item.setData(RemissionAttachments.REVENANT_DROP, new RevenantDrop(owner, now, now + timeout));
        item.setNeverPickUp();
        item.setUnlimitedLifetime();
    }

    /** Lifts the restriction: anyone may pick it up and it despawns as a freshly dropped item would, a little later. */
    static void free(ItemEntity item) {
        item.removeData(RemissionAttachments.REVENANT_DROP);
        item.setPickUpDelay(0);
        item.setExtendedLifetime();
    }

    @Nullable
    public static RevenantDrop restriction(ItemEntity item) {
        return item.getExistingDataOrNull(RemissionAttachments.REVENANT_DROP);
    }

    @SubscribeEvent
    static void onItemPickup(ItemEntityPickupEvent.Pre event) {
        ItemEntity item = event.getItemEntity();
        RevenantDrop drop = restriction(item);
        if (drop == null || item.level().isClientSide) {
            return;
        }
        long now = item.level().getGameTime();
        if (now >= drop.freeAt()) {
            free(item);
            return;
        }
        boolean allowed = now - drop.droppedAt() >= PICKUP_DELAY_TICKS && mayReclaim(event.getPlayer(), drop.owner(), item);
        event.setCanPickup(allowed ? TriState.TRUE : TriState.FALSE);
    }

    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof ItemEntity item && item.tickCount % FREE_CHECK_TICKS == 0 && !item.level().isClientSide) {
            RevenantDrop drop = restriction(item);
            if (drop != null && item.level().getGameTime() >= drop.freeAt()) {
                free(item);
            }
        }
    }

    @SubscribeEvent
    static void createAttributes(EntityAttributeCreationEvent event) {
        event.put(RemissionEntityTypes.REVENANT.get(), Revenant.createAttributes().build());
    }
}
