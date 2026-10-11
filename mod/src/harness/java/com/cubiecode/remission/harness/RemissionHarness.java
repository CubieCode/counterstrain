package com.cubiecode.remission.harness;

import com.mojang.logging.LogUtils;
import java.nio.file.Path;
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
 *       {@code harness/scenes/<id>.json} in this source set's resources.</li>
 *   <li>{@value #OUTPUT_PROPERTY}: directory for screenshots and {@code harness-result.txt};
 *       defaults to {@code <game dir>/screenshots/harness/<id>}.</li>
 *   <li>{@value #TIMEOUT_PROPERTY}: seconds before the harness fails the run (default 300).</li>
 * </ul>
 */
@Mod(value = RemissionHarness.MOD_ID, dist = Dist.CLIENT)
public final class RemissionHarness {
    public static final String MOD_ID = "remission_harness";
    public static final String SCENE_PROPERTY = "remission.harness.scene";
    public static final String OUTPUT_PROPERTY = "remission.harness.output";
    public static final String TIMEOUT_PROPERTY = "remission.harness.timeout";
    static final Logger LOGGER = LogUtils.getLogger();

    public RemissionHarness() {
        String sceneId = System.getProperty(SCENE_PROPERTY, "").trim();
        if (sceneId.isEmpty()) {
            LOGGER.info("Scene harness idle: {} is not set", SCENE_PROPERTY);
            return;
        }
        String output = System.getProperty(OUTPUT_PROPERTY, "").trim();
        Path outputDir = output.isEmpty()
                ? FMLPaths.GAMEDIR.get().resolve("screenshots/harness").resolve(sceneId)
                : Path.of(output);
        long timeoutSeconds = Long.getLong(TIMEOUT_PROPERTY, 300L);
        LOGGER.info("Scene harness enabled: scene {}, output {}", sceneId, outputDir.toAbsolutePath());
        NeoForge.EVENT_BUS.register(new SceneRunner(sceneId, outputDir, timeoutSeconds));
        NeoForge.EVENT_BUS.addListener(HarnessCommands::register);
    }
}
