/**
 * Remission's public API: the only package other mods, addons and KubeJS bindings may rely on. Everything outside it is
 * internal and may change without notice.
 *
 * <p>Event conventions:
 * <ul>
 *   <li>Every event is posted on {@link net.neoforged.neoforge.common.NeoForge#EVENT_BUS}, on the logical server and
 *   the server thread only.</li>
 *   <li>Events are posted after the state change is applied, so queries in a listener already see the new state. The
 *   one exception is {@link StrainSpreadAttemptEvent}, which is posted before its step and can cancel or scale it.</li>
 *   <li>Batched changes (catch-up of a reloaded chunk) post one event per chunk, never one per block or per missed
 *   pass, unless the event is block-scoped.</li>
 *   <li>Host events extend {@link HostEvent} (a NeoForge {@code LivingEvent}); land events extend
 *   {@link FesteringLandEvent}, which carries the {@code ServerLevel}. Both bases are abstract: listen to the concrete
 *   events.</li>
 * </ul>
 *
 * <p>Queries live in {@link CorruptionQueries}. Field-level contract: the remission-data-formats skill, "Public API
 * events and queries".
 */
package com.cubiecode.remission.api;
