package com.cubiecode.remission.behaviour;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/** How a host fights. Values are the {@code behaviour} field of {@code remission:mob_profile}. */
public enum MobBehaviour implements StringRepresentable {
    MELEE("melee"),
    RANGED("ranged"),
    FLYING("flying"),
    SWIMMING("swimming"),
    PASSIVE_HOSTILE("passive_hostile"),
    NONE("none");

    public static final Codec<MobBehaviour> CODEC = StringRepresentable.fromEnum(MobBehaviour::values);

    private final String serializedName;

    MobBehaviour(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }
}
