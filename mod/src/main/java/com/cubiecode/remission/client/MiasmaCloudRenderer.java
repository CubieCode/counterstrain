package com.cubiecode.remission.client;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.surge.MiasmaCloud;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import java.util.Arrays;
import java.util.Comparator;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a miasma cloud as {@link #PUFFS} camera-facing translucent puffs spread through its area. The layout is a fixed
 * function of the radius (no randomness and nothing tied to wall-clock time or entity ids), so frozen harness scenes
 * stay byte-identical. Puffs write colour only, not depth, and are drawn back to front so they blend into one another.
 */
final class MiasmaCloudRenderer extends EntityRenderer<MiasmaCloud> {
    private static final ResourceLocation TEXTURE = Remission.id("textures/entity/miasma_cloud.png");
    private static final int PUFFS = 18;
    private static final float GOLDEN_ANGLE = (float) (Math.PI * (3 - Math.sqrt(5)));
    /** Each puff: offset as a fraction of the radius (x, z) and height (y), width as a fraction of the radius, roll. */
    private static final float[][] LAYOUT = new float[PUFFS][];

    static {
        for (int i = 0; i < PUFFS; i++) {
            float distance = (float) Math.sqrt((i + 0.5) / PUFFS) * 0.72F;
            float angle = i * GOLDEN_ANGLE;
            float height = 0.35F + 0.4F * (((i * 7) % PUFFS) / (float) PUFFS);
            float size = 0.9F + 0.4F * (((i * 5) % PUFFS) / (float) PUFFS);
            LAYOUT[i] = new float[] {Mth.cos(angle) * distance, height, Mth.sin(angle) * distance, size, angle * 57.3F};
        }
    }

    private static final RenderType PUFF = RenderType.create(Remission.MOD_ID + "_miasma_cloud",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(TEXTURE, false, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setOverlayState(RenderStateShard.OVERLAY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    MiasmaCloudRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.0F;
    }

    @Override
    public void render(MiasmaCloud cloud, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                       int packedLight) {
        float radius = cloud.getRadius();
        float height = MiasmaCloud.HEIGHT;
        Vec3 camera = entityRenderDispatcher.camera.getPosition();
        Vec3 base = cloud.getPosition(partialTick);
        Integer[] order = new Integer[PUFFS];
        Arrays.setAll(order, i -> i);
        // Back to front from the camera.
        Arrays.sort(order, Comparator.comparingDouble((Integer i) -> -camera.distanceToSqr(
                base.x + LAYOUT[i][0] * radius, base.y + LAYOUT[i][1] * height, base.z + LAYOUT[i][2] * radius)));
        VertexConsumer consumer = buffers.getBuffer(PUFF);
        for (int i : order) {
            float[] puff = LAYOUT[i];
            poseStack.pushPose();
            poseStack.translate(puff[0] * radius, puff[1] * height, puff[2] * radius);
            poseStack.mulPose(entityRenderDispatcher.cameraOrientation());
            poseStack.mulPose(Axis.ZP.rotationDegrees(puff[4]));
            float size = puff[3] * radius;
            poseStack.scale(size, size, size);
            PoseStack.Pose pose = poseStack.last();
            vertex(consumer, pose, packedLight, -0.5F, -0.5F, 0, 1);
            vertex(consumer, pose, packedLight, 0.5F, -0.5F, 1, 1);
            vertex(consumer, pose, packedLight, 0.5F, 0.5F, 1, 0);
            vertex(consumer, pose, packedLight, -0.5F, 0.5F, 0, 0);
            poseStack.popPose();
        }
        super.render(cloud, yaw, partialTick, poseStack, buffers, packedLight);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, int light, float x, float y, float u, float v) {
        consumer.addVertex(pose, x, y, 0.0F)
                .setColor(255, 255, 255, 200)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    @Override
    public ResourceLocation getTextureLocation(MiasmaCloud cloud) {
        return TEXTURE;
    }
}
