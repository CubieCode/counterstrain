package com.cubiecode.remission.revenant;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * A revenant (P5.3): a host risen where a player died to the Strain, holding the items and experience that death would
 * have dropped. It wears copies of the player's armour and fights with a copy of their main-hand item; the held items
 * ({@link #contents()}) are separate, so what it drops is exactly what the player lost. It never despawns, never changes
 * dimension, is leashed to its {@link #home()} (where the player fell) and is hurt only by players, their projectiles and
 * {@code /kill}; in the void it returns home. Host goals come from {@code HostBehaviours} like any other host. Rendered
 * with its owner's skin by {@code client/RevenantRenderer}.
 */
public class Revenant extends Monster {
    /** How far from its home a revenant roams or chases (blocks). */
    public static final int LEASH_RADIUS = 8;
    /** Beyond this distance from home (blocks) a revenant is put back home at once instead of walking back. */
    public static final int RETURN_RADIUS = LEASH_RADIUS * 2;

    private static final EntityDataAccessor<Optional<UUID>> OWNER =
            SynchedEntityData.defineId(Revenant.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<String> OWNER_NAME =
            SynchedEntityData.defineId(Revenant.class, EntityDataSerializers.STRING);

    private final List<ItemStack> contents = new ArrayList<>();
    private int experience;
    @Nullable
    private BlockPos home;
    @Nullable
    private BlockPos indexedPos;

    public Revenant(EntityType<? extends Revenant> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setCanPickUpLoot(false);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            setDropChance(slot, 0.0F);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 2.0)
                .add(Attributes.FOLLOW_RANGE, LEASH_RADIUS * 2.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(OWNER, Optional.empty());
        builder.define(OWNER_NAME, "");
    }

    @Override
    protected void registerGoals() {
        // Host goals (HostBehaviours) strip and replace the target selector of a host; these targets apply only if the
        // revenant is ever cleansed.
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, false));
        goalSelector.addGoal(5, new MoveTowardsRestrictionGoal(this, 1.0));
        goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // ---- owner, home and contents ------------------------------------------------------------------------------------

    /** UUID of the player this revenant rose from, or null for one summoned without an owner. */
    @Nullable
    public UUID owner() {
        return entityData.get(OWNER).orElse(null);
    }

    /** The owner's name when the revenant rose (for its name and the client's skin lookup). */
    public String ownerName() {
        return entityData.get(OWNER_NAME);
    }

    public void setOwner(UUID owner, String name) {
        entityData.set(OWNER, Optional.of(owner));
        entityData.set(OWNER_NAME, name);
    }

    /** Where the owner fell: the centre of the leash and where the void returns it. Null until set. */
    @Nullable
    public BlockPos home() {
        return home;
    }

    public void setHome(BlockPos home) {
        this.home = home.immutable();
        restrictTo(this.home, LEASH_RADIUS);
    }

    /** The items it holds and drops when slain (a live view; equipment is display copies). */
    public List<ItemStack> contents() {
        return contents;
    }

    public int experience() {
        return experience;
    }

    /** Sets what it holds and drops. Equipment is set separately ({@link Revenants#rise}). */
    public void setContents(List<ItemStack> items, int experience) {
        contents.clear();
        items.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).forEach(contents::add);
        this.experience = Math.max(0, experience);
    }

    @Override
    protected Component getTypeName() {
        String name = ownerName();
        return name.isEmpty() ? super.getTypeName() : Component.translatable("entity.remission.revenant.named", name);
    }

    // ---- persistence, leash and damage -------------------------------------------------------------------------------

    /** No loot table rolls (so no host loot modifiers): it drops exactly what it holds. */
    @Override
    protected boolean shouldDropLoot() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Override
    public boolean canChangeDimensions(Level oldLevel, Level newLevel) {
        return false;
    }

    @Override
    public boolean canDrownInFluidType(net.neoforged.neoforge.fluids.FluidType type) {
        return false;
    }

    /** Hurt only by players (directly, by projectile, by anything they caused) and {@code /kill}. */
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return super.isInvulnerableTo(source) || !canBeHurtBy(source);
    }

    static boolean canBeHurtBy(DamageSource source) {
        return source.getEntity() instanceof Player || source.is(DamageTypes.GENERIC_KILL);
    }

    @Override
    protected void onBelowWorld() {
        returnHome();
    }

    /**
     * Puts it back at its home, standing still. Without a home (summoned), on the surface of its own column. Over a column
     * with no block at all it hovers (no gravity) rather than falling back into the void.
     */
    public void returnHome() {
        BlockPos target = home != null ? home : level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, blockPosition());
        stopRiding();
        ejectPassengers();
        setDeltaMovement(0.0, 0.0, 0.0);
        resetFallDistance();
        getNavigation().stop();
        teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        // A home over an empty column (a void dimension) has nothing to stand on: hover there instead of falling again.
        setNoGravity(level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, target).getY() <= level().getMinBuildHeight());
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (home == null) {
            return;
        }
        double fromHome = distanceToSqr(home.getCenter());
        if (fromHome > (double) RETURN_RADIUS * RETURN_RADIUS) {
            returnHome();
        }
        LivingEntity target = getTarget();
        if (target != null && target.distanceToSqr(home.getCenter()) > (double) (LEASH_RADIUS + 4) * (LEASH_RADIUS + 4)) {
            // Never follow prey past the leash; MoveTowardsRestrictionGoal walks it back.
            setTarget(null);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel serverLevel && !isRemoved() && !isDeadOrDying()) {
            BlockPos pos = blockPosition();
            if (!pos.equals(indexedPos)) {
                indexedPos = pos;
                Revenants.indexed(serverLevel, this);
            }
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (level() instanceof ServerLevel serverLevel && reason.shouldDestroy()) {
            RevenantIndex.get(serverLevel).remove(getUUID());
        }
        super.remove(reason);
    }

    // ---- death -------------------------------------------------------------------------------------------------------

    /** Drops everything it holds, whatever the loot game rules, each restricted to its owner (Revenants#restrict). */
    @Override
    protected void dropEquipment() {
        super.dropEquipment();
        UUID owner = owner();
        for (ItemStack stack : contents) {
            ItemEntity item = spawnAtLocation(stack.copy());
            if (item != null && owner != null) {
                Revenants.restrict(item, owner);
            }
        }
        contents.clear();
    }

    /** Drops exactly the experience it holds, whatever the loot game rules. */
    @Override
    protected void dropExperience(@Nullable Entity killer) {
        if (level() instanceof ServerLevel serverLevel && experience > 0) {
            int reward = net.neoforged.neoforge.event.EventHooks.getExperienceDrop(this, lastHurtByPlayer, experience);
            ExperienceOrb.award(serverLevel, position(), reward);
            experience = 0;
        }
    }

    @Override
    public void die(DamageSource source) {
        boolean wasDead = dead;
        super.die(source);
        if (!wasDead && dead && level() instanceof ServerLevel serverLevel) {
            Revenants.slain(serverLevel, this, source);
        }
    }

    // ---- save --------------------------------------------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        UUID owner = owner();
        if (owner != null) {
            tag.putUUID("Owner", owner);
            tag.putString("OwnerName", ownerName());
        }
        if (home != null) {
            tag.put("Home", NbtUtils.writeBlockPos(home));
        }
        ListTag items = new ListTag();
        for (ItemStack stack : contents) {
            items.add(stack.save(registryAccess()));
        }
        tag.put("Contents", items);
        tag.putInt("Experience", experience);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) {
            setOwner(tag.getUUID("Owner"), tag.getString("OwnerName"));
        }
        NbtUtils.readBlockPos(tag, "Home").ifPresent(this::setHome);
        contents.clear();
        for (Tag element : tag.getList("Contents", Tag.TAG_COMPOUND)) {
            ItemStack.parse(registryAccess(), element).ifPresent(contents::add);
        }
        experience = Math.max(0, tag.getInt("Experience"));
    }

    @Override
    protected void populateDefaultEquipmentSlots(net.minecraft.util.RandomSource random, DifficultyInstance difficulty) {
        // Equipment is the owner's, never random.
    }
}
