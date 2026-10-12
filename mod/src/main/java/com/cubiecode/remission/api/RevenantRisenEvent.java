package com.cubiecode.remission.api;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * A player died to the Strain and a revenant rose holding the items and experience the death would have dropped. Posted
 * after the revenant joined the level as a host and was added to the revenant index, so {@link CorruptionQueries}
 * already reports it.
 */
public class RevenantRisenEvent extends RevenantEvent {
    private final ServerPlayer player;
    private final DamageSource source;
    private final BlockPos deathPos;
    private final List<ItemStack> items;
    private final int experience;

    public RevenantRisenEvent(LivingEntity revenant, ServerPlayer player, DamageSource source, BlockPos deathPos,
            List<ItemStack> items, int experience) {
        super(revenant, player.getUUID());
        this.player = player;
        this.source = source;
        this.deathPos = deathPos.immutable();
        this.items = items.stream().map(ItemStack::copy).toList();
        this.experience = experience;
    }

    /** The player who died (dead at this point, not yet respawned). */
    public ServerPlayer getPlayer() {
        return player;
    }

    /** The damage that killed the player. */
    public DamageSource getSource() {
        return source;
    }

    /** Where the player died; the revenant stays near it (below the world, the revenant rose at the surface instead). */
    public BlockPos getDeathPos() {
        return deathPos;
    }

    /** Copies of the items the revenant holds, as the death would have dropped them (Curios slots included). */
    public List<ItemStack> getItems() {
        return items;
    }

    /** Experience points the revenant holds, as the death would have dropped them. */
    public int getExperience() {
        return experience;
    }
}
