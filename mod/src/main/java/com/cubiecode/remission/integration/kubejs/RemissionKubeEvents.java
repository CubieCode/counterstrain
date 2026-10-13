package com.cubiecode.remission.integration.kubejs;

import com.cubiecode.remission.api.BlockFesteredEvent;
import com.cubiecode.remission.api.BlockPurifiedEvent;
import com.cubiecode.remission.api.ChunkEnteredRemissionEvent;
import com.cubiecode.remission.api.ChunkStageChangedEvent;
import com.cubiecode.remission.api.CorruptionCause;
import com.cubiecode.remission.api.CountermeasureGrantedEvent;
import com.cubiecode.remission.api.HostCleansedEvent;
import com.cubiecode.remission.api.HostCorruptedEvent;
import com.cubiecode.remission.api.HostEvent;
import com.cubiecode.remission.api.NidusDamagedEvent;
import com.cubiecode.remission.api.NidusDestroyedEvent;
import com.cubiecode.remission.api.PurificationCause;
import com.cubiecode.remission.api.RevenantRisenEvent;
import com.cubiecode.remission.api.RevenantSlainEvent;
import com.cubiecode.remission.api.StrainHazardEvent;
import com.cubiecode.remission.api.StrainSpreadAttemptEvent;
import com.cubiecode.remission.api.SurgeEndedEvent;
import com.cubiecode.remission.api.SurgeStartedEvent;
import com.cubiecode.remission.api.TeamLookupEvent;
import com.cubiecode.remission.api.ViralLoadChangedEvent;
import dev.latvian.mods.kubejs.entity.KubeLivingEntityEvent;
import dev.latvian.mods.kubejs.event.KubeEvent;
import dev.latvian.mods.kubejs.level.KubeLevelEvent;
import dev.latvian.mods.kubejs.player.KubePlayerEvent;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The script-facing event objects of {@link RemissionEvents}, one per public API event. Each wraps the posted NeoForge
 * event and forwards its getters (and setters, where the API event has them), so a script's change lands on the API
 * event. {@code Optional} getters become nullable, which reads better in JavaScript. Level, entity and player events
 * extend KubeJS's own event interfaces, so {@code event.level}, {@code event.server}, {@code event.entity} and
 * {@code event.player} work as in KubeJS's built-in events.
 */
public final class RemissionKubeEvents {
    private RemissionKubeEvents() {
    }

    /** Base of the host events; {@code entity} is the host. */
    public abstract static class Host<E extends HostEvent> implements KubeLivingEntityEvent {
        protected final E event;

        protected Host(E event) {
            this.event = event;
        }

        @Override
        public LivingEntity getEntity() {
            return event.getEntity();
        }

        public int getOldLevel() {
            return event.getOldLevel();
        }

        public int getOldStage() {
            return event.getOldStage();
        }

        public int getNewLevel() {
            return event.getNewLevel();
        }

        public int getNewStage() {
            return event.getNewStage();
        }

        public CorruptionCause getCause() {
            return event.getCause();
        }

        public boolean wasHost() {
            return event.wasHost();
        }

        public boolean isHost() {
            return event.isHost();
        }
    }

    public static final class HostCorrupted extends Host<HostCorruptedEvent> {
        public HostCorrupted(HostCorruptedEvent event) {
            super(event);
        }

        public boolean becameHost() {
            return event.becameHost();
        }
    }

    public static final class HostCleansed extends Host<HostCleansedEvent> {
        public HostCleansed(HostCleansedEvent event) {
            super(event);
        }

        public boolean isFullyCleansed() {
            return event.isFullyCleansed();
        }
    }

    /** Base of the land events; {@code level} is the {@code ServerLevel} it happened in. */
    public abstract static class Land implements KubeLevelEvent {
        private final ServerLevel level;

        protected Land(ServerLevel level) {
            this.level = level;
        }

        @Override
        public ServerLevel getLevel() {
            return level;
        }
    }

    public static final class BlockFestered extends Land {
        private final BlockFesteredEvent event;

        public BlockFestered(BlockFesteredEvent event) {
            super(event.getLevel());
            this.event = event;
        }

        public BlockPos getPos() {
            return event.getPos();
        }

        public BlockState getOldState() {
            return event.getOldState();
        }

        public BlockState getNewState() {
            return event.getNewState();
        }
    }

    public static final class BlockPurified extends Land {
        private final BlockPurifiedEvent event;

        public BlockPurified(BlockPurifiedEvent event) {
            super(event.getLevel());
            this.event = event;
        }

        public BlockPos getPos() {
            return event.getPos();
        }

        public BlockState getOldState() {
            return event.getOldState();
        }

        public BlockState getNewState() {
            return event.getNewState();
        }

        public PurificationCause getCause() {
            return event.getCause();
        }
    }

    public static final class ChunkStageChanged extends Land {
        private final ChunkStageChangedEvent event;

        public ChunkStageChanged(ChunkStageChangedEvent event) {
            super(event.getLevel());
            this.event = event;
        }

        public ChunkPos getChunk() {
            return event.getChunk();
        }

        public int getOldLevel() {
            return event.getOldLevel();
        }

        public int getNewLevel() {
            return event.getNewLevel();
        }

        public int getOldStage() {
            return event.getOldStage();
        }

        public int getNewStage() {
            return event.getNewStage();
        }
    }

    public static final class ChunkEnteredRemission extends Land {
        private final ChunkEnteredRemissionEvent event;

        public ChunkEnteredRemission(ChunkEnteredRemissionEvent event) {
            super(event.getLevel());
            this.event = event;
        }

        public ChunkPos getChunk() {
            return event.getChunk();
        }

        public PurificationCause getCause() {
            return event.getCause();
        }

        /** The player who caused it, or null. */
        @Nullable
        public ServerPlayer getPlayer() {
            return event.getPlayer().orElse(null);
        }
    }

    /** Cancel to stop this step; or scale it with {@code rateMultiplier}. */
    public static final class StrainSpreadAttempt extends Land {
        private final StrainSpreadAttemptEvent event;

        public StrainSpreadAttempt(StrainSpreadAttemptEvent event) {
            super(event.getLevel());
            this.event = event;
        }

        public ChunkPos getFrom() {
            return event.getFrom();
        }

        public ChunkPos getTo() {
            return event.getTo();
        }

        public int getAmount() {
            return event.getAmount();
        }

        public boolean isCatchUp() {
            return event.isCatchUp();
        }

        public double getRateMultiplier() {
            return event.getRateMultiplier();
        }

        /** Scales this step; compose with other listeners by multiplying. 0 or less has the effect of cancelling. */
        public void setRateMultiplier(double rateMultiplier) {
            event.setRateMultiplier(rateMultiplier);
        }
    }

    /** Cancel so the nidus takes no damage; or change {@code amount}. */
    public static final class NidusDamaged extends Land {
        private final NidusDamagedEvent event;

        public NidusDamaged(NidusDamagedEvent event) {
            super(event.getLevel());
            this.event = event;
        }

        public BlockPos getPos() {
            return event.getPos();
        }

        public NidusDamagedEvent.Kind getKind() {
            return event.getKind();
        }

        /** The player credited with the damage, or null. */
        @Nullable
        public ServerPlayer getPlayer() {
            return event.getPlayer().orElse(null);
        }

        /** The projectile or the explosion's direct source, or null. */
        @Nullable
        public Entity getDirectEntity() {
            return event.getDirectEntity();
        }

        public float getAmount() {
            return event.getAmount();
        }

        public void setAmount(float amount) {
            event.setAmount(amount);
        }
    }

    public static final class NidusDestroyed extends Land {
        private final NidusDestroyedEvent event;

        public NidusDestroyed(NidusDestroyedEvent event) {
            super(event.getLevel());
            this.event = event;
        }

        public BlockPos getPos() {
            return event.getPos();
        }

        /** The player who landed the final blow, or null. */
        @Nullable
        public ServerPlayer getPlayer() {
            return event.getPlayer().orElse(null);
        }

        /** Every player credited with the fight, the final blow's player included. */
        public List<UUID> getContributors() {
            return event.getContributors();
        }
    }

    public static final class SurgeStarted extends Land {
        private final SurgeStartedEvent event;

        public SurgeStarted(SurgeStartedEvent event) {
            super(event.getLevel());
            this.event = event;
        }

        public BlockPos getOrigin() {
            return event.getOrigin();
        }

        public int getIntensity() {
            return event.getIntensity();
        }

        public long getEndTime() {
            return event.getEndTime();
        }

        /** {@code DIRECT}, or {@code SYMPATHETIC} for a surge set off by a nidus under attack. */
        public SurgeStartedEvent.Cause getCause() {
            return event.getCause();
        }

        /** For a sympathetic surge, the nidus under attack that set it off; null otherwise. */
        @Nullable
        public BlockPos getAttackedNidus() {
            return event.getAttackedNidus().orElse(null);
        }
    }

    public static final class SurgeEnded extends Land {
        private final SurgeEndedEvent event;

        public SurgeEnded(SurgeEndedEvent event) {
            super(event.getLevel());
            this.event = event;
        }

        public BlockPos getOrigin() {
            return event.getOrigin();
        }

        public int getIntensity() {
            return event.getIntensity();
        }

        public SurgeEndedEvent.Reason getReason() {
            return event.getReason();
        }

        public List<ServerPlayer> getSurvivors() {
            return event.getSurvivors();
        }
    }

    /** {@code entity} is the revenant. */
    public static final class RevenantRisen implements KubeLivingEntityEvent {
        private final RevenantRisenEvent event;

        public RevenantRisen(RevenantRisenEvent event) {
            this.event = event;
        }

        @Override
        public LivingEntity getEntity() {
            return event.getEntity();
        }

        public UUID getOwner() {
            return event.getOwner();
        }

        /** The player who died and whose revenant rose. */
        @Override
        public ServerPlayer getPlayer() {
            return event.getPlayer();
        }

        public DamageSource getSource() {
            return event.getSource();
        }

        public BlockPos getDeathPos() {
            return event.getDeathPos();
        }

        public List<ItemStack> getItems() {
            return event.getItems();
        }

        public int getExperience() {
            return event.getExperience();
        }
    }

    /** {@code entity} is the revenant. */
    public static final class RevenantSlain implements KubeLivingEntityEvent {
        private final RevenantSlainEvent event;

        public RevenantSlain(RevenantSlainEvent event) {
            this.event = event;
        }

        @Override
        public LivingEntity getEntity() {
            return event.getEntity();
        }

        public UUID getOwner() {
            return event.getOwner();
        }

        public DamageSource getSource() {
            return event.getSource();
        }

        /** The player who killed it, or null. */
        @Nullable
        public ServerPlayer getKiller() {
            return event.getKiller();
        }

        public boolean isReclaimed() {
            return event.isReclaimed();
        }
    }

    public static final class ViralLoadChanged implements KubePlayerEvent {
        private final ViralLoadChangedEvent event;

        public ViralLoadChanged(ViralLoadChangedEvent event) {
            this.event = event;
        }

        @Override
        public ServerPlayer getEntity() {
            return event.getEntity();
        }

        public float getOldLoad() {
            return event.getOldLoad();
        }

        public float getNewLoad() {
            return event.getNewLoad();
        }

        public ViralLoadChangedEvent.Cause getCause() {
            return event.getCause();
        }

        public boolean isRising() {
            return event.isRising();
        }
    }

    public static final class CountermeasureGranted implements KubePlayerEvent {
        private final CountermeasureGrantedEvent event;

        public CountermeasureGranted(CountermeasureGrantedEvent event) {
            this.event = event;
        }

        @Override
        public ServerPlayer getEntity() {
            return event.getEntity();
        }

        public ResourceLocation getHazard() {
            return event.getHazard();
        }

        public CountermeasureGrantedEvent.Cause getCause() {
            return event.getCause();
        }
    }

    /**
     * Cancel to hold the hazard back. Asked often (hazard systems may ask every tick), so keep listeners cheap and
     * target them at one hazard where possible.
     */
    public static final class StrainHazard implements KubeEvent {
        private final StrainHazardEvent event;

        public StrainHazard(StrainHazardEvent event) {
            this.event = event;
        }

        public MinecraftServer getServer() {
            return event.getServer();
        }

        public ResourceLocation getHazard() {
            return event.getHazard();
        }

        /** The player the hazard is asked for, or null. */
        @Nullable
        public ServerPlayer getPlayer() {
            return event.getPlayer().orElse(null);
        }

        /** The block's level for a block hazard, or null. */
        @Nullable
        public ServerLevel getLevel() {
            return event.getLevel().orElse(null);
        }

        /** The block's position for a block hazard, or null. */
        @Nullable
        public BlockPos getPos() {
            return event.getPos().orElse(null);
        }

        /** The block's placer when known, or null. */
        @Nullable
        public UUID getPlacer() {
            return event.getPlacer().orElse(null);
        }

        public boolean isBlockHazard() {
            return event.isBlockHazard();
        }
    }

    /** Answer with {@code setTeammates}; must be symmetric. Either player may be offline. */
    public static final class TeamLookup implements KubeEvent {
        private final TeamLookupEvent event;

        public TeamLookup(TeamLookupEvent event) {
            this.event = event;
        }

        public MinecraftServer getServer() {
            return event.getServer();
        }

        public UUID getFirst() {
            return event.getFirst();
        }

        public UUID getSecond() {
            return event.getSecond();
        }

        public boolean hasAnswer() {
            return event.hasAnswer();
        }

        public boolean areTeammates() {
            return event.areTeammates();
        }

        public void setTeammates(boolean teammates) {
            event.setTeammates(teammates);
        }
    }
}
