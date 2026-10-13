package com.cubiecode.remission.counterstrain;

import com.cubiecode.remission.registry.RemissionEntityTypes;
import com.cubiecode.remission.registry.RemissionItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

/** A thrown Counterstrain prototype: lands on the first block or entity it hits ({@link CounterstrainPrototypes#land}). */
public class ThrownPrototype extends ThrowableItemProjectile {
    public ThrownPrototype(EntityType<? extends ThrownPrototype> type, Level level) {
        super(type, level);
    }

    public ThrownPrototype(Level level, LivingEntity thrower) {
        super(RemissionEntityTypes.COUNTERSTRAIN_PROTOTYPE.get(), thrower, level);
    }

    public ThrownPrototype(Level level, double x, double y, double z) {
        super(RemissionEntityTypes.COUNTERSTRAIN_PROTOTYPE.get(), x, y, z, level);
    }

    @Override
    protected Item getDefaultItem() {
        return RemissionItems.COUNTERSTRAIN_PROTOTYPE.get();
    }

    @Override
    protected double getDefaultGravity() {
        return 0.05;
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (level() instanceof ServerLevel level) {
            CounterstrainPrototypes.land(level, result.getLocation(), getOwner() instanceof ServerPlayer player ? player : null,
                    PrototypeSettings.fromConfig(), random);
            discard();
        }
    }
}
