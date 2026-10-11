package com.cubiecode.remission.behaviour;

import java.util.List;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * What {@link HostBehaviours} changed on a host mob: goals and target goals it removed, and the ones it added, each with
 * its priority. Held in the transient {@code remission:host_goals} attachment.
 */
public record AppliedHostGoals(List<Slot> strippedGoals, List<Slot> strippedTargets, List<Slot> addedGoals,
        List<Slot> addedTargets) {
    public static final AppliedHostGoals EMPTY = new AppliedHostGoals(List.of(), List.of(), List.of(), List.of());

    /** A goal and the priority it was registered with. */
    public record Slot(int priority, Goal goal) {
    }
}
