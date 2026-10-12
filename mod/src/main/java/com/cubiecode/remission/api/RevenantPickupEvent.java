package com.cubiecode.remission.api;

import java.util.UUID;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

/**
 * May {@link #getEntity() this player} collect what a revenant of {@link #getOwner() that owner} holds? Asked when a
 * player touches an item a slain revenant dropped, before its timeout ({@code [revenants] pickupTimeoutTicks}) frees
 * it for anyone, and when a player kills a revenant (whether that counts as reclaiming it). Other systems ask it too,
 * without an item. Allowed by default only for the owner; listeners widen it (a pack's teammates) or narrow it with
 * {@link #setAllowed}. Server side, not cancellable.
 */
public class RevenantPickupEvent extends PlayerEvent {
    private final UUID owner;
    @Nullable
    private final ItemEntity item;
    private boolean allowed;

    public RevenantPickupEvent(Player player, UUID owner, @Nullable ItemEntity item) {
        super(player);
        this.owner = owner;
        this.item = item;
        this.allowed = player.getUUID().equals(owner);
    }

    /** UUID of the player the revenant rose from. */
    public UUID getOwner() {
        return owner;
    }

    /** Whether the player asking is the owner. */
    public boolean isOwner() {
        return getEntity().getUUID().equals(owner);
    }

    /** The dropped item being touched, or null when the question is not about one item. */
    @Nullable
    public ItemEntity getItem() {
        return item;
    }

    public boolean isAllowed() {
        return allowed;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
    }
}
