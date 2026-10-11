package com.cubiecode.remission.config;

import com.cubiecode.remission.corruption.CorruptionState;
import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Gameplay settings shared by client and server ({@code config/remission-common.toml}). */
public final class RemissionCommonConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> STAGE_THRESHOLDS;
    public static final ModConfigSpec.IntValue SPREAD_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue SPREAD_NIDUS_GROWTH;
    public static final ModConfigSpec.IntValue SPREAD_STEP;
    public static final ModConfigSpec.IntValue SPREAD_FALLOFF;
    public static final ModConfigSpec.IntValue SPREAD_THRESHOLD;
    public static final ModConfigSpec.IntValue SPREAD_CHUNK_BUDGET;
    public static final ModConfigSpec.IntValue SPREAD_CATCH_UP_BUDGET;
    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> FESTERING_STAGE_THRESHOLDS;
    public static final ModConfigSpec.BooleanValue INFECTION_FESTERING_LAND;
    public static final ModConfigSpec.IntValue INFECTION_FESTERING_LAND_MIN_STAGE;
    public static final ModConfigSpec.IntValue INFECTION_FESTERING_LAND_EXPOSURE_TICKS;

    static {
        BUILDER.push("hosts");
        STAGE_THRESHOLDS = BUILDER
                .comment("Strain levels (1-" + CorruptionState.MAX_LEVEL + ") at which a host reaches each stage.",
                        "A host's stage is the number of thresholds its level meets; one entry per stage.",
                        "Applied when a level is set, so existing hosts keep their stage until their level next changes.")
                .defineList(List.of("stageThresholds"), () -> List.of(1, 34, 67), () -> CorruptionState.MAX_LEVEL,
                        value -> value instanceof Integer i && i >= 1 && i <= CorruptionState.MAX_LEVEL,
                        ModConfigSpec.Range.of(1, 8));
        BUILDER.pop();

        BUILDER.push("spread");
        SPREAD_INTERVAL_TICKS = BUILDER
                .comment("Game ticks between spread passes. Unloaded land catches up by the passes it missed when it loads.")
                .defineInRange("intervalTicks", 400, 20, 72000);
        SPREAD_NIDUS_GROWTH = BUILDER
                .comment("Strain level the chunk holding a nidus gains per pass, up to " + CorruptionState.MAX_LEVEL + ".")
                .defineInRange("nidusGrowth", 5, 1, CorruptionState.MAX_LEVEL);
        SPREAD_STEP = BUILDER
                .comment("Strain level any other chunk in reach of a nidus gains per pass from its neighbour toward the nidus.")
                .defineInRange("spreadStep", 2, 1, CorruptionState.MAX_LEVEL);
        SPREAD_FALLOFF = BUILDER
                .comment("How much lower the highest reachable level is per chunk of distance from the nidus.",
                        "With the defaults a nidus reaches 7 chunks (Manhattan distance).")
                .defineInRange("falloff", 10, 1, CorruptionState.MAX_LEVEL);
        SPREAD_THRESHOLD = BUILDER
                .comment("Strain level a chunk needs before it passes the Strain on to the next chunk outward.")
                .defineInRange("spreadThreshold", 40, 1, CorruptionState.MAX_LEVEL);
        SPREAD_CHUNK_BUDGET = BUILDER
                .comment("Chunks the spread pass visits per server tick and level; a pass that needs more continues next tick.")
                .defineInRange("chunkBudget", 256, 1, 65536);
        SPREAD_CATCH_UP_BUDGET = BUILDER
                .comment("Loaded chunks fast-forwarded per server tick and level; the rest wait for later ticks.")
                .defineInRange("catchUpBudget", 32, 1, 4096);
        BUILDER.pop();

        BUILDER.push("festering");
        FESTERING_STAGE_THRESHOLDS = BUILDER
                .comment("Chunk Strain levels (1-" + CorruptionState.MAX_LEVEL + ") at which land reaches each festering stage.",
                        "A chunk's stage is the number of thresholds its level meets; one entry per stage.",
                        "Read whenever a chunk's level changes or a stage is queried.")
                .defineList(List.of("stageThresholds"), () -> List.of(1, 25, 50, 75), () -> CorruptionState.MAX_LEVEL,
                        value -> value instanceof Integer i && i >= 1 && i <= CorruptionState.MAX_LEVEL,
                        ModConfigSpec.Range.of(1, 8));
        BUILDER.pop();

        BUILDER.push("infection");
        INFECTION_FESTERING_LAND = BUILDER
                .comment("Whether mobs that stay in festering land become hosts. Exposure is frozen while this is off.")
                .define("festeringLand", true);
        INFECTION_FESTERING_LAND_MIN_STAGE = BUILDER
                .comment("Lowest festering stage of a chunk that builds up exposure in the mobs inside it, at any height.",
                        "Above the number of festering stage thresholds, no land infects.")
                .defineInRange("festeringLandMinStage", 2, 1, 8);
        INFECTION_FESTERING_LAND_EXPOSURE_TICKS = BUILDER
                .comment("Game ticks of exposure after which a mob becomes a host. Exposure falls by the same amount",
                        "per tick outside such land, and is checked once a second in budgeted batches.")
                .defineInRange("festeringLandExposureTicks", 6000, 20, 1728000);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RemissionCommonConfig() {
    }
}
