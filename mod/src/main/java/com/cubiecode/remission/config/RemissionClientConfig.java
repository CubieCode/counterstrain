package com.cubiecode.remission.config;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only presentation settings ({@code config/remission-client.toml}). */
public final class RemissionClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue HOST_OVERLAY_INTENSITY;
    public static final ModConfigSpec.BooleanValue SHOW_VIRAL_LOAD;
    public static final ModConfigSpec.DoubleValue FESTER_FOG;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> NOTICES_MUTED;
    public static final ModConfigSpec.IntValue NOTICES_WAYPOINT_SECONDS;

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

        BUILDER.push("atmosphere");
        FESTER_FOG = BUILDER
                .comment("How much the fog thickens in festering land as its stage rises, from 0 (off) to 1 (full).",
                        "Presentation only: the fester biome's own fog and sky colours stay.")
                .defineInRange("festerFog", 1.0, 0.0, 1.0);
        BUILDER.pop();

        BUILDER.push("notices");
        NOTICES_MUTED = BUILDER
                .comment("Strain hazards whose notices you do not want in chat or on the map, by hazard id",
                        "(for example \"remission:rot\"). Muted notices still show in the bestiary's hazards tab.")
                .defineListAllowEmpty("muted", List.<String>of(), () -> "remission:contact",
                        value -> value instanceof String id && ResourceLocation.tryParse(id) != null);
        NOTICES_WAYPOINT_SECONDS = BUILDER
                .comment("How long a hazard notice's temporary map waypoint stays, in seconds, with JourneyMap",
                        "installed. 0 adds no waypoints.")
                .defineInRange("waypointSeconds", 300, 0, 3600);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RemissionClientConfig() {
    }
}
