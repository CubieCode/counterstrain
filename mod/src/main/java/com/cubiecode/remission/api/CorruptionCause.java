package com.cubiecode.remission.api;

/**
 * Why an entity's Strain level changed, carried by {@link HostEvent}. Later systems insert their own causes before
 * {@link #OTHER}, so never persist a cause by ordinal.
 */
public enum CorruptionCause {
    /** Killed by a host and infected in place or converted (on-kill infection). */
    INFECTION,
    /** A {@code /remission infect} or {@code /remission cleanse} command. */
    COMMAND,
    /** Prolonged exposure to festering land (festering land infection). */
    FESTERING_LAND,
    /** Spawned as a host by a surge. */
    SURGE,
    /** Touching an infectious block or swimming in water in festering land (contact infection). */
    CONTACT,
    /** Cleansed with a handheld purifier (purification). */
    PURIFICATION,
    /** Any other caller, including other mods using Remission's internals. */
    OTHER
}
