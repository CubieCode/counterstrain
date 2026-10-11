package com.cubiecode.remission.client;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.DrownedOuterLayer;
import net.minecraft.client.renderer.entity.layers.HorseArmorLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.layers.SheepFurLayer;
import net.minecraft.client.renderer.entity.layers.SlimeOuterLayer;
import net.minecraft.client.renderer.entity.layers.WolfArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Draws the Strain overlay on render layers that bring their own model and texture (armour, sheep wool, slime gel,
 * the drowned's outer layer, horse and wolf armour), which {@link HostOverlayLayer} cannot reach because it only
 * redraws the parent model with the parent texture.
 *
 * <p>One companion is added after each such layer, in any {@link LivingEntityRenderer} that uses it (modded renderers
 * included). For hosts, the companion runs its layer again with a buffer source that sends every textured entity draw
 * into the host overlay render type for that same texture, with the overlay colour; glint, trim atlas and outline
 * draws are dropped. The layer's own logic (which pieces, which models, NeoForge custom armour models and textures)
 * therefore decides what gets the overlay, and the overlay follows each layer texture's cutout. Eye and emissive
 * layers are never wrapped.
 */
final class HostLayerOverlay<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
    /** Layers that draw their own model or texture over the body; subclasses (modded variants) count too. */
    private static final List<Class<?>> WRAPPED = List.of(HumanoidArmorLayer.class, SheepFurLayer.class,
            SlimeOuterLayer.class, DrownedOuterLayer.class, HorseArmorLayer.class, WolfArmorLayer.class);
    private static final String TRIM_ATLAS_PREFIX = "textures/atlas/";
    private static final VertexConsumer DISCARD = new Discard();

    /** The texture and layering of each render type, read once per render type; empty when it is not overlaid. */
    private static final Map<RenderType, Optional<Source>> SOURCES = new WeakHashMap<>();
    private static Field compositeState;
    private static Field textureState;
    private static Field layeringState;
    private static Method cutoutTexture;

    private final RenderLayer<T, M> wrapped;

    private HostLayerOverlay(LivingEntityRenderer<T, M> parent, RenderLayer<T, M> wrapped) {
        super(parent);
        this.wrapped = wrapped;
    }

    /**
     * Adds a companion after every wrapped layer of every living-entity renderer and player skin renderer. Runs at
     * the lowest priority so layers other mods add in this event are seen too.
     */
    static void addLayers(EntityRenderersEvent.AddLayers event) {
        if (!resolveReflection()) {
            return;
        }
        List<EntityRenderer<?>> renderers = new ArrayList<>();
        event.getEntityTypes().forEach(type -> renderers.add(event.getRenderer(type)));
        event.getSkins().forEach(skin -> renderers.add(event.getSkin(skin)));
        int added = 0;
        for (EntityRenderer<?> renderer : renderers) {
            if (renderer instanceof LivingEntityRenderer<?, ?> living) {
                added += attach(living);
            }
        }
        Remission.LOGGER.info("Host overlay added to {} armour, wool, gel and outer layers", added);
    }

    private static <T extends LivingEntity, M extends EntityModel<T>> int attach(LivingEntityRenderer<T, M> renderer) {
        List<RenderLayer<T, M>> layers;
        try {
            layers = ObfuscationReflectionHelper.getPrivateValue(LivingEntityRenderer.class, renderer, "layers");
        } catch (RuntimeException e) {
            Remission.LOGGER.warn("Could not read the render layers of {}; its layers get no host overlay",
                    renderer.getClass().getName(), e);
            return 0;
        }
        List<RenderLayer<T, M>> targets = layers.stream()
                .filter(layer -> WRAPPED.stream().anyMatch(type -> type.isInstance(layer)))
                .toList();
        targets.forEach(layer -> renderer.addLayer(new HostLayerOverlay<>(renderer, layer)));
        return targets.size();
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity, float limbSwing,
                       float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        CorruptionState state = Corruption.get(entity);
        int color = HostOverlayLayer.overlayColor(entity, state);
        if (color == 0) {
            return;
        }
        // One pass per pattern: two overlay buffers open at once would end each other's batch.
        wrapped.render(poseStack, redirect(buffer, HostOverlayLayer.stagePattern(state.stage()), color, false),
                packedLight, entity, limbSwing, limbSwingAmount, partialTick, ageInTicks, netHeadYaw, headPitch);
        if (state.stage() >= HostOverlayLayer.GLOW_STAGE) {
            wrapped.render(poseStack, redirect(buffer, HostOverlayLayer.GLOW_PATTERN, color, true),
                    packedLight, entity, limbSwing, limbSwingAmount, partialTick, ageInTicks, netHeadYaw, headPitch);
        }
    }

    /** A buffer source that turns each textured entity draw into the overlay for {@code pattern} and drops the rest. */
    private static MultiBufferSource redirect(MultiBufferSource buffer, ResourceLocation pattern, int color,
                                              boolean fullBright) {
        return type -> SOURCES.computeIfAbsent(type, HostLayerOverlay::readSource)
                .<VertexConsumer>map(source -> new Recolored(buffer.getBuffer(
                        HostRenderTypes.hostOverlay(source.texture(), pattern, source.layering())), color, fullBright))
                .orElse(DISCARD);
    }

    /**
     * The cutout texture and layering of an entity-format render type, unless its texture is an atlas (armour trims).
     */
    private static Optional<Source> readSource(RenderType type) {
        if (type.format() != DefaultVertexFormat.NEW_ENTITY || !compositeState.getDeclaringClass().isInstance(type)) {
            return Optional.empty();
        }
        try {
            Object state = compositeState.get(type);
            @SuppressWarnings("unchecked")
            Optional<ResourceLocation> texture = (Optional<ResourceLocation>) cutoutTexture.invoke(
                    textureState.get(state));
            var layering = (RenderStateShard.LayeringStateShard) layeringState.get(state);
            return texture.filter(location -> !location.getPath().startsWith(TRIM_ATLAS_PREFIX))
                    .map(location -> new Source(location, layering));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Remission.LOGGER.warn("Could not read the texture of render type {}; it gets no host overlay", type, e);
            return Optional.empty();
        }
    }

    private static boolean resolveReflection() {
        try {
            Class<?> composite = Class.forName("net.minecraft.client.renderer.RenderType$CompositeRenderType");
            compositeState = ObfuscationReflectionHelper.findField(composite, "state");
            textureState = ObfuscationReflectionHelper.findField(RenderType.CompositeState.class, "textureState");
            layeringState = ObfuscationReflectionHelper.findField(RenderType.CompositeState.class, "layeringState");
            cutoutTexture = ObfuscationReflectionHelper.findMethod(RenderStateShard.EmptyTextureStateShard.class,
                    "cutoutTexture");
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Remission.LOGGER.error("Host overlay cannot read render type textures; armour and outer layers get none",
                    e);
            return false;
        }
    }

    private record Source(ResourceLocation texture, RenderStateShard.LayeringStateShard layering) {
    }

    /** Passes vertices through with the overlay colour, and full-bright light for the glow pass. */
    private record Recolored(VertexConsumer delegate, int color, boolean fullBright) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(color);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            if (fullBright) {
                delegate.setLight(LightTexture.FULL_BRIGHT);
            } else {
                delegate.setUv2(u, v);
            }
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }

    /** Swallows draws the overlay does not follow (glint, trims, outlines). */
    private static final class Discard implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }
}
