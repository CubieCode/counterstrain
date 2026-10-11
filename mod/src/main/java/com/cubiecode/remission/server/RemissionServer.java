package com.cubiecode.remission.server;

import com.cubiecode.remission.Remission;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/** Dedicated-server-only entrypoint. Logic shared with integrated servers belongs in common code. */
@Mod(value = Remission.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class RemissionServer {
    public RemissionServer(IEventBus modBus, ModContainer container) {
    }
}
