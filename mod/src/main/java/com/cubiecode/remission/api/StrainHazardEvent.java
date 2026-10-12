package com.cubiecode.remission.api;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Asks whether a Strain hazard applies (P5.7), on behalf of {@link StrainHazards}: cancel it to hold the hazard back,
 * for example until a pack's stage. Posted on the server thread only when the hazard is not already ruled out: the
 * team holds no countermeasure for it and, for a block hazard, the presence rule ({@code [countermeasures]
 * blockHazards}) is met. A countermeasure therefore always overrides a listener; a listener can only take a hazard
 * away, never force it.
 *
 * <p>A player hazard (contact viral load, shedding) is asked for one player: {@link #getPlayer()} is present and
 * {@link #getPos()} empty. A block hazard (fouling, rot, containment breaches) is asked for a block: {@link #getLevel()}
 * and {@link #getPos()} are present, {@link #getPlacer()} names its placer when known, and {@link #getPlayer()} is the
 * player the presence rule found (the placer or an online teammate, or the nearest player for a block with no known
 * placer), empty under {@code ALWAYS} with nobody of the team online. Hazard systems may ask every tick, so listeners
 * must be cheap.
 */
public class StrainHazardEvent extends Event implements ICancellableEvent {
    private final MinecraftServer server;
    private final ResourceLocation hazard;
    @Nullable
    private final ServerPlayer player;
    @Nullable
    private final ServerLevel level;
    @Nullable
    private final BlockPos pos;
    @Nullable
    private final UUID placer;

    public StrainHazardEvent(MinecraftServer server, ResourceLocation hazard, @Nullable ServerPlayer player,
            @Nullable ServerLevel level, @Nullable BlockPos pos, @Nullable UUID placer) {
        this.server = server;
        this.hazard = hazard;
        this.player = player;
        this.level = level;
        this.pos = pos == null ? null : pos.immutable();
        this.placer = placer;
    }

    public MinecraftServer getServer() {
        return server;
    }

    /** The hazard's ID, such as {@code remission:contact}. */
    public ResourceLocation getHazard() {
        return hazard;
    }

    /** The player the hazard is asked for; always present for a player hazard. */
    public Optional<ServerPlayer> getPlayer() {
        return Optional.ofNullable(player);
    }

    /** The block's level, for a block hazard. */
    public Optional<ServerLevel> getLevel() {
        return Optional.ofNullable(level);
    }

    /** The block's position, for a block hazard. */
    public Optional<BlockPos> getPos() {
        return Optional.ofNullable(pos);
    }

    /** The block's placer, for a block hazard whose placer is known. */
    public Optional<UUID> getPlacer() {
        return Optional.ofNullable(placer);
    }

    /** Whether the hazard is asked for a block rather than a player. */
    public boolean isBlockHazard() {
        return pos != null;
    }
}
