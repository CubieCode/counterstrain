package com.cubiecode.remission.variant;

import com.cubiecode.remission.api.VariantEmergedEvent;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.network.VariantLandSync;
import com.cubiecode.remission.quest.QuestProgress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/**
 * Pressure and emergence (P7.5). Each use of a method against the Strain in a nidus's land (a chunk level purified by a
 * tool, a host killed with a player behind it) adds its amount to the pressure of every variant that resists it, and its
 * share (amount over that variant's threshold) to the nidus's total, whoever used it, so one team's methods count
 * together and tools and damage types compare in one unit. A variant emerges when its pressure reaches its threshold
 * (times {@code thresholdScale}) and its own share holds at least {@code minShare} of the total, so mixing methods delays
 * variants; the nidus's Strain then carries it, every pressure starts again from 0, the loaded hosts in its
 * land take it on, clients redraw its land and nearby players fire {@code remission:variant_emerged}.
 */
public final class VariantPressure {
    private VariantPressure() {
    }

    /**
     * Records {@code amount} of a method used in {@code chunk} (a host kill): the nidus whose land it is and the current
     * settings are looked up, then as {@link #record(ServerLevel, BlockPos, VariantSettings, double, Predicate)}.
     */
    public static void record(ServerLevel level, ChunkPos chunk, double amount, Predicate<ResistedMethod> matches) {
        VariantSettings settings = VariantSettings.current();
        if (settings.enabled() && amount > 0.0) {
            Variants.nidusFor(level, chunk).ifPresent(nidus -> record(level, nidus, settings, amount, matches));
        }
    }

    /**
     * Records {@code amount} of a method used in the land of the nidus at {@code nidus}: {@code matches} says which
     * variants resist it. Each matching variant's pressure grows by {@code amount} (in its method's own unit: levels
     * purified or kills), except the variant the nidus already carries (it already resists the method). The nidus's total
     * grows by the use's <em>share</em>: {@code amount / threshold} of the matching variant with the lowest threshold
     * (unscaled), counted once however many variants match, the carried one included. A variant's own share is
     * {@code pressure / threshold}, so purification levels and host kills compare in one unit: fractions of a threshold.
     * A use no variant resists counts nothing. Does nothing with variants off or for a non-positive amount.
     */
    public static void record(ServerLevel level, BlockPos nidus, VariantSettings settings, double amount,
                              Predicate<ResistedMethod> matches) {
        if (!settings.enabled() || amount <= 0.0) {
            return;
        }
        VariantRecords records = VariantRecords.get(level);
        Optional<ResourceLocation> current = records.variant(nidus);
        List<Map.Entry<ResourceLocation, Variant>> matched = new ArrayList<>();
        double share = 0.0;
        for (Map.Entry<ResourceLocation, Variant> entry : Variants.all(level.registryAccess())) {
            if (matches.test(entry.getValue().resists())) {
                share = Math.max(share, amount / entry.getValue().threshold());
                if (!current.equals(Optional.of(entry.getKey()))) {
                    matched.add(entry);
                }
            }
        }
        if (share <= 0.0) {
            return;
        }
        records.addPressure(nidus, matched.stream().map(Map.Entry::getKey).toList(), amount, share);
        VariantRecords.Entry record = records.get(nidus);
        ResourceLocation emerging = null;
        double best = 0.0;
        for (Map.Entry<ResourceLocation, Variant> entry : matched) {
            double own = record.pressure(entry.getKey()) / entry.getValue().threshold();
            double ratio = own / settings.thresholdScale();
            if (ratio >= 1.0 && own >= settings.minShare() * record.total() && ratio > best) {
                emerging = entry.getKey();
                best = ratio;
            }
        }
        if (emerging != null && !emerge(level, nidus, emerging, VariantEmergedEvent.Cause.PRESSURE)) {
            records.resetPressure(nidus, emerging);
        }
    }

    /**
     * Makes {@code variant} emerge at the nidus at {@code nidus} after posting {@link VariantEmergedEvent}; returns false
     * if a listener cancelled it. Setting the variant the nidus already carries changes nothing and posts nothing (true).
     */
    public static boolean emerge(ServerLevel level, BlockPos nidus, ResourceLocation variant, VariantEmergedEvent.Cause cause) {
        VariantRecords records = VariantRecords.get(level);
        Optional<ResourceLocation> previous = records.variant(nidus);
        if (previous.equals(Optional.of(variant))) {
            return true;
        }
        if (NeoForge.EVENT_BUS.post(new VariantEmergedEvent(level, nidus, variant, previous.orElse(null), cause)).isCanceled()) {
            return false;
        }
        records.setVariant(nidus, variant);
        changed(level, nidus, variant);
        QuestProgress.variantEmerged(level, nidus, variant, Variants.reach());
        return true;
    }

    /** Takes the nidus's variant away (the command); its hosts keep theirs. Returns whether it had one. */
    public static boolean clear(ServerLevel level, BlockPos nidus) {
        VariantRecords records = VariantRecords.get(level);
        if (records.variant(nidus).isEmpty()) {
            return false;
        }
        records.setVariant(nidus, null);
        changed(level, nidus, null);
        return true;
    }

    /** The nidus's variant changed: its loaded hosts take on a new one, and clients redraw its land. */
    private static void changed(ServerLevel level, BlockPos nidus, @Nullable ResourceLocation variant) {
        if (variant != null && VariantSettings.current().enabled()) {
            for (LivingEntity host : hostsInLand(level, nidus)) {
                Corruption.setVariant(host, Optional.of(variant));
            }
        }
        VariantLandSync.landChanged(level, nidus, Variants.reach());
    }

    /** Loaded hosts standing in the nidus's land. */
    static List<LivingEntity> hostsInLand(ServerLevel level, BlockPos nidus) {
        int reach = Variants.reach();
        ChunkPos centre = new ChunkPos(nidus);
        AABB box = new AABB(centre.getMinBlockX() - reach * 16.0, level.getMinBuildHeight(), centre.getMinBlockZ() - reach * 16.0,
                centre.getMaxBlockX() + 1 + reach * 16.0, level.getMaxBuildHeight(), centre.getMaxBlockZ() + 1 + reach * 16.0);
        return level.getEntitiesOfClass(LivingEntity.class, box, entity -> Corruption.isHost(entity)
                && Variants.nidusFor(level, entity.chunkPosition()).map(nidus::equals).orElse(false));
    }
}
