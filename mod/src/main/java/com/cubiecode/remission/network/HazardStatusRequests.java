package com.cubiecode.remission.network;

import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The one place the client asks the server for the hazards tab's status (P5.8): {@link #tabOpened()}, called when the
 * player switches the bestiary to its hazards tab. Rebuilding the tab (new hazard data arrived, the window resized) never
 * asks again, so a status answer cannot cause another request. Client main thread. Gametests swap the sender to count
 * requests.
 */
public final class HazardStatusRequests {
    private static Runnable sender = () -> PacketDistributor.sendToServer(HazardStatusRequestPayload.INSTANCE);

    private HazardStatusRequests() {
    }

    /** The hazards tab opened: asks the server for the status once. */
    public static void tabOpened() {
        sender.run();
    }

    /** Replaces how a request is sent (gametests); returns the previous sender. */
    public static Runnable setSender(Runnable newSender) {
        Runnable previous = sender;
        sender = newSender;
        return previous;
    }
}
