package com.cubiecode.remission.client;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.world.NidusBlock;
import com.cubiecode.remission.world.NidusBlockEntity;
import com.cubiecode.remission.world.NidusTentacles;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.model.GeoModel;

/**
 * The nidus's GeckoLib model (the Brood Crown, P4.19): one geometry and one animation file for every health stage, with
 * the texture and emissive mask chosen by stage ({@link NidusBlockEntity#healthStage()}); the block entity picks each
 * stage's loops by name.
 *
 * <p>Each tentacle's base bone turns by its aim ({@link NidusTentacles.Aim}) on top of its rest yaw; no animation keys
 * the bases, so the lash, flinch and loops bend the segments in whatever direction the base faces.
 *
 * <p>Animations run on the level's game time plus the partial tick rather than GeckoLib's wall clock, so they pause with
 * the game and under {@code /tick freeze} (harness scenes then shoot the same frame every run).
 */
public final class NidusModel extends GeoModel<NidusBlockEntity> {
    private static final String[] STAGES = {"healthy", "lightly_wounded", "badly_wounded", "near_death"};
    private static final ResourceLocation GEOMETRY = Remission.id("geo/block/nidus.geo.json");
    private static final ResourceLocation ANIMATIONS = Remission.id("animations/block/nidus.animation.json");
    private static final ResourceLocation[] TEXTURES = resources("textures/block/nidus_", ".png");
    private static final ResourceLocation[] EMISSIVE = resources("textures/block/nidus_", "_emissive.png");
    /**
     * Sign from an aim (degrees, positive toward east of north) to a base bone's Y rotation: GeckoLib's bones turn the
     * other way, so a tentacle resting at bearing b has rest rotation -b.
     */
    static final float AIM_TO_BONE = -1.0F;

    private static ResourceLocation[] resources(String prefix, String suffix) {
        ResourceLocation[] locations = new ResourceLocation[STAGES.length];
        for (int i = 0; i < STAGES.length; i++) {
            locations[i] = Remission.id(prefix + STAGES[i] + suffix);
        }
        return locations;
    }

    private static int stage(NidusBlockEntity nidus) {
        return Math.clamp(nidus.healthStage(), 0, NidusBlock.MAX_DAMAGE);
    }

    @Override
    public ResourceLocation getModelResource(NidusBlockEntity nidus) {
        return GEOMETRY;
    }

    @Override
    public ResourceLocation getTextureResource(NidusBlockEntity nidus) {
        return TEXTURES[stage(nidus)];
    }

    /** The emissive mask for the nidus's health stage: the glowing core, veins and roots, transparent elsewhere. */
    public static ResourceLocation emissive(NidusBlockEntity nidus) {
        return EMISSIVE[stage(nidus)];
    }

    @Override
    public ResourceLocation getAnimationResource(NidusBlockEntity nidus) {
        return ANIMATIONS;
    }

    /** Sets the animation clock (see the class comment) in place of the wall-clock tick the renderer set. */
    @Override
    public void handleAnimations(NidusBlockEntity nidus, long instanceId, AnimationState<NidusBlockEntity> state,
            float partialTick) {
        if (nidus.getLevel() != null) {
            state.setData(DataTickets.TICK, nidus.getLevel().getGameTime() + (double) frame());
        }
        super.handleAnimations(nidus, instanceId, state, partialTick);
    }

    /** Turns each tentacle's base by its aim, from its rest rotation (never from last frame's, so turns never add up). */
    @Override
    public void setCustomAnimations(NidusBlockEntity nidus, long instanceId, AnimationState<NidusBlockEntity> state) {
        float frame = frame();
        for (int tentacle = 0; tentacle < NidusTentacles.COUNT; tentacle++) {
            GeoBone base = getAnimationProcessor().getBone("tentacle_" + NidusTentacles.NAMES.get(tentacle));
            if (base != null) {
                base.setRotY(base.getInitialSnapshot().getRotY() + AIM_TO_BONE * nidus.aim().aim(tentacle, frame) * Mth.DEG_TO_RAD);
            }
        }
    }

    private static float frame() {
        return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
    }
}
