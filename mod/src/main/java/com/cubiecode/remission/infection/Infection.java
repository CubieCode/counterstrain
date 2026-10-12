package com.cubiecode.remission.infection;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CorruptionCause;
import com.cubiecode.remission.behaviour.MobProfile;
import com.cubiecode.remission.behaviour.MobProfiles;
import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.corruption.Corruption;
import java.util.Collections;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * How mobs become hosts. A {@link Mob} killed by a host (the damage source's responsible entity) does not die: it becomes a
 * host in place, or is replaced by its mob profile's {@code convert_to} type, at the first host stage. Immune mobs, mobs
 * that are already hosts and mobs whose profile turns off {@code conversion.on_kill} die normally. Mobs that stay in
 * festering land become hosts the same way through {@link #infectFromFesteringLand}, driven by {@link FesteringExposure},
 * and mobs that touch infectious blocks or festering water through {@link #infectFromContact}, driven by
 * {@link ContactInfection}.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class Infection {
    private Infection() {
    }

    /** Low priority, so death protection from other mods (which cancels the event) wins over infection. */
    @SubscribeEvent(priority = EventPriority.LOW)
    static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Mob victim && !victim.level().isClientSide()
                && event.getSource().getEntity() instanceof LivingEntity killer && Corruption.isHost(killer)
                && infectOnKill(victim, MobProfiles.find(victim)) != null) {
            event.setCanceled(true);
        }
    }

    /**
     * Turns a mob killed by a host into a host, following {@code profile}. Returns the new host (the victim itself, or its
     * replacement), or {@code null} if the victim is not eligible and should die. Server only.
     */
    @Nullable
    public static Mob infectOnKill(Mob victim, Optional<MobProfile> profile) {
        if (Corruption.isImmune(victim) || Corruption.isHost(victim)
                || !profile.map(p -> p.conversion().onKill()).orElse(true)) {
            return null;
        }
        return infect(victim, profile, CorruptionCause.INFECTION);
    }

    /**
     * Whether prolonged time in festering land may turn this mob into a host: not immune, not already a host, and its
     * profile allows {@code conversion.festering_land}.
     */
    public static boolean canInfectFromFesteringLand(Mob mob) {
        return canInfectFromFesteringLand(mob, MobProfiles.find(mob));
    }

    /** {@link #canInfectFromFesteringLand(Mob)} with {@code profile} in place of the mob's own. */
    public static boolean canInfectFromFesteringLand(Mob mob, Optional<MobProfile> profile) {
        return !Corruption.isImmune(mob) && !Corruption.isHost(mob)
                && profile.map(p -> p.conversion().festeringLand()).orElse(true);
    }

    /**
     * Turns a mob that built up enough festering land exposure into a host, in place or through the profile's
     * {@code convert_to}, with {@link CorruptionCause#FESTERING_LAND}. Returns the new host, or {@code null} if the mob is
     * not eligible or the conversion was refused. Server only.
     */
    @Nullable
    public static Mob infectFromFesteringLand(Mob mob, Optional<MobProfile> profile) {
        if (!canInfectFromFesteringLand(mob, profile)) {
            return null;
        }
        return infect(mob, profile, CorruptionCause.FESTERING_LAND);
    }

    /**
     * Turns a mob that touched an infectious block or festering water for long enough into a host (P4.8), with the
     * festering land eligibility ({@link #canInfectFromFesteringLand}) and {@code convert_to}, and
     * {@link CorruptionCause#CONTACT}. Returns the new host, or {@code null} if the mob is not eligible or the conversion
     * was refused. Server only.
     */
    @Nullable
    public static Mob infectFromContact(Mob mob, Optional<MobProfile> profile) {
        if (!canInfectFromFesteringLand(mob, profile)) {
            return null;
        }
        return infect(mob, profile, CorruptionCause.CONTACT);
    }

    @Nullable
    private static Mob infect(Mob victim, Optional<MobProfile> profile, CorruptionCause cause) {
        Optional<EntityType<?>> convertTo = profile.flatMap(MobProfile::convertTo).map(holder -> holder.value());
        if (convertTo.isEmpty() || convertTo.get() == victim.getType()) {
            if (cause == CorruptionCause.INFECTION) {
                victim.setHealth(victim.getMaxHealth());
            }
            Corruption.set(victim, initialLevel(), cause);
            return victim;
        }
        return convert(victim, convertTo.get(), cause);
    }

    /** The Strain level a newly infected host starts at: the lowest host stage threshold. */
    public static int initialLevel() {
        return Collections.min(RemissionCommonConfig.STAGE_THRESHOLDS.get());
    }

    /**
     * Replaces {@code victim} with a host of {@code type} through {@link Mob#convertTo} (which keeps position, age, AI,
     * name, persistence, invulnerability, equipment and vehicle) and infects the replacement once it has joined the level.
     * Returns {@code null}, leaving the victim untouched, if the type is immune, is not a mob, or another mod cancels the
     * conversion.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private static Mob convert(Mob victim, EntityType<?> type, CorruptionCause cause) {
        if (type.is(Corruption.IMMUNE)) {
            return null;
        }
        if (!(type.create(victim.level()) instanceof Mob)) {
            Remission.LOGGER.warn("Mob profile convert_to {} is not a mob; {} dies instead", EntityType.getKey(type),
                    EntityType.getKey(victim.getType()));
            return null;
        }
        EntityType<Mob> mobType = (EntityType<Mob>) type;
        if (!EventHooks.canLivingConvert(victim, mobType, timer -> {
        })) {
            return null;
        }
        Mob host = victim.convertTo(mobType, true);
        if (host == null) {
            return null;
        }
        Corruption.set(host, initialLevel(), cause);
        EventHooks.onLivingConvert(victim, host);
        return host;
    }
}
