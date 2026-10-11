package com.cubiecode.remission.surge;

import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.registry.RemissionEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;

/**
 * {@code remission:miasma_cloud}: an airborne area of miasma released by a surge (P4.3). It drifts slowly along its
 * heading at {@link #ALTITUDE} blocks above the ground and stays above festering land: when its next step would leave
 * a loaded chunk at festering stage 1 or higher it turns (right, left, then back) and holds still if every way out
 * leaves festering land. It dissipates after its lifetime. The area is its bounding box ({@link #getRadius()} each way
 * horizontally, {@link #HEIGHT} tall); it does nothing to what it touches yet (viral load is P5.1). Not attackable,
 * pushed or collided with; it saves with its chunk.
 */
public class MiasmaCloud extends Entity {
    public static final float DEFAULT_RADIUS = 4.0F;
    public static final float MAX_RADIUS = 16.0F;
    public static final float HEIGHT = 3.0F;
    /** Height of the cloud's base above the highest motion-blocking block beneath it. */
    public static final int ALTITUDE = 8;
    /** Horizontal drift in blocks per tick. */
    public static final double SPEED = 0.04;
    /** Vertical correction toward {@link #ALTITUDE} in blocks per tick at most. */
    private static final double CLIMB = 0.05;
    public static final int DEFAULT_LIFETIME = 3600;

    private static final EntityDataAccessor<Float> DATA_RADIUS =
            SynchedEntityData.defineId(MiasmaCloud.class, EntityDataSerializers.FLOAT);

    private int age;
    private int lifetime = DEFAULT_LIFETIME;
    /** Drift direction in degrees, as entity yaw: 0 is south (+z), 90 west (-x). */
    private float heading;
    private double climb;

    public MiasmaCloud(EntityType<? extends MiasmaCloud> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    /** A new cloud at the given position (the centre of its base), not yet added to the level. */
    public static MiasmaCloud create(ServerLevel level, double x, double y, double z, float heading, float radius,
                                     int lifetime) {
        MiasmaCloud cloud = new MiasmaCloud(RemissionEntityTypes.MIASMA_CLOUD.get(), level);
        cloud.setRadius(radius);
        cloud.heading = Mth.wrapDegrees(heading);
        cloud.lifetime = lifetime;
        cloud.setPos(x, y, z);
        return cloud;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_RADIUS, DEFAULT_RADIUS);
    }

    public float getRadius() {
        return entityData.get(DATA_RADIUS);
    }

    public void setRadius(float radius) {
        if (!level().isClientSide) {
            entityData.set(DATA_RADIUS, Mth.clamp(radius, 0.5F, MAX_RADIUS));
        }
    }

    public float getHeading() {
        return heading;
    }

    public int getAge() {
        return age;
    }

    public int getLifetime() {
        return lifetime;
    }

    @Override
    public void tick() {
        if (level().isClientSide) {
            // The server sends the position every tick it moves and the client takes it as is (no extrapolation), so a
            // frozen or stepped world always shows the server's position.
            return;
        }
        if (++age >= lifetime) {
            discard();
            return;
        }
        ServerLevel level = (ServerLevel) level();
        if (age % 10 == 1) {
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(getX()), Mth.floor(getZ()));
            climb = Mth.clamp(ground + ALTITUDE - getY(), -CLIMB, CLIMB);
        }
        double dx = 0;
        double dz = 0;
        for (float turn : new float[] {0F, 90F, -90F, 180F}) {
            float candidate = Mth.wrapDegrees(heading + turn);
            double stepX = -Mth.sin(candidate * Mth.DEG_TO_RAD) * SPEED;
            double stepZ = Mth.cos(candidate * Mth.DEG_TO_RAD) * SPEED;
            if (festering(level, getX() + stepX, getZ() + stepZ)) {
                heading = candidate;
                dx = stepX;
                dz = stepZ;
                break;
            }
        }
        setDeltaMovement(dx, climb, dz);
        setPos(getX() + dx, getY() + climb, getZ() + dz);
    }

    /** Whether the column at {@code x, z} is in a loaded chunk at festering stage 1 or higher. */
    static boolean festering(ServerLevel level, double x, double z) {
        return CorruptionQueries.stageAt(level, BlockPos.containing(x, 0, z)) >= 1;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return EntityDimensions.scalable(getRadius() * 2.0F, HEIGHT);
    }

    @Override
    public void refreshDimensions() {
        double x = getX();
        double y = getY();
        double z = getZ();
        super.refreshDimensions();
        setPos(x, y, z);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        if (DATA_RADIUS.equals(key)) {
            refreshDimensions();
        }
        super.onSyncedDataUpdated(key);
    }

    // Puffs reach a little past the area; keep them from being culled at the edge of the view.
    @Override
    public AABB getBoundingBoxForCulling() {
        return getBoundingBox().inflate(getRadius() * 0.5F, 1.0, getRadius() * 0.5F);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
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
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Radius")) {
            setRadius(tag.getFloat("Radius"));
        }
        age = tag.getInt("Age");
        if (tag.contains("Lifetime")) {
            lifetime = Math.max(1, tag.getInt("Lifetime"));
        }
        heading = Mth.wrapDegrees(tag.getFloat("Heading"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("Radius", getRadius());
        tag.putInt("Age", age);
        tag.putInt("Lifetime", lifetime);
        tag.putFloat("Heading", heading);
    }
}
