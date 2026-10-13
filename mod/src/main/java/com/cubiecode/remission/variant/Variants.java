package com.cubiecode.remission.variant;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.world.Nidus;
import com.cubiecode.remission.world.NidusIndex;
import com.cubiecode.remission.world.SpreadSettings;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Resistant variants (P7.5): the {@code remission:variant} datapack registry ({@code data/<ns>/remission/variant/<name>.json})
 * and where variants are. Each nidus's land is the chunks within its spread reach ({@link SpreadSettings#radius()},
 * Manhattan); a chunk in several nidi's reach belongs to the nearest (ties to the first in the nidus index's order). A
 * nidus's Strain carries at most one variant ({@link VariantRecords}), which its land and the hosts that turn in it
 * take on. The registry is synced to clients (they draw the tints); like every datapack registry it loads with the world,
 * so changes need a world reload.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class Variants {
    public static final ResourceKey<Registry<Variant>> KEY = ResourceKey.createRegistryKey(Remission.id("variant"));

    private Variants() {
    }

    @SubscribeEvent
    static void newRegistry(DataPackRegistryEvent.NewRegistry event) {
        event.dataPackRegistry(KEY, Variant.CODEC, Variant.CODEC);
    }

    public static Optional<Variant> get(RegistryAccess access, ResourceLocation id) {
        return access.registry(KEY).flatMap(registry -> registry.getOptional(id));
    }

    /** The sorted entries of one registry instance; datapack registries are rebuilt, never changed, on a world (re)load. */
    private record Sorted(Registry<Variant> registry, List<Map.Entry<ResourceLocation, Variant>> entries) {
    }

    @Nullable
    private static volatile Sorted sorted;

    /** Every variant, sorted by id. Cached per registry instance, since purification steps ask on every step. */
    public static List<Map.Entry<ResourceLocation, Variant>> all(RegistryAccess access) {
        Registry<Variant> registry = access.registry(KEY).orElse(null);
        if (registry == null) {
            return List.of();
        }
        Sorted cached = sorted;
        if (cached == null || cached.registry() != registry) {
            cached = new Sorted(registry, registry.entrySet().stream()
                    .map(entry -> Map.entry(entry.getKey().location(), entry.getValue()))
                    .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .toList());
            sorted = cached;
        }
        return cached.entries();
    }

    /** A variant's name: lang key {@code variant.<namespace>.<path>}. */
    public static Component name(ResourceLocation id) {
        return Component.translatable("variant." + id.getNamespace() + "." + id.getPath());
    }

    /** How far a nidus's land reaches, in chunks (Manhattan): its spread reach. */
    public static int reach() {
        return SpreadSettings.fromConfig().radius();
    }

    /** The nidus whose land {@code chunk} is in, if any. Server side, from the nidus index; loads nothing. */
    public static Optional<BlockPos> nidusFor(ServerLevel level, ChunkPos chunk) {
        int reach = reach();
        BlockPos best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Nidus nidus : NidusIndex.get(level).nidi()) {
            BlockPos pos = nidus.pos();
            int distance = Math.abs((pos.getX() >> 4) - chunk.x) + Math.abs((pos.getZ() >> 4) - chunk.z);
            if (distance <= reach && distance < bestDistance) {
                best = pos;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The variant of the land at {@code chunk}: the variant of the nidus whose land it is, if that nidus carries one and
     * {@code [variants]} is on.
     */
    public static Optional<ResourceLocation> variantAt(ServerLevel level, ChunkPos chunk) {
        VariantRecords records = VariantRecords.get(level);
        if (!records.anyVariant() || !VariantSettings.current().enabled()) {
            return Optional.empty();
        }
        return nidusFor(level, chunk).flatMap(records::variant);
    }

    /** The variant an entity becoming a host takes: its chunk's (server side; none on the client or with variants off). */
    public static Optional<ResourceLocation> forNewHost(LivingEntity entity) {
        return entity.level() instanceof ServerLevel level ? variantAt(level, entity.chunkPosition()) : Optional.empty();
    }
}
