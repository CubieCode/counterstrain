package com.cubiecode.remission.viralload;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.StrainHazards;
import com.cubiecode.remission.api.ViralLoadChangedEvent;
import com.cubiecode.remission.infection.Contact;
import com.cubiecode.remission.network.ViralLoadPayload;
import com.cubiecode.remission.quest.QuestProgress;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionEntityTypes;
import com.cubiecode.remission.serum.SerumSettings;
import com.cubiecode.remission.serum.Serums;
import com.cubiecode.remission.surge.MiasmaCloud;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Viral load (P5.1): a player's exposure to the Strain, 0 to {@link ViralLoadState#MAX}, in the
 * {@link RemissionAttachments#VIRAL_LOAD} attachment. Every {@link ViralLoadSettings#INTERVAL_TICKS} ticks of a player
 * in survival or adventure, {@link #update} adds the gain from festering land (falling off with height above the
 * ground), water in festering land and miasma clouds, or takes off the decay when nothing adds any, then applies the
 * debuffs and damage for the new value. An inoculated player (a serum, P7.3) takes only part of the gain. Drinking a
 * water bottle in festering land adds a dose at once. Server authoritative; the owner's client gets
 * {@link ViralLoadPayload}. Updates skip while the level's ticks are frozen ({@code /tick freeze}), so a frozen world
 * keeps every player's viral load.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class ViralLoad {
    /** Damage type of viral load damage at the top of the meter; death message {@code death.attack.remission.viral_load}. */
    public static final ResourceKey<DamageType> DAMAGE_TYPE = ResourceKey.create(Registries.DAMAGE_TYPE, Remission.id("viral_load"));

    private static final double TICKS_PER_MINUTE = 1200.0;
    private static final Set<ResourceLocation> WARNED_EFFECTS = new HashSet<>();

    private ViralLoad() {
    }

    /** The player's state, or {@link ViralLoadState#NONE}. Never creates the attachment. Either side. */
    public static ViralLoadState state(Player player) {
        ViralLoadState state = player.getExistingDataOrNull(RemissionAttachments.VIRAL_LOAD);
        return state == null ? ViralLoadState.NONE : state;
    }

    /** The player's viral load, 0 to {@link ViralLoadState#MAX}. Either side (a client knows only its own player's). */
    public static float get(Player player) {
        return state(player).load();
    }

    /**
     * Sets the player's viral load (clamped) and trend. When the state changed, sends it to the player; when the load
     * changed, posts {@link ViralLoadChangedEvent} and fires {@code remission:viral_load_changed}. Server only.
     */
    public static void set(ServerPlayer player, float load, ViralLoadState.Trend trend, ViralLoadChangedEvent.Cause cause) {
        ViralLoadState old = state(player);
        ViralLoadState next = new ViralLoadState(load, trend);
        if (next.equals(old)) {
            return;
        }
        if (next.equals(ViralLoadState.NONE)) {
            player.removeData(RemissionAttachments.VIRAL_LOAD);
        } else {
            player.setData(RemissionAttachments.VIRAL_LOAD, next);
        }
        sync(player);
        if (next.load() != old.load()) {
            NeoForge.EVENT_BUS.post(new ViralLoadChangedEvent(player, old.load(), next.load(), cause));
            QuestProgress.viralLoadChanged(player, next.load());
        }
    }

    /** Sends the player their current state. */
    public static void sync(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, ViralLoadPayload.of(state(player)));
    }

    /**
     * One update over {@code ticks} ticks: gain if anything adds viral load, else decay; then debuffs and damage. Players
     * in creative or spectator, dead players and fake players gain nothing and take no debuffs; their viral load still
     * decays. Nothing changes while {@code settings} are disabled.
     */
    public static void update(ServerPlayer player, int ticks, ViralLoadSettings settings) {
        if (!settings.enabled() || player instanceof FakePlayer) {
            return;
        }
        boolean exposed = isExposed(player);
        double gain = exposed ? gainPerMinute(player, settings) : 0.0;
        float load = get(player);
        if (gain > 0) {
            // An inoculated player (a serum, P7.3) takes a fraction of the gain, never none: exposure still rises.
            gain *= Serums.gainFactor(player, SerumSettings.fromConfig());
            set(player, (float) (load + gain * ticks / TICKS_PER_MINUTE), ViralLoadState.Trend.RISING,
                    ViralLoadChangedEvent.Cause.EXPOSURE);
        } else if (load > 0 && settings.decay() > 0) {
            float next = (float) Math.max(0.0, load - settings.decay() * ticks / TICKS_PER_MINUTE);
            set(player, next, next > 0 ? ViralLoadState.Trend.FALLING : ViralLoadState.Trend.STEADY,
                    ViralLoadChangedEvent.Cause.DECAY);
        } else {
            set(player, load, ViralLoadState.Trend.STEADY, ViralLoadChangedEvent.Cause.OTHER);
        }
        if (exposed) {
            applyDebuffs(player, get(player), ticks, settings);
        }
    }

    /**
     * Viral load per minute the player gains where they are now: land gain by the chunk's festering stage times the
     * altitude factor, plus water gain when in water in festering land, plus cloud gain inside a miasma cloud, plus
     * contact gain while touching an infectious block anywhere (P5.2; festering water is already water gain, so
     * {@link Contact.Kind#WATER} adds nothing here) unless the Strain hazard {@link StrainHazards#CONTACT} does not
     * apply to the player (P5.7: their team holds its countermeasure, or a listener held it back).
     */
    public static double gainPerMinute(ServerPlayer player, ViralLoadSettings settings) {
        ServerLevel level = player.serverLevel();
        int stage = CorruptionQueries.stageAt(level, player.blockPosition());
        double gain = 0.0;
        if (stage >= 1) {
            gain += settings.landGainAt(stage) * settings.altitudeFactor(heightAboveGround(level, player));
            if (inWater(level, player)) {
                gain += settings.waterGain();
            }
        }
        if (settings.cloudGain() > 0 && inMiasmaCloud(level, player)) {
            gain += settings.cloudGain();
        }
        if (settings.contact() && settings.contactGain() > 0 && Contact.of(player) == Contact.Kind.BLOCK
                && StrainHazards.applies(player, StrainHazards.CONTACT)) {
            gain += settings.contactGain();
        }
        return gain;
    }

    /**
     * Blocks between the entity's feet and the top of the highest block in its column that blocks motion (leaves
     * excluded, fluids included). Zero or less standing on the ground, in water at the surface, in a cave or under any
     * roof.
     */
    public static double heightAboveGround(ServerLevel level, Entity entity) {
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(entity.getX()), Mth.floor(entity.getZ()));
        return entity.getY() - ground;
    }

    /** Whether the entity's feet or eyes are in water (swimming, wading or diving). */
    public static boolean inWater(ServerLevel level, Entity entity) {
        return entity.isInWater() || level.getFluidState(entity.blockPosition()).is(FluidTags.WATER)
                || level.getFluidState(BlockPos.containing(entity.getEyePosition())).is(FluidTags.WATER);
    }

    /** Whether the entity's bounding box overlaps a miasma cloud's area (its bounding box). */
    public static boolean inMiasmaCloud(ServerLevel level, Entity entity) {
        AABB box = entity.getBoundingBox();
        // Clouds are wider than an entity section, so search wide enough to find one whose centre is in another section.
        AABB search = box.inflate(MiasmaCloud.MAX_RADIUS, MiasmaCloud.HEIGHT, MiasmaCloud.MAX_RADIUS);
        return !level.getEntities(RemissionEntityTypes.MIASMA_CLOUD.get(), search,
                cloud -> cloud.isAlive() && cloud.getBoundingBox().intersects(box)).isEmpty();
    }

    /** Applies each configured debuff at or below {@code load} (highest amplifier per effect) and the damage. */
    static void applyDebuffs(ServerPlayer player, float load, int ticks, ViralLoadSettings settings) {
        if (load <= 0) {
            return;
        }
        Map<ResourceLocation, Integer> amplifiers = new LinkedHashMap<>();
        for (ViralLoadSettings.Debuff debuff : settings.debuffs()) {
            if (load >= debuff.load()) {
                amplifiers.merge(debuff.effect(), debuff.amplifier(), Math::max);
            }
        }
        amplifiers.forEach((id, amplifier) -> BuiltInRegistries.MOB_EFFECT.getHolder(id).ifPresentOrElse(
                effect -> player.addEffect(new MobEffectInstance(effect, ViralLoadSettings.DEBUFF_TICKS, amplifier,
                        true, false, true)),
                () -> {
                    if (WARNED_EFFECTS.add(id)) {
                        Remission.LOGGER.warn("Unknown effect {} in [viral_load] debuffs; skipped", id);
                    }
                }));
        if (settings.damage() > 0 && load >= settings.damageAt()) {
            player.hurt(player.damageSources().source(DAMAGE_TYPE), (float) (settings.damage() * ticks / 20.0));
        }
    }

    /** Whether the player can gain viral load and take its debuffs: alive, and in survival or adventure. */
    public static boolean isExposed(ServerPlayer player) {
        return player.isAlive() && !player.isCreative() && !player.isSpectator();
    }

    /**
     * Adds a dose of viral load at once (a water bottle, a backfiring prototype) to a player who can gain it (enabled,
     * {@link #isExposed}, not a fake player). Returns whether it was added.
     */
    public static boolean addDose(ServerPlayer player, double amount, ViralLoadChangedEvent.Cause cause) {
        if (amount <= 0 || player instanceof FakePlayer || !isExposed(player) || !ViralLoadSettings.fromConfig().enabled()) {
            return false;
        }
        set(player, (float) (get(player) + amount), ViralLoadState.Trend.RISING, cause);
        return true;
    }

    /** Whether {@code stack} is a water bottle (a potion item holding plain water). */
    public static boolean isWaterBottle(ItemStack stack) {
        return stack.is(Items.POTION) && stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(Potions.WATER);
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % ViralLoadSettings.INTERVAL_TICKS == 0
                && player.serverLevel().tickRateManager().runsNormally()) {
            update(player, ViralLoadSettings.INTERVAL_TICKS, ViralLoadSettings.fromConfig());
        }
    }

    // The bottle's origin is not tracked: the dose depends on where it is drunk.
    @SubscribeEvent
    static void onItemUseFinish(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer || !isWaterBottle(event.getItem())) {
            return;
        }
        ViralLoadSettings settings = ViralLoadSettings.fromConfig();
        if (settings.enabled() && settings.drinkDose() > 0 && isExposed(player)
                && CorruptionQueries.stageAt(player.serverLevel(), player.blockPosition()) >= 1) {
            set(player, (float) (get(player) + settings.drinkDose()), ViralLoadState.Trend.RISING,
                    ViralLoadChangedEvent.Cause.DRINK);
        }
    }

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player);
        }
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player);
        }
    }

    @SubscribeEvent
    static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player);
        }
    }
}
