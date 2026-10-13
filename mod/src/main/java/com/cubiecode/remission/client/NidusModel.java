package com.cubiecode.remission.client;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.world.NidusBlock;
import com.cubiecode.remission.world.NidusBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.model.GeoModel;

/**
 * The nidus's GeckoLib model: geometry, texture and emissive mask chosen together from its health stage
 * ({@link NidusBlockEntity#healthStage()}), so a stage never shows another stage's skin. Every stage has the same bones,
 * so the animations carry over a stage change; near death has its own, weaker idle and enraged loops.
 *
 * <p>Animations run on the level's game time plus the partial tick rather than GeckoLib's wall clock, so they pause with
 * the game and under {@code /tick freeze} (harness scenes then shoot the same frame every run).
 */
public final class NidusModel extends GeoModel<NidusBlockEntity> {
    private static final String[] STAGES = {"healthy", "lightly_wounded", "badly_wounded", "near_death"};
    private static final ResourceLocation[] GEOMETRY = resources("geo/block/nidus_", ".geo.json");
    private static final ResourceLocation[] TEXTURES = resources("textures/block/nidus_", ".png");
    private static final ResourceLocation[] EMISSIVE = resources("textures/block/nidus_", "_emissive.png");
    private static final ResourceLocation ANIMATIONS = Remission.id("animations/block/nidus.animation.json");
    private static final ResourceLocation NEAR_DEATH_ANIMATIONS = Remission.id("animations/block/nidus_near_death.animation.json");

    private static ResourceLocation[] resources(String prefix, String suffix) {
        ResourceLocation[] locations = new ResourceLocation[STAGES.length];
        for (int i = 0; i < STAGES.length; i++) {
            locations[i] = Remission.id(prefix + STAGES[i] + suffix);
        }
        return locations;
    }

    private static int stage(NidusBlockEntity nidus) {
        return Math.clamp(nidus.healthStage(), 0, STAGES.length - 1);
    }

    @Override
    public ResourceLocation getModelResource(NidusBlockEntity nidus) {
        return GEOMETRY[stage(nidus)];
    }

    @Override
    public ResourceLocation getTextureResource(NidusBlockEntity nidus) {
        return TEXTURES[stage(nidus)];
    }

    /** The emissive mask for the nidus's health stage: the glowing core, wounds and stumps, transparent elsewhere. */
    public static ResourceLocation emissive(NidusBlockEntity nidus) {
        return EMISSIVE[stage(nidus)];
    }

    @Override
    public ResourceLocation getAnimationResource(NidusBlockEntity nidus) {
        return stage(nidus) == NidusBlock.MAX_DAMAGE ? NEAR_DEATH_ANIMATIONS : ANIMATIONS;
    }

    /** Sets the animation clock (see the class comment) in place of the wall-clock tick the renderer set. */
    @Override
    public void handleAnimations(NidusBlockEntity nidus, long instanceId, AnimationState<NidusBlockEntity> state,
            float partialTick) {
        if (nidus.getLevel() != null) {
            float frame = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
            state.setData(DataTickets.TICK, nidus.getLevel().getGameTime() + (double) frame);
        }
        super.handleAnimations(nidus, instanceId, state, partialTick);
    }
}
