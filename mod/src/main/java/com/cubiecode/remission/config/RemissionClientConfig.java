package com.cubiecode.remission.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only presentation settings ({@code config/remission-client.toml}). */
public final class RemissionClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue HOST_OVERLAY_INTENSITY;
    public static final ModConfigSpec.BooleanValue SHOW_VIRAL_LOAD;

    static {
        BUILDER.push("hosts");
        HOST_OVERLAY_INTENSITY = BUILDER
                .comment("Opacity of the Strain overlay drawn on hosts, from 0 (off) to 1 (full).",
                        "Presentation only: it changes nothing about a host's stage or behaviour.")
                .defineInRange("overlayIntensity", 1.0, 0.0, 1.0);
        BUILDER.pop();

        BUILDER.push("hud");
        SHOW_VIRAL_LOAD = BUILDER
                .comment("Whether the viral load meter shows above the hunger bar while your viral load is above 0.",
                        "Presentation only: viral load and its debuffs apply either way.")
                .define("showViralLoad", true);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RemissionClientConfig() {
    }
}
