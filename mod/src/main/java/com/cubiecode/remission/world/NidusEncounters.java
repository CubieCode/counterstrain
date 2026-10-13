package com.cubiecode.remission.world;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.NidusDamagedEvent;
import com.cubiecode.remission.quest.QuestProgress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Nidus encounters (P4.10): how strikes, projectiles and explosions turn into damage to a nidus's vitality, and who is
 * credited when it falls. The fight itself (vitality, thresholds, regeneration, boss bar) is {@link NidusBlockEntity}.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class NidusEncounters {
    /** Players gametests add to one nidus's view of its level (mock players are never in a level's list), by nidus. */
    private static final Map<BlockPos, List<ServerPlayer>> TEST_PLAYERS = new ConcurrentHashMap<>();

    private NidusEncounters() {
    }

    /**
     * Damage of a strike by {@code player} with what it holds: its attack damage (the held item's modifiers included)
     * scaled by attack strength as a vanilla melee hit is, from 20% at no charge to 100% at full charge.
     */
    public static float strikeDamage(Player player) {
        float strength = player.getAttackStrengthScale(0.5F);
        return (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE)
                * (0.2F + strength * strength * 0.8F);
    }

    /**
     * Damage a projectile would deal a mob, before the projectile fraction: an arrow or trident deals its speed times its
     * base damage, rounded up, as vanilla arrows do; other projectiles deal none (explosive ones count through their
     * explosion).
     */
    public static float projectileDamage(Projectile projectile) {
        if (projectile instanceof AbstractArrow arrow) {
            return (float) Math.ceil(Math.max(0.0, arrow.getDeltaMovement().length() * arrow.getBaseDamage()));
        }
        return 0.0F;
    }

    /**
     * Damage an explosion of {@code radius} at {@code centre} would deal an unshielded mob at the centre of the block at
     * {@code pos}, before the explosion fraction (the vanilla formula); 0 beyond twice the radius.
     */
    public static float explosionDamage(Vec3 centre, float radius, BlockPos pos) {
        double diameter = radius * 2.0;
        if (diameter <= 0) {
            return 0.0F;
        }
        double distance = Math.sqrt(Vec3.atCenterOf(pos).distanceToSqr(centre)) / diameter;
        if (distance > 1.0) {
            return 0.0F;
        }
        double impact = 1.0 - distance;
        return (float) ((impact * impact + impact) / 2.0 * 7.0 * diameter + 1.0);
    }

    /** The player behind an explosion (who lit the TNT, fired the charge), if any. */
    @Nullable
    static ServerPlayer explosionPlayer(Explosion explosion) {
        return explosion.getIndirectSourceEntity() instanceof ServerPlayer player ? player : null;
    }

    /** Players of {@code level} as the nidus at {@code nidus} sees them (with any gametest players added for it). */
    static List<ServerPlayer> players(ServerLevel level, BlockPos nidus) {
        List<ServerPlayer> tests = TEST_PLAYERS.get(nidus);
        if (tests == null) {
            return level.players();
        }
        List<ServerPlayer> players = new ArrayList<>(level.players());
        for (ServerPlayer player : tests) {
            if (player.level() == level && !players.contains(player)) {
                players.add(player);
            }
        }
        return players;
    }

    /** An online player by id, in any level (or a gametest player), or null. */
    @Nullable
    static ServerPlayer player(ServerLevel level, UUID id) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
        if (player != null) {
            return player;
        }
        for (List<ServerPlayer> tests : TEST_PLAYERS.values()) {
            for (ServerPlayer test : tests) {
                if (test.getUUID().equals(id)) {
                    return test;
                }
            }
        }
        return null;
    }

    /**
     * Adds a mock player the encounter code then treats as online, and as in its level for the nidus at {@code nidus}
     * only, so gametests running side by side do not see each other's players.
     */
    @VisibleForTesting
    public static void addTestPlayer(BlockPos nidus, ServerPlayer player) {
        TEST_PLAYERS.computeIfAbsent(nidus.immutable(), pos -> new CopyOnWriteArrayList<>()).add(player);
    }

    /** Removes the mock players added for the nidus at {@code nidus}. */
    @VisibleForTesting
    public static void removeTestPlayers(BlockPos nidus) {
        TEST_PLAYERS.remove(nidus);
    }

    /**
     * Awards every online contributor the {@code remission:nidus_destroyed} trigger and the {@code remission:nidi_destroyed}
     * stat for the nidus at {@code pos} (already removed); {@code destroyer} is credited as is, the others are looked up
     * by id. Fake players earn nothing.
     */
    static void credit(ServerLevel level, BlockPos pos, List<UUID> contributors, @Nullable ServerPlayer destroyer) {
        for (UUID id : contributors) {
            ServerPlayer player = destroyer != null && destroyer.getUUID().equals(id) ? destroyer : player(level, id);
            if (player != null) {
                QuestProgress.nidusDestroyed(player, level, pos);
            }
        }
    }

    /** Whether {@code player} may earn credit for damage (fake players never do). */
    static boolean creditable(@Nullable ServerPlayer player) {
        return player != null && !(player instanceof FakePlayer);
    }

    // Explosions never reach a nidus through the block list (its explosion resistance keeps it off), so each loaded nidus
    // within twice the radius takes its share here.
    @SubscribeEvent
    static void onDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        NidusSettings settings = NidusSettings.current();
        if (!settings.encounters() || settings.explosionFraction() <= 0) {
            return;
        }
        Explosion explosion = event.getExplosion();
        Vec3 centre = explosion.center();
        float radius = explosion.radius();
        double reach = radius * 2.0 + 1.0;
        long now = level.getGameTime();
        for (Nidus nidus : NidusIndex.get(level).nidi()) {
            BlockPos pos = nidus.pos();
            if (Vec3.atCenterOf(pos).distanceToSqr(centre) > reach * reach || !level.isLoaded(pos)
                    || !(level.getBlockEntity(pos) instanceof NidusBlockEntity entity)) {
                continue;
            }
            float amount = explosionDamage(centre, radius, pos) * (float) settings.explosionFraction();
            Entity direct = explosion.getDirectSourceEntity();
            entity.damage(level, NidusDamagedEvent.Kind.EXPLOSION, explosionPlayer(explosion), direct, amount, settings, now);
        }
    }
}
