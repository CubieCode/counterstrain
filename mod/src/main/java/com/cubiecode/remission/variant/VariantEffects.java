package com.cubiecode.remission.variant;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.NidusDestroyedEvent;
import com.cubiecode.remission.api.PurificationCause;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.cubiecode.remission.network.VariantLandSync;
import com.cubiecode.remission.purification.HandheldPurifierItem;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionDataComponents;
import it.unimi.dsi.fastutil.HashCommon;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

/**
 * What a variant does (P7.5), and the pressure the methods it answers build:
 * <ul>
 *   <li>Purification: {@link #purification} scales a tool's step in a variant nidus's land by the variant's resistance
 *       unless a fuel of that variant counters the nidus, and records the tool's pressure.</li>
 *   <li>Damage: a host takes its variant's resistance less damage of the types it resists, unless the attacking player
 *       is countered by that variant's serum. A host killed with a player behind it (the killer, or the player who hurt
 *       it in the last 100 ticks) records its killing blow's damage type as pressure.</li>
 *   <li>Counters: drinking a variant's serum counters it for the player ({@link #serumDrunk}); using a variant's fuel on
 *       a block in that variant's land, or a reclaimer burning it with that land in its square, counters the nidus for
 *       every tool.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class VariantEffects {
    private VariantEffects() {
    }

    /**
     * The Strain level a {@code cause} step of {@code amount} takes from {@code chunk} (whose level is {@code old}):
     * {@code amount} scaled by the resistance of the land's variant when it resists the cause and is not countered,
     * with a fractional remainder resolved by a hash of the chunk, game time and cause, so it stays deterministic. Records
     * the step's pressure (what it actually lowers). Server side; called by {@code Purification.lowerLevel}.
     */
    public static int purification(ServerLevel level, ChunkPos chunk, int amount, PurificationCause cause, int old) {
        VariantSettings settings = VariantSettings.current();
        if (!settings.enabled()) {
            return amount;
        }
        Optional<BlockPos> nidus = Variants.nidusFor(level, chunk);
        if (nidus.isEmpty()) {
            return amount;
        }
        int lowered = amount;
        VariantRecords.Entry entry = VariantRecords.get(level).get(nidus.get());
        Optional<Variant> variant = entry == null ? Optional.empty()
                : entry.variant().flatMap(id -> Variants.get(level.registryAccess(), id));
        if (variant.isPresent() && variant.get().resists().matches(cause) && !entry.countered(level.getGameTime())) {
            double scaled = variant.get().resisted(amount);
            lowered = (int) Math.floor(scaled);
            double fraction = scaled - lowered;
            if (fraction > 0.0 && unitHash(chunk.toLong() ^ level.getGameTime() * 31 ^ cause.ordinal()) < fraction) {
                lowered++;
            }
        }
        VariantPressure.record(level, nidus.get(), settings, Math.min(old, lowered), method -> method.matches(cause));
        return lowered;
    }

    private static double unitHash(long seed) {
        return (HashCommon.mix(seed) >>> 11) * 0x1.0p-53;
    }

    /** Whether {@code player} is countered against {@code variant} by its serum. */
    public static boolean countered(Player player, ResourceLocation variant) {
        VariantCounters counters = player.getExistingDataOrNull(RemissionAttachments.VARIANT_COUNTERS);
        return counters != null && counters.countered(variant, player.level().getGameTime());
    }

    /** A serum was drunk: one of a variant counters that variant for the drinker for {@code counterTicks}. */
    public static void serumDrunk(LivingEntity drinker, ItemStack serum) {
        ResourceLocation variant = serum.get(RemissionDataComponents.VARIANT);
        if (variant == null || !(drinker instanceof Player player) || player.level().isClientSide()) {
            return;
        }
        long now = player.level().getGameTime();
        VariantCounters counters = player.getData(RemissionAttachments.VARIANT_COUNTERS);
        player.setData(RemissionAttachments.VARIANT_COUNTERS,
                counters.with(variant, now + VariantSettings.current().counterTicks(), now));
    }

    /**
     * Counters {@code variant} at the nidus at {@code nidus} for {@code counterTicks} if it carries that variant.
     * Returns whether it did.
     */
    public static boolean counterNidus(ServerLevel level, BlockPos nidus, ResourceLocation variant) {
        VariantRecords records = VariantRecords.get(level);
        if (!records.variant(nidus).equals(Optional.of(variant))) {
            return false;
        }
        records.counter(nidus, level.getGameTime() + VariantSettings.current().counterTicks());
        return true;
    }

    /**
     * A reclaimer at {@code pos} with {@code radius} burned {@code fuel}: a variant fuel counters every nidus with that
     * variant whose land holds a chunk of the reclaimer's square. Returns how many it countered.
     */
    public static int reclaimerBurned(ServerLevel level, BlockPos pos, int radius, ItemStack fuel) {
        ResourceLocation variant = fuel.get(RemissionDataComponents.VARIANT);
        if (variant == null || !VariantSettings.current().enabled() || !VariantRecords.get(level).anyVariant()) {
            return 0;
        }
        ChunkPos at = new ChunkPos(pos);
        Set<BlockPos> owners = new HashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Variants.nidusFor(level, new ChunkPos(at.x + dx, at.z + dz)).ifPresent(owners::add);
            }
        }
        int countered = 0;
        for (BlockPos nidus : owners) {
            if (counterNidus(level, nidus, variant)) {
                countered++;
            }
        }
        return countered;
    }

    // A variant fuel used on a block in the land of a nidus carrying that variant counters it there. Any other use goes
    // ahead as usual (placing, doors, machines, an anvil), so the event is cancelled only when the counter applies; the
    // client cannot tell, so it is decided on the server.
    @SubscribeEvent
    static void onUseOnBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        ResourceLocation variant = stack.get(RemissionDataComponents.VARIANT);
        if (variant == null || !stack.is(HandheldPurifierItem.FUEL) || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof ServerPlayer player) || !VariantSettings.current().enabled()
                || !VariantRecords.get(level).anyVariant()) {
            return;
        }
        Optional<BlockPos> nidus = Variants.nidusFor(level, new ChunkPos(event.getPos()));
        if (nidus.isPresent() && counterNidus(level, nidus.get(), variant)) {
            stack.consume(1, player);
            player.displayClientMessage(Component.translatable("message.remission.variant.fuel_countered", Variants.name(variant)), true);
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity target = event.getEntity();
        CorruptionState state = Corruption.get(target);
        if (state.variant().isEmpty() || target.level().isClientSide() || !VariantSettings.current().enabled()) {
            return;
        }
        ResourceLocation id = state.variant().get();
        Optional<Variant> variant = Variants.get(target.level().registryAccess(), id);
        DamageSource source = event.getSource();
        if (variant.isEmpty() || !variant.get().resists().matches(source.typeHolder())
                || source.getEntity() instanceof Player attacker && countered(attacker, id)) {
            return;
        }
        event.setAmount((float) variant.get().resisted(event.getAmount()));
    }

    // After infection on kill (LOW), which cancels a death to turn the victim instead.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onDeath(LivingDeathEvent event) {
        LivingEntity host = event.getEntity();
        if (event.isCanceled() || host instanceof Player || !(host.level() instanceof ServerLevel level) || !Corruption.isHost(host)
                || killingPlayer(host, event.getSource()) == null) {
            return;
        }
        VariantPressure.record(level, host.chunkPosition(), 1.0, method -> method.matches(event.getSource().typeHolder()));
    }

    @Nullable
    private static Player killingPlayer(LivingEntity host, DamageSource source) {
        if (source.getEntity() instanceof Player player) {
            return player;
        }
        return host.getLastHurtByMob() instanceof Player player ? player : null;
    }

    // A destroyed nidus takes its variant and pressure with it; its land goes to the next nidus in reach, if any.
    @SubscribeEvent
    static void onNidusDestroyed(NidusDestroyedEvent event) {
        ServerLevel level = event.getLevel();
        if (VariantRecords.get(level).remove(event.getPos())) {
            VariantLandSync.landChanged(level, event.getPos(), Variants.reach());
        }
    }
}
