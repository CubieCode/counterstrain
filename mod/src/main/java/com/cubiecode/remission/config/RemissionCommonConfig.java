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
    public static final ModConfigSpec.BooleanValue SURGES_SCHEDULED;
    public static final ModConfigSpec.IntValue SURGE_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue SURGE_WARNING_TICKS;
    public static final ModConfigSpec.IntValue SURGE_DURATION_TICKS;
    public static final ModConfigSpec.IntValue SURGE_SPREAD_MULTIPLIER;
    public static final ModConfigSpec.IntValue SURGE_HOSTS_PER_WAVE;
    public static final ModConfigSpec.IntValue SURGE_HOST_LEVEL;
    public static final ModConfigSpec.IntValue SURGE_MAX_HOSTS;
    public static final ModConfigSpec.IntValue SURGE_CLOUDS;

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

        BUILDER.push("surges");
        SURGES_SCHEDULED = BUILDER
                .comment("Whether surges start on their own. Commands and the API start them either way.")
                .define("scheduled", true);
        SURGE_INTERVAL_TICKS = BUILDER
                .comment("Game ticks from the end of one surge until the next comes due in the same level.",
                        "A due surge starts once a player (not a spectator) is within a nidus's reach.")
                .defineInRange("intervalTicks", 24000, 1200, 2400000);
        SURGE_WARNING_TICKS = BUILDER
                .comment("Game ticks between a surge's warning and its start.")
                .defineInRange("warningTicks", 600, 0, 12000);
        SURGE_DURATION_TICKS = BUILDER
                .comment("Game ticks a surge lasts once it has started.")
                .defineInRange("durationTicks", 2400, 200, 72000);
        SURGE_SPREAD_MULTIPLIER = BUILDER
                .comment("During a surge, spread from its nidus gains this many times its usual step per pass, times the intensity.")
                .defineInRange("spreadMultiplier", 4, 1, 100);
        SURGE_HOSTS_PER_WAVE = BUILDER
                .comment("Hosts a surge tries to spawn near each player inside its reach every 10 seconds, times the intensity.",
                        "None spawn on Peaceful.")
                .defineInRange("hostsPerWave", 2, 0, 32);
        SURGE_HOST_LEVEL = BUILDER
                .comment("Strain level of the hosts a surge spawns.")
                .defineInRange("hostLevel", 50, 1, CorruptionState.MAX_LEVEL);
        SURGE_MAX_HOSTS = BUILDER
                .comment("Most hosts one surge keeps alive at once; waves spawn none while it has this many.")
                .defineInRange("maxHosts", 16, 0, 256);
        SURGE_CLOUDS = BUILDER
                .comment("Miasma clouds a surge releases above festering land when it starts, times the intensity (at most 32).")
                .defineInRange("clouds", 3, 0, 32);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RemissionCommonConfig() {
    }
}
