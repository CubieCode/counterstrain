package com.cubiecode.remission.harness;

import com.cubiecode.remission.client.FesterFog;
import com.cubiecode.remission.corruption.Corruption;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sun.jna.NativeLibrary;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import net.minecraft.Util;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
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
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.TimeSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
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
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Drives a list of scenes in one game session. Each gets a fresh fixed superflat world, setup commands with the tick
 * frozen, then for each shot a camera move, a wait for chunks and section compiles to settle, and a screenshot. Between
 * scenes it leaves the world for the title screen; a scene that fails is recorded and the next one runs. Quits the game
 * after the last scene: exit 0 if every scene succeeded, 1 otherwise, 3 when a scene times out (the rest do not run).
 */
final class SceneRunner {
    static final String WORLD_NAME = "remission_harness";
    static final long SEED = 1_210_101L;
    static final int RENDER_DISTANCE = 8;
    /** Client ticks the world must stay loaded and compiled before a shot (2 s). */
    static final int SETTLE_TICKS = 40;
    /**
     * Settled client ticks before a {@code map} shot (5 s): JourneyMap maps the loaded chunks on its own thread every 2 s
     * ({@code autoMapPoll}), so this spans two of its passes after the last chunk arrived.
     */
    static final int MAP_SETTLE_TICKS = 100;
    static final String RESULT_FILE = "harness-result.txt";
    /** JourneyMap full-screen zoom for {@code map} shots: four times its default (512), so the land near the camera fills the screen. */
    static final int MAP_ZOOM = 2048;
    /** JourneyMap's map data for the harness world, which it saves when a scene leaves the world. */
    static final String JOURNEYMAP_DATA = "journeymap/data/sp/" + WORLD_NAME;
    /**
     * JourneyMap settings the harness changed in memory for map shots (full-map zoom, splash), with their values before,
     * and the properties objects that own them. JourneyMap saves its settings when a world is left or its map closes,
     * so they are put back (and saved again) before each scene leaves its world and before the next scene starts. The
     * full-screen map still writes its own zoom back as the next world loads, so map shots set both themselves and
     * tools/run-client-headless.sh puts JourneyMap's config files back after a launch. The minimap is pinned by every
     * shot instead ({@link #pinJourneyMapMinimap}).
     */
    private static final Map<Object, Object> JOURNEYMAP_ORIGINALS = new IdentityHashMap<>();
    private static final Set<Object> JOURNEYMAP_PROPERTIES = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Whether {@link Util}'s clock reads {@link #SCREEN_NANOS}: only while one of Remission's screens renders in a shot.
     * A label wider than its button scrolls with that clock (lang values are their keys until the writing pass), so a
     * shot would otherwise catch it at a different offset in every run.
     */
    private static volatile boolean screenClockFrozen;
    private static final long SCREEN_NANOS = 0L;

    private enum Phase { WAIT_TITLE, LOADING, SETUP, SHOOTING, LEAVING, DONE }

    private final List<String> sceneIds;
    private final Path outputRoot;
    private final long timeoutNanos;
    private final List<String> taken = new ArrayList<>();
    private int sceneIndex;
    private int failures;
    private volatile String sceneId;
    private volatile Path outputDir;
    private volatile long deadline;
    private Scene scene;
    private volatile Phase phase = Phase.WAIT_TITLE;
    private CompletableFuture<Void> pending;
    private int shotIndex;
    private int stableTicks;
    private boolean captureRequested;
    private boolean pressed;
    private boolean buttonPressed;
    private boolean recipesOpened;
    private boolean mapOpened;
    private boolean inventoryOpened;

    /**
     * {@code outputRoot} holds the screenshots and result of a single scene, or one {@code <scene id>/} directory per
     * scene when there are several. Each scene gets {@code timeoutSeconds}, the first one counted from game start.
     */
    SceneRunner(List<String> sceneIds, Path outputRoot, long timeoutSeconds) {
        this.sceneIds = List.copyOf(sceneIds);
        this.outputRoot = outputRoot;
        this.timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds);
        TimeSource.NanoTimeSource clock = Util.timeSource;
        Util.timeSource = () -> screenClockFrozen ? SCREEN_NANOS : clock.getAsLong();
        beginScene(0);
        Thread watchdog = new Thread(() -> {
            while (phase != Phase.DONE) {
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
                if (phase != Phase.DONE && System.nanoTime() - deadline > 0) {
                    RemissionHarness.LOGGER.error("Scene {} timed out after {}s in phase {}", sceneId, timeoutSeconds, phase);
                    writeResult("failed", "timed out after " + timeoutSeconds + "s in phase " + phase);
                    exit(3);
                }
            }
        }, "remission-harness-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void beginScene(int index) {
        sceneIndex = index;
        sceneId = sceneIds.get(index);
        outputDir = sceneIds.size() == 1 ? outputRoot : outputRoot.resolve(sceneId);
        taken.clear();
        scene = null;
        captureRequested = false;
        deadline = System.nanoTime() + timeoutNanos;
    }

    /** Records the scene's result, then quits after the last scene or leaves the world for the next one. */
    private void finishScene(String status, String message) {
        if (!"ok".equals(status)) {
            failures++;
        }
        writeResult(status, message);
        if (sceneIndex + 1 >= sceneIds.size()) {
            phase = Phase.DONE;
            RemissionHarness.LOGGER.info("Scene {}: {}, quitting", sceneId, "ok".equals(status) ? "done" : "failed");
            exit(failures == 0 ? 0 : 1);
        }
        RemissionHarness.LOGGER.info("Scene {}: {}, next scene {}", sceneId, "ok".equals(status) ? "done" : "failed",
                sceneIds.get(sceneIndex + 1));
        // Left on the next client tick: this may run inside a frame.
        phase = Phase.LEAVING;
    }

    /** Leaves the world as the pause menu's quit button does, then starts the next scene from the title screen. */
    private void leaveWorld(Minecraft mc) {
        // Close a shot's screen first: JourneyMap's full-screen map saves its zoom as it closes.
        if (mc.screen != null) {
            mc.setScreen(null);
        }
        restoreJourneyMap();
        if (mc.level != null) {
            mc.level.disconnect();
        }
        if (mc.level != null || mc.getSingleplayerServer() != null) {
            mc.disconnect(new GenericMessageScreen(Component.literal("Next scene")));
        }
        beginScene(sceneIndex + 1);
        mc.setScreen(new TitleScreen());
        phase = Phase.WAIT_TITLE;
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
                    scene = Scene.load(sceneId, Scene.parseArgs(System.getProperty(RemissionHarness.ARGS_PROPERTY, "")));
                    applyOptions(mc.options);
                    // A previous scene's shot may have changed the GUI scale or JourneyMap's settings.
                    mc.resizeDisplay();
                    restoreJourneyMap();
                    FesterFog.setSuppressed(!scene.festerFog());
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
                pressButton(mc);
                openRecipes(mc);
                openMap(mc);
                openInventory(mc);
                stableTicks = isSettled(mc) ? stableTicks + 1 : 0;
                if (stableTicks >= (scene.shots().get(shotIndex).map() ? MAP_SETTLE_TICKS : SETTLE_TICKS)) {
                    captureRequested = true;
                }
            }
            case LEAVING -> leaveWorld(mc);
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
        if (shot.hud()) {
            pinJourneyMapMinimap(true, true);
        }
        if (shotIndex + 1 < scene.shots().size()) {
            startShot(mc, shotIndex + 1);
        } else {
            finishScene("ok", "");
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
     * Pins JourneyMap's minimap, when that dev-runtime mod is loaded: off for a HUD shot (it maps chunks in the
     * background, so its picture would differ between runs) and on for every other shot (screens such as the machine
     * menus show it behind them, as the references do). With {@code save}, the setting is also written to JourneyMap's
     * config. JourneyMap can save its properties while a HUD shot runs (P4.10's {@code world/nidus_fight} made it) and the
     * run directory persists, so after each HUD shot the minimap is turned back on and saved: no scene depends on what
     * an earlier one left in the config.
     */
    private static void pinJourneyMapMinimap(boolean enabled, boolean save) {
        if (!ModList.get().isLoaded("journeymap")) {
            return;
        }
        try {
            Object properties = journeyMapMinimapProperties();
            Object field = properties.getClass().getField("enabled").get(properties);
            field.getClass().getMethod("set", Boolean.class).invoke(field, enabled);
            if (save) {
                properties.getClass().getMethod("save").invoke(properties);
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("cannot pin the JourneyMap minimap", e);
        }
    }

    private static Object journeyMapMinimapProperties() throws ReflectiveOperationException {
        Class<?> manager = Class.forName("journeymap.client.ui.UIManager");
        Object minimap = manager.getMethod("getMiniMap").invoke(manager.getField("INSTANCE").get(null));
        return minimap.getClass().getMethod("getCurrentMinimapProperties").invoke(minimap);
    }

    /** Puts back and saves every JourneyMap setting the harness changed, so the next scene or launch starts as fresh. */
    private static void restoreJourneyMap() {
        try {
            for (Map.Entry<Object, Object> entry : JOURNEYMAP_ORIGINALS.entrySet()) {
                invokeSet(entry.getKey(), entry.getValue());
            }
            for (Object properties : JOURNEYMAP_PROPERTIES) {
                properties.getClass().getMethod("save").invoke(properties);
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("cannot restore the JourneyMap settings", e);
        }
    }

    /**
     * Recreates the fixed world every scene so no state carries over between scenes or runs, with JourneyMap's map of
     * the previous one (a fresh launch never has one: the harness quits without the save that writes it).
     */
    private void createWorld(Minecraft mc) throws IOException {
        deleteTree(mc.getLevelSource().getBaseDir().resolve(WORLD_NAME));
        deleteTree(mc.gameDirectory.toPath().resolve(JOURNEYMAP_DATA));
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

    private static void deleteTree(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
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
        buttonPressed = false;
        recipesOpened = false;
        mapOpened = false;
        inventoryOpened = false;
        Scene.Shot shot = scene.shots().get(index);
        Scene.Camera camera = shot.camera();
        mc.options.setCameraType(shot.view());
        mc.options.hideGui = !shot.hud();
        mc.getEntityRenderDispatcher().setRenderHitBoxes(shot.hitboxes());
        pinJourneyMapMinimap(!shot.hud(), false);
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
     * the server or the client (a shot command may step the frozen world; the shot shows where the steps end). A
     * {@code map} shot also waits for every chunk the server sends the player, out to the edge of the view distance:
     * JourneyMap maps each loaded chunk, so one still on its way would leave a gap at the map's edge.
     */
    private boolean isSettled(Minecraft mc) {
        boolean wantsScreen = scene.shots().get(shotIndex).wantsScreen();
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
        if (scene.shots().get(shotIndex).map()) {
            int[] missing = {0};
            ChunkTrackingView.of(center, RENDER_DISTANCE).forEach(chunk -> {
                if (!mc.level.getChunkSource().hasChunk(chunk.x, chunk.z)) {
                    missing[0]++;
                }
            });
            if (missing[0] > 0) {
                return false;
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

    /**
     * Presses the shot's {@code button} (by its message's lang key) on the screen its key press opened, once (P5.8).
     * The shot settles after it, so it shows the screen as the button left it.
     */
    private void pressButton(Minecraft mc) {
        String key = scene.shots().get(shotIndex).button().orElse(null);
        if (key == null || buttonPressed || !pressed || mc.screen == null) {
            return;
        }
        AbstractButton button = mc.screen.children().stream()
                .filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast)
                .filter(b -> b.getMessage().getContents() instanceof TranslatableContents contents
                        && contents.getKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no button " + key + " on " + mc.screen.getClass().getName()));
        button.onPress();
        buttonPressed = true;
        stableTicks = 0;
        RemissionHarness.LOGGER.debug("Scene {}: pressed button {}", sceneId, key);
    }

    /**
     * Opens the shot's {@code recipes} category in the recipe viewer once the camera move reached the server, retrying
     * every tick until the viewer has loaded its recipes (both load after the world joins).
     */
    private void openRecipes(Minecraft mc) {
        ResourceLocation category = scene.shots().get(shotIndex).recipes().orElse(null);
        if (category == null || recipesOpened || !pending.isDone()) {
            return;
        }
        if (ModList.get().isLoaded("emi")) {
            recipesOpened = EmiRecipes.open(mc, category);
        } else if (ModList.get().isLoaded("jei")) {
            recipesOpened = JeiRecipes.open(mc, category);
        } else {
            throw new IllegalStateException("shot " + scene.shots().get(shotIndex).name() + " needs EMI or JEI for recipes " + category);
        }
        if (recipesOpened) {
            RemissionHarness.LOGGER.info("Scene {}: opened recipes {}", sceneId, category);
        }
    }

    /** Opens JourneyMap's full-screen map once the camera move reached the server, for a shot with {@code map}. */
    private void openMap(Minecraft mc) {
        Scene.Shot shot = scene.shots().get(shotIndex);
        if (!shot.map() || mapOpened || !pending.isDone()) {
            return;
        }
        if (!ModList.get().isLoaded("journeymap")) {
            throw new IllegalStateException("shot " + shot.name() + " needs JourneyMap for its map");
        }
        try {
            // Set in memory; the harness never saves them. The first-run splash would cover the map; the zoom is fixed.
            Class<?> client = Class.forName("journeymap.client.JourneymapClient");
            Object instance = client.getMethod("getInstance").invoke(null);
            Object core = client.getMethod("getCoreProperties").invoke(instance);
            Object version = Class.forName("journeymap.common.Journeymap").getField("JM_VERSION").get(null);
            setField(core, "splashViewed", version.toString());
            Object fullMap = client.getMethod("getFullMapProperties").invoke(instance);
            setField(fullMap, "zoomLevel", MAP_ZOOM);
            Class<?> manager = Class.forName("journeymap.client.ui.UIManager");
            manager.getMethod("getOrOpenFullscreenMap").invoke(manager.getField("INSTANCE").get(null));
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("cannot open the JourneyMap full-screen map", e);
        }
        mapOpened = true;
        RemissionHarness.LOGGER.info("Scene {}: opened the JourneyMap full-screen map", sceneId);
    }

    /** Opens the player's inventory once the camera move reached the server, for a shot with {@code tooltip}. */
    private void openInventory(Minecraft mc) {
        Scene.Shot shot = scene.shots().get(shotIndex);
        if (shot.tooltip().isEmpty() || inventoryOpened || !pending.isDone() || mc.player == null) {
            return;
        }
        if (mc.player.isCreative()) {
            throw new IllegalStateException("shot " + shot.name() + " shows a tooltip in the survival inventory; set gamemode survival");
        }
        mc.setScreen(new InventoryScreen(mc.player));
        inventoryOpened = true;
        RemissionHarness.LOGGER.info("Scene {}: opened the inventory for the tooltip of slot {}", sceneId, shot.tooltip().get());
    }

    /** Freezes {@link Util}'s clock while one of Remission's screens renders in a shot ({@link #screenClockFrozen}). */
    @SubscribeEvent
    public void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        screenClockFrozen = phase == Phase.SHOOTING
                && event.getScreen().getClass().getName().startsWith("com.cubiecode.remission.");
    }

    @SubscribeEvent
    public void onScreenRenderPost(ScreenEvent.Render.Post event) {
        screenClockFrozen = false;
    }

    /** Never leaves the clock frozen past a frame, should a screen's render throw between Pre and Post. */
    @SubscribeEvent
    public void onRenderFramePre(RenderFrameEvent.Pre event) {
        screenClockFrozen = false;
    }

    /**
     * Draws the tooltip of the shot's {@code tooltip} slot (an inventory menu slot index: 9-35 main inventory, 36-44
     * hotbar) as if the mouse hovered it, after the inventory screen rendered. The real cursor is not moved.
     */
    @SubscribeEvent
    public void onScreenRender(ScreenEvent.Render.Post event) {
        if (phase != Phase.SHOOTING || scene == null || !(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        Scene.Shot shot = scene.shots().get(shotIndex);
        if (shot.tooltip().isEmpty() || !inventoryOpened) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Slot slot = screen.getMenu().getSlot(shot.tooltip().get());
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            throw new IllegalStateException("shot " + shot.name() + ": slot " + shot.tooltip().get() + " is empty");
        }
        int x = screen.getGuiLeft() + slot.x;
        int y = screen.getGuiTop() + slot.y;
        GuiGraphics graphics = event.getGuiGraphics();
        AbstractContainerScreen.renderSlotHighlight(graphics, x, y, 0);
        graphics.renderTooltip(mc.font, stack, x + 8, y + 8);
    }

    /**
     * Sets a JourneyMap properties field ({@code ConfigField#set}) in memory, remembering its first value for
     * {@link #restoreJourneyMap}.
     */
    private static void setField(Object properties, String name, Object value) throws ReflectiveOperationException {
        Object field = properties.getClass().getField(name).get(properties);
        if (!JOURNEYMAP_ORIGINALS.containsKey(field)) {
            JOURNEYMAP_ORIGINALS.put(field, field.getClass().getMethod("get").invoke(field));
            JOURNEYMAP_PROPERTIES.add(properties);
        }
        invokeSet(field, value);
    }

    /** Calls the field's one-argument {@code set} that takes the value (typed, e.g. {@code set(Boolean)}, or Object). */
    private static void invokeSet(Object field, Object value) throws ReflectiveOperationException {
        for (Method method : field.getClass().getMethods()) {
            if (method.getName().equals("set") && method.getParameterCount() == 1
                    && (value == null || method.getParameterTypes()[0].isInstance(value))) {
                method.invoke(field, value);
                return;
            }
        }
        throw new NoSuchMethodException(field.getClass().getName() + ".set(" + value + ")");
    }

    private void fail(String message, Throwable cause) {
        RemissionHarness.LOGGER.error("Scene {} failed: {}", sceneId, message, cause);
        if (phase == Phase.LEAVING) {
            // Leaving the failed scene's world failed too; the next scene cannot start cleanly.
            phase = Phase.DONE;
            exit(1);
        }
        finishScene("failed", message + ": " + cause);
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
