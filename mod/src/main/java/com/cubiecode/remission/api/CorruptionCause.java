package com.cubiecode.remission.api;

/** Why an entity's Strain level changed, carried by {@link HostEvent}. Later systems append their own causes. */
public enum CorruptionCause {
    /** Killed by a host and infected in place or converted (on-kill infection). */
    INFECTION,
    /** A {@code /remission infect} or {@code /remission cleanse} command. */
    COMMAND,
    /** Any other caller, including other mods using Remission's internals. */
    OTHER
}
