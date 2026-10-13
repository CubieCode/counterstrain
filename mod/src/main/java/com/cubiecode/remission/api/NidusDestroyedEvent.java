package com.cubiecode.remission.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * A nidus stopped existing and left the level's index, so its spread has stopped. Posted after removal, whether the
 * block was broken, replaced or edited away (an index entry found without its block on a spread pass also counts). Land
 * it festered keeps its level. Not cancellable; to protect a nidus, cancel {@link NidusDamagedEvent} (and, for creative
 * players or plain mining, the block break).
 *
 * <p>When a player destroyed it (the final blow of a fight, P4.10, or a creative or plain-mining break), {@link #getPlayer()}
 * is that player and {@link #getContributors()} every player who damaged it within {@code [nidi] contributorWindowTicks}
 * plus that player; every contributor online earns {@code remission:nidus_destroyed}. Other removals have neither.
 *
 * <p>When the block was removed, this is posted from the nidus block's {@code onRemove}, inside the level's
 * {@code setBlockState} for that position: listeners must not set the block at {@link #getPos()} (defer it to a later
 * tick). Other positions are safe.
 */
public class NidusDestroyedEvent extends FesteringLandEvent {
    private final BlockPos pos;
    @Nullable
    private final ServerPlayer player;
    private final List<UUID> contributors;

    public NidusDestroyedEvent(ServerLevel level, BlockPos pos) {
        this(level, pos, null, List.of());
    }

    public NidusDestroyedEvent(ServerLevel level, BlockPos pos, @Nullable ServerPlayer player, List<UUID> contributors) {
        super(level);
        this.pos = pos.immutable();
        this.player = player;
        this.contributors = List.copyOf(contributors);
    }

    /** Position of the nidus block that was removed. */
    public BlockPos getPos() {
        return pos;
    }

    /** The player who destroyed it (landed the final blow, or broke it); empty for any other removal. */
    public Optional<ServerPlayer> getPlayer() {
        return Optional.ofNullable(player);
    }

    /**
     * Every player credited with destroying it, in the order they first damaged it, the destroying player included;
     * they may be offline. Empty when no player destroyed it. Immutable.
     */
    public List<UUID> getContributors() {
        return contributors;
    }
}
