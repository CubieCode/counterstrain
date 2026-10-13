package com.cubiecode.remission.variant;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.PurificationCause;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;

/**
 * What a variant resists (P7.5): one way the Strain is beaten, as a typed key ({@code "type"} plus that type's fields),
 * so later systems add their own types without changing a variant's shape (P9.1 resists prototypes through
 * {@code remission:purification} with cause {@code prototype}).
 *
 * <ul>
 *   <li>{@code {"type": "remission:purification", "cause": "<cause>"}}: purification by one tool, a
 *       {@link PurificationCause} in lower case ({@code purifier}, {@code pylon}, {@code mendroot}, {@code prototype},
 *       {@code reclaimer}, {@code other}).</li>
 *   <li>{@code {"type": "remission:damage", "damage_types": <damage type, list or #tag>}}: damage to hosts of those
 *       types.</li>
 * </ul>
 */
public sealed interface ResistedMethod permits ResistedMethod.Purification, ResistedMethod.Damage {
    ResourceLocation PURIFICATION = Remission.id("purification");
    ResourceLocation DAMAGE = Remission.id("damage");

    Codec<PurificationCause> CAUSE_CODEC = Codec.STRING.comapFlatMap(name -> {
        try {
            return DataResult.success(PurificationCause.valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return DataResult.error(() -> "Unknown purification cause: " + name);
        }
    }, cause -> cause.name().toLowerCase(Locale.ROOT));

    Map<ResourceLocation, MapCodec<? extends ResistedMethod>> TYPES = Map.of(
            PURIFICATION, Purification.CODEC,
            DAMAGE, Damage.CODEC);

    Codec<ResistedMethod> CODEC = ResourceLocation.CODEC
            .validate(type -> TYPES.containsKey(type) ? DataResult.success(type)
                    : DataResult.error(() -> "Unknown resisted method type: " + type))
            .dispatch("type", ResistedMethod::type, TYPES::get);

    ResourceLocation type();

    /** Whether purification with {@code cause} is this method. */
    default boolean matches(PurificationCause cause) {
        return false;
    }

    /** Whether damage of {@code type} is this method. */
    default boolean matches(Holder<DamageType> type) {
        return false;
    }

    record Purification(PurificationCause cause) implements ResistedMethod {
        static final MapCodec<Purification> CODEC = CAUSE_CODEC.fieldOf("cause").xmap(Purification::new, Purification::cause);

        @Override
        public ResourceLocation type() {
            return PURIFICATION;
        }

        @Override
        public boolean matches(PurificationCause cause) {
            return this.cause == cause;
        }
    }

    record Damage(HolderSet<DamageType> damageTypes) implements ResistedMethod {
        static final MapCodec<Damage> CODEC = RegistryCodecs.homogeneousList(Registries.DAMAGE_TYPE)
                .fieldOf("damage_types").xmap(Damage::new, Damage::damageTypes);

        @Override
        public ResourceLocation type() {
            return DAMAGE;
        }

        @Override
        public boolean matches(Holder<DamageType> type) {
            return damageTypes.contains(type);
        }
    }
}
