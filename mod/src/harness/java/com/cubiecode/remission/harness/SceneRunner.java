package com.cubiecode.remission.harness;

import com.cubiecode.remission.corruption.Corruption;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sun.jna.NativeLibrary;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
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
import net.minecraft.world.entity.LivingEntity;
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
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

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
    private boolean pressed;

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
                    pinTextureAnimations(mc);
                    startShot(mc, 0);
                }
            }
            case SHOOTING -> {
                mc.getToasts().clear();
                mc.gui.getChat().clearMessages(false);
                // Players keep ticking under /tick freeze; pin the local player's age so its idle
                // animation (arm bob) is the same in every run when a shot shows it.
                if (mc.player != null) {
                    mc.player.tickCount = 0;
                }
                if (captureRequested) {
                    return;
                }
                pressKey(mc);
                stableTicks = isSettled(mc) ? stableTicks + 1 : 0;
                if (stableTicks >= SETTLE_TICKS) {
                    captureRequested = true;
                }
            }
            case DONE -> {
            }
        }
    }

    /**
     * HUD shots leave out the camera overlays: the vignette's brightness eases toward its target once per rendered frame,
     * so it would differ with the number of frames a run happens to render.
     */
    @SubscribeEvent
    public void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (phase == Phase.SHOOTING && VanillaGuiLayers.CAMERA_OVERLAYS.equals(event.getName())) {
            event.setCanceled(true);
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
        options.guiScale().set(0);
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

    /**
     * Turns JourneyMap's minimap off in memory (never saved to its config), when that dev-runtime mod is loaded: it maps
     * chunks in the background, so its picture would differ between runs of a HUD shot.
     */
    private static void hideJourneyMapMinimap() {
        if (!ModList.get().isLoaded("journeymap")) {
            return;
        }
        try {
            Class<?> manager = Class.forName("journeymap.client.ui.UIManager");
            Object minimap = manager.getMethod("getMiniMap").invoke(manager.getField("INSTANCE").get(null));
            Object properties = minimap.getClass().getMethod("getCurrentMinimapProperties").invoke(minimap);
            Object enabled = properties.getClass().getField("enabled").get(properties);
            enabled.getClass().getMethod("set", Boolean.class).invoke(enabled, Boolean.FALSE);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("cannot hide the JourneyMap minimap for a HUD shot", e);
        }
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
            } else if (step.spawn().isPresent()) {
                spawn(server.overworld(), step);
            } else {
                corrupt(server, step.corrupt().get(), step.level());
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

    /** Sets the Strain level of every living entity the selector matches; fails if none match. */
    private void corrupt(MinecraftServer server, String selector, int level) {
        List<? extends Entity> targets;
        try {
            targets = EntityArgument.entities().parse(new StringReader(selector))
                    .findEntities(server.createCommandSourceStack());
        } catch (CommandSyntaxException e) {
            throw new IllegalStateException("bad selector for corrupt " + selector + ": " + e.getMessage(), e);
        }
        List<LivingEntity> living = targets.stream()
                .filter(LivingEntity.class::isInstance).map(LivingEntity.class::cast).toList();
        if (living.isEmpty()) {
            throw new IllegalStateException("corrupt " + selector + " matched no living entity");
        }
        living.forEach(entity -> Corruption.set(entity, level));
        RemissionHarness.LOGGER.debug("Scene {}: corrupted {} entities ({}) to level {}", sceneId, living.size(),
                selector, level);
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

    /**
     * Stops atlas texture animations and shows every sprite's first frame, so animated block textures (sculk in
     * {@code world/caves}) look the same in every run: they advance with client ticks, which {@code /tick freeze}
     * does not stop and which differ between runs. Dev-only reflection on the texture manager's tickable set.
     */
    private static void pinTextureAnimations(Minecraft mc) {
        try {
            Field field = TextureManager.class.getDeclaredField("tickableTextures");
            field.setAccessible(true);
            ((Set<?>) field.get(mc.getTextureManager())).removeIf(texture -> texture instanceof TextureAtlas);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot pin texture animations", e);
        }
        TextureAtlas blocks = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        blocks.bind();
        blocks.getTextures().values().forEach(TextureAtlasSprite::uploadFirstFrame);
    }

    private void startShot(Minecraft mc, int index) {
        shotIndex = index;
        stableTicks = 0;
        // A screen a previous shot's key press opened.
        if (mc.screen != null) {
            mc.setScreen(null);
        }
        pressed = false;
        Scene.Shot shot = scene.shots().get(index);
        Scene.Camera camera = shot.camera();
        mc.options.setCameraType(shot.view());
        mc.options.hideGui = !shot.hud();
        if (shot.hud()) {
            hideJourneyMapMinimap();
        }
        if (mc.options.guiScale().get() != shot.guiScale()) {
            mc.options.guiScale().set(shot.guiScale());
            mc.resizeDisplay();
        }
        MinecraftServer server = mc.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.teleportTo(server.overworld(), camera.pos().x, camera.pos().y, camera.pos().z,
                    camera.yaw(), camera.pitch());
            shot.commands().forEach(command -> runCommand(server, command));
        }, server);
        phase = Phase.SHOOTING;
    }

    /**
     * Camera in place, every chunk in view loaded, no section compiles queued, and no {@code tick step} still running on
     * the server or the client (a shot command may step the frozen world; the shot shows where the steps end).
     */
    private boolean isSettled(Minecraft mc) {
        boolean wantsScreen = scene.shots().get(shotIndex).press().isPresent();
        if (!pending.isDone() || mc.level == null || mc.player == null || (mc.screen != null) != wantsScreen
                || mc.getOverlay() != null) {
            return false;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if ((server != null && server.tickRateManager().isSteppingForward()) || mc.level.tickRateManager().isSteppingForward()) {
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

    /** Clicks the shot's {@code press} key mapping once, after the camera move reached the server. */
    private void pressKey(Minecraft mc) {
        String name = scene.shots().get(shotIndex).press().orElse(null);
        if (name == null || pressed || !pending.isDone()) {
            return;
        }
        KeyMapping mapping = Arrays.stream(mc.options.keyMappings)
                .filter(m -> m.getName().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown key mapping " + name));
        // KeyMapping.click presses every mapping on the key; a shot must open exactly one thing.
        List<String> clashes = Arrays.stream(mc.options.keyMappings)
                .filter(m -> m != mapping && m.getKey().equals(mapping.getKey()) && m.getKeyConflictContext().isActive())
                .map(KeyMapping::getName).toList();
        if (!clashes.isEmpty()) {
            throw new IllegalStateException("key " + mapping.getKey().getName() + " of " + name + " is also bound to " + clashes);
        }
        KeyMapping.click(mapping.getKey());
        pressed = true;
        RemissionHarness.LOGGER.debug("Scene {}: pressed {}", sceneId, name);
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
