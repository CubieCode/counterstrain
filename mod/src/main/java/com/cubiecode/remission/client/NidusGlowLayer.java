package com.cubiecode.remission.client;

import com.cubiecode.remission.world.NidusBlock;
import com.cubiecode.remission.world.NidusBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * The nidus's emissive pass: its health stage's mask ({@link NidusModel#emissive}: the core, the veins and the root veins)
 * drawn again over the model at full brightness, while the purple flesh beneath stays lit by the world. The glow beats
 * with the heart: its strength follows the {@code core} bone's animated swell, so it keeps time with whichever loop is
 * playing (a slow double beat at idle, a fast hard one when enraged). Near death the core also flickers, dropping out
 * for a tick or two at a time. The glow takes the model's overlay, so it flashes red with the flesh after a hit.
 */
public final class NidusGlowLayer extends GeoRenderLayer<NidusBlockEntity> {
    /** Glow at rest, between beats. */
    private static final float REST = 0.55F;
    /** Core swell (scale above 1) that gives the full glow; the enraged beat reaches it. */
    private static final float FULL_SWELL = 0.25F;

    public NidusGlowLayer(GeoRenderer<NidusBlockEntity> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, NidusBlockEntity nidus, BakedGeoModel model, @Nullable RenderType renderType,
            MultiBufferSource buffers, @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        float glow = heartbeat(model) * flicker(nidus);
        RenderType glowType = RenderType.entityTranslucentEmissive(NidusModel.emissive(nidus));
        int colour = FastColor.ARGB32.colorFromFloat(glow, 1.0F, 1.0F, 1.0F);
        getRenderer().reRender(model, poseStack, buffers, nidus, glowType, buffers.getBuffer(glowType), partialTick,
                LightTexture.FULL_BRIGHT, packedOverlay, colour);
    }

    /** Glow strength from the core's current swell: {@link #REST} at rest, 1 at {@link #FULL_SWELL}. */
    static float heartbeat(BakedGeoModel model) {
        float swell = model.getBone("core").map(GeoBone::getScaleY).orElse(1.0F) - 1.0F;
        return REST + (1.0F - REST) * Mth.clamp(swell / FULL_SWELL, 0.0F, 1.0F);
    }

    /**
     * 1 unless the nidus is near death; then a steady glow broken by dips, from a hash of the animation clock (ticks
     * since it was first drawn, see {@link NidusModel}) in 2-tick steps and the position, so neighbouring nidi do not
     * flicker in step and a frozen scene shows the same frame every run.
     */
    static float flicker(NidusBlockEntity nidus) {
        if (nidus.healthStage() < NidusBlock.MAX_DAMAGE) {
            return 1.0F;
        }
        long step = (long) (nidus.getAnimatableInstanceCache().getManagerForId(0).getLastUpdateTime() / 2.0);
        int hash = Mth.murmurHash3Mixer((int) step * 31 + nidus.getBlockPos().hashCode());
        float noise = (hash & 0xFFFF) / 65535.0F;
        return noise < 0.2F ? 0.15F + noise : 0.8F + noise * 0.2F;
    }
}
