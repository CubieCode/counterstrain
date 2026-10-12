package com.cubiecode.remission.config;

import com.cubiecode.remission.corruption.CorruptionState;
import com.cubiecode.remission.viralload.ViralLoadSettings;
import com.cubiecode.remission.viralload.ViralLoadState;
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
    public static final ModConfigSpec.BooleanValue FESTERING_VEINS;
    public static final ModConfigSpec.IntValue FESTERING_VEIN_MIN_STAGE;
    public static final ModConfigSpec.BooleanValue INFECTION_FESTERING_LAND;
    public static final ModConfigSpec.IntValue INFECTION_FESTERING_LAND_MIN_STAGE;
    public static final ModConfigSpec.IntValue INFECTION_FESTERING_LAND_EXPOSURE_TICKS;
    public static final ModConfigSpec.BooleanValue INFECTION_CONTACT;
    public static final ModConfigSpec.IntValue INFECTION_CONTACT_TICKS;
    public static final ModConfigSpec.BooleanValue SURGES_SCHEDULED;
    public static final ModConfigSpec.IntValue SURGE_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue SURGE_WARNING_TICKS;
    public static final ModConfigSpec.IntValue SURGE_DURATION_TICKS;
    public static final ModConfigSpec.IntValue SURGE_SPREAD_MULTIPLIER;
    public static final ModConfigSpec.IntValue SURGE_HOSTS_PER_WAVE;
    public static final ModConfigSpec.IntValue SURGE_HOST_LEVEL;
    public static final ModConfigSpec.IntValue SURGE_MAX_HOSTS;
    public static final ModConfigSpec.IntValue SURGE_CLOUDS;
    public static final ModConfigSpec.BooleanValue VIRAL_LOAD_ENABLED;
    public static final ModConfigSpec.ConfigValue<List<? extends Number>> VIRAL_LOAD_LAND_GAIN;
    public static final ModConfigSpec.DoubleValue VIRAL_LOAD_WATER_GAIN;
    public static final ModConfigSpec.DoubleValue VIRAL_LOAD_DRINK_DOSE;
    public static final ModConfigSpec.DoubleValue VIRAL_LOAD_CLOUD_GAIN;
    public static final ModConfigSpec.DoubleValue VIRAL_LOAD_DECAY;
    public static final ModConfigSpec.BooleanValue VIRAL_LOAD_ALTITUDE_FALLOFF;
    public static final ModConfigSpec.DoubleValue VIRAL_LOAD_FALLOFF_START;
    public static final ModConfigSpec.DoubleValue VIRAL_LOAD_FALLOFF_END;
    public static final ModConfigSpec.DoubleValue VIRAL_LOAD_FALLOFF_MINIMUM;
    public static final ModConfigSpec.EnumValue<ViralLoadSettings.FalloffCurve> VIRAL_LOAD_FALLOFF_CURVE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> VIRAL_LOAD_DEBUFFS;
    public static final ModConfigSpec.DoubleValue VIRAL_LOAD_DAMAGE_AT;
    public static final ModConfigSpec.DoubleValue VIRAL_LOAD_DAMAGE;
    public static final ModConfigSpec.BooleanValue VIRAL_LOAD_CONTACT;
    public static final ModConfigSpec.DoubleValue VIRAL_LOAD_CONTACT_GAIN;
    public static final ModConfigSpec.IntValue PURIFICATION_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue PURIFICATION_PYLON_RADIUS;
    public static final ModConfigSpec.IntValue PURIFICATION_PYLON_STEP;
    public static final ModConfigSpec.IntValue PURIFICATION_PURIFIER_RADIUS;
    public static final ModConfigSpec.IntValue PURIFICATION_PURIFIER_STEP;
    public static final ModConfigSpec.IntValue PURIFICATION_PURIFIER_HOST_STEP;
    public static final ModConfigSpec.IntValue PURIFICATION_MENDROOT_RADIUS;
    public static final ModConfigSpec.IntValue PURIFICATION_MENDROOT_STEP;
    public static final ModConfigSpec.IntValue PURIFICATION_MENDROOT_INTERVAL_TICKS;
    public static final ModConfigSpec.BooleanValue REMISSION_SPAWN_SAFETY;
    public static final ModConfigSpec.DoubleValue REMISSION_GROWTH_CHANCE;
    public static final ModConfigSpec.DoubleValue REMISSION_YIELD_CHANCE;
    public static final ModConfigSpec.IntValue REVENANT_PICKUP_TIMEOUT_TICKS;

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
        FESTERING_VEINS = BUILDER
                .comment("Whether festering veins creep over the solid faces of blocks in festering chunks, player builds included.",
                        "Blocks in #remission:vein_resistant never get veins. Veins already grown stay when this is turned off.")
                .define("veins", true);
        FESTERING_VEIN_MIN_STAGE = BUILDER
                .comment("Lowest festering stage of a chunk at which veins grow; each stage above it grows more.",
                        "Above the number of festering stage thresholds, no chunk grows veins.")
                .defineInRange("veinMinStage", 2, 1, 8);
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
        INFECTION_CONTACT = BUILDER
                .comment("Whether mobs touching a block in #remission:infectious (on, in or against it), or in water in a chunk",
                        "at or above festeringLandMinStage, become hosts. Players never do. Contact is frozen while this is off.")
                .define("contact", true);
        INFECTION_CONTACT_TICKS = BUILDER
                .comment("Game ticks of contact after which a mob becomes a host; 0 infects on first touch. Contact falls by",
                        "the same amount per tick without it, and is checked every 10 ticks in budgeted batches.")
                .defineInRange("contactTicks", 100, 0, 72000);
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

        BUILDER.push("viral_load");
        VIRAL_LOAD_ENABLED = BUILDER
                .comment("Whether players build up viral load. While off, viral load and its debuffs are frozen.")
                .define("enabled", true);
        VIRAL_LOAD_LAND_GAIN = BUILDER
                .comment("Viral load (0-" + ViralLoadState.MAX + ") gained per minute standing in festering land, by festering stage:",
                        "the first entry is stage 1, the second stage 2 and so on; stages past the end use the last entry.",
                        "Reduced by height above the ground when altitudeFalloff is on; full strength at and below ground level.")
                .defineList(List.of("landGain"), () -> List.<Number>of(1.0, 3.0, 6.0, 12.0), () -> 1.0,
                        value -> value instanceof Number n && n.doubleValue() >= 0.0 && n.doubleValue() <= 6000.0,
                        ModConfigSpec.Range.of(1, 8));
        VIRAL_LOAD_WATER_GAIN = BUILDER
                .comment("Viral load gained per minute in water (swimming, wading or diving) in festering land, on top of landGain.")
                .defineInRange("waterGain", 20.0, 0.0, 6000.0);
        VIRAL_LOAD_DRINK_DOSE = BUILDER
                .comment("Viral load gained at once from drinking a water bottle in festering land.")
                .defineInRange("drinkDose", 10.0, 0.0, 100.0);
        VIRAL_LOAD_CLOUD_GAIN = BUILDER
                .comment("Viral load gained per minute inside a miasma cloud, at any height and over any land.")
                .defineInRange("cloudGain", 30.0, 0.0, 6000.0);
        VIRAL_LOAD_DECAY = BUILDER
                .comment("Viral load lost per minute while nothing adds any.")
                .defineInRange("decay", 5.0, 0.0, 6000.0);
        VIRAL_LOAD_ALTITUDE_FALLOFF = BUILDER
                .comment("Whether land gain falls off with height above the ground (the highest block that blocks motion,",
                        "leaves excluded). Players at or below ground level, in caves or under any roof, always gain in full.")
                .define("altitudeFalloff", true);
        VIRAL_LOAD_FALLOFF_START = BUILDER
                .comment("Blocks above the ground where land gain starts to fall off.")
                .defineInRange("falloffStart", 4.0, 0.0, 512.0);
        VIRAL_LOAD_FALLOFF_END = BUILDER
                .comment("Blocks above the ground where land gain reaches falloffMinimum. At or below falloffStart, there is no falloff.")
                .defineInRange("falloffEnd", 40.0, 0.0, 512.0);
        VIRAL_LOAD_FALLOFF_MINIMUM = BUILDER
                .comment("Fraction of land gain left at and above falloffEnd (0 = none, 1 = no falloff).")
                .defineInRange("falloffMinimum", 0.1, 0.0, 1.0);
        VIRAL_LOAD_FALLOFF_CURVE = BUILDER
                .comment("Shape of the falloff between falloffStart and falloffEnd: LINEAR, QUADRATIC (drops fast, then levels out)",
                        "or SMOOTH (eases in and out).")
                .defineEnum("falloffCurve", ViralLoadSettings.FalloffCurve.LINEAR);
        VIRAL_LOAD_DEBUFFS = BUILDER
                .comment("Debuffs by viral load, each \"<viral load> <effect id> <amplifier>\": the effect applies while viral load is",
                        "at or above the value (amplifier 0 is level I). Where several entries name one effect, the highest",
                        "amplifier reached wins. Refreshed every second; effects wear off about 12 seconds after dropping below.")
                .defineList(List.of("debuffs"), () -> List.of("25 minecraft:hunger 0", "50 minecraft:weakness 0",
                                "50 minecraft:mining_fatigue 0", "75 minecraft:slowness 0", "75 minecraft:hunger 1"),
                        () -> "50 minecraft:weakness 0", ViralLoadSettings.Debuff::isValid, ModConfigSpec.Range.of(0, 64));
        VIRAL_LOAD_DAMAGE_AT = BUILDER
                .comment("Viral load at and above which players take viral load damage (damage type remission:viral_load).")
                .defineInRange("damageAt", 100.0, 0.0, 100.0);
        VIRAL_LOAD_DAMAGE = BUILDER
                .comment("Damage per second at or above damageAt (2 = one heart). 0 turns viral load damage off.")
                .defineInRange("damage", 1.0, 0.0, 100.0);
        VIRAL_LOAD_CONTACT = BUILDER
                .comment("Whether touching a block in #remission:infectious (standing on it, inside it or against it) adds viral load,",
                        "anywhere, on top of landGain. Water is not counted again: waterGain already covers festering water.")
                .define("contact", true);
        VIRAL_LOAD_CONTACT_GAIN = BUILDER
                .comment("Viral load gained per minute while touching an infectious block.")
                .defineInRange("contactGain", 10.0, 0.0, 6000.0);
        BUILDER.pop();

        BUILDER.push("purification");
        PURIFICATION_INTERVAL_TICKS = BUILDER
                .comment("Game ticks between quarantine pylon passes. Each pass lowers the Strain level of every loaded warded chunk.")
                .defineInRange("intervalTicks", 200, 20, 72000);
        PURIFICATION_PYLON_RADIUS = BUILDER
                .comment("Chunks a quarantine pylon wards around its own, in a square (2 = 5 by 5 chunks). Inside the ward the Strain",
                        "does not spread, fester, grow veins or infect mobs by contact, and festered blocks and veins are cleansed.")
                .defineInRange("pylonRadius", 2, 0, 8);
        PURIFICATION_PYLON_STEP = BUILDER
                .comment("Strain level every warded chunk loses per pylon pass, until it reaches 0 and enters remission.",
                        "Overlapping wards do not stack.")
                .defineInRange("pylonStep", 5, 1, CorruptionState.MAX_LEVEL);
        PURIFICATION_PURIFIER_RADIUS = BUILDER
                .comment("Blocks around the block a handheld purifier is used on whose festered blocks and veins it cleanses (a cube).")
                .defineInRange("purifierRadius", 2, 0, 8);
        PURIFICATION_PURIFIER_STEP = BUILDER
                .comment("Strain level the chunk of that block loses per use of a handheld purifier.")
                .defineInRange("purifierStep", 1, 0, CorruptionState.MAX_LEVEL);
        PURIFICATION_PURIFIER_HOST_STEP = BUILDER
                .comment("Strain level a host loses when a handheld purifier is used on it (34 is about one host stage).")
                .defineInRange("purifierHostStep", 34, 0, CorruptionState.MAX_LEVEL);
        PURIFICATION_MENDROOT_RADIUS = BUILDER
                .comment("Blocks around a mendroot whose festered blocks and veins it cleanses (a cube).")
                .defineInRange("mendrootRadius", 3, 0, 8);
        PURIFICATION_MENDROOT_STEP = BUILDER
                .comment("Strain level the chunk of a mendroot loses each time it cleanses.")
                .defineInRange("mendrootStep", 1, 0, CorruptionState.MAX_LEVEL);
        PURIFICATION_MENDROOT_INTERVAL_TICKS = BUILDER
                .comment("Game ticks between a mendroot's cleanses.")
                .defineInRange("mendrootIntervalTicks", 600, 20, 72000);
        BUILDER.pop();

        BUILDER.push("remission_zones");
        REMISSION_SPAWN_SAFETY = BUILDER
                .comment("Whether monsters never spawn naturally in chunks in remission (spawners and events still spawn them).")
                .define("spawnSafety", true);
        REMISSION_GROWTH_CHANCE = BUILDER
                .comment("Chance that a crop's growth attempt in a chunk in remission succeeds when it would otherwise fail.")
                .defineInRange("growthChance", 0.25, 0.0, 1.0);
        REMISSION_YIELD_CHANCE = BUILDER
                .comment("Chance that a block in #remission:remission_yields broken in a chunk in remission drops one more of each item.")
                .defineInRange("yieldChance", 0.25, 0.0, 1.0);
        BUILDER.pop();

        BUILDER.push("revenants");
        REVENANT_PICKUP_TIMEOUT_TICKS = BUILDER
                .comment("Game ticks after a slain revenant drops its items during which only its owner (and players the",
                        "RevenantPickupEvent allows, such as a pack's teammates) can pick them up. After that anyone can, and the",
                        "items despawn like other dropped items. Until then they never despawn and mobs and players cannot take them.")
                .defineInRange("pickupTimeoutTicks", 12000, 0, 1728000);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RemissionCommonConfig() {
    }
}
