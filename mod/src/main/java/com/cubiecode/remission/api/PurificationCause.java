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
    /** A thrown Counterstrain prototype landing (P7.3). */
    PROTOTYPE,
    /** A powered reclaimer draining the chunks around it (P7.4). */
    RECLAIMER,
    /** Any other caller, including other mods using Remission's internals. */
    OTHER
}
