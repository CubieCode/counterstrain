package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.PurificationCause;
import com.cubiecode.remission.variant.ResistedMethod;
import com.cubiecode.remission.variant.Variant;
import com.cubiecode.remission.variant.Variants;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;

/**
 * Remission's default {@code remission:variant} entries (P7.5): one per purification tool and one per class of damage to
 * hosts, each resisting half of its method. Purification thresholds count Strain levels purified in the nidus's land,
 * damage thresholds host kills, so each tool's threshold follows how much land it purifies. Packs replace or add entries
 * and their recipes.
 */
public final class RemissionVariantProvider extends DatapackBuiltinEntriesProvider {
    /** Resistance of every default variant. */
    static final float RESISTANCE = 0.5F;

    /** A default variant: its id, whether a serum (damage) or a purifier fuel (purification) counters it, and its entry. */
    record Default(ResourceLocation id, boolean damage, Function<HolderGetter<DamageType>, Variant> variant) {
    }

    static final List<Default> DEFAULTS = List.of(
            purification("purifier_resistant", PurificationCause.PURIFIER, 120, 0x80E0FF),
            purification("pylon_resistant", PurificationCause.PYLON, 1500, 0x8C8CFF),
            purification("mendroot_resistant", PurificationCause.MENDROOT, 150, 0x8CFF8C),
            purification("reclaimer_resistant", PurificationCause.RECLAIMER, 2000, 0xFFD966),
            new Default(Remission.id("melee_resistant"), true, types -> new Variant(new ResistedMethod.Damage(HolderSet.direct(
                    types.getOrThrow(DamageTypes.PLAYER_ATTACK), types.getOrThrow(DamageTypes.MOB_ATTACK),
                    types.getOrThrow(DamageTypes.MOB_ATTACK_NO_AGGRO))), RESISTANCE, 40, 0xFF7A7A)),
            damage("projectile_resistant", DamageTypeTags.IS_PROJECTILE, 30, 0xC8FF5A),
            damage("fire_resistant", DamageTypeTags.IS_FIRE, 30, 0xFF9A3C),
            damage("blast_resistant", DamageTypeTags.IS_EXPLOSION, 30, 0xBDBDBD),
            damage("magic_resistant", DamageTypeTags.WITCH_RESISTANT_TO, 30, 0xFF7AFF));

    private static final RegistrySetBuilder VARIANTS = new RegistrySetBuilder().add(Variants.KEY, context -> {
        HolderGetter<DamageType> types = context.lookup(Registries.DAMAGE_TYPE);
        for (Default variant : DEFAULTS) {
            context.register(ResourceKey.create(Variants.KEY, variant.id()), variant.variant().apply(types));
        }
    });

    public RemissionVariantProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
        super(output, lookup, VARIANTS, Set.of(Remission.MOD_ID));
    }

    private static Default purification(String name, PurificationCause cause, float threshold, int tint) {
        return new Default(Remission.id(name), false,
                types -> new Variant(new ResistedMethod.Purification(cause), RESISTANCE, threshold, tint));
    }

    private static Default damage(String name, TagKey<DamageType> tag, float threshold, int tint) {
        return new Default(Remission.id(name), true,
                types -> new Variant(new ResistedMethod.Damage(types.getOrThrow(tag)), RESISTANCE, threshold, tint));
    }

    @Override
    public String getName() {
        return "Remission variants";
    }
}
