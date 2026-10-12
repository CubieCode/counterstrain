package com.cubiecode.remission.viralload;

import com.cubiecode.remission.config.RemissionCommonConfig;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Viral load settings ({@code [viral_load]} in {@code remission-common.toml}). Rates are viral load per minute; an update
 * runs every {@link #INTERVAL_TICKS} ticks and applies the rate for the ticks since the last one.
 *
 * @param enabled         whether viral load changes at all (off freezes it and its debuffs)
 * @param landGain        gain per minute in festering land by festering stage (index 0 is stage 1; the last entry
 *                        covers higher stages)
 * @param waterGain       gain per minute in water in festering land, on top of land gain
 * @param drinkDose       gain at once from a water bottle drunk in festering land
 * @param cloudGain       gain per minute inside a miasma cloud
 * @param decay           loss per minute while nothing adds viral load
 * @param altitudeFalloff whether land gain falls off with height above the ground
 * @param falloffStart    height above the ground where the falloff starts
 * @param falloffEnd      height above the ground where it reaches {@code falloffMinimum}
 * @param falloffMinimum  fraction of land gain left at and above {@code falloffEnd}
 * @param falloffCurve    shape of the falloff in between
 * @param debuffs         effects by viral load
 * @param damageAt        viral load at and above which players take damage
 * @param damage          damage per second there (0 = none)
 */
public record ViralLoadSettings(boolean enabled, List<Double> landGain, double waterGain, double drinkDose,
                                double cloudGain, double decay, boolean altitudeFalloff, double falloffStart,
                                double falloffEnd, double falloffMinimum, FalloffCurve falloffCurve, List<Debuff> debuffs,
                                double damageAt, double damage) {
    /** Ticks between viral load updates. */
    public static final int INTERVAL_TICKS = 20;
    /** How long each debuff lasts when it is refreshed; above 200 so the effect icon does not blink between refreshes. */
    public static final int DEBUFF_TICKS = 240;

    public ViralLoadSettings {
        landGain = List.copyOf(landGain);
        debuffs = List.copyOf(debuffs);
    }

    public static ViralLoadSettings fromConfig() {
        return new ViralLoadSettings(
                RemissionCommonConfig.VIRAL_LOAD_ENABLED.get(),
                RemissionCommonConfig.VIRAL_LOAD_LAND_GAIN.get().stream().map(Number::doubleValue).toList(),
                RemissionCommonConfig.VIRAL_LOAD_WATER_GAIN.get(),
                RemissionCommonConfig.VIRAL_LOAD_DRINK_DOSE.get(),
                RemissionCommonConfig.VIRAL_LOAD_CLOUD_GAIN.get(),
                RemissionCommonConfig.VIRAL_LOAD_DECAY.get(),
                RemissionCommonConfig.VIRAL_LOAD_ALTITUDE_FALLOFF.get(),
                RemissionCommonConfig.VIRAL_LOAD_FALLOFF_START.get(),
                RemissionCommonConfig.VIRAL_LOAD_FALLOFF_END.get(),
                RemissionCommonConfig.VIRAL_LOAD_FALLOFF_MINIMUM.get(),
                RemissionCommonConfig.VIRAL_LOAD_FALLOFF_CURVE.get(),
                RemissionCommonConfig.VIRAL_LOAD_DEBUFFS.get().stream().map(Debuff::parse).toList(),
                RemissionCommonConfig.VIRAL_LOAD_DAMAGE_AT.get(),
                RemissionCommonConfig.VIRAL_LOAD_DAMAGE.get());
    }

    /** Land gain per minute at festering {@code stage} (0 below stage 1 or with an empty list). */
    public double landGainAt(int stage) {
        if (stage < 1 || landGain.isEmpty()) {
            return 0.0;
        }
        return landGain.get(Math.min(stage, landGain.size()) - 1);
    }

    /**
     * Share of land gain left at {@code height} blocks above the ground: 1 at or below {@link #falloffStart} (so always
     * underground), {@link #falloffMinimum} at or above {@link #falloffEnd}, and the curve in between. 1 when the falloff
     * is off.
     */
    public double altitudeFactor(double height) {
        if (!altitudeFalloff || height <= falloffStart) {
            return 1.0;
        }
        if (height >= falloffEnd) {
            return falloffMinimum;
        }
        double t = (height - falloffStart) / (falloffEnd - falloffStart);
        return falloffMinimum + (1.0 - falloffMinimum) * falloffCurve.remaining(t);
    }

    /** Shape of the altitude falloff between its start and end heights. */
    public enum FalloffCurve {
        /** Even drop. */
        LINEAR,
        /** Drops fast just above the start, then levels out. */
        QUADRATIC,
        /** Eases in at the start and out at the end. */
        SMOOTH;

        /** The share left at {@code t} (0 at the start, 1 at the end of the falloff), from 1 down to 0. */
        public double remaining(double t) {
            double clamped = Mth.clamp(t, 0.0, 1.0);
            return switch (this) {
                case LINEAR -> 1.0 - clamped;
                case QUADRATIC -> (1.0 - clamped) * (1.0 - clamped);
                case SMOOTH -> 1.0 - clamped * clamped * (3.0 - 2.0 * clamped);
            };
        }
    }

    /**
     * One debuff: the effect {@code effect} at {@code amplifier} (0 is level I) while viral load is at or above
     * {@code load}. Config form: {@code "<load> <effect id> <amplifier>"}.
     */
    public record Debuff(double load, ResourceLocation effect, int amplifier) {
        /** Parses a config entry; call only on entries {@link #isValid} accepts. */
        public static Debuff parse(String text) {
            String[] parts = text.trim().split("\\s+");
            return new Debuff(Double.parseDouble(parts[0]), ResourceLocation.parse(parts[1]), Integer.parseInt(parts[2]));
        }

        /** Whether {@code value} is a config entry: a viral load 0-100, an effect id and an amplifier 0-255. */
        public static boolean isValid(Object value) {
            if (!(value instanceof String text)) {
                return false;
            }
            String[] parts = text.trim().split("\\s+");
            if (parts.length != 3 || ResourceLocation.tryParse(parts[1]) == null) {
                return false;
            }
            try {
                double load = Double.parseDouble(parts[0]);
                int amplifier = Integer.parseInt(parts[2]);
                return load >= 0 && load <= ViralLoadState.MAX && amplifier >= 0 && amplifier <= 255;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }
}
