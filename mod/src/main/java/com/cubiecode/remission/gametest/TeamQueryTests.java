package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.TeamLookupEvent;
import com.cubiecode.remission.api.TeamQueries;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The team query (P4.12) without a team source for the asked players: each player is only their own teammate, a
 * listener can make two players teammates, and answers are cached per pair until invalidated or expired. Players are
 * fresh random UUIDs that no source (FTB Teams included) knows, so these tests hold with and without FTB Teams.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TeamQueryTests {
    private TeamQueryTests() {
    }

    @GameTest(template = "empty")
    public static void unansweredPlayersAreTheirOwnTeam(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        helper.assertTrue(TeamQueries.areTeammates(server, a, a), "a player is not their own teammate");
        helper.assertFalse(TeamQueries.areTeammates(server, a, b), "two unanswered players are teammates");
        helper.assertFalse(TeamQueries.areTeammates(server, b, a), "two unanswered players are teammates (reversed)");

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.assertTrue(TeamQueries.areTeammates(player, player), "a player object is not its own teammate");
        helper.assertTrue(TeamQueries.areTeammates(player, player.getUUID()), "a player is not teammate of own UUID");
        helper.assertFalse(TeamQueries.areTeammates(player, a), "a player is teammate of an unanswered UUID");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void listenerAnswersAndIsCached(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        AtomicInteger lookups = new AtomicInteger();
        Consumer<TeamLookupEvent> listener = event -> {
            boolean ab = (event.getFirst().equals(a) && event.getSecond().equals(b))
                    || (event.getFirst().equals(b) && event.getSecond().equals(a));
            if (ab) {
                lookups.incrementAndGet();
                event.setTeammates(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            helper.assertTrue(TeamQueries.areTeammates(server, a, b), "listener did not make a and b teammates");
            helper.assertTrue(TeamQueries.areTeammates(server, b, a), "the answer is not symmetric");
            helper.assertValueEqual(lookups.get(), 1, "lookups for a cached pair");
            helper.assertFalse(TeamQueries.areTeammates(server, a, c), "the listener's answer leaked to a and c");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }

        // The cached answer outlives its source until invalidated.
        helper.assertTrue(TeamQueries.areTeammates(server, a, b), "cached answer dropped before it expired");
        TeamQueries.invalidate(b);
        helper.assertFalse(TeamQueries.areTeammates(server, a, b), "invalidate did not drop the cached answer");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = TeamQueries.CACHE_TICKS + 40)
    public static void cachedAnswerExpires(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Consumer<TeamLookupEvent> listener = event -> {
            if (event.getFirst().equals(a) || event.getSecond().equals(a)) {
                event.setTeammates(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            helper.assertTrue(TeamQueries.areTeammates(server, a, b), "listener did not make a and b teammates");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        helper.runAfterDelay(TeamQueries.CACHE_TICKS - 10, () ->
                helper.assertTrue(TeamQueries.areTeammates(server, a, b), "answer expired early"));
        helper.runAfterDelay(TeamQueries.CACHE_TICKS + 1, () -> {
            helper.assertFalse(TeamQueries.areTeammates(server, a, b), "answer outlived its cache time");
            helper.succeed();
        });
    }
}
