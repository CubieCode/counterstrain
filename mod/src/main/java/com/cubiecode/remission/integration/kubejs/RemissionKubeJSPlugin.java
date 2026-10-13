package com.cubiecode.remission.integration.kubejs;

import com.cubiecode.remission.api.BestiaryQueries;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.StrainHazards;
import com.cubiecode.remission.api.TeamQueries;
import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;

/**
 * Remission's KubeJS plugin (P8.3), listed in {@code kubejs.plugins.txt}, which only KubeJS reads: registers the
 * {@link RemissionEvents} group and binds the public API queries for server scripts. The events reach scripts through
 * {@link KubeJSIntegration}'s listeners.
 */
public final class RemissionKubeJSPlugin implements KubeJSPlugin {
    @Override
    public void registerEvents(EventGroupRegistry registry) {
        registry.register(RemissionEvents.GROUP);
    }

    @Override
    public void registerBindings(BindingRegistry bindings) {
        // The queries need a running server, so only server scripts get them.
        if (bindings.type().isServer()) {
            bindings.add("CorruptionQueries", CorruptionQueries.class);
            bindings.add("TeamQueries", TeamQueries.class);
            bindings.add("StrainHazards", StrainHazards.class);
            bindings.add("BestiaryQueries", BestiaryQueries.class);
        }
    }
}
