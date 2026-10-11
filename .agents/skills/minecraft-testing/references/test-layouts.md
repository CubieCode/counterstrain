# Minecraft Testing Layouts (NeoForge 1.21.1)

The Gradle project root is `mod/`. `src/test` holds JUnit tests and is optional
for a GameTest-only setup. (Upstream's MockBukkit, Fabric and 26.x data-driven
`test_instance` layouts were removed; they do not apply to 1.21.1 NeoForge.)

```text
mod/
  build.gradle                  # test { useJUnitPlatform() } when src/test exists
  src/
    main/
      java/com/cubiecode/remission/
        gametest/StrainGameTests.java   # @GameTestHolder("remission")
      resources/
        META-INF/neoforge.mods.toml
        data/remission/structure/
          empty_3x3.nbt                  # GameTest templates (singular "structure")
    test/                               # only when JUnit tests exist
      java/com/cubiecode/remission/
        CooldownManagerTest.java
```

GameTest classes live in `src/main` (NeoForge discovers `@GameTestHolder` via
mod scan data; tests only register in dev/gametest runs, never in production).

Checklist:

- `@GameTestHolder(MOD_ID)` **or** a mod-bus `RegisterGameTestsEvent` listener
  calling `event.register(MyTests.class)` (then set `templateNamespace` on each
  `@GameTest`). Do not use both.
- Each referenced template exists at `data/<ns>/structure/<path>.nbt`, where
  `<path>` is `<classname>.<template-or-method>` lowercased unless
  `@PrefixGameTestTemplate(false)`.
- `runs { gameTestServer { type = "gameTestServer"; systemProperty 'neoforge.enabledGameTestNamespaces', mod_id } }`
  in `build.gradle` (MDK default).

## Validator Usage

```bash
./scripts/validate-test-layout.sh --root mod
./scripts/validate-test-layout.sh --root mod --strict
```

What it checks:

- build file exists
- JUnit Platform is enabled when JUnit tests are present
- `META-INF/neoforge.mods.toml` exists when GameTests are present
- each `@GameTest` class is registered via `@GameTestHolder` or `RegisterGameTestsEvent`
- literal GameTest templates have matching committed `.nbt` fixtures
