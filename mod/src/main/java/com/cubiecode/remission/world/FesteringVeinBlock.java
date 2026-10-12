package com.cubiecode.remission.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MultifaceSpreader;

/**
 * {@code remission:festering_vein} (P4.8): a multiface block, as vanilla glow lichen and sculk veins, with one boolean
 * property per face it covers. A face drops off when the block behind it can no longer hold it, and a vein with no face
 * left turns to air. It never spreads on its own: {@link FesteringVeins} grows it from the chunk's festering stage.
 */
public final class FesteringVeinBlock extends MultifaceBlock {
    public static final MapCodec<FesteringVeinBlock> CODEC = simpleCodec(FesteringVeinBlock::new);
    private final MultifaceSpreader spreader = new MultifaceSpreader(this);

    public FesteringVeinBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<FesteringVeinBlock> codec() {
        return CODEC;
    }

    /** Used by vanilla placement helpers only; veins grow through {@link FesteringVeins}, not by spreading. */
    @Override
    public MultifaceSpreader getSpreader() {
        return spreader;
    }
}
