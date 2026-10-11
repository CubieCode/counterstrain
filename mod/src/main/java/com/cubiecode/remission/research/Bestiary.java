package com.cubiecode.remission.research;

import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.registry.RemissionAttachments;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.IAttachmentHolder;

/**
 * The bestiary: which entity types are host types, and each player's scan progress ({@link BestiaryProgress}). Host types
 * are derived from the entity type registry, so every installed mod's living mobs widen it. Progress is written on the
 * server when the scanner records a host scan and syncs to its owner only. Reads work on either side.
 */
public final class Bestiary {
    private Bestiary() {
    }

    /**
     * Whether the bestiary lists the type: a living type (one with default attributes, which every living entity type
     * must register), other than the player, and not in {@code #remission:immune}. Needs tags bound (any loaded world).
     */
    public static boolean isHostType(EntityType<?> type) {
        return type != EntityType.PLAYER && DefaultAttributes.hasSupplier(type) && !type.is(Corruption.IMMUNE);
    }

    /** Every host type, in registry order. */
    public static List<EntityType<?>> hostTypes() {
        return BuiltInRegistries.ENTITY_TYPE.stream().filter(Bestiary::isHostType).toList();
    }

    /** The player's progress, or {@link BestiaryProgress#EMPTY}. Never creates an attachment. */
    public static BestiaryProgress get(Player player) {
        BestiaryProgress progress = player.getExistingDataOrNull(RemissionAttachments.BESTIARY);
        return progress == null ? BestiaryProgress.EMPTY : progress;
    }

    /** Records a host scan of the type at the stage; keeps the highest stage per type. Server only. */
    public static void recordScan(Player player, ResourceLocation type, int stage) {
        if (player.level().isClientSide()) {
            throw new IllegalStateException("Bestiary progress is server-authoritative; record it on the server");
        }
        BestiaryProgress old = get(player);
        BestiaryProgress progress = old.withScan(type, stage);
        if (progress != old) {
            player.setData(RemissionAttachments.BESTIARY, progress);
        }
    }

    /** Attachment sync filter: progress goes to the player it belongs to and nobody else. */
    public static boolean syncsTo(IAttachmentHolder holder, Player to) {
        return holder == to;
    }
}
