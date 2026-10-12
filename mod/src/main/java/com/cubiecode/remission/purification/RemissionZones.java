package com.cubiecode.remission.purification;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.config.RemissionCommonConfig;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;

/**
 * Remission zone bonuses (P5.2, {@code [remission_zones]}): in a chunk in remission monsters do not spawn naturally,
 * crops grow faster and blocks in {@link #YIELDS} drop more. Each reads the config when it fires.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class RemissionZones {
    /** Blocks whose drops the yield bonus raises. Defaults to {@code #minecraft:crops}. */
    public static final TagKey<Block> YIELDS = TagKey.create(Registries.BLOCK, Remission.id("remission_yields"));

    private RemissionZones() {
    }

    /** Whether the chunk holding {@code pos} is in remission. */
    public static boolean inRemission(ServerLevel level, BlockPos pos) {
        return CorruptionQueries.isInRemission(level, new ChunkPos(pos));
    }

    /** Whether a natural spawn of a mob in {@code category} at {@code pos} is refused by the spawn safety bonus. */
    public static boolean refusesSpawn(ServerLevel level, BlockPos pos, MobCategory category, MobSpawnType type) {
        return type == MobSpawnType.NATURAL && category == MobCategory.MONSTER
                && RemissionCommonConfig.REMISSION_SPAWN_SAFETY.get() && inRemission(level, pos);
    }

    @SubscribeEvent
    static void onSpawnCheck(MobSpawnEvent.PositionCheck event) {
        ServerLevel level = event.getLevel().getLevel();
        if (refusesSpawn(level, event.getEntity().blockPosition(), event.getEntity().getType().getCategory(), event.getSpawnType())) {
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
        }
    }

    @SubscribeEvent
    static void onCropGrow(CropGrowEvent.Pre event) {
        double chance = RemissionCommonConfig.REMISSION_GROWTH_CHANCE.get();
        if (chance > 0 && event.getResult() == CropGrowEvent.Pre.Result.DEFAULT && event.getLevel() instanceof ServerLevel level
                && inRemission(level, event.getPos()) && level.random.nextDouble() < chance) {
            event.setResult(CropGrowEvent.Pre.Result.GROW);
        }
    }

    @SubscribeEvent
    static void onBlockDrops(BlockDropsEvent event) {
        double chance = RemissionCommonConfig.REMISSION_YIELD_CHANCE.get();
        ServerLevel level = event.getLevel();
        if (chance > 0 && event.getState().is(YIELDS) && inRemission(level, event.getPos()) && level.random.nextDouble() < chance) {
            addYield(event.getDrops());
        }
    }

    /** The yield bonus: one more of each dropped stack, up to its stack size. */
    public static void addYield(List<ItemEntity> drops) {
        for (ItemEntity drop : drops) {
            ItemStack stack = drop.getItem();
            if (!stack.isEmpty() && stack.getCount() < stack.getMaxStackSize()) {
                drop.setItem(stack.copyWithCount(stack.getCount() + 1));
            }
        }
    }
}
