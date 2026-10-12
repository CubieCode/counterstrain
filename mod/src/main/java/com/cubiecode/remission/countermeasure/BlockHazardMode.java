package com.cubiecode.remission.countermeasure;

/**
 * When a block hazard (fouling, rot, containment breaches) may strike a placed block (P5.7, config
 * {@code [countermeasures] blockHazards}). A block with no known placer is always treated as {@link #PLAYER_NEARBY}.
 */
public enum BlockHazardMode {
    /** A player from the placer's team is online, anywhere. */
    TEAM_ONLINE,
    /** A player from the placer's team is in the block's level within {@link Countermeasures#NEARBY_RADIUS} blocks. */
    PLAYER_NEARBY,
    /** Whether or not anyone is there. */
    ALWAYS
}
