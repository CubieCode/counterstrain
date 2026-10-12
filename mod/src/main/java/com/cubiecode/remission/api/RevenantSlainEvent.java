package com.cubiecode.remission.api;

import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * A revenant died. Posted after its items and experience were dropped at its position, after it left the revenant
 * index and after the killer was awarded {@code remission:revenant_reclaimed} where it applies. Revenants take damage
 * only from players (and their projectiles) and {@code /kill}, so the killer is a player unless a command killed it.
 */
public class RevenantSlainEvent extends RevenantEvent {
    private final DamageSource source;
    @Nullable
    private final ServerPlayer killer;
    private final boolean reclaimed;

    public RevenantSlainEvent(LivingEntity revenant, UUID owner, DamageSource source, @Nullable ServerPlayer killer,
            boolean reclaimed) {
        super(revenant, owner);
        this.source = source;
        this.killer = killer;
        this.reclaimed = reclaimed;
    }

    public DamageSource getSource() {
        return source;
    }

    /** The player who killed it, or null (a command). */
    @Nullable
    public ServerPlayer getKiller() {
        return killer;
    }

    /** Whether the killer may pick up its items (its owner, or the owner's teammate by {@link TeamQueries}): a reclaim. */
    public boolean isReclaimed() {
        return reclaimed;
    }
}
