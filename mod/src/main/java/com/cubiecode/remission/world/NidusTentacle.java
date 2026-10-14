package com.cubiecode.remission.world;

import com.cubiecode.remission.api.NidusDamagedEvent;
import com.cubiecode.remission.registry.RemissionEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * {@code remission:nidus_tentacle}: the invisible hitbox of one of a nidus's tentacles (P4.19). Its nidus spawns one per
 * tentacle while it can be fought and moves each to where its tentacle points; a hitbox that finds no nidus owning it at
 * its position discards itself. Never saved (its type does not serialize), so chunk loads never duplicate them.
 *
 * <p>Hits go to the nidus: a player's attack is a strike (the strike cooldown and the charge-scaled attack damage, not the
 * weapon's hit) and an arrow is projectile damage, each times {@code [nidi] tentacleDamageFactor}, and the nidus's hit
 * event names the tentacle so clients make it flinch. Explosions pass through (the nidus already takes its share of a
 * blast), and it is never pushed, collided with or counted as a mob.
 */
public class NidusTentacle extends Entity {
    public static final float WIDTH = 0.8F;
    public static final float HEIGHT = 1.4F;

    private BlockPos nidus = BlockPos.ZERO;
    private int tentacle;

    public NidusTentacle(EntityType<? extends NidusTentacle> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    /** A new hitbox for {@code tentacle} of the nidus at {@code nidus}, not yet placed or added to the level. */
    static NidusTentacle create(ServerLevel level, BlockPos nidus, int tentacle) {
        NidusTentacle hitbox = new NidusTentacle(RemissionEntityTypes.NIDUS_TENTACLE.get(), level);
        hitbox.nidus = nidus.immutable();
        hitbox.tentacle = tentacle;
        return hitbox;
    }

    /** The nidus it belongs to. */
    public BlockPos nidus() {
        return nidus;
    }

    /** Its tentacle's index ({@link NidusTentacles#NAMES}). */
    public int tentacle() {
        return tentacle;
    }

    /** Moves it to where its tentacle points with {@code aim} degrees of turn. */
    void place(float aim) {
        Vec3 centre = NidusTentacles.hitboxCentre(tentacle, aim);
        double x = nidus.getX() + 0.5 + centre.x;
        double y = nidus.getY() + centre.y - HEIGHT / 2.0;
        double z = nidus.getZ() + 0.5 + centre.z;
        if (distanceToSqr(x, y, z) > 1.0E-4) {
            moveTo(x, y, z);
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    public void tick() {
        if (level() instanceof ServerLevel level && owner(level) == null) {
            discard();
        }
    }

    /** Its nidus, if one is loaded at its position and owns it. */
    private NidusBlockEntity owner(ServerLevel level) {
        return level.isLoaded(nidus) && level.getBlockEntity(nidus) instanceof NidusBlockEntity entity && entity.owns(this)
                ? entity : null;
    }

    /**
     * A player's attack: a strike on the nidus (fake players never strike), wearing the held item by 1 when it counts.
     * The vanilla hit (weapon damage, knockback, sweeps) never happens, on either side.
     */
    @Override
    public boolean skipAttackInteraction(Entity attacker) {
        if (!(level() instanceof ServerLevel level) || !(attacker instanceof ServerPlayer player) || player instanceof FakePlayer
                || player.isSpectator()) {
            return true;
        }
        NidusBlockEntity owner = owner(level);
        NidusSettings settings = NidusSettings.current();
        if (owner == null || !settings.encounters()) {
            return true;
        }
        ItemStack held = player.getMainHandItem();
        float dealt = owner.strike(level, player, settings, level.getGameTime(), this);
        if (dealt > 0 && held.isDamageableItem()) {
            held.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        }
        return true;
    }

    /** An arrow or trident: projectile damage to the nidus, credited to its owner. Anything else does nothing. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!(level() instanceof ServerLevel level) || !(source.getDirectEntity() instanceof Projectile projectile)) {
            return false;
        }
        NidusBlockEntity owner = owner(level);
        NidusSettings settings = NidusSettings.current();
        if (owner == null || !settings.encounters() || settings.projectileFraction() <= 0) {
            return false;
        }
        float damage = NidusEncounters.projectileDamage(projectile) * (float) (settings.projectileFraction() * settings.tentacleDamageFactor());
        ServerPlayer player = projectile.getOwner() instanceof ServerPlayer shooter ? shooter : null;
        owner.damage(level, NidusDamagedEvent.Kind.PROJECTILE, player, projectile, damage, settings, level.getGameTime(), tentacle + 1);
        return true;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean ignoreExplosion(Explosion explosion) {
        return true;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    public boolean displayFireAnimation() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
