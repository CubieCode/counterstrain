package com.cubiecode.remission.infection;

import com.cubiecode.remission.behaviour.MobProfile;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.registry.RemissionAttachments;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * Contact infection for one level's mobs (P4.8), built like {@link ExposureTracker}. Every
 * {@link ContactSettings#intervalTicks} a pass snapshots the mobs and checks up to {@link ContactSettings#mobBudget} of
 * them per tick, stopping early once {@link ContactSettings#infectionBudget} have become hosts. Each mob checked that is
 * in {@link Contact contact} gains the game ticks since the previous pass started (nothing on the first pass); one out of
 * contact loses them. A mob whose contact reaches {@link ContactSettings#contactTicks} becomes a host through
 * {@link Infection#infectFromContact}. Players are never checked: only {@link Mob}s are. Mobs in chunks that are not
 * entity-ticking are skipped.
 */
public final class ContactTracker {
    private static final long NEVER = Long.MIN_VALUE;

    private final ServerLevel level;
    private final Supplier<ContactSettings> settings;
    private final Supplier<? extends Iterable<? extends Entity>> source;
    private final Function<Mob, Optional<MobProfile>> profiles;
    private final ArrayDeque<Mob> pass = new ArrayDeque<>();
    private long passStart = NEVER;
    private int credit;
    private int contactTicks;
    private int waterMinLevel;

    /**
     * A tracker over the mobs that {@code source} yields when each pass starts, finding each mob's profile with
     * {@code profiles} ({@code MobProfiles::find} in play).
     */
    public ContactTracker(ServerLevel level, Supplier<ContactSettings> settings,
                          Supplier<? extends Iterable<? extends Entity>> source, Function<Mob, Optional<MobProfile>> profiles) {
        this.level = level;
        this.settings = settings;
        this.source = source;
        this.profiles = profiles;
    }

    /** Mobs still waiting in the current pass. */
    public int pending() {
        return pass.size();
    }

    /** Runs one server tick at game time {@code now}. */
    public ExposureTracker.TickWork tick(long now) {
        ContactSettings current = settings.get();
        if (!current.enabled()) {
            pass.clear();
            passStart = NEVER;
            return ExposureTracker.TickWork.NONE;
        }
        if (pass.isEmpty()) {
            if (passStart != NEVER && now - passStart < current.intervalTicks()) {
                return ExposureTracker.TickWork.NONE;
            }
            credit = passStart == NEVER ? 0 : (int) Math.min(now - passStart, Integer.MAX_VALUE);
            passStart = now;
            contactTicks = current.contactTicks();
            waterMinLevel = current.waterMinLevel();
            for (Entity entity : source.get()) {
                if (entity instanceof Mob mob) {
                    pass.add(mob);
                }
            }
        }
        int checked = 0;
        int infected = 0;
        while (checked < current.mobBudget() && infected < current.infectionBudget() && !pass.isEmpty()) {
            checked++;
            if (check(pass.poll())) {
                infected++;
            }
        }
        return new ExposureTracker.TickWork(checked, infected);
    }

    /** Applies this pass's contact change to one mob; returns whether it became a host. */
    private boolean check(Mob mob) {
        if (!mob.isAlive() || mob.level() != level || !level.isPositionEntityTicking(mob.blockPosition())) {
            return false;
        }
        if (Corruption.isImmune(mob) || Corruption.isHost(mob)) {
            setContact(mob, 0);
            return false;
        }
        int contact = contact(mob);
        if (Contact.of(mob, waterMinLevel) == Contact.Kind.NONE) {
            if (contact > 0) {
                setContact(mob, contact - credit);
            }
            return false;
        }
        Optional<MobProfile> profile = profiles.apply(mob);
        if (!Infection.canInfectFromFesteringLand(mob, profile)) {
            setContact(mob, 0);
            return false;
        }
        contact = (int) Math.min((long) contact + credit, Integer.MAX_VALUE);
        if (contact < contactTicks) {
            setContact(mob, contact);
            return false;
        }
        setContact(mob, 0);
        return Infection.infectFromContact(mob, profile) != null;
    }

    /** A mob's contact in ticks; 0 if it has none. */
    public static int contact(Mob mob) {
        Integer contact = mob.getExistingDataOrNull(RemissionAttachments.CONTACT_EXPOSURE);
        return contact == null ? 0 : contact;
    }

    /** Sets a mob's contact, removing the attachment at 0 or below. Server only. */
    public static void setContact(Mob mob, int ticks) {
        if (ticks <= 0) {
            mob.removeData(RemissionAttachments.CONTACT_EXPOSURE);
        } else {
            mob.setData(RemissionAttachments.CONTACT_EXPOSURE, ticks);
        }
    }
}
