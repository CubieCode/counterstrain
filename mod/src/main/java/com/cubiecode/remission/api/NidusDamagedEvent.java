package com.cubiecode.remission.api;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.ICancellableEvent;
import org.jetbrains.annotations.Nullable;

/**
 * A nidus is about to take damage to its vitality (P4.10). Posted before the damage is applied, with the amount after
 * the configured projectile or explosion fraction. Cancelling it (or setting the amount to 0) means the nidus takes no
 * damage and the player earns no credit for it; changing the amount changes what it takes, so a pack can make a
 * prepared item deal more or protect a story nidus. Not posted while {@code [nidi] vitality} is 0 (nidi are mined).
 *
 * <p>Only a {@link Kind#STRIKE} can destroy a nidus: projectile and explosion damage stops at 1 vitality, whatever the
 * amount.
 */
public class NidusDamagedEvent extends FesteringLandEvent implements ICancellableEvent {
    /** How the nidus is damaged. */
    public enum Kind {
        /** A player struck it (left-click), with the held item's attack damage scaled by attack strength. */
        STRIKE,
        /** An arrow or trident hit it. */
        PROJECTILE,
        /** An explosion went off near it. */
        EXPLOSION
    }

    private final BlockPos pos;
    private final Kind kind;
    @Nullable
    private final ServerPlayer player;
    @Nullable
    private final Entity directEntity;
    private float amount;

    public NidusDamagedEvent(ServerLevel level, BlockPos pos, Kind kind, @Nullable ServerPlayer player,
                             @Nullable Entity directEntity, float amount) {
        super(level);
        this.pos = pos.immutable();
        this.kind = kind;
        this.player = player;
        this.directEntity = directEntity;
        this.amount = amount;
    }

    /** Position of the nidus block. */
    public BlockPos getPos() {
        return pos;
    }

    public Kind getKind() {
        return kind;
    }

    /**
     * The player credited with the damage: the striker, the projectile's owner or the player who caused the explosion
     * (for example who lit the TNT). Empty when no player is behind it.
     */
    public Optional<ServerPlayer> getPlayer() {
        return Optional.ofNullable(player);
    }

    /** The projectile, or the explosion's direct source (such as the primed TNT); null for a strike or an unknown source. */
    @Nullable
    public Entity getDirectEntity() {
        return directEntity;
    }

    /** Damage to vitality, after the projectile or explosion fraction. */
    public float getAmount() {
        return amount;
    }

    /** Sets the damage to vitality; negative values and NaN become 0. */
    public void setAmount(float amount) {
        this.amount = Float.isNaN(amount) ? 0.0F : Math.max(0.0F, amount);
    }
}
