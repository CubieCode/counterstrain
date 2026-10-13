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
 * factor as intensity, and lashes the players within {@code lashRadius}. After {@code regenDelayTicks} without damage the
 * nidus regains {@code regenRate} of its vitality per second; once whole the fight is over and the thresholds re-arm.
 * Only a strike can take the last point of vitality: the striker destroys it, its loot drops and every player who
 * damaged it within {@code contributorWindowTicks} is credited. Players within range see a boss bar while it fights (a
 * vanilla packet), and the block's {@link NidusBlock#DAMAGE} state shows the quarters of vitality lost. Saved with the
 * block entity.
 *
 * <p>It is also a GeckoLib animatable: the client draws it with {@code client/NidusRenderer}, at the health stage the
 * {@link NidusBlock#DAMAGE} state gives. A {@code body} controller loops {@link #IDLE}, or {@link #ENRAGED} while a
 * surge from this nidus is running (synced to clients in the block entity's update tag, not saved: the surge controller
 * is the record), and a {@code hit} controller plays {@link #HIT} once on top of it for each damage dealt (a block event,
 * {@link #EVENT_HIT}), after which the loop carries on. Clients tick it for its spores and wet heartbeat sounds.
 */
public final class NidusBlockEntity extends BlockEntity implements GeoBlockEntity {
    /** Ticks between counts of the players in the fight (players joining raise vitality) and contributor pruning. */
    public static final int CHECK_TICKS = 20;
    /** Ticks between boss bar refreshes (who sees it); its progress also updates on every change. */
    public static final int BAR_TICKS = 10;

    /** Block event id: the nidus took damage; clients play its hit reaction. */
    public static final int EVENT_HIT = 1;
    public static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.nidus.idle");
    public static final RawAnimation ENRAGED = RawAnimation.begin().thenLoop("animation.nidus.enraged");
    public static final RawAnimation HIT = RawAnimation.begin().thenPlay("animation.nidus.hit");
    /** Ticks the body controller takes to blend between idle and enraged. */
    public static final int BLEND_TICKS = 10;
    private static final Vector3f SPORE_GREEN = new Vector3f(0.62F, 0.9F, 0.25F);
    private static final Vector3f SPORE_VIOLET = new Vector3f(0.5F, 0.22F, 0.6F);
    private static final Vector3f WOUND_CRIMSON = new Vector3f(0.75F, 0.08F, 0.12F);

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
    /** Client: the health stage the body controller's loop was loaded for (near death has its own, weaker loops). */
    private int animatedStage = -1;

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
        if (!settings.encounters() || !NidusEncounters.creditable(player)) {
            return 0.0F;
        }
        Long last = lastStrikes.get(player.getUUID());
        if (last != null && now - last < settings.strikeCooldownTicks() && now >= last) {
            return 0.0F;
        }
        float amount = NidusEncounters.strikeDamage(player);
        player.resetAttackStrengthTicker();
        lastStrikes.put(player.getUUID(), now);
        return damage(level, NidusDamagedEvent.Kind.STRIKE, player, null, amount, settings, now);
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
        if (!settings.encounters() || isRemoved() || !(amount > 0) || !NidusEncounters.creditable(player)) {
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
            destroy(level, player, settings, now);
            return dealt;
        }
        level.blockEvent(worldPosition, getBlockState().getBlock(), EVENT_HIT, 0);
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
        lash(level, settings);
        SympatheticSurges.thresholdPassed(level, worldPosition, intensity, NidusDefenceSettings.current(), now);
    }

    /**
     * Lashes every player within {@code lashRadius} in survival or adventure: viral load (while viral load is enabled) and
     * knockback away from the nidus.
     */
    void lash(ServerLevel level, NidusSettings settings) {
        Vec3 centre = Vec3.atCenterOf(worldPosition);
        boolean viralLoad = settings.lashViralLoad() > 0 && ViralLoadSettings.fromConfig().enabled();
        for (ServerPlayer player : playersInRange(level, settings.lashRadius())) {
            if (viralLoad) {
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
        }
        level.playSound(null, worldPosition, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 3.0F, 0.7F);
        level.sendParticles(ParticleTypes.SCULK_SOUL, centre.x, centre.y, centre.z, 40, 1.5, 1.0, 1.5, 0.08);
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

    /** The final blow: removes the block with its drops, posting the destroyed event, then credits every contributor. */
    private void destroy(ServerLevel level, ServerPlayer player, NidusSettings settings, long now) {
        List<UUID> credited = credited(settings, now, player);
        BlockPos pos = worldPosition;
        destruction = new Destruction(player, credited);
        boolean removed = level.destroyBlock(pos, true, player);
        destruction = null;
        if (removed) {
            NidusEncounters.credit(level, pos, credited, player);
        }
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
        if (now % CHECK_TICKS == 0) {
            Surge surge = Surges.controller(level).current();
            setEnraged(surge != null && surge.started() && surge.origin().equals(worldPosition));
        }
        rally.tick(level, underAttack(settings, now), NidusDefenceSettings.current(), now);
        if (!fighting()) {
            return;
        }
        if (!settings.encounters()) {
            endFight(level);
            return;
        }
        if (now - lastDamaged >= settings.regenDelayTicks() && settings.regenRate() > 0) {
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
                triggerAnim("hit", "hit");
            }
            return true;
        }
        return super.triggerEvent(id, param);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "body", BLEND_TICKS, state -> {
            // Near death loads its own idle and enraged loops under the same names: reload when the stage changes.
            int stage = healthStage();
            if (stage != animatedStage) {
                animatedStage = stage;
                state.getController().forceAnimationReset();
            }
            return state.setAndContinue(enraged ? ENRAGED : IDLE);
        }));
        controllers.add(new AnimationController<>(this, "hit", 0, state -> PlayState.STOP).triggerableAnim("hit", HIT));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return animations;
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, NidusBlockEntity entity) {
        entity.ambience(level, level.random);
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

    /** What clients get with the chunk and on {@link #setEnraged} changes: only whether it is enraged. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("enraged", enraged);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        enraged = tag.getBoolean("enraged");
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        handleUpdateTag(packet.getTag(), registries);
    }

    // ---- saving -----------------------------------------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
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
