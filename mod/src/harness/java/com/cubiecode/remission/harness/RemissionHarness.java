package com.cubiecode.remission.harness;

import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Dev-only scene harness entrypoint (roadmap P1.1). This source set is loaded only by the
 * {@code runClient} configuration and is never part of the Remission jar. Even there it does
 * nothing unless {@value #SCENE_PROPERTY} names a scene; no listener is registered otherwise.
 *
 * <p>System properties (set by {@code tools/run-client-headless.sh --scene}):
 * <ul>
 *   <li>{@value #SCENE_PROPERTY}: scene id, e.g. {@code smoke/mob_lineup}, read from
 *       {@code harness/scenes/<id>.json} in this source set's resources; or several, separated by commas, run one
 *       after another in the same game session.</li>
 *   <li>{@value #OUTPUT_PROPERTY}: directory for screenshots and {@code harness-result.txt}, or with several scenes
 *       for one {@code <id>/} directory each; defaults to {@code <game dir>/screenshots/harness/<id>} (or
 *       {@code screenshots/harness} with several).</li>
 *   <li>{@value #TIMEOUT_PROPERTY}: seconds each scene may take before the harness fails it and quits (default
 *       300).</li>
 *   <li>{@value #ARGS_PROPERTY}: scene arguments, {@code name=value,name=value} (see {@link Scene}).</li>
 * </ul>
 */
@Mod(value = RemissionHarness.MOD_ID, dist = Dist.CLIENT)
public final class RemissionHarness {
    public static final String MOD_ID = "remission_harness";
    public static final String SCENE_PROPERTY = "remission.harness.scene";
    public static final String OUTPUT_PROPERTY = "remission.harness.output";
    public static final String TIMEOUT_PROPERTY = "remission.harness.timeout";
    public static final String ARGS_PROPERTY = "remission.harness.args";
    static final Logger LOGGER = LogUtils.getLogger();

    public RemissionHarness() {
        List<String> sceneIds = Arrays.stream(System.getProperty(SCENE_PROPERTY, "").split(","))
                .map(String::trim).filter(id -> !id.isEmpty()).toList();
        if (sceneIds.isEmpty()) {
            LOGGER.info("Scene harness idle: {} is not set", SCENE_PROPERTY);
            return;
        }
        String output = System.getProperty(OUTPUT_PROPERTY, "").trim();
        Path defaultOutput = FMLPaths.GAMEDIR.get().resolve("screenshots/harness");
        Path outputDir = !output.isEmpty() ? Path.of(output)
                : sceneIds.size() == 1 ? defaultOutput.resolve(sceneIds.getFirst()) : defaultOutput;
        long timeoutSeconds = Long.getLong(TIMEOUT_PROPERTY, 300L);
        LOGGER.info("Scene harness enabled: {} {}, output {}", sceneIds.size() == 1 ? "scene" : "scenes",
                String.join(", ", sceneIds), outputDir.toAbsolutePath());
        NeoForge.EVENT_BUS.register(new SceneRunner(sceneIds, outputDir, timeoutSeconds));
        NeoForge.EVENT_BUS.addListener(HarnessCommands::register);
    }
}
