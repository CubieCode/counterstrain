package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.TeamQueries;
import com.cubiecode.remission.integration.ftbteams.FtbTeamsIntegration;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The FTB Teams answer to the team query (P4.12). Registered by {@link FtbTeamsIntegration} only when FTB Teams is
 * loaded, so it is not a {@code @GameTestHolder}. Players are random UUIDs registered with FTB Teams as if they had
 * logged in once and are never online, so this also covers offline players. Parties are left (and so deleted) after
 * each test; the players' personal FTB teams stay in the throwaway gametest world.
 */
@PrefixGameTestTemplate(false)
public final class FtbTeamsTests {
    private FtbTeamsTests() {
    }

    @GameTest(templateNamespace = Remission.MOD_ID, template = "empty")
    public static void membersOfOneTeamAreTeammates(GameTestHelper helper) throws CommandSyntaxException {
        MinecraftServer server = helper.getLevel().getServer();
        TeamManagerImpl manager = manager();
        UUID a = known(manager);
        UUID b = known(manager);
        UUID c = known(manager);
        UUID d = known(manager);
        helper.assertFalse(TeamQueries.areTeammates(server, a, b), "two solo FTB players are teammates");

        PartyTeam ab = manager.createParty(a, null, "remission_test_ab", null, null);
        PartyTeam cd = manager.createParty(c, null, "remission_test_cd", null, null);
        try {
            join(ab, b);
            join(cd, d);
            helper.assertTrue(TeamQueries.areTeammates(server, a, b), "members of one party are not teammates");
            helper.assertTrue(TeamQueries.areTeammates(server, d, c), "members of one party are not teammates");
            helper.assertFalse(TeamQueries.areTeammates(server, a, c), "members of different parties are teammates");
            helper.assertFalse(TeamQueries.areTeammates(server, b, d), "members of different parties are teammates");
            helper.assertFalse(TeamQueries.areTeammates(server, a, UUID.randomUUID()),
                    "a party member is teammate of a player FTB Teams does not know");
        } finally {
            ab.leave(b);
            ab.leave(a);
            cd.leave(d);
            cd.leave(c);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = Remission.MOD_ID, template = "empty")
    public static void cacheFollowsTeamChanges(GameTestHelper helper) throws CommandSyntaxException {
        MinecraftServer server = helper.getLevel().getServer();
        TeamManagerImpl manager = manager();
        UUID a = known(manager);
        UUID b = known(manager);
        // Cached "not teammates"; each change below must show without waiting out the cache.
        helper.assertFalse(TeamQueries.areTeammates(server, a, b), "two solo FTB players are teammates");

        PartyTeam party = manager.createParty(a, null, "remission_test_cache", null, null);
        try {
            join(party, b);
            helper.assertTrue(TeamQueries.areTeammates(server, a, b), "cache kept 'not teammates' after a join");
            party.leave(b);
            helper.assertFalse(TeamQueries.areTeammates(server, b, a), "cache kept 'teammates' after a leave");
        } finally {
            if (party.getMembers().contains(b)) {
                party.leave(b);
            }
            party.leave(a);
        }
        helper.assertFalse(TeamQueries.areTeammates(server, a, b), "players of a disbanded party are teammates");
        helper.succeed();
    }

    private static TeamManagerImpl manager() {
        return (TeamManagerImpl) FTBTeamsAPI.api().getManager();
    }

    /** A new player FTB Teams knows (with a personal team), as after a first login; never online. */
    private static UUID known(TeamManagerImpl manager) {
        UUID id = UUID.randomUUID();
        manager.playerLoggedIn(null, id, name(id));
        return id;
    }

    private static void join(PartyTeam party, UUID player) throws CommandSyntaxException {
        party.join(null, new GameProfile(player, name(player)));
    }

    private static String name(UUID id) {
        return "rt_" + id.toString().substring(0, 8);
    }
}
