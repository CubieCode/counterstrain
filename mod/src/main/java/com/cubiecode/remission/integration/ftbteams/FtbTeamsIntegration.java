package com.cubiecode.remission.integration.ftbteams;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.TeamLookupEvent;
import com.cubiecode.remission.api.TeamQueries;
import com.cubiecode.remission.gametest.FtbTeamsTests;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import dev.ftb.mods.ftbteams.api.event.TeamManagerEvent;
import java.util.Optional;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Optional FTB Teams integration (P4.12): answers {@link TeamLookupEvent} from FTB Teams, so members of one FTB team
 * are teammates. Players FTB Teams has never seen are left unanswered. FTB Teams knows offline
 * players by UUID, so the answer holds for them too. FTB Teams is {@code compileOnly}; this class is loaded only from
 * {@link Remission}'s constructor after a {@code ModList} check.
 */
public final class FtbTeamsIntegration {
    public static final String MOD_ID = "ftbteams";

    private FtbTeamsIntegration() {
    }

    public static void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(FtbTeamsIntegration::onTeamLookup);
        // Every join, leave, party creation and disband fires PLAYER_CHANGED for the player who moved.
        TeamEvent.PLAYER_CHANGED.register(event -> TeamQueries.invalidate(event.getPlayerId()));
        TeamEvent.DELETED.register(event -> TeamQueries.invalidateAll());
        TeamManagerEvent.LOADED.register(event -> TeamQueries.invalidateAll());
        TeamManagerEvent.DESTROYED.register(event -> TeamQueries.invalidateAll());
        modBus.addListener(RegisterGameTestsEvent.class, event -> event.register(FtbTeamsTests.class));
        Remission.LOGGER.info("FTB Teams present: answering the team query from FTB teams");
    }

    private static void onTeamLookup(TeamLookupEvent event) {
        if (!FTBTeamsAPI.api().isManagerLoaded()) {
            return;
        }
        TeamManager manager = FTBTeamsAPI.api().getManager();
        Optional<Team> first = manager.getTeamForPlayerID(event.getFirst());
        Optional<Team> second = manager.getTeamForPlayerID(event.getSecond());
        if (first.isPresent() && second.isPresent()) {
            event.setTeammates(first.get().getId().equals(second.get().getId()));
        }
    }
}
