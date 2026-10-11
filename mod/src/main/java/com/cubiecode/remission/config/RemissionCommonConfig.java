package com.cubiecode.remission.config;

import com.cubiecode.remission.corruption.CorruptionState;
import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Gameplay settings shared by client and server ({@code config/remission-common.toml}). */
public final class RemissionCommonConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> STAGE_THRESHOLDS;

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
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RemissionCommonConfig() {
    }
}
