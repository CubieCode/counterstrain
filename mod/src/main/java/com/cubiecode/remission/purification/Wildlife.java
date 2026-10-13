package com.cubiecode.remission.purification;

import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Wildlife return (P5.5): vanilla spawns passive animals only when a chunk is generated, so a chunk in remission gets
 * them back here. One attempt picks a creature from the biome's {@code creature} spawn list at a random column of the
 * chunk and spawns one group of it, as natural spawning would (spawn placement, spawn rules and NeoForge's position
 * check, spawn type {@code NATURAL}, then finalisation), up to the cap of creatures the chunk holds. Spawned animals are
 * new, clean mobs: never hosts.
 */
public final class Wildlife {
    private static final int TRIES = 4;

    private Wildlife() {
    }

    /** The full-height box of {@code chunk}. */
    public static AABB box(ServerLevel level, ChunkPos chunk) {
        return new AABB(chunk.getMinBlockX(), level.getMinBuildHeight(), chunk.getMinBlockZ(),
                chunk.getMaxBlockX() + 1, level.getMaxBuildHeight(), chunk.getMaxBlockZ() + 1);
    }

    /** Living creature-category mobs standing in {@code chunk}: what the cap counts. */
    public static int creatures(ServerLevel level, ChunkPos chunk) {
        return level.getEntitiesOfClass(Mob.class, box(level, chunk), mob -> mob.isAlive()
                && mob.getType().getCategory() == MobCategory.CREATURE && mob.chunkPosition().equals(chunk)).size();
    }

    /**
     * One wildlife attempt in {@code chunk}, holding at most {@code cap} creatures. Returns the animals spawned. A chunk
     * whose entities are not ticking (as natural spawning requires) gets none: its entities may not be loaded yet.
     */
    public static int attempt(ServerLevel level, ChunkPos chunk, int cap, RandomSource random) {
        if (!level.isPositionEntityTicking(chunk.getMiddleBlockPosition(level.getSeaLevel()))) {
            return 0;
        }
        int room = cap - creatures(level, chunk);
        if (room <= 0) {
            return 0;
        }
        BlockPos probe = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(chunk.getBlockX(random.nextInt(16)), 0, chunk.getBlockZ(random.nextInt(16))));
        Optional<MobSpawnSettings.SpawnerData> picked = Regrowth.biomeAt(level, probe).value().getMobSettings()
                .getMobs(MobCategory.CREATURE).getRandom(random);
        if (picked.isEmpty() || picked.get().type.getCategory() != MobCategory.CREATURE) {
            return 0;
        }
        MobSpawnSettings.SpawnerData data = picked.get();
        int group = Math.min(room, data.minCount + random.nextInt(1 + Math.max(0, data.maxCount - data.minCount)));
        int spawned = 0;
        for (int i = 0; i < group; i++) {
            for (int attempt = 0; attempt < TRIES; attempt++) {
                if (spawn(level, chunk, data.type, random) != null) {
                    spawned++;
                    break;
                }
            }
        }
        return spawned;
    }

    @Nullable
    private static Mob spawn(ServerLevel level, ChunkPos chunk, EntityType<?> type, RandomSource random) {
        int x = chunk.getBlockX(random.nextInt(16));
        int z = chunk.getBlockZ(random.nextInt(16));
        BlockPos pos = new BlockPos(x, level.getHeight(SpawnPlacements.getHeightmapType(type), x, z), z);
        if (!SpawnPlacements.isSpawnPositionOk(type, level, pos)
                || !SpawnPlacements.checkSpawnRules(type, level, MobSpawnType.NATURAL, pos, random)
                || !level.noCollision(type.getSpawnAABB(x + 0.5, pos.getY(), z + 0.5))) {
            return null;
        }
        Entity entity = type.create(level);
        if (!(entity instanceof Mob mob)) {
            if (entity != null) {
                entity.discard();
            }
            return null;
        }
        mob.moveTo(x + 0.5, pos.getY(), z + 0.5, random.nextFloat() * 360.0F, 0.0F);
        if (!EventHooks.checkSpawnPosition(mob, level, MobSpawnType.NATURAL)) {
            mob.discard();
            return null;
        }
        EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(pos), MobSpawnType.NATURAL, null);
        level.addFreshEntityWithPassengers(mob);
        return mob.isAddedToLevel() ? mob : null;
    }
}
