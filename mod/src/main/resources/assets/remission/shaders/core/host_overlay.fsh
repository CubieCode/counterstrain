#version 150

#moj_import <fog.glsl>

// Host overlay (HostRenderTypes): the Strain pattern in Sampler3, masked by the mob texture in Sampler0.
uniform sampler2D Sampler0;
uniform sampler2D Sampler3;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    // Same cutout as the mob's own pass, so transparent outer-layer texels stay empty.
    if (texture(Sampler0, texCoord0).a < 0.1) {
        discard;
    }
    // One pattern texel per mob texel, repeating, whatever the mob texture's size.
    vec2 patternUv = texCoord0 * vec2(textureSize(Sampler0, 0)) / vec2(textureSize(Sampler3, 0));
    vec4 color = texture(Sampler3, patternUv) * vertexColor * ColorModulator;
    if (color.a < 0.004) {
        discard;
    }
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
