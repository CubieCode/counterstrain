package com.cubiecode.remission.harness;

import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * A harness scene, read from {@code harness/scenes/<id>.json}. The world itself is fixed by
 * {@link SceneRunner} (superflat, fixed seed, no structures); a scene sets time and weather,
 * runs setup commands with the tick frozen, and lists the shots to take.
 */
record Scene(long time, String weather, List<Step> setup, List<Shot> shots) {
    private static final Codec<Step> STEP_CODEC = Codec.either(Codec.STRING, Step.CODEC)
            .xmap(e -> e.map(Step::command, step -> step), Either::right);

    static final Codec<Scene> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.optionalFieldOf("time", 6000L).forGetter(Scene::time),
            Codec.STRING.optionalFieldOf("weather", "clear").forGetter(Scene::weather),
            STEP_CODEC.listOf().optionalFieldOf("setup", List.of()).forGetter(Scene::setup),
            Shot.CODEC.listOf().fieldOf("shots").forGetter(Scene::shots)
    ).apply(i, Scene::new));

    /**
     * One setup step: either a server {@code command} (no leading slash) or a direct
     * {@code spawn} of an entity at {@code pos} facing {@code yaw}, with optional SNBT
     * {@code nbt}. Spawning skips {@code finalizeSpawn} (no random equipment or variants) and
     * works for entity types that {@code /summon} refuses. Skipped when {@code requires_mod}
     * is not loaded.
     */
    record Step(Optional<String> command, Optional<ResourceLocation> spawn, Optional<Vec3> pos, float yaw,
                String nbt, Optional<String> requiresMod) {
        static final Codec<Step> CODEC = RecordCodecBuilder.<Step>create(i -> i.group(
                Codec.STRING.optionalFieldOf("command").forGetter(Step::command),
                ResourceLocation.CODEC.optionalFieldOf("spawn").forGetter(Step::spawn),
                Vec3.CODEC.optionalFieldOf("pos").forGetter(Step::pos),
                Codec.FLOAT.optionalFieldOf("yaw", 0.0F).forGetter(Step::yaw),
                Codec.STRING.optionalFieldOf("nbt", "{}").forGetter(Step::nbt),
                Codec.STRING.optionalFieldOf("requires_mod").forGetter(Step::requiresMod)
        ).apply(i, Step::new)).validate(step -> step.command().isPresent() == step.spawn().isPresent()
                ? DataResult.error(() -> "a setup step needs exactly one of command or spawn")
                : step.spawn().isPresent() && step.pos().isEmpty()
                        ? DataResult.error(() -> "spawn " + step.spawn().get() + " needs pos")
                        : DataResult.success(step));

        static Step command(String command) {
            return new Step(Optional.of(command), Optional.empty(), Optional.empty(), 0.0F, "{}", Optional.empty());
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

    /** One screenshot, saved as {@code <name>.png}. */
    record Shot(String name, Camera camera) {
        static final Codec<Shot> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.validate(n -> n.matches("[a-z0-9_]+")
                                ? DataResult.success(n)
                                : DataResult.error(() -> "shot name must match [a-z0-9_]+: " + n))
                        .fieldOf("name").forGetter(Shot::name),
                Camera.CODEC.fieldOf("camera").forGetter(Shot::camera)
        ).apply(i, Shot::new));
    }

    static Scene load(String id) throws IOException {
        if (!id.matches("[a-z0-9_]+(/[a-z0-9_]+)*")) {
            throw new IOException("invalid scene id '" + id + "' (expected <area>/<subject>)");
        }
        String path = "/harness/scenes/" + id + ".json";
        try (InputStream in = Scene.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("scene not found: " + path);
            }
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
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
