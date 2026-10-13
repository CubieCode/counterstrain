package com.cubiecode.remission.variant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HexFormat;

/**
 * A {@code remission:variant} entry (P7.5): a resistant mutation of the Strain. Where it has emerged, the method it
 * {@code resists} works {@code resistance} less (0.5 halves it); it emerges at a nidus once that method's pressure there
 * reaches {@code threshold} (times {@code [variants] thresholdScale}); its hosts and festering land are drawn with
 * {@code tint} (RGB, {@code "#RRGGBB"} or an int).
 */
public record Variant(ResistedMethod resists, float resistance, float threshold, int tint) {
    /** {@code "#RRGGBB"} or a plain int; written as the string. */
    public static final Codec<Integer> TINT_CODEC = Codec.withAlternative(Codec.STRING.comapFlatMap(text -> {
        if (text.length() != 7 || text.charAt(0) != '#') {
            return DataResult.error(() -> "Tint must be \"#RRGGBB\": " + text);
        }
        try {
            return DataResult.success(HexFormat.fromHexDigits(text, 1, 7));
        } catch (IllegalArgumentException e) {
            return DataResult.error(() -> "Tint must be \"#RRGGBB\": " + text);
        }
    }, tint -> String.format("#%06X", tint & 0xFFFFFF)), Codec.intRange(0, 0xFFFFFF));

    public static final Codec<Variant> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResistedMethod.CODEC.fieldOf("resists").forGetter(Variant::resists),
            Codec.floatRange(0.0F, 1.0F).fieldOf("resistance").forGetter(Variant::resistance),
            Codec.floatRange(Float.MIN_NORMAL, Float.MAX_VALUE).fieldOf("threshold").forGetter(Variant::threshold),
            TINT_CODEC.fieldOf("tint").forGetter(Variant::tint)
    ).apply(instance, Variant::new));

    /** {@code amount} of a method this variant resists, after its resistance. */
    public double resisted(double amount) {
        return amount * (1.0 - resistance);
    }
}
