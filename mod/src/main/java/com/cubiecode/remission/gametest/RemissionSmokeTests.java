package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Baseline gametest so {@code runGameTestServer} has at least one test (it fails with none).
 * Feature tests go in sibling classes; {@code remission:empty} is a 3x3x3 air template.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RemissionSmokeTests {
    private RemissionSmokeTests() {
    }

    @GameTest(template = "empty")
    public static void modLoads(GameTestHelper helper) {
        helper.succeed();
    }
}
