package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.surge.MiasmaCloud;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Entity types. Hosts are never entity types (infection is state); these are effects such as miasma clouds. */
public final class RemissionEntityTypes {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Remission.MOD_ID);

    /** A drifting miasma cloud released by a surge (P4.3). Its size follows its radius. */
    public static final Supplier<EntityType<MiasmaCloud>> MIASMA_CLOUD = ENTITY_TYPES.register("miasma_cloud",
            () -> EntityType.Builder.<MiasmaCloud>of(MiasmaCloud::new, MobCategory.MISC)
                    .sized(MiasmaCloud.DEFAULT_RADIUS * 2, MiasmaCloud.HEIGHT)
                    .fireImmune()
                    .clientTrackingRange(10)
                    .updateInterval(5)
                    .build(Remission.id("miasma_cloud").toString()));

    private RemissionEntityTypes() {
    }
}
