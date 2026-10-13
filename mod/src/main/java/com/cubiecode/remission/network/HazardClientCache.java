package com.cubiecode.remission.network;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jetbrains.annotations.Nullable;

/**
 * The client's copy of what the server sent about hazards (P5.8): the last {@link #MAX_NOTICES} notices
 * ({@code remission:hazard_notice}), newest first, and the last status ({@code remission:hazard_status}) for the
 * bestiary's hazards tab. Nothing is saved; logging out clears it. Main thread only. It lives in common code with the
 * payloads that fill it and uses no client-only classes; only the client fills it, and client code listens to it for
 * chat and map output.
 */
public final class HazardClientCache {
    /** How many recent notices are kept for the hazards tab. */
    public static final int MAX_NOTICES = 8;

    public interface Listener {
        default void notice(HazardNoticePayload notice) {
        }

        default void status(HazardStatusPayload status) {
        }

        /** Everything was cleared (logout). */
        default void cleared() {
        }
    }

    private static final Deque<HazardNoticePayload> NOTICES = new ArrayDeque<>();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    @Nullable
    private static HazardStatusPayload status;

    private HazardClientCache() {
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    /** Recent notices, newest first. */
    public static List<HazardNoticePayload> notices() {
        return List.copyOf(NOTICES);
    }

    /** The last status the server sent this session, or null before the first. */
    @Nullable
    public static HazardStatusPayload status() {
        return status;
    }

    /** A notice arrived (from its payload handler). Never asks the server for anything. */
    public static void received(HazardNoticePayload notice) {
        NOTICES.addFirst(notice);
        while (NOTICES.size() > MAX_NOTICES) {
            NOTICES.removeLast();
        }
        LISTENERS.forEach(listener -> listener.notice(notice));
    }

    /** A status arrived (from its payload handler). Never asks the server again; see {@link HazardStatusRequests}. */
    public static void received(HazardStatusPayload payload) {
        status = payload;
        LISTENERS.forEach(listener -> listener.status(payload));
    }

    public static void clear() {
        NOTICES.clear();
        status = null;
        LISTENERS.forEach(Listener::cleared);
    }
}
