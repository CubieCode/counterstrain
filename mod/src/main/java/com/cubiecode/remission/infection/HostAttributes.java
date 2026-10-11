package com.cubiecode.remission.infection;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.behaviour.MobProfile;
import com.cubiecode.remission.behaviour.MobProfiles;
import com.cubiecode.remission.corruption.Corruption;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * A host's mob profile {@code attributes}, held as permanent {@code add_value} modifiers with id
 * {@code remission:mob_profile}. Applied when the entity becomes a host, removed when it stops being one, and reconciled
 * with the current profile each time a host joins a level, so profile changes reach saved hosts after a world reload.
 * Attributes the entity does not have are skipped.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class HostAttributes {
    public static final ResourceLocation MODIFIER_ID = Remission.id("mob_profile");

    private HostAttributes() {
    }

    /** Removes every profile modifier, then applies the entity's mob profile's if the entity is a host. Server only. */
    public static void update(LivingEntity entity) {
        update(entity, MobProfiles.find(entity));
    }

    /** {@link #update(LivingEntity)} with an explicit profile. */
    public static void update(LivingEntity entity, Optional<MobProfile> profile) {
        for (Holder<Attribute> attribute : BuiltInRegistries.ATTRIBUTE.asHolderIdMap()) {
            AttributeInstance instance = entity.getAttribute(attribute);
            if (instance != null) {
                instance.removeModifier(MODIFIER_ID);
            }
        }
        if (Corruption.isHost(entity)) {
            profile.map(MobProfile::attributes).ifPresent(attributes -> attributes.forEach((attribute, amount) -> {
                AttributeInstance instance = entity.getAttribute(attribute);
                if (instance != null) {
                    instance.addPermanentModifier(new AttributeModifier(MODIFIER_ID, amount, AttributeModifier.Operation.ADD_VALUE));
                }
            }));
        }
    }

    @SubscribeEvent
    static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof LivingEntity living
                && Corruption.isHost(living)) {
            update(living);
        }
    }
}
