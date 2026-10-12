package com.cubiecode.remission.world;

import com.cubiecode.remission.api.NidusDamagedEvent;
import com.cubiecode.remission.registry.RemissionBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * {@code remission:nidus}: a source of the Strain. Indexed while it exists; removing it stops its spread.
 *
 * <p>While {@code [nidi] vitality} is above 0 (P4.10) it cannot be mined or blown up: survival players strike it down
 * (each left-click deals the held item's attack damage, scaled by attack strength, and wears the item by 1), arrows and
 * explosions wear it down at their fractions, and the fight is its {@link NidusBlockEntity}; {@link #DAMAGE} shows how
 * far it has gone. Creative players and edits
 * (such as {@code /setblock}) still remove it at once. At vitality 0 it is mined like any block.
 */
public final class NidusBlock extends BaseEntityBlock {
    public static final MapCodec<NidusBlock> CODEC = simpleCodec(NidusBlock::new);
    /** Explosion resistance while encounters are on, as bedrock's: explosions never remove a nidus then. */
    public static final float ENCOUNTER_EXPLOSION_RESISTANCE = 3_600_000.0F;

    /** Most damage shown: quarters of vitality lost in the current fight (P4.10). */
    public static final int MAX_DAMAGE = 3;
    /** Damage shown on the block: quarters of vitality lost in the current fight, 0 to {@link #MAX_DAMAGE}. */
    public static final IntegerProperty DAMAGE = IntegerProperty.create("damage", 0, MAX_DAMAGE);

    public NidusBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(DAMAGE, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(DAMAGE);
    }

    @Override
    protected MapCodec<NidusBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NidusBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, RemissionBlockEntities.NIDUS.get(), NidusBlockEntity::serverTick);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !oldState.is(this)) {
            FesteringLand.nidusPresent(serverLevel, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (level instanceof ServerLevel serverLevel && !newState.is(this)) {
            NidusBlockEntity.Destruction destruction = level.getBlockEntity(pos) instanceof NidusBlockEntity entity
                    ? entity.takeDestruction() : null;
            if (destruction != null) {
                FesteringLand.nidusRemoved(serverLevel, pos, destruction.player(), destruction.contributors());
            } else {
                FesteringLand.nidusRemoved(serverLevel, pos);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // ---- encounters (P4.10) ------------------------------------------------------------------------------------------

    /** While encounters are on, mining makes no progress (creative players break it at once before this is asked). */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return NidusSettings.current().encounters() && !player.isCreative() ? 0.0F : super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    public float getExplosionResistance(BlockState state, BlockGetter level, BlockPos pos, Explosion explosion) {
        return NidusSettings.current().encounters() ? ENCOUNTER_EXPLOSION_RESISTANCE : super.getExplosionResistance(state, level, pos, explosion);
    }

    /** A survival player's left-click (after the left-click event and claim checks): a strike. */
    @Override
    protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
        super.attack(state, level, pos, player);
        NidusSettings settings = NidusSettings.current();
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer) || !settings.encounters()
                || player.isSpectator() || !(level.getBlockEntity(pos) instanceof NidusBlockEntity entity)) {
            return;
        }
        float amount = NidusEncounters.strikeDamage(player);
        player.resetAttackStrengthTicker();
        ItemStack held = player.getMainHandItem();
        float dealt = entity.damage(serverLevel, NidusDamagedEvent.Kind.STRIKE, serverPlayer, null, amount, settings,
                serverLevel.getGameTime());
        if (dealt > 0 && held.isDamageableItem()) {
            held.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        }
    }

    @Override
    protected void onProjectileHit(Level level, BlockState state, BlockHitResult hit, Projectile projectile) {
        super.onProjectileHit(level, state, hit, projectile);
        NidusSettings settings = NidusSettings.current();
        if (!(level instanceof ServerLevel serverLevel) || !settings.encounters() || settings.projectileFraction() <= 0
                || !(level.getBlockEntity(hit.getBlockPos()) instanceof NidusBlockEntity entity)) {
            return;
        }
        float amount = NidusEncounters.projectileDamage(projectile) * (float) settings.projectileFraction();
        ServerPlayer owner = projectile.getOwner() instanceof ServerPlayer player ? player : null;
        entity.damage(serverLevel, NidusDamagedEvent.Kind.PROJECTILE, owner, projectile, amount, settings, serverLevel.getGameTime());
    }

    /**
     * A player's break: refused while encounters are on unless the player is in creative (so survival mining, machines
     * acting as players and any other player-driven break leave it in place). An allowed break credits the player and
     * recent contributors as a final blow does.
     */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest,
            FluidState fluid) {
        NidusSettings settings = NidusSettings.current();
        if (settings.encounters() && !player.isCreative()) {
            return false;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(level.getBlockEntity(pos) instanceof NidusBlockEntity entity)) {
            return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
        }
        long now = serverLevel.getGameTime();
        ServerPlayer serverPlayer = player instanceof ServerPlayer p ? p : null;
        entity.breaking(serverPlayer, settings, now);
        var credited = entity.credited(settings, now, serverPlayer);
        boolean removed = super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
        entity.takeDestruction();
        if (removed && !level.getBlockState(pos).is(this)) {
            NidusEncounters.credit(serverLevel, pos, credited, serverPlayer);
        }
        return removed;
    }
}
