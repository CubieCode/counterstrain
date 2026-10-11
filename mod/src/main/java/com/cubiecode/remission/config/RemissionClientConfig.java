package com.cubiecode.remission.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only presentation settings ({@code config/remission-client.toml}). No options yet. */
public final class RemissionClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RemissionClientConfig() {
    }
}
