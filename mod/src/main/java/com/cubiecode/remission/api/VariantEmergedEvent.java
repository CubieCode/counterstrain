package com.cubiecode.remission.api;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.ICancellableEvent;
import org.jetbrains.annotations.Nullable;

/**
 * A nidus's Strain is about to become a variant (P7.5): the pressure of one method at the nidus passed that variant's
 * threshold and share, or a command set it. Posted before the change; cancelling it keeps the nidus as it was, and for
 * a pressure emergence clears that variant's pressure there, so it has to build again. {@link #getVariant()} is the id of
 * a {@code remission:variant} entry.
 */
public class VariantEmergedEvent extends FesteringLandEvent implements ICancellableEvent {
    /** What made the variant emerge. */
    public enum Cause {
        /** One method was overused near the nidus. */
        PRESSURE,
        /** {@code /remission variant set}. */
        COMMAND,
        /** Any other caller. */
        OTHER
    }

    private final BlockPos nidus;
    private final ResourceLocation variant;
    @Nullable
    private final ResourceLocation previous;
    private final Cause cause;

    public VariantEmergedEvent(ServerLevel level, BlockPos nidus, ResourceLocation variant, @Nullable ResourceLocation previous,
                               Cause cause) {
        super(level);
        this.nidus = nidus.immutable();
        this.variant = variant;
        this.previous = previous;
        this.cause = cause;
    }

    /** Position of the nidus whose Strain adapts. */
    public BlockPos getNidus() {
        return nidus;
    }

    /** The variant that emerges. */
    public ResourceLocation getVariant() {
        return variant;
    }

    /** The variant the nidus carried before, if any. */
    public Optional<ResourceLocation> getPreviousVariant() {
        return Optional.ofNullable(previous);
    }

    public Cause getCause() {
        return cause;
    }
}
