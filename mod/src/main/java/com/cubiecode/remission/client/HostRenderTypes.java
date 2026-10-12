package com.cubiecode.remission.client;

import com.cubiecode.remission.Remission;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Function;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

/**
 * The host overlay render type. Its shader ({@code assets/remission/shaders/core/host_overlay.json}) draws the Strain
 * pattern in sampler 3 over the mob's own texture in sampler 0, keeping only texels where the mob texture is opaque, so
 * empty outer-layer boxes (hats, jackets, sleeves) stay invisible. The pattern repeats at one pattern texel per mob
 * texel whatever the mob texture's size.
 */
final class HostRenderTypes {
    static final int PATTERN_SAMPLER = 3;

    private static ShaderInstance shader;

    /** Bounded by the loaded mob textures x patterns x layering states in use. */
    private static final Function<Key, RenderType> HOST_OVERLAY = Util.memoize(key -> RenderType.create(
            Remission.MOD_ID + "_host_overlay",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536, true, true,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(() -> shader))
                    .setTextureState(new RenderStateShard.EmptyTextureStateShard(() -> {
                        var textures = Minecraft.getInstance().getTextureManager();
                        textures.getTexture(key.mobTexture()).setFilter(false, false);
                        textures.getTexture(key.pattern()).setFilter(false, false);
                        RenderSystem.setShaderTexture(0, key.mobTexture());
                        RenderSystem.setShaderTexture(PATTERN_SAMPLER, key.pattern());
                    }, () -> {
                    }))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setOverlayState(RenderStateShard.OVERLAY)
                    .setLayeringState(key.layering())
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false)));

    private record Key(ResourceLocation mobTexture, ResourceLocation pattern,
                       RenderStateShard.LayeringStateShard layering) {
    }

    private HostRenderTypes() {
    }

    static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), Remission.id("host_overlay"),
                    DefaultVertexFormat.NEW_ENTITY), loaded -> shader = loaded);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load the host overlay shader", e);
        }
    }

    /** Whether the shader has loaded; the layer draws nothing until it has. */
    static boolean ready() {
        return shader != null;
    }

    /** The overlay for a mob drawn with {@code mobTexture}, patterned with {@code pattern}. */
    static RenderType hostOverlay(ResourceLocation mobTexture, ResourceLocation pattern) {
        return hostOverlay(mobTexture, pattern, RenderStateShard.NO_LAYERING);
    }

    /**
     * The same, with the layering of the draw it covers: armour is drawn with {@code VIEW_OFFSET_Z_LAYERING}, nudged
     * towards the camera, and an overlay without that offset would lose the depth test against it.
     */
    static RenderType hostOverlay(ResourceLocation mobTexture, ResourceLocation pattern,
                                  RenderStateShard.LayeringStateShard layering) {
        return HOST_OVERLAY.apply(new Key(mobTexture, pattern, layering));
    }
}
