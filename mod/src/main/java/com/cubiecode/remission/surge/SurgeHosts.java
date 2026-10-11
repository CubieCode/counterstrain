package com.cubiecode.remission.surge;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CorruptionCause;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.corruption.Corruption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Where surge hosts spawn and what they are. A spot is a block in a loaded festering chunk (stage 1 or higher) inside
 * the surge's area, with a sturdy floor and room for the mob, within {@link #VERTICAL} blocks above or below a reference
 * height: the surface when the reference is on the surface, and cave floors and other covered spaces when the
 * reference (a player) is underground. The spot closest to the reference height in its column wins; covered spots
 * also need the type's spawn rules ({@link #fits}). Types come from {@code #remission:surge_hosts}.
 */
public final class SurgeHosts {
    /** Entity types surges spawn as hosts. */
    public static final TagKey<EntityType<?>> SURGE_HOSTS = TagKey.create(Registries.ENTITY_TYPE, Remission.id("surge_hosts"));
    /** Horizontal distance from a player to a host spawn, in blocks. */
    public static final int MIN_DISTANCE = 8;
    public static final int MAX_DISTANCE = 20;
    /** Blocks searched above and below the reference height in a spawn column. */
    public static final int VERTICAL = 16;
    private static final int ATTEMPTS_PER_HOST = 6;

    private SurgeHosts() {
    }

    /** The types in {@code #remission:surge_hosts} that can spawn as mobs, in id order. */
    public static List<EntityType<?>> types() {
        List<EntityType<?>> types = new ArrayList<>();
        BuiltInRegistries.ENTITY_TYPE.getTag(SURGE_HOSTS).ifPresent(set -> set.stream().map(Holder::value).forEach(types::add));
        types.sort(Comparator.comparing(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type)));
        return types;
    }

    /**
     * Tries to spawn {@code count} hosts at {@code minDistance}–{@code maxDistance} blocks from {@code centre}
     * horizontally, in spots (see the class comment) whose chunk {@code area} accepts. {@code finalize} runs the mob's
     * spawn finalisation (equipment, variants; scenes skip it for fixed images). Returns the hosts spawned.
     */
    public static List<Mob> spawnAround(ServerLevel level, BlockPos centre, int count, int minDistance, int maxDistance,
                                        int hostLevel, RandomSource random, boolean finalize, Predicate<ChunkPos> area) {
        List<EntityType<?>> types = types();
        List<Mob> spawned = new ArrayList<>();
        if (types.isEmpty()) {
            return spawned;
        }
        for (int i = 0; i < count; i++) {
            EntityType<?> type = types.get(random.nextInt(types.size()));
            for (int attempt = 0; attempt < ATTEMPTS_PER_HOST; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2;
                int distance = minDistance + random.nextInt(Math.max(1, maxDistance - minDistance + 1));
                int x = centre.getX() + Mth.floor(Math.cos(angle) * distance);
                int z = centre.getZ() + Mth.floor(Math.sin(angle) * distance);
                Optional<BlockPos> spot = findSpot(level, x, z, centre.getY(), type, random, area);
                if (spot.isPresent()) {
                    Mob mob = spawn(level, type, spot.get(), hostLevel, random, finalize);
                    if (mob != null) {
                        spawned.add(mob);
                    }
                    break;
                }
            }
        }
        return spawned;
    }

    /**
     * The spawn spot for {@code type} in column {@code x, z} closest to {@code referenceY} (ties: the higher one), within
     * {@link #VERTICAL} blocks of it; empty if the column's chunk is not loaded, not festering or outside {@code area},
     * or no spot fits.
     */
    public static Optional<BlockPos> findSpot(ServerLevel level, int x, int z, int referenceY, EntityType<?> type,
                                              RandomSource random, Predicate<ChunkPos> area) {
        BlockPos column = new BlockPos(x, referenceY, z);
        ChunkPos chunk = new ChunkPos(column);
        if (level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null || !area.test(chunk)
                || CorruptionQueries.stageAt(level, column) < 1) {
            return Optional.empty();
        }
        int bottom = Math.max(level.getMinBuildHeight() + 1, referenceY - VERTICAL);
        int top = Math.min(level.getMaxBuildHeight() - 2, referenceY + VERTICAL);
        for (int offset = 0; offset <= VERTICAL; offset++) {
            int up = referenceY + offset;
            if (up <= top && fits(level, new BlockPos(x, up, z), type, random)) {
                return Optional.of(new BlockPos(x, up, z));
            }
            int down = referenceY - offset;
            if (offset > 0 && down >= bottom && fits(level, new BlockPos(x, down, z), type, random)) {
                return Optional.of(new BlockPos(x, down, z));
            }
        }
        return Optional.empty();
    }

    /**
     * A sturdy floor under {@code pos}, room for the mob at it, the type's spawn placement (on the ground, in water...)
     * and, where the spot is covered (no sky above it), the type's own spawn rules for an event spawn: dark enough for
     * monsters, grass and light for animals, so cows stay out of caves. Open spots skip the rules' light and floor
     * checks: a surge brings hosts out in daylight on festering land.
     */
    static boolean fits(ServerLevel level, BlockPos pos, EntityType<?> type, RandomSource random) {
        BlockPos below = pos.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        for (BlockPos body : new BlockPos[] {pos, pos.above()}) {
            BlockState state = level.getBlockState(body);
            if (!NaturalSpawner.isValidEmptySpawnBlock(level, body, state, state.getFluidState(), type)) {
                return false;
            }
        }
        if (!SpawnPlacements.isSpawnPositionOk(type, level, pos)
                || !level.noCollision(type.getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5))) {
            return false;
        }
        return level.canSeeSky(pos) || SpawnPlacements.checkSpawnRules(type, level, MobSpawnType.EVENT, pos, random);
    }

    /**
     * Spawns a {@code type} mob at {@code pos} and makes it (and any passengers its finalisation added) a host at
     * {@code hostLevel} with {@link CorruptionCause#SURGE}. Returns null if the type is not a mob.
     */
    public static Mob spawn(ServerLevel level, EntityType<?> type, BlockPos pos, int hostLevel, RandomSource random,
                            boolean finalize) {
        Entity entity = type.create(level);
        if (!(entity instanceof Mob mob)) {
            if (entity != null) {
                entity.discard();
            }
            return null;
        }
        mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
        mob.yHeadRot = mob.getYRot();
        mob.yBodyRot = mob.getYRot();
        if (finalize) {
            EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
        }
        level.addFreshEntityWithPassengers(mob);
        mob.getSelfAndPassengers().forEach(rider -> {
            if (rider instanceof LivingEntity living) {
                Corruption.set(living, hostLevel, CorruptionCause.SURGE);
            }
        });
        return mob;
    }
}
