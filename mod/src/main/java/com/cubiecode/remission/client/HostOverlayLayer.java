package com.cubiecode.remission.client;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.config.RemissionClientConfig;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Draws the Strain overlay on hosts: the parent renderer's own model, already posed, scaled and animated for this
 * frame, drawn again with the stage's pattern. Added to every {@link LivingEntityRenderer} (players included), so it
 * needs no per-entity-type code. Renderers that are not living-entity renderers, or that have no model, are skipped.
 */
public final class HostOverlayLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
    /** Patterns for stages 1, 2 and 3; higher stages (more thresholds in the common config) use the last. */
    private static final ResourceLocation[] STAGE_PATTERNS = {
            Remission.id("textures/entity/host/stage_1.png"),
            Remission.id("textures/entity/host/stage_2.png"),
            Remission.id("textures/entity/host/stage_3.png"),
    };
    /** Full-bright veins drawn on top from {@link #GLOW_STAGE}. */
    private static final ResourceLocation GLOW_PATTERN = Remission.id("textures/entity/host/veins_glow.png");
    private static final int GLOW_STAGE = 3;

    private HostOverlayLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    /** Adds the layer to every living-entity renderer and player skin renderer. */
    static void addLayers(EntityRenderersEvent.AddLayers event) {
        int added = 0;
        int skipped = 0;
        for (var type : event.getEntityTypes()) {
            if (attach(event.getRenderer(type))) {
                added++;
            } else {
                skipped++;
            }
        }
        for (var skin : event.getSkins()) {
            if (attach(event.getSkin(skin))) {
                added++;
            }
        }
        Remission.LOGGER.info("Host overlay added to {} renderers; {} entity renderers are not living-entity renderers",
                added, skipped);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean attach(EntityRenderer<?> renderer) {
        if (renderer instanceof LivingEntityRenderer living) {
            living.addLayer(new HostOverlayLayer<>(living));
            return true;
        }
        return false;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity, float limbSwing,
                       float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        CorruptionState state = Corruption.get(entity);
        if (!state.isHost() || entity.isInvisible() || !HostRenderTypes.ready()) {
            return;
        }
        double intensity = RemissionClientConfig.HOST_OVERLAY_INTENSITY.get();
        M model = getParentModel();
        ResourceLocation texture = getTextureLocation(entity);
        if (intensity <= 0.0 || model == null || texture == null) {
            return;
        }
        int color = FastColor.ARGB32.colorFromFloat((float) intensity, 1.0F, 1.0F, 1.0F);
        int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0.0F);
        ResourceLocation pattern = STAGE_PATTERNS[Math.min(state.stage(), STAGE_PATTERNS.length) - 1];
        model.renderToBuffer(poseStack, buffer.getBuffer(HostRenderTypes.hostOverlay(texture, pattern)),
                packedLight, overlay, color);
        if (state.stage() >= GLOW_STAGE) {
            model.renderToBuffer(poseStack, buffer.getBuffer(HostRenderTypes.hostOverlay(texture, GLOW_PATTERN)),
                    LightTexture.FULL_BRIGHT, overlay, color);
        }
    }
}
