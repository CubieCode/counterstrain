package com.cubiecode.remission.client;

import com.cubiecode.remission.world.NidusBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * Draws a nidus as its animated organism ({@link NidusModel}) with the glowing core and veins ({@link NidusGlowLayer}),
 * flashing red (the vanilla hurt overlay, glow included) for {@link NidusBlockEntity#HURT_TICKS} game ticks after a hit.
 * The model is far bigger than its block: about 4 blocks across with its tentacles and 2.7 tall at rest, and a lash
 * reaches up to 5.4 blocks out from the centre. Its render box covers that with a margin, and it is drawn as a global block entity
 * (off screen), so it does not vanish when its own block or chunk section leaves the view.
 */
public final class NidusRenderer extends GeoBlockRenderer<NidusBlockEntity> {
    /** Horizontal reach from the block's centre, in blocks: a lash's 5.4 plus a margin. */
    public static final double REACH = 6.0;
    /** Height above the block's bottom, in blocks: the rest pose's 2.7 plus room for the tentacles rearing to lash. */
    public static final double HEIGHT = 5.0;
    /** The buried mass reaches a little below the block. */
    public static final double DEPTH = 0.25;

    public NidusRenderer(BlockEntityRendererProvider.Context context) {
        super(new NidusModel());
        addRenderLayer(new NidusGlowLayer(this));
    }

    @Override
    public AABB getRenderBoundingBox(NidusBlockEntity nidus) {
        BlockPos pos = nidus.getBlockPos();
        double x = pos.getX() + 0.5;
        double z = pos.getZ() + 0.5;
        return new AABB(x - REACH, pos.getY() - DEPTH, z - REACH, x + REACH, pos.getY() + HEIGHT, z + REACH);
    }

    @Override
    public boolean shouldRenderOffScreen(NidusBlockEntity nidus) {
        return true;
    }

    /** The hurt overlay while the hit flash lasts, as a hurt mob's; none otherwise. */
    @Override
    public int getPackedOverlay(NidusBlockEntity nidus, float u, float partialTick) {
        return nidus.getLevel() != null && nidus.hurtFlash(nidus.getLevel().getGameTime())
                ? OverlayTexture.pack(OverlayTexture.u(u), true) : OverlayTexture.NO_OVERLAY;
    }

    /** A landmark: seen from further than the 64 blocks most block entities are. */
    @Override
    public int getViewDistance() {
        return 96;
    }
}
