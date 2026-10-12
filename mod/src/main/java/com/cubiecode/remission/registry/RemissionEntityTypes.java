package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.revenant.Revenant;
import com.cubiecode.remission.surge.MiasmaCloud;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Entity types. Hosts are never entity types (infection is state); these are effects such as miasma clouds, and the
 * revenant, a host that is its own type because it carries a player's items and skin.
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

    private RemissionEntityTypes() {
    }
}
