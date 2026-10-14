package com.cubiecode.remission.world;

import com.cubiecode.remission.api.NidusDamagedEvent;
import com.cubiecode.remission.api.ViralLoadChangedEvent;
import com.cubiecode.remission.registry.RemissionBlockEntities;
import com.cubiecode.remission.registry.RemissionSounds;
import com.cubiecode.remission.surge.Surge;
import com.cubiecode.remission.surge.Surges;
import com.cubiecode.remission.viralload.ViralLoad;
import com.cubiecode.remission.viralload.ViralLoadSettings;
import com.cubiecode.remission.viralload.ViralLoadState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * The nidus block entity. It re-indexes its nidus when loaded, which covers nidi placed by world generation or
 * structures, where {@link NidusBlock#onPlace} does not run, and holds the nidus's fight (P4.10).
 *
 * <p>A fight starts with the first damage: the nidus's vitality is fixed then from {@code [nidi] vitality}, its age
 * ({@code growthBonus} over {@code growthTicks}) and its distance from world spawn, times the player factor for the
 * players within {@code radius}; when more players come within range the vitality rises in proportion (never falls).
 * Each threshold passed starts a surge from the nidus at once, at the threshold's index (1, 2, 3...) times the player
 * factor as intensity, and lashes (below). After {@code regenDelayTicks} without damage the nidus regains
 * {@code regenRate} of its vitality per second; once whole the fight is over and the thresholds re-arm. Only a strike can
 * take the last point of vitality: the nidus dies, playing its death for {@link #DEATH_TICKS} while it takes no damage
 * and does nothing, then its block is removed, its loot drops and the striker and every player who damaged it within
 * {@code contributorWindowTicks} are credited. Players within range see a boss bar while it fights (a vanilla packet),
 * and the block's {@link NidusBlock#DAMAGE} state shows the quarters of vitality lost. Saved with the block entity, a
 * death under way included.
 *
 * <p>Tentacles (P4.19): while it can be fought it keeps one {@link NidusTentacle} hitbox per tentacle, never saved, and
 * each tentacle turns toward the player {@link NidusTentacles#assign} gives it among those within {@code lashRadius}
 * (the hitbox follows). A lash, at each threshold and every {@code lashIntervalTicks} while it is fought, is one aimed
 * strike per assigned player: the tentacle turns at the player and rears back ({@link #EVENT_LASH}), and
 * {@link NidusTentacles#LASH_CONTACT_TICKS} later the player, if still within reach, gains viral load and is knocked
 * away.
 *
 * <p>It is also a GeckoLib animatable: the client draws it with {@code client/NidusRenderer}, at the health stage the
 * {@link NidusBlock#DAMAGE} state gives. A {@code body} controller loops {@link #IDLE}, or {@link #ENRAGED} while a
 * surge from this nidus is running (synced to clients in the block entity's update tag, not saved: the surge controller
 * is the record), and a {@code hit} controller plays {@link #HIT} once on top of it for each damage dealt (a block event,
 * {@link #EVENT_HIT}), after which the loop carries on; a hit on a tentacle also makes that tentacle flinch, and the
 * model flashes red for {@link #HURT_TICKS}. Each tentacle has its own controller for its lash and flinch, which win over
 * the loop for that tentacle while they play, and a {@code death} controller plays the death over everything once it is
 * dying (synced in the update tag). The health stage picks the loops by name. Clients tick it for its spores, wet
 * heartbeat sounds and the tentacles' aim.
 */
public final class NidusBlockEntity extends BlockEntity implements GeoBlockEntity {
    /** Ticks between counts of the players in the fight (players joining raise vitality) and contributor pruning. */
    public static final int CHECK_TICKS = 20;
    /** Ticks between boss bar refreshes (who sees it); its progress also updates on every change. */
    public static final int BAR_TICKS = 10;

    /**
     * Block event id: the nidus took damage; clients play its hit reaction and flash. The param is what was hit: 0 the
     * nidus, 1 to 6 a tentacle ({@link NidusTentacles#NAMES} index plus 1), which also flinches.
     */
    public static final int EVENT_HIT = 1;
    /**
     * Block event id: a tentacle lashes. Block event params travel as one byte, so the param is the tentacle's index in the
     * low 3 bits and the bearing it lashes toward in the high 5, in steps of {@link #LASH_BEARING_STEP} degrees
     * ({@link #lashParam}).
     */
    public static final int EVENT_LASH = 2;
    /** Degrees per step of a lash event's bearing: 32 steps around. */
    public static final float LASH_BEARING_STEP = 360.0F / 32.0F;
    /** Ticks the model flashes red after a hit, as a mob's hurt time. */
    public static final int HURT_TICKS = 10;
    /** Ticks from the final blow until the block goes: the death animation's 2.5 s. */
    public static final int DEATH_TICKS = 50;
    /** The health stage's loops, by stage: healthy and lightly wounded share one pair. */
    private static final RawAnimation[] IDLE = loops("idle");
    private static final RawAnimation[] ENRAGED = loops("enraged");
    public static final RawAnimation HIT = RawAnimation.begin().thenPlay("animation.nidus.hit");
    public static final RawAnimation DEATH = RawAnimation.begin().thenPlayAndHold("animation.nidus.death");
    /** Extra reach beyond {@code lashRadius} a lash still lands at: a player must step clearly back to dodge it. */
    public static final double LASH_DODGE_MARGIN = 0.5;
    /** Ticks after a player comes within reach before the first lash at them, fought or not. */
    public static final int FIRST_LASH_TICKS = 20;

    /** Ticks the body controller takes to blend between idle and enraged. */
    public static final int BLEND_TICKS = 10;
    private static final Vector3f SPORE_GREEN = new Vector3f(0.62F, 0.9F, 0.25F);
    private static final Vector3f SPORE_VIOLET = new Vector3f(0.5F, 0.22F, 0.6F);
    private static final Vector3f WOUND_CRIMSON = new Vector3f(0.75F, 0.08F, 0.12F);

    private static RawAnimation[] loops(String name) {
        RawAnimation calm = RawAnimation.begin().thenLoop("animation.nidus." + name);
        return new RawAnimation[] {calm, calm, RawAnimation.begin().thenLoop("animation.nidus.badly_wounded." + name),
                RawAnimation.begin().thenLoop("animation.nidus.near_death." + name)};
    }

    private float base;
    private int players;
    private float damage;
    private int thresholdsPassed;
    private long lastDamaged;
    private final Map<UUID, Long> contributors = new LinkedHashMap<>();
    /** Game time of each player's last strike that counted, for the strike cooldown; transient. */
    private final Map<UUID, Long> lastStrikes = new HashMap<>();

    /** A surge from this nidus is running: the body thrashes (server: from the surge controller; client: synced). */
    private boolean enraged;
    private final AnimatableInstanceCache animations = GeckoLibUtil.createInstanceCache(this);
    /** Client: the health stage the body controller's loop was chosen for. */
    private int animatedStage = -1;
    /** Client: game time of the last hit, for the red flash. */
    private long lastHit = Long.MIN_VALUE;

    /** The tentacles' aim (server: moves the hitboxes; client: drawn). */
    private final NidusTentacles.Aim aim = new NidusTentacles.Aim();
    /** Server: the tentacle hitboxes, by tentacle; transient. */
    private final NidusTentacle[] hitboxes = new NidusTentacle[NidusTentacles.COUNT];
    /** Server: lashes on their way to their players; transient. */
    private final List<PendingLash> lashes = new ArrayList<>();
    /** Server: game time of the last lash, for {@code lashIntervalTicks}; transient. */
    private long lastLash = Long.MIN_VALUE;
    /** Server: game time its last surge ended (it stopped being enraged), for regeneration; transient. */
    private long calmedAt = Long.MIN_VALUE;

    /** Game time of the final blow, or {@link Long#MIN_VALUE} while alive (client: whether it is dying, synced). */
    private long dyingSince = Long.MIN_VALUE;
    /** Who struck the final blow, while dying. */
    @Nullable
    private UUID killer;
    /** Players credited with the destruction, fixed at the final blow. */
    private final List<UUID> killers = new ArrayList<>();

    private final ServerBossEvent bar = new ServerBossEvent(Component.translatable("event.remission.nidus.bar"),
            BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
    /** Who destroyed it, set just before a player's destruction removes the block (read by {@link NidusBlock#onRemove}). */
    @Nullable
    private Destruction destruction;
    /** Hosts rallying to the nidus while it is under attack (P4.13); transient. */
    private final NidusRally rally;

    /** A player's destruction of the nidus: the player and the credited contributors (the player included). */
    record Destruction(@Nullable ServerPlayer player, List<UUID> contributors) {
    }

    /** A lash by {@code tentacle} that reaches {@code player} at game time {@code contact}. */
    private record PendingLash(UUID player, int tentacle, long contact) {
    }

    public NidusBlockEntity(BlockPos pos, BlockState state) {
        super(RemissionBlockEntities.NIDUS.get(), pos, state);
        rally = new NidusRally(pos);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            FesteringLand.nidusPresent(serverLevel, worldPosition);
        }
    }

    // ---- state ------------------------------------------------------------------------------------------------------

    /** Whether a fight is under way (it has been damaged and has not regenerated fully since). */
    public boolean fighting() {
        return base > 0;
    }

    /** Players counted for the fight; 0 when there is none. */
    public int fightPlayers() {
        return players;
    }

    /** Thresholds passed in this fight. */
    public int thresholdsPassed() {
        return thresholdsPassed;
    }

    /** Damage taken in this fight. */
    public float damageTaken() {
        return damage;
    }

    /** Full vitality now: of the fight if one is under way, else what a fight by one player would start with. */
    public float maxVitality(NidusSettings settings) {
        if (fighting()) {
            return (float) (base * settings.playerMultiplier(players));
        }
        return level instanceof ServerLevel serverLevel ? baseVitality(serverLevel, settings) : (float) settings.vitality();
    }

    /** Vitality left. */
    public float vitality(NidusSettings settings) {
        return Math.max(0.0F, maxVitality(settings) - damage);
    }

    /** Vitality before the player factor: configured vitality times growth and distance. */
    float baseVitality(ServerLevel level, NidusSettings settings) {
        long now = level.getGameTime();
        long age = now - NidusIndex.get(level).since(worldPosition, now);
        BlockPos spawn = level.getSharedSpawnPos();
        double dx = worldPosition.getX() - spawn.getX();
        double dz = worldPosition.getZ() - spawn.getZ();
        return (float) (settings.vitality() * settings.growthMultiplier(age) * settings.distanceMultiplier(Math.sqrt(dx * dx + dz * dz)));
    }

    /** Players who count toward the fight: alive, in survival or adventure, within {@code radius} blocks. */
    List<ServerPlayer> playersInRange(ServerLevel level, double radius) {
        Vec3 centre = Vec3.atCenterOf(worldPosition);
        List<ServerPlayer> found = new ArrayList<>();
        for (ServerPlayer player : NidusEncounters.players(level, worldPosition)) {
            if (player.isAlive() && !player.isSpectator() && !player.isCreative() && player.level() == level
                    && player.distanceToSqr(centre) <= radius * radius && NidusEncounters.creditable(player)) {
                found.add(player);
            }
        }
        return found;
    }

    // ---- damage -----------------------------------------------------------------------------------------------------

    /**
     * A strike by {@code player} at game time {@code now}: unless the player struck this nidus within
     * {@code strikeCooldownTicks}, it deals {@link NidusEncounters#strikeDamage} (charge scaled), resets the player's
     * attack strength and goes through {@link #damage}. Fake players' strikes do nothing. Returns the damage dealt.
     */
    public float strike(ServerLevel level, ServerPlayer player, NidusSettings settings, long now) {
        return strike(level, player, settings, now, null);
    }

    /**
     * A strike as {@link #strike(ServerLevel, ServerPlayer, NidusSettings, long)}, on {@code tentacle}'s hitbox when not
     * null: then it deals {@code tentacleDamageFactor} of the damage, the event's direct entity is the hitbox and the hit
     * event names the tentacle.
     */
    public float strike(ServerLevel level, ServerPlayer player, NidusSettings settings, long now, @Nullable NidusTentacle tentacle) {
        if (!settings.encounters() || !NidusEncounters.creditable(player) || dying()) {
            return 0.0F;
        }
        Long last = lastStrikes.get(player.getUUID());
        if (last != null && now - last < settings.strikeCooldownTicks() && now >= last) {
            return 0.0F;
        }
        float amount = NidusEncounters.strikeDamage(player);
        player.resetAttackStrengthTicker();
        lastStrikes.put(player.getUUID(), now);
        if (tentacle == null) {
            return damage(level, NidusDamagedEvent.Kind.STRIKE, player, null, amount, settings, now);
        }
        return damage(level, NidusDamagedEvent.Kind.STRIKE, player, tentacle, amount * (float) settings.tentacleDamageFactor(),
                settings, now, tentacle.tentacle() + 1);
    }

    /**
     * Deals {@code amount} (after any projectile or explosion fraction) to the nidus's vitality at game time {@code now},
     * after {@link NidusDamagedEvent}. Only damage traceable to a player counts: with no player (or a fake player) it does
     * nothing, so hosts, mob explosions and unattributed TNT never wear a nidus down. Starts a fight if none is under way,
     * credits {@code player}, passes thresholds and, for a strike that takes the last point, destroys the nidus.
     * Projectile and explosion damage stops at 1 vitality. Does nothing while encounters are off. Returns the damage dealt.
     */
    public float damage(ServerLevel level, NidusDamagedEvent.Kind kind, @Nullable ServerPlayer player, @Nullable Entity direct,
                        float amount, NidusSettings settings, long now) {
        return damage(level, kind, player, direct, amount, settings, now, 0);
    }

    /**
     * Damage as {@link #damage(ServerLevel, NidusDamagedEvent.Kind, ServerPlayer, Entity, float, NidusSettings, long)}
     * to {@code part}: 0 the nidus, 1 to 6 a tentacle (sent with the hit event, so it flinches). A dying nidus takes none.
     */
    public float damage(ServerLevel level, NidusDamagedEvent.Kind kind, @Nullable ServerPlayer player, @Nullable Entity direct,
                        float amount, NidusSettings settings, long now, int part) {
        if (!settings.encounters() || isRemoved() || dying() || !(amount > 0) || !NidusEncounters.creditable(player)) {
            return 0.0F;
        }
        NidusDamagedEvent event = NeoForge.EVENT_BUS.post(new NidusDamagedEvent(level, worldPosition, kind, player, direct, amount));
        float dealt = event.getAmount();
        if (event.isCanceled() || !(dealt > 0)) {
            return 0.0F;
        }
        if (fighting()) {
            join(level, settings);
        } else {
            base = baseVitality(level, settings);
            players = Math.max(1, playersInRange(level, settings.radius()).size());
            damage = 0.0F;
            thresholdsPassed = 0;
        }
        float max = maxVitality(settings);
        if (kind != NidusDamagedEvent.Kind.STRIKE) {
            dealt = Math.min(dealt, max - damage - 1.0F);
            if (dealt <= 0) {
                return 0.0F;
            }
        }
        damage += dealt;
        lastDamaged = now;
        contributors.put(player.getUUID(), now);
        setChanged();
        if (damage >= max && kind == NidusDamagedEvent.Kind.STRIKE) {
            die(level, player, settings, now);
            return dealt;
        }
        level.blockEvent(worldPosition, getBlockState().getBlock(), EVENT_HIT, part);
        float percent = (max - damage) / max * 100.0F;
        List<Integer> thresholds = settings.thresholds();
        while (thresholdsPassed < thresholds.size() && percent <= thresholds.get(thresholdsPassed)) {
            thresholdsPassed++;
            fightBack(level, settings, thresholdsPassed, now);
        }
        updateBar(level, settings);
        updateDamageState(level, settings);
        return dealt;
    }

    /** Raises the fight's player count, and its vitality in proportion, when more players are in range. */
    private void join(ServerLevel level, NidusSettings settings) {
        int count = playersInRange(level, settings.radius()).size();
        if (count > players) {
            float oldMax = maxVitality(settings);
            players = count;
            float newMax = maxVitality(settings);
            if (oldMax > 0) {
                damage *= newMax / oldMax;
            }
            setChanged();
            updateDamageState(level, settings);
        }
    }

    /** Threshold {@code index} (from 1) passed: a surge from this nidus at once, and a lash. */
    private void fightBack(ServerLevel level, NidusSettings settings, int index, long now) {
        int intensity = (int) Math.round(index * settings.playerMultiplier(players));
        Surges.controller(level).startNow(worldPosition, intensity, now);
        lash(level, settings, now);
        SympatheticSurges.thresholdPassed(level, worldPosition, intensity, NidusDefenceSettings.current(), now);
    }

    /** Players the tentacles track: as {@link #playersInRange} within {@code lashRadius}. */
    private List<ServerPlayer> lashTargets(ServerLevel level, NidusSettings settings) {
        return settings.lashRadius() > 0 ? playersInRange(level, settings.lashRadius()) : List.of();
    }

    /**
     * A lash at {@code now}: each tentacle with a player assigned (and not still lashing) turns at that player and rears
     * back (clients see {@link #EVENT_LASH}); {@link NidusTentacles#LASH_CONTACT_TICKS} later it strikes. Players with no
     * tentacle are spared.
     */
    public void lash(ServerLevel level, NidusSettings settings, long now) {
        lastLash = now;
        Vec3 centre = Vec3.atCenterOf(worldPosition);
        boolean any = false;
        for (NidusTentacles.Assignment assignment : NidusTentacles.assign(centre, lashTargets(level, settings))) {
            int tentacle = assignment.tentacle();
            if (aim.lashing(tentacle, now)) {
                continue;
            }
            any = true;
            aim.lash(tentacle, assignment.bearing(), now);
            level.blockEvent(worldPosition, getBlockState().getBlock(), EVENT_LASH, lashParam(tentacle, assignment.bearing()));
            lashes.add(new PendingLash(assignment.player().getUUID(), tentacle, now + NidusTentacles.LASH_CONTACT_TICKS));
        }
        if (any) {
            level.playSound(null, worldPosition, SoundEvents.WARDEN_ATTACK_IMPACT, SoundSource.HOSTILE, 2.0F, 0.6F);
        }
    }

    /** The {@link #EVENT_LASH} param for {@code tentacle} lashing toward {@code bearing}. */
    public static int lashParam(int tentacle, float bearing) {
        int step = Math.floorMod(Math.round(bearing / LASH_BEARING_STEP), 32);
        return tentacle | step << 3;
    }

    /** Lashes that reach their players at {@code now} strike them, if they are still within reach. */
    private void landLashes(ServerLevel level, NidusSettings settings, long now) {
        if (lashes.isEmpty()) {
            return;
        }
        List<ServerPlayer> inReach = playersInRange(level, settings.lashRadius() + LASH_DODGE_MARGIN);
        for (var it = lashes.iterator(); it.hasNext(); ) {
            PendingLash lash = it.next();
            if (now < lash.contact()) {
                continue;
            }
            it.remove();
            for (ServerPlayer player : inReach) {
                if (player.getUUID().equals(lash.player())) {
                    strikePlayer(level, settings, player);
                }
            }
        }
    }

    /** A lash lands on {@code player}: viral load (while viral load is enabled) and knockback away from the nidus. */
    private void strikePlayer(ServerLevel level, NidusSettings settings, ServerPlayer player) {
        Vec3 centre = Vec3.atCenterOf(worldPosition);
        if (settings.lashViralLoad() > 0 && ViralLoadSettings.fromConfig().enabled()) {
            ViralLoad.set(player, ViralLoad.get(player) + (float) settings.lashViralLoad(), ViralLoadState.Trend.RISING,
                    ViralLoadChangedEvent.Cause.NIDUS);
        }
        if (settings.lashKnockback() > 0) {
            double dx = centre.x - player.getX();
            double dz = centre.z - player.getZ();
            if (dx * dx + dz * dz < 1.0E-4) {
                dz = 1.0;
            }
            player.knockback(settings.lashKnockback(), dx, dz);
            player.hurtMarked = true;
        }
        player.displayClientMessage(Component.translatable("event.remission.nidus.lash"), true);
        level.playSound(null, player.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 1.5F, 0.9F);
        level.sendParticles(ParticleTypes.SCULK_SOUL, player.getX(), player.getY() + 1.0, player.getZ(), 12, 0.3, 0.5, 0.3, 0.04);
    }

    /** Contributors credited at {@code now}: players who damaged it within the window, then {@code destroyer} if absent. */
    List<UUID> credited(NidusSettings settings, long now, @Nullable ServerPlayer destroyer) {
        List<UUID> credited = new ArrayList<>();
        contributors.forEach((id, time) -> {
            if (now - time <= settings.contributorWindowTicks()) {
                credited.add(id);
            }
        });
        if (NidusEncounters.creditable(destroyer) && !credited.contains(destroyer.getUUID())) {
            credited.add(destroyer.getUUID());
        }
        return credited;
    }

    /**
     * The final blow: the nidus starts dying (its fight, bar, hitboxes and lashes end; clients play its death) with the
     * credit fixed now; {@link #DEATH_TICKS} later {@link #finishDying} removes it.
     */
    private void die(ServerLevel level, ServerPlayer player, NidusSettings settings, long now) {
        dyingSince = now;
        killer = player.getUUID();
        killers.clear();
        killers.addAll(credited(settings, now, player));
        bar.removeAllPlayers();
        lashes.clear();
        discardHitboxes();
        enraged = false;
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        level.playSound(null, worldPosition, SoundEvents.WARDEN_DEATH, SoundSource.HOSTILE, 3.0F, 0.6F);
    }

    /** The death has played: removes the block with its drops, posting the destroyed event, then credits every contributor. */
    private void finishDying(ServerLevel level) {
        ServerPlayer player = killer == null ? null : NidusEncounters.player(level, killer);
        List<UUID> credited = List.copyOf(killers);
        BlockPos pos = worldPosition;
        destruction = new Destruction(player, credited);
        boolean removed = level.destroyBlock(pos, true, player);
        destruction = null;
        if (removed) {
            NidusEncounters.credit(level, pos, credited, player);
        }
    }

    /** Whether the final blow has been struck and the nidus is playing its death (on clients, as last synced). */
    public boolean dying() {
        return dyingSince != Long.MIN_VALUE;
    }

    /** A player is breaking the block outright (creative, or plain mining): remember who for the destroyed event. */
    void breaking(@Nullable ServerPlayer player, NidusSettings settings, long now) {
        destruction = player == null ? null : new Destruction(player, credited(settings, now, player));
    }

    /** Takes the pending destruction, if a player is destroying the block. */
    @Nullable
    Destruction takeDestruction() {
        Destruction taken = destruction;
        destruction = null;
        return taken;
    }

    // ---- ticking ----------------------------------------------------------------------------------------------------

    public static void serverTick(Level level, BlockPos pos, BlockState state, NidusBlockEntity entity) {
        if (level instanceof ServerLevel serverLevel) {
            entity.tick(serverLevel, NidusSettings.current(), serverLevel.getGameTime());
        }
    }

    /**
     * One tick at game time {@code now}: the enraged check (every {@link #CHECK_TICKS}; surge events set it at once),
     * regeneration, players joining, contributor pruning, boss bar and damage state.
     */
    public void tick(ServerLevel level, NidusSettings settings, long now) {
        if (dying()) {
            if (now - dyingSince >= DEATH_TICKS || now < dyingSince) {
                finishDying(level);
            }
            return;
        }
        List<ServerPlayer> targets = tentacles(level, settings, now);
        if (now % CHECK_TICKS == 0) {
            Surge surge = Surges.controller(level).current();
            setEnraged(surge != null && surge.started() && surge.origin().equals(worldPosition));
        }
        periodicLash(level, settings, targets, now);
        rally.tick(level, underAttack(settings, now), NidusDefenceSettings.current(), now);
        if (!fighting()) {
            return;
        }
        if (!settings.encounters()) {
            endFight(level);
            return;
        }
        if (regenerating(settings, now)) {
            damage -= (float) (settings.regenRate() * maxVitality(settings) / 20.0);
            setChanged();
            if (damage <= 0) {
                endFight(level);
                return;
            }
        }
        if (now % CHECK_TICKS == 0) {
            join(level, settings);
            if (contributors.values().removeIf(time -> now - time > settings.contributorWindowTicks())) {
                setChanged();
            }
        }
        if (now % CHECK_TICKS == 0) {
            lastStrikes.values().removeIf(time -> now - time >= settings.strikeCooldownTicks() || now < time);
        }
        // While it regenerates, the bar moves only every BAR_TICKS or when it has moved by 1% since last sent.
        if (now % BAR_TICKS == 0) {
            updateBar(level, settings);
        } else if (Math.abs(progress(settings) - bar.getProgress()) >= 0.01F) {
            bar.setProgress(progress(settings));
        }
        updateDamageState(level, settings);
    }

    /**
     * Whether it regenerates at {@code now}: not while a surge from it runs, and only once {@code regenDelayTicks} have
     * passed both since it was last damaged and since its last surge ended, so a fight's surge is never time it heals.
     */
    private boolean regenerating(NidusSettings settings, long now) {
        return settings.regenRate() > 0 && !enraged && now - lastDamaged >= settings.regenDelayTicks()
                && (calmedAt == Long.MIN_VALUE || now - calmedAt >= settings.regenDelayTicks() || now < calmedAt);
    }

    /**
     * Lashes on its own, fought or not, while encounters are on: {@link #FIRST_LASH_TICKS} after a player comes within
     * reach, then every {@code lashIntervalTicks} while anyone stays there (a threshold's lash restarts the count).
     */
    private void periodicLash(ServerLevel level, NidusSettings settings, List<ServerPlayer> targets, long now) {
        if (!settings.encounters() || settings.lashIntervalTicks() <= 0 || targets.isEmpty()) {
            lastLash = Long.MIN_VALUE;
            return;
        }
        if (lastLash == Long.MIN_VALUE || now < lastLash) {
            lastLash = now - settings.lashIntervalTicks() + FIRST_LASH_TICKS;
        }
        if (now - lastLash >= settings.lashIntervalTicks()) {
            lash(level, settings, now);
        }
    }

    /**
     * The tentacles at {@code now}: lashes land, the aim eases toward the players in reach, and the hitboxes (while
     * encounters are on) are spawned where missing and moved to follow. Returns the players in reach.
     */
    private List<ServerPlayer> tentacles(ServerLevel level, NidusSettings settings, long now) {
        landLashes(level, settings, now);
        List<ServerPlayer> targets = lashTargets(level, settings);
        aim.tick(Vec3.atCenterOf(worldPosition), targets, now);
        if (!settings.encounters()) {
            discardHitboxes();
            return targets;
        }
        for (int tentacle = 0; tentacle < NidusTentacles.COUNT; tentacle++) {
            NidusTentacle hitbox = hitboxes[tentacle];
            if (hitbox == null || hitbox.isRemoved()) {
                hitbox = NidusTentacle.create(level, worldPosition, tentacle);
                hitboxes[tentacle] = hitbox;
                placeHitbox(hitbox, tentacle, now);
                level.addFreshEntity(hitbox);
            } else {
                placeHitbox(hitbox, tentacle, now);
            }
        }
        return targets;
    }

    private void placeHitbox(NidusTentacle hitbox, int tentacle, long now) {
        if (aim.grounded(tentacle, now)) {
            hitbox.placeOnGround(aim.lashBearing(tentacle));
        } else {
            hitbox.place(aim.aim(tentacle));
        }
    }

    /** Whether {@code hitbox} is one of this nidus's current tentacle hitboxes. */
    boolean owns(NidusTentacle hitbox) {
        int tentacle = hitbox.tentacle();
        return !isRemoved() && tentacle >= 0 && tentacle < hitboxes.length && hitboxes[tentacle] == hitbox;
    }

    /** Its current tentacle hitboxes (server), by tentacle; an entry is null or removed while missing. */
    public List<NidusTentacle> hitboxes() {
        List<NidusTentacle> list = new ArrayList<>();
        for (NidusTentacle hitbox : hitboxes) {
            if (hitbox != null && !hitbox.isRemoved()) {
                list.add(hitbox);
            }
        }
        return list;
    }

    private void discardHitboxes() {
        for (int tentacle = 0; tentacle < hitboxes.length; tentacle++) {
            if (hitboxes[tentacle] != null) {
                hitboxes[tentacle].discard();
                hitboxes[tentacle] = null;
            }
        }
    }

    /** The tentacles' aim. */
    public NidusTentacles.Aim aim() {
        return aim;
    }

    /** The fight is over (vitality whole again): thresholds re-arm, the bar goes and the block shows no damage. */
    private void endFight(ServerLevel level) {
        base = 0.0F;
        players = 0;
        damage = 0.0F;
        thresholdsPassed = 0;
        setChanged();
        bar.removeAllPlayers();
        updateDamageState(level, null);
    }

    private float progress(NidusSettings settings) {
        float max = maxVitality(settings);
        return max <= 0 ? 0.0F : Math.clamp(vitality(settings) / max, 0.0F, 1.0F);
    }

    /** Keeps the boss bar on the players within {@code radius} (spectators and creative players too) at the current vitality. */
    private void updateBar(ServerLevel level, NidusSettings settings) {
        bar.setProgress(progress(settings));
        Vec3 centre = Vec3.atCenterOf(worldPosition);
        double radius = settings.radius();
        List<ServerPlayer> inRange = new ArrayList<>();
        for (ServerPlayer player : NidusEncounters.players(level, worldPosition)) {
            if (player.level() == level && player.distanceToSqr(centre) <= radius * radius) {
                inRange.add(player);
            }
        }
        for (ServerPlayer player : new ArrayList<>(bar.getPlayers())) {
            if (!inRange.contains(player)) {
                bar.removePlayer(player);
            }
        }
        inRange.forEach(bar::addPlayer);
    }

    /** The players the boss bar is shown to. */
    public List<ServerPlayer> barPlayers() {
        return new ArrayList<>(bar.getPlayers());
    }

    /** The boss bar's progress (vitality left, 0 to 1). */
    public float barProgress() {
        return bar.getProgress();
    }

    /**
     * Sets the block's {@link NidusBlock#DAMAGE} to the quarters of vitality lost (0 to 3), so every player sees how far
     * the fight has gone; 0 with {@code settings} null (the fight is over).
     */
    private void updateDamageState(ServerLevel level, @Nullable NidusSettings settings) {
        float max = settings == null ? 0.0F : maxVitality(settings);
        int stage = max <= 0 ? 0 : Math.clamp((int) (damage / max * 4.0F), 0, NidusBlock.MAX_DAMAGE);
        BlockState state = getBlockState();
        if (state.hasProperty(NidusBlock.DAMAGE) && state.getValue(NidusBlock.DAMAGE) != stage
                && level.getBlockState(worldPosition).is(state.getBlock())) {
            level.setBlock(worldPosition, state.setValue(NidusBlock.DAMAGE, stage), Block.UPDATE_CLIENTS);
        }
    }

    /** The damage shown on the block (0 to 3). */
    public int damageStage() {
        BlockState state = getBlockState();
        return state.hasProperty(NidusBlock.DAMAGE) ? state.getValue(NidusBlock.DAMAGE) : 0;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        bar.removeAllPlayers();
        if (level != null && !level.isClientSide) {
            discardHitboxes();
        }
    }

    // ---- defence (P4.13) --------------------------------------------------------------------------------------------

    /** Whether the nidus is under attack at {@code now}: in a fight and damaged within {@code contributorWindowTicks}. */
    public boolean underAttack(NidusSettings settings, long now) {
        return fighting() && settings.encounters() && now - lastDamaged <= settings.contributorWindowTicks();
    }

    /** The hosts rallying to it. */
    public NidusRally rally() {
        return rally;
    }

    // ---- appearance -------------------------------------------------------------------------------------------------

    /** Whether a surge from this nidus is running (on clients, as last synced). */
    public boolean enraged() {
        return enraged;
    }

    /** Server: sets whether a surge from this nidus is running and sends it to clients tracking the chunk on a change. */
    public void setEnraged(boolean value) {
        if (enraged == value) {
            return;
        }
        enraged = value;
        if (!value && level != null) {
            calmedAt = level.getGameTime();
        }
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * The health stage the nidus shows, 0 (healthy) to {@link NidusBlock#MAX_DAMAGE} (near death): its
     * {@link NidusBlock#DAMAGE} state, so with the default fight it is healthy above 75% vitality, lightly wounded above
     * 50%, badly wounded above 25% and near death at 25% or below.
     */
    public int healthStage() {
        return damageStage();
    }

    @Override
    public boolean triggerEvent(int id, int param) {
        if (id == EVENT_HIT) {
            if (level != null && level.isClientSide) {
                lastHit = level.getGameTime();
                triggerAnim("hit", "hit");
                if (param >= 1 && param <= NidusTentacles.COUNT) {
                    triggerAnim(tentacleController(param - 1), "flinch");
                }
            }
            return true;
        }
        if (id == EVENT_LASH) {
            int tentacle = param & 7;
            if (level != null && level.isClientSide && tentacle < NidusTentacles.COUNT) {
                aim.lash(tentacle, (param >> 3 & 31) * LASH_BEARING_STEP, level.getGameTime());
                triggerAnim(tentacleController(tentacle), "lash");
            }
            return true;
        }
        return super.triggerEvent(id, param);
    }

    /** Client: whether the model shows the red hurt flash at game time {@code now}. */
    public boolean hurtFlash(long now) {
        return lastHit != Long.MIN_VALUE && now >= lastHit && now - lastHit < HURT_TICKS;
    }

    private static String tentacleController(int tentacle) {
        return "tentacle_" + NidusTentacles.NAMES.get(tentacle);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "body", BLEND_TICKS, state -> {
            int stage = Math.clamp(healthStage(), 0, NidusBlock.MAX_DAMAGE);
            if (stage != animatedStage) {
                animatedStage = stage;
                state.getController().forceAnimationReset();
            }
            return state.setAndContinue(enraged ? ENRAGED[stage] : IDLE[stage]);
        }));
        controllers.add(new AnimationController<>(this, "hit", 0, state -> PlayState.STOP).triggerableAnim("hit", HIT));
        // After the body, so a tentacle's lash or flinch wins over the loop for that tentacle's bones while it plays.
        for (String name : NidusTentacles.NAMES) {
            // No transition: GeckoLib's transition delays the animation by its length, which would put the strike after
            // the server's contact tick.
            controllers.add(new AnimationController<>(this, "tentacle_" + name, 0, state -> PlayState.STOP)
                    .triggerableAnim("lash", RawAnimation.begin().thenPlay("animation.nidus.lash_" + name))
                    .triggerableAnim("flinch", RawAnimation.begin().thenPlay("animation.nidus.flinch_" + name)));
        }
        // Last, over everything.
        controllers.add(new AnimationController<>(this, "death", 0,
                state -> dying() ? state.setAndContinue(DEATH) : PlayState.STOP));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return animations;
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, NidusBlockEntity entity) {
        if (entity.dying()) {
            return;
        }
        entity.trackPlayers(level);
        entity.ambience(level, level.random);
    }

    /**
     * Client: the tentacles turn toward the players in reach as the server's do (the same players, by the same rules), so
     * what is drawn matches the hitboxes.
     */
    private void trackPlayers(Level level) {
        Vec3 centre = Vec3.atCenterOf(worldPosition);
        double radius = NidusSettings.current().lashRadius();
        List<Entity> players = new ArrayList<>();
        for (var player : level.players()) {
            if (player.isAlive() && !player.isSpectator() && !player.isCreative() && player.distanceToSqr(centre) <= radius * radius) {
                players.add(player);
            }
        }
        aim.tick(centre, players, level.getGameTime());
    }

    /**
     * Client: spores drifting up from the crown (more while enraged), crimson drops from the wounds once badly wounded,
     * and now and then the festering land's wet additions sound from the nidus itself, faster and lower as it weakens.
     */
    private void ambience(Level level, RandomSource random) {
        int stage = healthStage();
        double x = worldPosition.getX() + 0.5;
        double y = worldPosition.getY();
        double z = worldPosition.getZ() + 0.5;
        if (random.nextInt(enraged ? 2 : 6) == 0) {
            Vector3f colour = random.nextInt(3) == 0 ? SPORE_VIOLET : SPORE_GREEN;
            level.addParticle(new DustParticleOptions(colour, 0.7F + random.nextFloat() * 0.5F),
                    x + random.triangle(0.0, 1.2), y + 1.0 + random.nextDouble() * 1.4, z + random.triangle(0.0, 1.2),
                    0.0, 0.02 + random.nextDouble() * 0.03, 0.0);
        }
        if (stage >= 2 && random.nextInt(stage == NidusBlock.MAX_DAMAGE ? 4 : 10) == 0) {
            level.addParticle(new DustParticleOptions(WOUND_CRIMSON, 0.8F),
                    x + random.triangle(0.0, 0.7), y + 0.4 + random.nextDouble() * 0.9, z - 0.55 + random.triangle(0.0, 0.3),
                    0.0, -0.03, 0.0);
        }
        int soundChance = enraged ? 60 : 200 - stage * 40;
        if (random.nextInt(soundChance) == 0) {
            level.playLocalSound(worldPosition, RemissionSounds.FESTER_ADDITIONS.get(), SoundSource.BLOCKS, 0.6F,
                    0.8F - stage * 0.08F + random.nextFloat() * 0.1F, false);
        }
    }

    // ---- syncing ----------------------------------------------------------------------------------------------------

    /** What clients get with the chunk and on {@link #setEnraged} changes and the final blow: whether it is enraged or dying. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("enraged", enraged);
        if (dying()) {
            tag.putBoolean("dying", true);
        }
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        enraged = tag.getBoolean("enraged");
        dyingSince = tag.getBoolean("dying") ? 0L : Long.MIN_VALUE;
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        handleUpdateTag(packet.getTag(), registries);
    }

    // ---- saving -----------------------------------------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (dying()) {
            CompoundTag death = new CompoundTag();
            death.putLong("since", dyingSince);
            if (killer != null) {
                death.put("killer", NbtUtils.createUUID(killer));
            }
            ListTag credited = new ListTag();
            killers.forEach(id -> credited.add(NbtUtils.createUUID(id)));
            death.put("credited", credited);
            tag.put("death", death);
        }
        if (!fighting() && contributors.isEmpty()) {
            return;
        }
        CompoundTag fight = new CompoundTag();
        fight.putFloat("base", base);
        fight.putInt("players", players);
        fight.putFloat("damage", damage);
        fight.putInt("thresholds_passed", thresholdsPassed);
        fight.putLong("last_damaged", lastDamaged);
        ListTag list = new ListTag();
        contributors.forEach((id, time) -> {
            CompoundTag contributor = new CompoundTag();
            contributor.put("player", NbtUtils.createUUID(id));
            contributor.putLong("time", time);
            list.add(contributor);
        });
        fight.put("contributors", list);
        tag.put("fight", fight);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        killers.clear();
        if (tag.contains("death", Tag.TAG_COMPOUND)) {
            CompoundTag death = tag.getCompound("death");
            dyingSince = death.getLong("since");
            killer = death.hasUUID("killer") ? death.getUUID("killer") : null;
            for (Tag element : death.getList("credited", Tag.TAG_INT_ARRAY)) {
                killers.add(NbtUtils.loadUUID(element));
            }
        } else {
            dyingSince = Long.MIN_VALUE;
            killer = null;
        }
        CompoundTag fight = tag.getCompound("fight");
        base = Math.max(0.0F, fight.getFloat("base"));
        players = fight.getInt("players");
        damage = Math.max(0.0F, fight.getFloat("damage"));
        thresholdsPassed = fight.getInt("thresholds_passed");
        lastDamaged = fight.getLong("last_damaged");
        contributors.clear();
        for (Tag element : fight.getList("contributors", Tag.TAG_COMPOUND)) {
            CompoundTag contributor = (CompoundTag) element;
            if (contributor.hasUUID("player")) {
                contributors.put(contributor.getUUID("player"), contributor.getLong("time"));
            }
        }
    }
}
