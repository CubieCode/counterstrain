package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.BlockFesteredEvent;
import com.cubiecode.remission.api.BlockPurifiedEvent;
import com.cubiecode.remission.api.ChunkEnteredRemissionEvent;
import com.cubiecode.remission.api.ChunkStageChangedEvent;
import com.cubiecode.remission.api.CorruptionCause;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.CountermeasureGrantedEvent;
import com.cubiecode.remission.api.HostCleansedEvent;
import com.cubiecode.remission.api.HostCorruptedEvent;
import com.cubiecode.remission.api.NidusDamagedEvent;
import com.cubiecode.remission.api.NidusDestroyedEvent;
import com.cubiecode.remission.api.PurificationCause;
import com.cubiecode.remission.api.RevenantRisenEvent;
import com.cubiecode.remission.api.RevenantSlainEvent;
import com.cubiecode.remission.api.StrainHazardEvent;
import com.cubiecode.remission.api.StrainSpreadAttemptEvent;
import com.cubiecode.remission.api.SurgeEndedEvent;
import com.cubiecode.remission.api.SurgeStartedEvent;
import com.cubiecode.remission.api.TeamQueries;
import com.cubiecode.remission.api.ViralLoadChangedEvent;
import com.cubiecode.remission.integration.kubejs.KubeJSIntegration;
import dev.latvian.mods.kubejs.core.MinecraftServerKJS;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The KubeJS bindings (P8.3), end to end through server scripts. Registered by {@link KubeJSIntegration} only when
 * KubeJS is loaded, so it is not a {@code @GameTestHolder}. build.gradle syncs the probe script
 * ({@code src/gametest/kubejs/}) and the pack's sample ({@code pack/kubejs/server_scripts/remission/}) into the gametest
 * run's {@code kubejs/server_scripts/}. The probe counts each handler call in the server's persistent data, which is
 * how these tests see that a script ran. Events are posted on the NeoForge bus as Remission posts them, at chunk and
 * block positions far from any test structure, with values the engine's own listeners ignore.
 */
@PrefixGameTestTemplate(false)
public final class KubeJSBindingTests {
    private static final String PROBE = "remission_probe:";
    private static final UUID PROBE_PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000a5a5");
    private static final ChunkPos FAR = new ChunkPos(30002, 30002);

    private KubeJSBindingTests() {
    }

    @GameTest(templateNamespace = Remission.MOD_ID, template = "empty")
    public static void scriptsReceiveEveryEvent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos far = FAR.getMiddleBlockPosition(64);
        Zombie host = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 1));
        ServerPlayer player = SurgeTests.player(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        var source = level.damageSources().generic();

        expectScript(helper, "hostCorrupted", () -> new HostCorruptedEvent(host, 0, 0, 0, 0, CorruptionCause.COMMAND));
        helper.assertTrue(data(helper).getBoolean(PROBE + "isHost") == CorruptionQueries.isHost(host),
                "CorruptionQueries from a script disagrees with Java");
        expectScript(helper, "hostCleansed", () -> new HostCleansedEvent(host, 0, 0, 0, 0, CorruptionCause.COMMAND));
        expectScript(helper, "blockFestered",
                () -> new BlockFesteredEvent(level, far, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        expectScript(helper, "blockPurified", () -> new BlockPurifiedEvent(level, far, Blocks.AIR.defaultBlockState(),
                Blocks.AIR.defaultBlockState(), PurificationCause.PURIFIER));
        expectScript(helper, "chunkStageChanged", () -> new ChunkStageChangedEvent(level, FAR, 0, 0, 0, 0));
        expectScript(helper, "chunkEnteredRemission",
                () -> new ChunkEnteredRemissionEvent(level, FAR, PurificationCause.PURIFIER, null));
        expectScript(helper, "strainSpreadAttempt",
                () -> new StrainSpreadAttemptEvent(level, FAR, new ChunkPos(FAR.x + 1, FAR.z), 1, false));
        expectScript(helper, "nidusDamaged",
                () -> new NidusDamagedEvent(level, far, NidusDamagedEvent.Kind.STRIKE, null, null, 1.0F));
        // Away from FAR: the pack sample turns a destroyed nidus's chunk into a firebreak.
        expectScript(helper, "nidusDestroyed",
                () -> new NidusDestroyedEvent(level, new ChunkPos(50000, 50000).getMiddleBlockPosition(64)));
        expectScript(helper, "surgeStarted", () -> new SurgeStartedEvent(level, far, 1, level.getGameTime()));
        expectScript(helper, "surgeEnded",
                () -> new SurgeEndedEvent(level, far, 1, SurgeEndedEvent.Reason.STOPPED, List.of()));
        expectScript(helper, "revenantRisen",
                () -> new RevenantRisenEvent(host, player, source, far, List.of(), 0));
        expectScript(helper, "revenantSlain",
                () -> new RevenantSlainEvent(host, player.getUUID(), source, null, false));
        expectScript(helper, "viralLoadChanged",
                () -> new ViralLoadChangedEvent(player, 0.0F, 0.0F, ViralLoadChangedEvent.Cause.OTHER));
        expectScript(helper, "strainHazard", () -> new StrainHazardEvent(level.getServer(), probeHazard(), player,
                null, null, null));
        expectScript(helper, "countermeasureGranted",
                () -> new CountermeasureGrantedEvent(player, probeHazard(), CountermeasureGrantedEvent.Cause.COMMAND));

        // Team lookup is posted by the query itself (on a cache miss).
        int before = count(helper, "teamLookup");
        helper.assertTrue(TeamQueries.areTeammates(level.getServer(), PROBE_PLAYER, UUID.randomUUID()),
                "a script's teamLookup answer did not reach the team query");
        helper.assertTrue(count(helper, "teamLookup") == before + 1, "the teamLookup script did not run once");
        helper.succeed();
    }

    @GameTest(templateNamespace = Remission.MOD_ID, template = "empty")
    public static void scriptsCancelAndScale(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos far = FAR.getMiddleBlockPosition(64);

        var cancelled = post(new StrainSpreadAttemptEvent(level, FAR, new ChunkPos(30000, 30002), 4, false));
        helper.assertTrue(cancelled.isCanceled(), "event.cancel() did not cancel a spread step");
        var scaled = post(new StrainSpreadAttemptEvent(level, FAR, new ChunkPos(30001, 30002), 4, false));
        helper.assertFalse(scaled.isCanceled(), "a scaled spread step was cancelled");
        helper.assertTrue(scaled.getRateMultiplier() == 0.25, "rateMultiplier from script was not applied: "
                + scaled.getRateMultiplier());
        var untouched = post(new StrainSpreadAttemptEvent(level, FAR, new ChunkPos(30003, 30002), 4, false));
        helper.assertTrue(!untouched.isCanceled() && untouched.getRateMultiplier() == 1.0,
                "a spread step the scripts ignore was changed");

        var blocked = post(new NidusDamagedEvent(level, far, NidusDamagedEvent.Kind.STRIKE, null, null, 13.0F));
        helper.assertTrue(blocked.isCanceled(), "event.cancel() did not cancel nidus damage");
        var doubled = post(new NidusDamagedEvent(level, far, NidusDamagedEvent.Kind.STRIKE, null, null, 7.0F));
        helper.assertTrue(!doubled.isCanceled() && doubled.getAmount() == 14.0F,
                "event.amount from script was not applied: " + doubled.getAmount());

        var hazard = post(new StrainHazardEvent(level.getServer(), probeHazard(), null, level, far, null));
        helper.assertTrue(hazard.isCanceled(), "a targeted strainHazard script did not hold its hazard back");
        int before = count(helper, "strainHazard");
        var other = post(new StrainHazardEvent(level.getServer(), Remission.id("contact"), null, level, far, null));
        helper.assertTrue(!other.isCanceled() && count(helper, "strainHazard") == before,
                "a strainHazard script targeted at another hazard ran");
        helper.succeed();
    }

    /** The pack's sample, server_scripts/remission/nidus_firebreak.js. */
    @GameTest(templateNamespace = Remission.MOD_ID, template = "empty")
    public static void sampleFirebreakFollowsDestroyedNidus(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // A fresh chunk each run: firebreaks persist with the server's data.
        int x = 40000 + level.getRandom().nextInt(100000) * 8;
        ChunkPos chunk = new ChunkPos(x, 40000);
        ChunkPos neighbour = new ChunkPos(x + 1, 40001);
        ChunkPos beyond = new ChunkPos(x + 3, 40000);

        BlockPos nidus = chunk.getMiddleBlockPosition(64);
        // A nidus removed without a player leaves no firebreak.
        post(new NidusDestroyedEvent(level, nidus, null, List.of()));
        var unfought = post(new StrainSpreadAttemptEvent(level, neighbour, chunk, 4, false));
        helper.assertTrue(!unfought.isCanceled() && unfought.getRateMultiplier() == 1.0,
                "the sample made a firebreak for a nidus no player destroyed");

        ServerPlayer player = SurgeTests.player(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        post(new NidusDestroyedEvent(level, nidus, player, List.of(player.getUUID())));
        var into = post(new StrainSpreadAttemptEvent(level, neighbour, chunk, 4, false));
        helper.assertTrue(into.isCanceled(), "the sample did not cancel spread into a destroyed nidus's chunk");
        var beside = post(new StrainSpreadAttemptEvent(level, beyond, neighbour, 4, false));
        helper.assertTrue(!beside.isCanceled() && beside.getRateMultiplier() == 0.5,
                "the sample did not halve spread beside the firebreak: " + beside.getRateMultiplier());
        var away = post(new StrainSpreadAttemptEvent(level, new ChunkPos(x + 4, 40000), beyond, 4, false));
        helper.assertTrue(!away.isCanceled() && away.getRateMultiplier() == 1.0,
                "the sample changed spread away from the firebreak");
        helper.succeed();
    }

    private static void expectScript(GameTestHelper helper, String handler, Supplier<? extends Event> event) {
        int before = count(helper, handler);
        post(event.get());
        int after = count(helper, handler);
        helper.assertTrue(after == before + 1, "RemissionEvents." + handler + " script ran " + (after - before)
                + " times, expected once (is the probe script synced?)");
    }

    private static <E extends Event> E post(E event) {
        return NeoForge.EVENT_BUS.post(event);
    }

    private static int count(GameTestHelper helper, String handler) {
        return data(helper).getInt(PROBE + handler);
    }

    private static CompoundTag data(GameTestHelper helper) {
        return ((MinecraftServerKJS) helper.getLevel().getServer()).kjs$getPersistentData();
    }

    private static ResourceLocation probeHazard() {
        return Remission.id("kubejs_probe");
    }
}
