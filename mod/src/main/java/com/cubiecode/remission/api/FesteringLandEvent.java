package com.cubiecode.remission.api;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

/** Base of the events about festering land and nidi in one server level. Server side, server thread. */
public abstract class FesteringLandEvent extends Event {
    private final ServerLevel level;

    protected FesteringLandEvent(ServerLevel level) {
        this.level = level;
    }

    public ServerLevel getLevel() {
        return level;
    }
}
