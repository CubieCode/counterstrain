package com.cubiecode.remission.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Gameplay settings shared by client and server ({@code config/remission-common.toml}). No options yet. */
public final class RemissionCommonConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RemissionCommonConfig() {
    }
}
