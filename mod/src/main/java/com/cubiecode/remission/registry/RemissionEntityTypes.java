package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.counterstrain.ThrownPrototype;
import com.cubiecode.remission.revenant.Revenant;
import com.cubiecode.remission.surge.MiasmaCloud;
import com.cubiecode.remission.world.NidusTentacle;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Entity types. Hosts are never entity types (infection is state); these are effects such as miasma clouds, the
 * revenant, a host that is its own type because it carries a player's items and skin, and the nidus's tentacle hitboxes.
 */
public final class RemissionEntityTypes {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Remission.MOD_ID);

    /** A drifting miasma cloud released by a surge (P4.3). Its size follows its radius; its position syncs every tick. */
    public static final Supplier<EntityType<MiasmaCloud>> MIASMA_CLOUD = ENTITY_TYPES.register("miasma_cloud",
            () -> EntityType.Builder.<MiasmaCloud>of(MiasmaCloud::new, MobCategory.MISC)
                    .sized(MiasmaCloud.DEFAULT_RADIUS * 2, MiasmaCloud.HEIGHT)
                    .fireImmune()
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build(Remission.id("miasma_cloud").toString()));

    /**
     * A revenant (P5.3): a player who died to the Strain, risen as a host holding their items. Player-sized; it never
     * despawns and is not a natural spawn.
     */
    public static final Supplier<EntityType<Revenant>> REVENANT = ENTITY_TYPES.register("revenant",
            () -> EntityType.Builder.<Revenant>of(Revenant::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.8F)
                    .eyeHeight(1.62F)
                    .fireImmune()
                    .clientTrackingRange(8)
                    .build(Remission.id("revenant").toString()));

    /** A thrown Counterstrain prototype (P7.3), sized and tracked like a thrown splash potion. */
    public static final Supplier<EntityType<ThrownPrototype>> COUNTERSTRAIN_PROTOTYPE = ENTITY_TYPES.register(
            "counterstrain_prototype", () -> EntityType.Builder.<ThrownPrototype>of(ThrownPrototype::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build(Remission.id("counterstrain_prototype").toString()));

    /**
     * The invisible hitbox of one of a nidus's tentacles (P4.19). Never saved: its nidus spawns it and moves it.
     */
    public static final Supplier<EntityType<NidusTentacle>> NIDUS_TENTACLE = ENTITY_TYPES.register("nidus_tentacle",
            () -> EntityType.Builder.<NidusTentacle>of(NidusTentacle::new, MobCategory.MISC)
                    .sized(NidusTentacle.WIDTH, NidusTentacle.HEIGHT)
                    .noSave()
                    .noSummon()
                    .fireImmune()
                    .clientTrackingRange(4)
                    .build(Remission.id("nidus_tentacle").toString()));

    private RemissionEntityTypes() {
    }
}
