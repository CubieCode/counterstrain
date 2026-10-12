package com.cubiecode.remission.api;

/**
 * What purified a block or brought a chunk into remission, carried by {@link BlockPurifiedEvent} and
 * {@link ChunkEnteredRemissionEvent}. Later systems insert their own causes before {@link #OTHER}, so never persist a
 * cause by ordinal.
 */
public enum PurificationCause {
    /** A player using a handheld purifier. */
    PURIFIER,
    /** A quarantine pylon's ward. */
    PYLON,
    /** Mendroot growing nearby. */
    MENDROOT,
    /** Any other caller, including other mods using Remission's internals. */
    OTHER
}
