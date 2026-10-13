package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CountermeasureGrantedEvent;
import com.cubiecode.remission.api.HazardNotices;
import com.cubiecode.remission.api.StrainHazardEvent;
import com.cubiecode.remission.api.StrainHazards;
import com.cubiecode.remission.api.TeamLookupEvent;
import com.cubiecode.remission.api.TeamQueries;
import com.cubiecode.remission.countermeasure.Countermeasures;
import com.cubiecode.remission.network.HazardClientCache;
import com.cubiecode.remission.network.HazardNoticePayload;
import com.cubiecode.remission.network.HazardStatusPayload;
import com.cubiecode.remission.network.HazardStatusRequests;
import com.cubiecode.remission.network.RemissionNetwork;
import com.cubiecode.remission.notice.HazardNoticeTracker;
import com.cubiecode.remission.notice.HazardStatus;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Hazard notices and the hazards tab's status (P5.8): who a reported strike reaches, grouping and the rate limit, the
 * status the tab shows, and the payloads' wire format. Players are never added to the server's player list: the tests
 * use their own {@link HazardNoticeTracker} with explicit online players and clock, and teams come from a test listener
 * on fresh random UUIDs.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HazardNoticeTests {
    /** Hazard ids no gameplay asks for, so these tests never change what other tests see. */
    private static final ResourceLocation ROT = Remission.id("test_notice_rot");
    private static final ResourceLocation FOULING = Remission.id("test_notice_fouling");

    private HazardNoticeTests() {
    }

    @GameTest(template = "empty")
    public static void noticeReachesOnlineTeammatesOnly(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer placer = SurgeTests.player(helper, pos);
        ServerPlayer teammate = SurgeTests.player(helper, pos.offset(400, 0, 0));
        ServerPlayer stranger = SurgeTests.player(helper, pos.offset(1, 0, 0));
        UUID offlineTeammate = UUID.randomUUID();
        List<ServerPlayer> online = List.of(placer, teammate, stranger);
        withTeam(Set.of(placer.getUUID(), teammate.getUUID(), offlineTeammate), () -> {
            HazardNoticeTracker tracker = new HazardNoticeTracker();
            List<ServerPlayer> told = tracker.report(helper.getLevel(), ROT, pos, placer.getUUID(), online, 0);
            helper.assertValueEqual(Set.copyOf(told), Set.of(placer, teammate), "recipients");
            helper.assertValueEqual(tracker.flush(HazardNoticeTracker.GROUP_TICKS - 1), List.of(), "notices before the group closes");
            List<HazardNoticeTracker.Notice> notices = tracker.flush(HazardNoticeTracker.GROUP_TICKS);
            helper.assertValueEqual(recipientsOf(notices), Set.of(placer.getUUID(), teammate.getUUID()), "notified");
            for (HazardNoticeTracker.Notice notice : notices) {
                helper.assertValueEqual(notice.payload(), new HazardNoticePayload(ROT, helper.getLevel().dimension(), pos, 1),
                        "notice");
            }

            // An offline placer's online teammates are still told.
            told = tracker.report(helper.getLevel(), ROT, pos, offlineTeammate, online, 0);
            helper.assertValueEqual(Set.copyOf(told), Set.of(placer, teammate), "recipients for an offline placer");

            // No known placer: the nearest player stands in, and only their team is told.
            told = tracker.report(helper.getLevel(), ROT, pos.offset(2, 0, 0), null, online, 0);
            helper.assertValueEqual(told, List.of(stranger), "recipients with no placer");
        });

        // A report through the API lists the hazard in the hazards tab from then on.
        ResourceLocation reported = Remission.id("test_notice_reported");
        HazardNotices.report(helper.getLevel(), reported, pos, placer.getUUID());
        helper.assertTrue(StrainHazards.declared().contains(reported), "a reported hazard is not listed");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rateLimitGroupsRepeats(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer player = SurgeTests.player(helper, pos);
        List<ServerPlayer> online = List.of(player);
        HazardNoticeTracker tracker = new HazardNoticeTracker();
        int window = HazardNoticeTracker.GROUP_TICKS;
        int cooldown = HazardNoticeTracker.COOLDOWN_TICKS;

        // Three strikes near each other in the grouping time read as one notice; a far one gets its own.
        tracker.report(helper.getLevel(), ROT, pos, player.getUUID(), online, 0);
        tracker.report(helper.getLevel(), ROT, pos.offset(5, 0, 3), player.getUUID(), online, 20);
        tracker.report(helper.getLevel(), ROT, pos.offset(-8, 1, 0), player.getUUID(), online, 40);
        BlockPos far = pos.offset(HazardNoticeTracker.GROUP_RADIUS * 3, 0, 0);
        tracker.report(helper.getLevel(), ROT, far, player.getUUID(), online, 40);
        List<HazardNoticeTracker.Notice> first = tracker.flush(window);
        helper.assertValueEqual(first.size(), 1, "notices when the first group closes");
        helper.assertValueEqual(first.getFirst().count(), 3, "strikes in the first notice");
        helper.assertValueEqual(first.getFirst().pos(), pos, "the first notice's position");
        // The far group opened at tick 40; it is within the cooldown of the first notice, so it waits too.
        helper.assertValueEqual(tracker.flush(40 + window), List.of(), "the far group inside the cooldown");

        // Repeats inside the cooldown collect into the waiting groups and are sent when it ends.
        tracker.report(helper.getLevel(), ROT, pos, player.getUUID(), online, window + 10);
        tracker.report(helper.getLevel(), ROT, pos.offset(1, 0, 0), player.getUUID(), online, window + 20);
        helper.assertValueEqual(tracker.flush(window + cooldown - 1), List.of(), "notices inside the cooldown");
        List<HazardNoticeTracker.Notice> second = tracker.flush(window + cooldown);
        helper.assertValueEqual(second.size(), 2, "notices when the cooldown ends");
        helper.assertValueEqual(second.stream().mapToInt(HazardNoticeTracker.Notice::count).sum(), 3,
                "strikes after the cooldown");

        // The cooldown is per hazard: another hazard is not held back by it.
        long now = window + cooldown + 10;
        tracker.report(helper.getLevel(), FOULING, pos, player.getUUID(), online, now);
        tracker.report(helper.getLevel(), ROT, pos, player.getUUID(), online, now);
        List<HazardNoticeTracker.Notice> third = tracker.flush(now + window);
        helper.assertValueEqual(third.size(), 1, "notices of another hazard inside the cooldown");
        helper.assertValueEqual(third.getFirst().hazard(), FOULING, "the hazard sent");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void statusHidesHeldBackAndListsHeld(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer player = SurgeTests.player(helper, pos);
        ServerPlayer teammate = SurgeTests.player(helper, pos);
        ResourceLocation open = Remission.id("test_status_open");
        ResourceLocation heldBack = Remission.id("test_status_held_back");
        ResourceLocation held = Remission.id("test_status_held");
        ResourceLocation teamHeld = Remission.id("test_status_team_held");
        List.of(open, heldBack, held, teamHeld).forEach(StrainHazards::declare);
        Consumer<StrainHazardEvent> listener = event -> {
            if (event.getHazard().equals(heldBack) && event.getPlayer().filter(p -> p == player).isPresent()) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            withTeam(Set.of(player.getUUID(), teammate.getUUID()), () -> {
                Countermeasures.grant(player, held, CountermeasureGrantedEvent.Cause.OTHER, List.of(player));
                // Granted while the player is "offline" to it, so only the records say the team holds it.
                Countermeasures.grant(teammate, teamHeld, CountermeasureGrantedEvent.Cause.OTHER, List.of(teammate));
                HazardStatusPayload status = HazardStatus.of(player);
                helper.assertTrue(status.active().contains(open), "an open hazard is not active: " + status);
                helper.assertTrue(status.active().contains(StrainHazards.CONTACT), "contact is not active: " + status);
                helper.assertFalse(status.active().contains(heldBack), "a held-back hazard is listed: " + status);
                helper.assertFalse(status.active().contains(held), "a held hazard is active: " + status);
                helper.assertFalse(status.active().contains(teamHeld), "a team-held hazard is active: " + status);
                helper.assertTrue(status.held().containsAll(List.of(held, teamHeld)), "held countermeasures: " + status);

                // The teammate is not held back.
                helper.assertTrue(HazardStatus.of(teammate).active().contains(heldBack), "held back for the teammate");
            });
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void payloadsRoundTrip(GameTestHelper helper) {
        HazardNoticePayload notice = new HazardNoticePayload(ROT, Level.NETHER, new BlockPos(120, 64, -340), 9, 4);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        HazardNoticePayload.STREAM_CODEC.encode(buf, notice);
        helper.assertValueEqual(HazardNoticePayload.STREAM_CODEC.decode(buf), notice, "notice after a round trip");
        HazardStatusPayload status = new HazardStatusPayload(List.of(StrainHazards.CONTACT, ROT), List.of(FOULING));
        HazardStatusPayload.STREAM_CODEC.encode(buf, status);
        helper.assertValueEqual(HazardStatusPayload.STREAM_CODEC.decode(buf), status, "status after a round trip");
        helper.assertValueEqual(buf.readableBytes(), 0, "bytes left");
        helper.assertTrue(Integer.parseInt(RemissionNetwork.PROTOCOL_VERSION) >= 4, "protocol version with the hazard payloads");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void floodIsCappedPerHazard(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer player = SurgeTests.player(helper, pos);
        List<ServerPlayer> online = List.of(player);
        HazardNoticeTracker tracker = new HazardNoticeTracker();
        int apart = HazardNoticeTracker.GROUP_RADIUS * 3;
        int[] strikes = {1, 5, 2, 4, 1, 3};
        for (int area = 0; area < strikes.length; area++) {
            for (int i = 0; i < strikes[area]; i++) {
                tracker.report(helper.getLevel(), ROT, pos.offset(area * apart, 0, 0), player.getUUID(), online, 0);
            }
        }
        List<HazardNoticeTracker.Notice> notices = tracker.flush(HazardNoticeTracker.GROUP_TICKS);
        helper.assertValueEqual(notices.size(), HazardNoticeTracker.MAX_NOTICES_PER_HAZARD, "notices for six due groups");
        helper.assertValueEqual(notices.get(0).count(), 5, "the largest group");
        helper.assertValueEqual(notices.get(1).count(), 4, "the second largest group");
        HazardNoticeTracker.Notice summary = notices.get(2);
        helper.assertValueEqual(summary.count(), 7, "strikes in the summary");
        helper.assertValueEqual(summary.areas(), 4, "areas in the summary");
        helper.assertValueEqual(summary.pos(), pos.offset(5 * apart, 0, 0), "the summary names its largest area");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void overflowReportsAreKept(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer player = SurgeTests.player(helper, pos);
        List<ServerPlayer> online = List.of(player);
        HazardNoticeTracker tracker = new HazardNoticeTracker();
        int apart = HazardNoticeTracker.GROUP_RADIUS * 3;
        int full = 32;
        for (int area = 0; area < full; area++) {
            tracker.report(helper.getLevel(), FOULING, pos.offset(area * apart, 0, 0), player.getUUID(), online, 0);
        }
        // Past the cap: a fouling report joins the nearest fouling group, a new hazard still gets its own.
        tracker.report(helper.getLevel(), FOULING, pos.offset(-apart, 0, 0), player.getUUID(), online, 0);
        tracker.report(helper.getLevel(), ROT, pos, player.getUUID(), online, 0);
        List<HazardNoticeTracker.Notice> notices = tracker.flush(HazardNoticeTracker.GROUP_TICKS);
        List<HazardNoticeTracker.Notice> fouling = notices.stream().filter(n -> n.hazard().equals(FOULING)).toList();
        helper.assertValueEqual(fouling.stream().mapToInt(HazardNoticeTracker.Notice::count).sum(), full + 1,
                "fouling strikes notified");
        helper.assertValueEqual(fouling.stream().mapToInt(HazardNoticeTracker.Notice::areas).sum(), full + 1,
                "fouling areas notified");
        helper.assertValueEqual(fouling.getFirst().pos(), pos, "the overflow joined the nearest group");
        helper.assertValueEqual(fouling.getFirst().count(), 2, "strikes in the nearest group");
        helper.assertValueEqual(notices.stream().filter(n -> n.hazard().equals(ROT)).count(), 1L, "rot notices");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void statusArrivalSendsNoRequest(GameTestHelper helper) {
        AtomicInteger requests = new AtomicInteger();
        Runnable previous = HazardStatusRequests.setSender(requests::incrementAndGet);
        try {
            HazardClientCache.received(new HazardStatusPayload(List.of(ROT), List.of()));
            HazardClientCache.received(new HazardNoticePayload(ROT, helper.getLevel().dimension(), BlockPos.ZERO, 1));
            helper.assertValueEqual(requests.get(), 0, "requests after a status and a notice arrived");
            HazardStatusRequests.tabOpened();
            helper.assertValueEqual(requests.get(), 1, "requests after the tab opened");
        } finally {
            HazardStatusRequests.setSender(previous);
            HazardClientCache.clear();
        }
        helper.succeed();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static Set<UUID> recipientsOf(List<HazardNoticeTracker.Notice> notices) {
        return Set.copyOf(notices.stream().map(HazardNoticeTracker.Notice::recipient).toList());
    }

    /** Runs {@code body} with every pair of {@code members} answered as teammates. */
    private static void withTeam(Set<UUID> members, Runnable body) {
        Consumer<TeamLookupEvent> listener = event -> {
            if (members.contains(event.getFirst()) && members.contains(event.getSecond())) {
                event.setTeammates(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            members.forEach(TeamQueries::invalidate);
            body.run();
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            members.forEach(TeamQueries::invalidate);
        }
    }
}
