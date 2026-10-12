package com.cubiecode.remission.client;

import com.cubiecode.remission.revenant.Revenant;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

/**
 * Draws a revenant (P5.3) as its owner: the player model (wide or slim to match the skin) with the owner's skin, all
 * outer skin layers shown, armour, held items, head items, elytra and stuck arrows. The host overlay
 * ({@code HostOverlayLayer}, {@code HostLayerOverlay}) is added to it in {@code AddLayers} like any living-entity renderer.
 *
 * <p>Skin: an owner on the client's player list uses their current skin. Otherwise the owner's profile is fetched once
 * per UUID through the game's profile cache and session service (as player heads do) and its skin loaded; until then,
 * or offline, it is the owner's default skin. No payload.
 */
public final class RevenantRenderer extends HumanoidMobRenderer<Revenant, PlayerModel<Revenant>> {
    private static final Map<UUID, Supplier<PlayerSkin>> FETCHED = new HashMap<>();

    private final PlayerModel<Revenant> wide;
    private final PlayerModel<Revenant> slim;

    public RevenantRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.wide = model;
        this.slim = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        // Player armour models fit both arm widths closely enough; the player renderer uses the slim ones only for slim skins.
        addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
        addLayer(new ArrowLayer<>(context, this));
    }

    @Override
    public void render(Revenant revenant, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer,
            int packedLight) {
        model = skin(revenant).model() == PlayerSkin.Model.SLIM ? slim : wide;
        model.setAllVisible(true);
        HumanoidModel.ArmPose main = armPose(revenant, InteractionHand.MAIN_HAND);
        HumanoidModel.ArmPose off = armPose(revenant, InteractionHand.OFF_HAND);
        if (main.isTwoHanded()) {
            off = revenant.getOffhandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        }
        if (revenant.getMainArm() == HumanoidArm.RIGHT) {
            model.rightArmPose = main;
            model.leftArmPose = off;
        } else {
            model.rightArmPose = off;
            model.leftArmPose = main;
        }
        super.render(revenant, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    private static HumanoidModel.ArmPose armPose(Revenant revenant, InteractionHand hand) {
        ItemStack stack = revenant.getItemInHand(hand);
        if (stack.isEmpty()) {
            return HumanoidModel.ArmPose.EMPTY;
        }
        UseAnim anim = stack.getUseAnimation();
        if (revenant.isAggressive() && (anim == UseAnim.BOW || anim == UseAnim.CROSSBOW)) {
            return anim == UseAnim.BOW ? HumanoidModel.ArmPose.BOW_AND_ARROW : HumanoidModel.ArmPose.CROSSBOW_HOLD;
        }
        return HumanoidModel.ArmPose.ITEM;
    }

    @Override
    protected void scale(Revenant revenant, PoseStack poseStack, float partialTicks) {
        poseStack.scale(0.9375F, 0.9375F, 0.9375F);
    }

    @Override
    public ResourceLocation getTextureLocation(Revenant revenant) {
        return skin(revenant).texture();
    }

    /** The owner's skin: live from the player list, else fetched once by UUID, else the default for the UUID. */
    static PlayerSkin skin(Revenant revenant) {
        UUID owner = revenant.owner();
        if (owner == null) {
            return DefaultPlayerSkin.get(net.minecraft.Util.NIL_UUID);
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(owner);
        if (info != null) {
            return info.getSkin();
        }
        return FETCHED.computeIfAbsent(owner, RevenantRenderer::fetch).get();
    }

    private static Supplier<PlayerSkin> fetch(UUID owner) {
        PlayerSkin fallback = DefaultPlayerSkin.get(owner);
        var lookup = new java.util.concurrent.atomic.AtomicReference<Supplier<PlayerSkin>>(() -> fallback);
        SkullBlockEntity.fetchGameProfile(owner).thenAcceptAsync((Optional<GameProfile> profile) -> profile
                        .ifPresent(found -> lookup.set(Minecraft.getInstance().getSkinManager().lookupInsecure(found))),
                Minecraft.getInstance());
        return () -> lookup.get().get();
    }
}
