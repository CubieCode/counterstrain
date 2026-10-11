package com.cubiecode.remission.harness;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sun.jna.NativeLibrary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * Drives one scene: fixed superflat world, setup commands with the tick frozen, then for each
 * shot a camera move, a wait for chunks and section compiles to settle, and a screenshot.
 * Quits the game when done (exit 0) and exits non-zero on any failure or timeout.
 */
final class SceneRunner {
    static final String WORLD_NAME = "remission_harness";
    static final long SEED = 1_210_101L;
    static final int RENDER_DISTANCE = 8;
    /** Client ticks the world must stay loaded and compiled before a shot (2 s). */
    static final int SETTLE_TICKS = 40;
    static final String RESULT_FILE = "harness-result.txt";

    private enum Phase { WAIT_TITLE, LOADING, SETUP, SHOOTING, DONE }

    private final String sceneId;
    private final Path outputDir;
    private final List<String> taken = new ArrayList<>();
    private Scene scene;
    private volatile Phase phase = Phase.WAIT_TITLE;
    private CompletableFuture<Void> pending;
    private int shotIndex;
    private int stableTicks;
    private boolean captureRequested;

    SceneRunner(String sceneId, Path outputDir, long timeoutSeconds) {
        this.sceneId = sceneId;
        this.outputDir = outputDir;
        Thread watchdog = new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(timeoutSeconds);
            } catch (InterruptedException e) {
                return;
            }
            if (phase != Phase.DONE) {
                RemissionHarness.LOGGER.error("Scene {} timed out after {}s in phase {}", sceneId, timeoutSeconds, phase);
                writeResult("failed", "timed out after " + timeoutSeconds + "s in phase " + phase);
                exit(3);
            }
        }, "remission-harness-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        try {
            tick(Minecraft.getInstance());
        } catch (Exception e) {
            fail("scene step threw", e);
        }
    }

    private void tick(Minecraft mc) throws IOException {
        switch (phase) {
            case WAIT_TITLE -> {
                if (mc.getOverlay() == null && mc.screen instanceof TitleScreen) {
                    scene = Scene.load(sceneId);
                    applyOptions(mc.options);
                    createWorld(mc);
                    phase = Phase.LOADING;
                }
            }
            case LOADING -> {
                MinecraftServer server = mc.getSingleplayerServer();
                if (mc.level != null && mc.player != null && mc.screen == null && server != null) {
                    RemissionHarness.LOGGER.info("Scene {}: world loaded, running setup", sceneId);
                    pending = CompletableFuture.runAsync(() -> runSetup(server), server);
                    phase = Phase.SETUP;
                }
            }
            case SETUP -> {
                if (pending.isDone()) {
                    pending.join();
                    startShot(mc, 0);
                }
            }
            case SHOOTING -> {
                mc.getToasts().clear();
                if (captureRequested) {
                    return;
                }
                stableTicks = isSettled(mc) ? stableTicks + 1 : 0;
                if (stableTicks >= SETTLE_TICKS) {
                    captureRequested = true;
                }
            }
            case DONE -> {
            }
        }
    }

    @SubscribeEvent
    public void onRenderFrame(RenderFrameEvent.Post event) {
        if (phase != Phase.SHOOTING || !captureRequested) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Scene.Shot shot = scene.shots().get(shotIndex);
        Path file = outputDir.resolve(shot.name() + ".png");
        try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            Files.createDirectories(outputDir);
            image.writeToFile(file);
        } catch (IOException e) {
            fail("could not write " + file, e);
            return;
        }
        taken.add(shot.name());
        RemissionHarness.LOGGER.info("Scene {}: saved {}", sceneId, file.toAbsolutePath());
        captureRequested = false;
        if (shotIndex + 1 < scene.shots().size()) {
            startShot(mc, shotIndex + 1);
        } else {
            phase = Phase.DONE;
            writeResult("ok", "");
            RemissionHarness.LOGGER.info("Scene {}: done, quitting", sceneId);
            exit(0);
        }
    }

    /** Pins every option that changes the image, in memory only. */
    private static void applyOptions(Options options) {
        options.hideGui = true;
        options.pauseOnLostFocus = false;
        options.tutorialStep = TutorialSteps.NONE;
        options.renderDistance().set(RENDER_DISTANCE);
        options.fov().set(70);
        options.gamma().set(0.5);
        options.graphicsMode().set(GraphicsStatus.FANCY);
        options.ambientOcclusion().set(true);
        options.cloudStatus().set(CloudStatus.OFF);
        options.entityShadows().set(true);
        options.bobView().set(false);
        options.biomeBlendRadius().set(2);
    }

    /** Recreates the fixed world every run so no state carries over between runs. */
    private void createWorld(Minecraft mc) throws IOException {
        Path worldDir = mc.getLevelSource().getBaseDir().resolve(WORLD_NAME);
        if (Files.exists(worldDir)) {
            try (Stream<Path> paths = Files.walk(worldDir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_DOFIRETICK).set(false, null);
        rules.getRule(GameRules.RULE_ANNOUNCE_ADVANCEMENTS).set(false, null);
        rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, null);
        LevelSettings settings = new LevelSettings(WORLD_NAME, GameType.SPECTATOR, false, Difficulty.NORMAL, true,
                rules, WorldDataConfiguration.DEFAULT);
        RemissionHarness.LOGGER.info("Scene {}: creating superflat world {} (seed {})", sceneId, WORLD_NAME, SEED);
        mc.createWorldOpenFlows().createFreshLevel(WORLD_NAME, settings, new WorldOptions(SEED, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                mc.screen);
    }

    /** Server thread: time, weather, freeze, then the scene's own commands. */
    private void runSetup(MinecraftServer server) {
        List<Scene.Step> steps = new ArrayList<>();
        steps.add(Scene.Step.command("time set " + scene.time()));
        steps.add(Scene.Step.command("weather " + scene.weather()));
        // Frozen ticks keep mobs, fluids and animations still, so frames repeat exactly.
        steps.add(Scene.Step.command("tick freeze"));
        steps.addAll(scene.setup());
        for (Scene.Step step : steps) {
            if (step.requiresMod().isPresent() && !ModList.get().isLoaded(step.requiresMod().get())) {
                RemissionHarness.LOGGER.info("Scene {}: skipped (needs {}): {}", sceneId, step.requiresMod().get(), step);
                continue;
            }
            if (step.command().isPresent()) {
                runCommand(server, step.command().get());
            } else {
                spawn(server.overworld(), step);
            }
        }
    }

    private void spawn(ServerLevel level, Scene.Step step) {
        ResourceLocation type = step.spawn().orElseThrow();
        Vec3 pos = step.pos().orElseThrow();
        CompoundTag tag;
        try {
            tag = TagParser.parseTag(step.nbt());
        } catch (CommandSyntaxException e) {
            throw new IllegalStateException("bad nbt for spawn " + type + ": " + e.getMessage(), e);
        }
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(type)) {
            throw new IllegalStateException("unknown entity type " + type);
        }
        tag.putString("id", type.toString());
        Entity entity = EntityType.loadEntityRecursive(tag, level, e -> {
            e.moveTo(pos.x, pos.y, pos.z, step.yaw(), 0.0F);
            e.setYHeadRot(step.yaw());
            e.setYBodyRot(step.yaw());
            return e;
        });
        if (entity == null || !level.tryAddFreshEntityWithPassengers(entity)) {
            throw new IllegalStateException("could not spawn " + type + " at " + pos);
        }
        RemissionHarness.LOGGER.debug("Scene {}: spawned {} at {}", sceneId, type, pos);
    }

    private void runCommand(MinecraftServer server, String command) {
        List<String> failures = new ArrayList<>();
        AtomicBoolean succeeded = new AtomicBoolean();
        CommandSource capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                failures.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return false;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack source = server.createCommandSourceStack()
                .withSource(capture)
                .withCallback((success, result) -> succeeded.set(success));
        server.getCommands().performPrefixedCommand(source, command);
        if (!succeeded.get()) {
            throw new IllegalStateException("command failed: /" + command + " " + failures);
        }
        RemissionHarness.LOGGER.debug("Scene {}: /{}", sceneId, command);
    }

    private void startShot(Minecraft mc, int index) {
        shotIndex = index;
        stableTicks = 0;
        Scene.Camera camera = scene.shots().get(index).camera();
        MinecraftServer server = mc.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.teleportTo(server.overworld(), camera.pos().x, camera.pos().y, camera.pos().z,
                    camera.yaw(), camera.pitch());
        }, server);
        phase = Phase.SHOOTING;
    }

    /** Camera in place, every chunk in view loaded and no section compiles queued. */
    private boolean isSettled(Minecraft mc) {
        if (!pending.isDone() || mc.level == null || mc.player == null || mc.screen != null || mc.getOverlay() != null) {
            return false;
        }
        pending.join();
        Scene.Camera camera = scene.shots().get(shotIndex).camera();
        if (mc.player.position().distanceToSqr(camera.pos()) > 1.0E-6
                || Mth.degreesDifferenceAbs(mc.player.getYRot(), camera.yaw()) > 0.01F
                || Math.abs(mc.player.getXRot() - camera.pitch()) > 0.01F) {
            return false;
        }
        ChunkPos center = new ChunkPos(mc.player.blockPosition());
        int radius = RENDER_DISTANCE - 2;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radius * radius
                        && !mc.level.getChunkSource().hasChunk(center.x + dx, center.z + dz)) {
                    return false;
                }
            }
        }
        return mc.levelRenderer.hasRenderedAllSections();
    }

    private void fail(String message, Throwable cause) {
        phase = Phase.DONE;
        RemissionHarness.LOGGER.error("Scene {} failed: {}", sceneId, message, cause);
        writeResult("failed", message + ": " + cause);
        exit(1);
    }

    /**
     * Ends the process without native teardown. Under VirtualGL's EGL back end the NVIDIA
     * driver segfaults in libEGL_nvidia during libc {@code exit()} (atexit handlers), so a
     * normal quit, {@code System.exit} and {@code Runtime.halt} all end with exit code 134.
     * Logs are flushed, then libc {@code _exit} skips those handlers. The throwaway world
     * needs no save.
     */
    static void exit(int status) {
        org.apache.logging.log4j.LogManager.shutdown();
        System.out.flush();
        System.err.flush();
        try {
            NativeLibrary.getInstance("c").getFunction("_exit").invokeVoid(new Object[] {status});
        } finally {
            Runtime.getRuntime().halt(status);
        }
    }

    private void writeResult(String status, String message) {
        String text = "scene=" + sceneId + "\n"
                + "status=" + status + "\n"
                + "shots=" + String.join(",", taken) + "\n"
                + "message=" + message.replace('\n', ' ') + "\n";
        try {
            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve(RESULT_FILE), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            RemissionHarness.LOGGER.error("Could not write {}", outputDir.resolve(RESULT_FILE), e);
        }
    }
}
