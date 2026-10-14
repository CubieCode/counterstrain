package com.cubiecode.remission.harness;

import com.cubiecode.remission.corruption.CorruptionState;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.client.CameraType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * A harness scene, read from {@code harness/scenes/<id>.json}. The world itself is fixed by
 * {@link SceneRunner} (superflat, fixed seed, no structures); a scene sets time and weather,
 * runs setup commands with the tick frozen, and lists the shots to take. The festering fog (P4.11) is off unless the
 * scene sets {@code fester_fog}, so scenes about other things keep their images.
 *
 * <p>A scene may declare {@code args}, named string values with defaults (e.g. {@code "args": {"item":
 * "remission:scanner"}}); every {@code ${name}} in the file is replaced by the value given on the command line
 * ({@value RemissionHarness#ARGS_PROPERTY}) or else the default, before the scene is parsed. Values are limited to
 * {@code [a-z0-9_.:/-]}, so they cannot break out of a JSON string or a command argument.
 */
record Scene(long time, String weather, boolean festerFog, List<Step> setup, List<Shot> shots) {
    private static final Codec<Step> STEP_CODEC = Codec.either(Codec.STRING, Step.CODEC)
            .xmap(e -> e.map(Step::command, step -> step), Either::right);

    static final Codec<Scene> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.optionalFieldOf("time", 6000L).forGetter(Scene::time),
            Codec.STRING.optionalFieldOf("weather", "clear").forGetter(Scene::weather),
            Codec.BOOL.optionalFieldOf("fester_fog", false).forGetter(Scene::festerFog),
            STEP_CODEC.listOf().optionalFieldOf("setup", List.of()).forGetter(Scene::setup),
            Shot.CODEC.listOf().fieldOf("shots").forGetter(Scene::shots)
    ).apply(i, Scene::new));

    /**
     * One setup step: a server {@code command} (no leading slash), a direct {@code spawn} of
     * an entity at {@code pos} facing {@code yaw} with optional SNBT {@code nbt}, or a
     * {@code corrupt} step that sets the Strain {@code level} of every living entity matched
     * by an entity selector through the server API (players included). Spawning skips
     * {@code finalizeSpawn} (no random equipment or variants) and works for entity types that
     * {@code /summon} refuses. Skipped when {@code requires_mod} is not loaded.
     */
    record Step(Optional<String> command, Optional<ResourceLocation> spawn, Optional<Vec3> pos, float yaw,
                String nbt, Optional<String> corrupt, int level, Optional<String> requiresMod) {
        static final Codec<Step> CODEC = RecordCodecBuilder.<Step>create(i -> i.group(
                Codec.STRING.optionalFieldOf("command").forGetter(Step::command),
                ResourceLocation.CODEC.optionalFieldOf("spawn").forGetter(Step::spawn),
                Vec3.CODEC.optionalFieldOf("pos").forGetter(Step::pos),
                Codec.FLOAT.optionalFieldOf("yaw", 0.0F).forGetter(Step::yaw),
                Codec.STRING.optionalFieldOf("nbt", "{}").forGetter(Step::nbt),
                Codec.STRING.optionalFieldOf("corrupt").forGetter(Step::corrupt),
                Codec.intRange(0, CorruptionState.MAX_LEVEL).optionalFieldOf("level", 0).forGetter(Step::level),
                Codec.STRING.optionalFieldOf("requires_mod").forGetter(Step::requiresMod)
        ).apply(i, Step::new)).validate(step -> Stream.of(step.command(), step.spawn(), step.corrupt())
                .filter(Optional::isPresent).count() != 1
                ? DataResult.error(() -> "a setup step needs exactly one of command, spawn or corrupt")
                : step.spawn().isPresent() && step.pos().isEmpty()
                        ? DataResult.error(() -> "spawn " + step.spawn().get() + " needs pos")
                        : DataResult.success(step));

        static Step command(String command) {
            return new Step(Optional.of(command), Optional.empty(), Optional.empty(), 0.0F, "{}", Optional.empty(), 0,
                    Optional.empty());
        }
    }

    /** Player (feet) position and rotation, as for {@code /tp x y z yaw pitch}. */
    record Camera(Vec3 pos, float yaw, float pitch) {
        static final Codec<Camera> CODEC = RecordCodecBuilder.create(i -> i.group(
                Vec3.CODEC.fieldOf("pos").forGetter(Camera::pos),
                Codec.FLOAT.fieldOf("yaw").forGetter(Camera::yaw),
                Codec.FLOAT.fieldOf("pitch").forGetter(Camera::pitch)
        ).apply(i, Camera::new));
    }

    /**
     * One screenshot, saved as {@code <name>.png}. {@code view} is the camera type:
     * {@code first_person} (default), {@code third_person_back} or {@code third_person_front}.
     * {@code press} names a key mapping (e.g. {@code key.remission.bestiary}) clicked once the
     * camera is in place; the shot then waits for the screen it opens, which closes before the
     * next shot. {@code commands} are server commands (no leading slash) run right after the
     * camera move, before the shot settles: changes to chunks an earlier shot already loaded
     * on the client. {@code hud} shows the GUI (hidden otherwise) at {@code gui_scale} (0, the default, is auto), to
     * review HUD elements; chat is cleared before the shot.
     * {@code recipes} names a recipe category (e.g. {@code remission:analyzing}) to open in the recipe
     * viewer, EMI when it is loaded, else JEI (P7.2). {@code screen} says a shot command opens a screen (such as
     * {@code harness player use} on a machine); the shot then waits for it. {@code map} opens JourneyMap's full-screen map
     * (P8.2; its key shares J with other mappings, so {@code press} cannot). {@code press}, {@code recipes} and {@code map}
     * imply {@code screen}. {@code button} (P5.8) names the lang key of a button on the screen {@code press} opened (e.g.
     * {@code gui.remission.bestiary.tab.hazards}), pressed once that screen is open; the shot fails if it has none.
     */
    record Shot(String name, Camera camera, CameraType view, Optional<String> press, List<String> commands, boolean hud,
                int guiScale, Optional<ResourceLocation> recipes, boolean screen, boolean map, Optional<Integer> tooltip,
                Optional<String> button, boolean hitboxes) {
        private static final Codec<CameraType> VIEW_CODEC = Codec.STRING.comapFlatMap(
                v -> Arrays.stream(CameraType.values()).filter(t -> t.name().toLowerCase(Locale.ROOT).equals(v))
                        .findFirst().map(DataResult::success)
                        .orElseGet(() -> DataResult.error(() -> "unknown view " + v)),
                t -> t.name().toLowerCase(Locale.ROOT));

        static final Codec<Shot> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.validate(n -> n.matches("[a-z0-9_]+")
                                ? DataResult.success(n)
                                : DataResult.error(() -> "shot name must match [a-z0-9_]+: " + n))
                        .fieldOf("name").forGetter(Shot::name),
                Camera.CODEC.fieldOf("camera").forGetter(Shot::camera),
                VIEW_CODEC.optionalFieldOf("view", CameraType.FIRST_PERSON).forGetter(Shot::view),
                Codec.STRING.optionalFieldOf("press").forGetter(Shot::press),
                Codec.STRING.listOf().optionalFieldOf("commands", List.of()).forGetter(Shot::commands),
                Codec.BOOL.optionalFieldOf("hud", false).forGetter(Shot::hud),
                Codec.intRange(0, 8).optionalFieldOf("gui_scale", 0).forGetter(Shot::guiScale),
                ResourceLocation.CODEC.optionalFieldOf("recipes").forGetter(Shot::recipes),
                Codec.BOOL.optionalFieldOf("screen", false).forGetter(Shot::screen),
                Codec.BOOL.optionalFieldOf("map", false).forGetter(Shot::map),
                Codec.intRange(0, 45).optionalFieldOf("tooltip").forGetter(Shot::tooltip),
                Codec.STRING.optionalFieldOf("button").forGetter(Shot::button),
                Codec.BOOL.optionalFieldOf("hitboxes", false).forGetter(Shot::hitboxes)
        ).apply(i, Shot::new));

        /** Whether the shot shows a screen rather than the world. */
        boolean wantsScreen() {
            return screen || map || press.isPresent() || recipes.isPresent() || tooltip.isPresent();
        }
    }

    private static final Pattern ARG_NAME = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final Pattern ARG_VALUE = Pattern.compile("[a-z0-9_.:/-]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");

    /** Parses {@code name=value,name=value} from {@value RemissionHarness#ARGS_PROPERTY}. */
    static Map<String, String> parseArgs(String spec) throws IOException {
        Map<String, String> args = new LinkedHashMap<>();
        for (String part : spec.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            int eq = part.indexOf('=');
            String name = eq < 0 ? part.trim() : part.substring(0, eq).trim();
            String value = eq < 0 ? "" : part.substring(eq + 1).trim();
            if (!ARG_NAME.matcher(name).matches() || !ARG_VALUE.matcher(value).matches()) {
                throw new IOException("bad scene argument '" + part + "' (expected name=value, value in [a-z0-9_.:/-])");
            }
            args.put(name, value);
        }
        return args;
    }

    /** Replaces each {@code ${name}} in {@code text} with the given or default value of that argument. */
    static String substitute(String path, String text, Map<String, String> given) throws IOException {
        var root = JsonParser.parseString(text);
        Map<String, String> values = new LinkedHashMap<>();
        if (root.isJsonObject() && root.getAsJsonObject().has("args")) {
            for (var entry : root.getAsJsonObject().getAsJsonObject("args").entrySet()) {
                String value = entry.getValue().getAsString();
                if (!ARG_NAME.matcher(entry.getKey()).matches() || !ARG_VALUE.matcher(value).matches()) {
                    throw new IOException(path + ": bad default for argument " + entry.getKey());
                }
                values.put(entry.getKey(), value);
            }
        }
        Set<String> unknown = new HashSet<>(given.keySet());
        unknown.removeAll(values.keySet());
        if (!unknown.isEmpty()) {
            throw new IOException(path + ": takes no argument " + unknown + " (declared: " + values.keySet() + ")");
        }
        values.putAll(given);
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) {
                throw new IOException(path + ": ${" + m.group(1) + "} is not a declared argument");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    static Scene load(String id, Map<String, String> args) throws IOException {
        if (!id.matches("[a-z0-9_]+(/[a-z0-9_]+)*")) {
            throw new IOException("invalid scene id '" + id + "' (expected <area>/<subject>)");
        }
        String path = "/harness/scenes/" + id + ".json";
        try (InputStream in = Scene.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("scene not found: " + path);
            }
            var json = JsonParser.parseString(substitute(path, new String(in.readAllBytes(), StandardCharsets.UTF_8), args));
            Scene scene = CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(msg -> new IOException(path + ": " + msg));
            if (!List.of("clear", "rain", "thunder").contains(scene.weather())) {
                throw new IOException(path + ": weather must be clear, rain or thunder");
            }
            if (scene.shots().isEmpty()) {
                throw new IOException(path + ": no shots");
            }
            return scene;
        }
    }
}
