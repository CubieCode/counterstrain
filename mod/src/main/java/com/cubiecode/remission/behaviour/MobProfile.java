package com.cubiecode.remission.behaviour;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;

/**
 * One entry of the {@code remission:mob_profile} datapack registry: per-mob overrides for the {@code entities} it matches.
 * Where several profiles match an entity, the highest {@code priority} wins ({@link MobProfiles#find}). Field contract:
 * remission-data-formats.
 *
 * @param entities   entity types this profile applies to (id, tag or list)
 * @param priority   higher wins when several profiles match
 * @param behaviour  how the host fights; empty means the category default
 * @param attributes attribute modifiers ({@code add_value}) applied while the mob is a host
 * @param convertTo  entity type that replaces this mob when a host kills it; empty converts in place
 * @param conversion which infection sources can turn this mob into a host
 */
public record MobProfile(HolderSet<EntityType<?>> entities, int priority, Optional<MobBehaviour> behaviour,
                         Map<Holder<Attribute>, Double> attributes, Optional<Holder<EntityType<?>>> convertTo,
                         Conversion conversion) {
    public static final Codec<MobProfile> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            RegistryCodecs.homogeneousList(Registries.ENTITY_TYPE).fieldOf("entities").forGetter(MobProfile::entities),
            Codec.INT.optionalFieldOf("priority", 0).forGetter(MobProfile::priority),
            MobBehaviour.CODEC.optionalFieldOf("behaviour").forGetter(MobProfile::behaviour),
            Codec.unboundedMap(Attribute.CODEC, Codec.DOUBLE).optionalFieldOf("attributes", Map.of())
                    .forGetter(MobProfile::attributes),
            BuiltInRegistries.ENTITY_TYPE.holderByNameCodec().optionalFieldOf("convert_to").forGetter(MobProfile::convertTo),
            Conversion.CODEC.optionalFieldOf("conversion", Conversion.DEFAULT).forGetter(MobProfile::conversion)
    ).apply(instance, MobProfile::new));

    public boolean appliesTo(EntityType<?> type) {
        return entities.contains(type.builtInRegistryHolder());
    }

    /**
     * Which infection sources can turn the profiled mob into a host.
     *
     * @param onKill        being killed by a host
     * @param festeringLand prolonged time in festering land (phase 4)
     */
    public record Conversion(boolean onKill, boolean festeringLand) {
        public static final Conversion DEFAULT = new Conversion(true, true);

        public static final Codec<Conversion> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("on_kill", true).forGetter(Conversion::onKill),
                Codec.BOOL.optionalFieldOf("festering_land", true).forGetter(Conversion::festeringLand)
        ).apply(instance, Conversion::new));
    }
}
