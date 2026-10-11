---
name: minecraft-testing
description: "Design and implement automated tests for a NeoForge 1.21.1 (ModDevGradle, Java 21) mod using JUnit 5 and NeoForge GameTests (@GameTestHolder, runGameTestServer). Use for test code and test execution, not release publishing or gameplay implementation."
---

> Upstream: https://github.com/jahrome907/minecraft-codex-skills (commit `dd57c5a97741cdc0eb5bb3a8f876581a4f09eeb0`), MIT license (see `../LICENSE.minecraft-codex-skills`).
> Adapted for Minecraft 1.21.1 + NeoForge (ModDevGradle) for Counterstrain/Remission.
> Project skills (neoforge-mod-dev, headless-client, visual-checks, etc.) take precedence where they overlap.

# Minecraft Testing Skill (NeoForge 1.21.1)

## Testing Strategies Overview

| Approach | Best For | Requires Game? |
|----------|---------|----------------|
| **JUnit 5** (pure unit tests) | Logic, codecs, data structures | No |
| **JUnit 5 + MDG `unitTest`** | Code touching registries/Minecraft classes | Loads NeoForge in the test JVM |
| **NeoForge GameTests** (`@GameTestHolder`) | In-world block/entity/gameplay behaviour | Yes (`runGameTestServer`) |

Java 21 throughout. This project's Gradle root is `mod/`; run tasks from there.
Upstream's MockBukkit (Paper), Fabric GameTest and 26.x data-driven
`test_instance`/`TEST_FUNCTION` sections were removed: none apply to 1.21.1
NeoForge (data-driven game tests arrived in 1.21.5).

### Routing Boundaries
- `Use when`: designing or implementing automated tests (unit, gametest, CI test jobs) for the mod.
- `Do not use when`: implementing gameplay features (`minecraft-modding`) or release pipelines (`minecraft-ci-release`).

## Bundled References And Helpers

- Layout guide: `references/test-layouts.md`
- Fixture/layout validator: `./scripts/validate-test-layout.sh --root mod`

The validator checks JUnit Platform wiring, `neoforge.mods.toml`, GameTest
registration and that literal template names have a committed `.nbt` fixture.
It does not compile the project or prove a Game Test can run.

---

## Unit Testing (JUnit 5)

### Pure unit tests (no Minecraft)
```groovy
dependencies {
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
test { useJUnitPlatform() }
```

```java
class CooldownManagerTest {
    @Test
    void cooldown_tracksPlayersIndependently() {
        var manager = new CooldownManager(500L);
        manager.startCooldown("steve");
        assertTrue(manager.isOnCooldown("steve"));
        assertFalse(manager.isOnCooldown("alex"));
    }
}
```

Keep gameplay math in plain classes so most logic is testable this way.

### Tests that touch Minecraft classes (ModDevGradle)
Plain JUnit does not bootstrap registries. MDG can launch NeoForge for the
`test` source set:

```groovy
neoForge {
    unitTest {
        enable()
        testedMod = mods.remission   // name from the neoForge.mods { } block
    }
}
```

Optionally add `testImplementation "net.neoforged:testframework:${neo_version}"`
and annotate a class with `@ExtendWith(EphemeralTestServerProvider.class)` to
receive a `MinecraftServer` parameter in `@Test` methods (see the
ModDevGradle README, "Unit testing with JUnit").

---

## NeoForge Game Tests (1.21.1)

1.21.1 uses the annotation API: methods annotated with vanilla
`@GameTest` taking a `GameTestHelper`, registered via `@GameTestHolder(MODID)`
**or** a mod-bus `RegisterGameTestsEvent` listener (not both).

```java
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Remission.MOD_ID)     // template namespace = remission
@PrefixGameTestTemplate(false)        // template path not prefixed with class name
public final class StrainGameTests {
    @GameTest(template = "empty_3x3")  // -> data/remission/structure/empty_3x3.nbt
    public static void zombieTakesDamage(GameTestHelper helper) {
        var zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 2, 1);
        zombie.hurt(helper.getLevel().damageSources().generic(), 4.0f);
        helper.succeedIf(() -> helper.assertTrue(zombie.getHealth() < zombie.getMaxHealth(), "no damage"));
    }
}
```

Template rules (from NeoForge 1.21.1 `GameTestHooks` / `GameTestRegistry`):
- Namespace: `@GameTest(templateNamespace=...)`, else `@GameTestHolder` value, else `minecraft`.
- Path: `template` if set, else the lowercase method name; prefixed with
  `<lowercaseclassname>.` unless `@PrefixGameTestTemplate(false)` (on method or class).
- Fixtures are `.nbt` files in `data/<namespace>/structure/` (singular since 1.21).
  Create them in-game with a structure block, or `/test create <name>`; `/test pos`
  prints helper-relative coordinates.

With `RegisterGameTestsEvent` call `event.register(MyTests.class)` and set
`templateNamespace = MOD_ID` on every `@GameTest`.

Useful `GameTestHelper` calls: `succeed()`, `succeedIf(...)`, `succeedWhen(...)`
(retries until timeout), `fail(msg)`, `assertBlockPresent`, `assertEntityPresent`,
`spawn`/`spawnWithNoFreeWill`, `setBlock`, `runAtTickTime`, `makeMockPlayer`.
Every test must end in success or failure before `timeoutTicks` (default 100).

### Running
- `./gradlew runGameTestServer` (from `mod/`): runs all registered tests, exits
  non-zero on required failures, and **crashes if no tests are registered**.
- In `runClient`/`runServer`: `/test runall`, `/test run <name>`, `/test runthis`.
- The MDK `runs {}` set `neoforge.enabledGameTestNamespaces` to the mod id; tests
  whose template namespace is not listed are silently skipped. Unset/empty = all.
- Game tests are only enabled in dev runs (`client`, `server`, `gameTestServer`)
  or with `-Dneoforge.enableGameTest=true`.

---

## CI

Keep fast unit tests (`./gradlew test`) separate from `./gradlew runGameTestServer`,
run both with `working-directory: mod` on Java 21, and upload
`mod/build/reports/` (and the game test server log) when a job fails.

---

## References

- NeoForge 1.21.1 Game Tests: https://docs.neoforged.net/docs/1.21.1/misc/gametest/
- ModDevGradle (runs, unitTest): https://github.com/neoforged/ModDevGradle
- JUnit 5 user guide: https://junit.org/junit5/docs/current/user-guide/
