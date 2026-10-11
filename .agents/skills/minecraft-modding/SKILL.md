---
name: minecraft-modding
description: "Create, modify, or debug Minecraft mod code for NeoForge 1.21.1 (ModDevGradle, Java 21). Use for registries, events, networking, data attachments, datagen wiring, and the matching assets/data JSON."
---

> Upstream: https://github.com/jahrome907/minecraft-codex-skills (commit `dd57c5a97741cdc0eb5bb3a8f876581a4f09eeb0`), MIT license (see `../LICENSE.minecraft-codex-skills`).
> Adapted for Minecraft 1.21.1 + NeoForge (ModDevGradle) for Counterstrain/Remission.
> Project skills (neoforge-mod-dev, headless-client, visual-checks, etc.) take precedence where they overlap.

# Minecraft Modding Skill (NeoForge 1.21.1)

## Overview

| Item | Value |
|---|---|
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.x (project: 21.1.259) |
| Build | Gradle 9 + ModDevGradle 2.0.x (`net.neoforged.moddev`, project: 2.0.148) |
| Mappings | Mojang + Parchment 1.21.1 / 2024.11.17 |
| Java | 21 |
| This project | mod id `remission`, package `com.cubiecode.remission`, Gradle project in `mod/` |

Fabric, legacy Forge (1.20.1, `net.minecraftforge.*`), Architectury and
Paper/Bukkit guidance from upstream has been removed. Upstream also targets
Minecraft 26.x; APIs introduced after 1.21.1 (1.21.2+ `ToolMaterial`/equipment
assets, 1.21.4+ `assets/<ns>/items/` item definitions and split
`GatherDataEvent.Client/Server`, 1.21.5+ data-driven game tests, 1.21.6+
`ValueInput`/`ValueOutput`, 1.21.9+ transfer `ResourceHandler`, `Identifier`)
do **not** exist in 1.21.1. When in doubt, check
https://docs.neoforged.net/docs/1.21.1/ or the NeoForge `1.21.1` branch sources.

### Routing Boundaries
- `Use when`: the task is Java mod code, registry/event work, networking, datagen wiring, and NeoForge APIs.
- `Do not use when`: the task is pure datapack JSON (`minecraft-datapack`) or test design (`minecraft-testing`).

---

## 1. Build & Run Commands

Run from `mod/` (the Gradle project root). Task names come from the `runs {}`
block in `build.gradle` (the 1.21.1 MDG MDK defines `client`, `server`,
`gameTestServer`, `data`):

```bash
cd mod
./gradlew build              # jar in mod/build/libs/
./gradlew runClient
./gradlew runServer
./gradlew runGameTestServer  # runs registered game tests, then exits
./gradlew runData            # datagen -> src/generated/resources/
./gradlew clean
```

`runGameTestServer` crashes if no game tests are registered. Datagen output
goes to `src/generated/resources/`, which the MDK adds as a resource dir.

---

## 2. Project Layout

```
mod/
  build.gradle, gradle.properties, settings.gradle
  src/main/
    java/com/cubiecode/remission/
      Remission.java            <- @Mod entry point
      block/ModBlocks.java      <- DeferredRegister.Blocks
      item/ModItems.java        <- DeferredRegister.Items
      entity/ModEntityTypes.java<- DeferredRegister<EntityType<?>>
      datagen/                  <- GatherDataEvent handler + providers
    resources/
      META-INF/neoforge.mods.toml  <- not mods.toml (renamed in 1.20.5)
      assets/remission/
        blockstates/  models/block/  models/item/
        textures/block/  textures/item/  lang/en_us.json
      data/remission/
        recipe/  loot_table/blocks/  tags/block/  tags/item/  tags/entity_type/
  src/generated/resources/      <- runData output
```

Since 1.21 the data folders are **singular** (`recipe`, `loot_table`,
`structure`, `advancement`, `tags/block`, `tags/item`, `tags/entity_type`, ...).
There is no `assets/<ns>/items/` folder in 1.21.1 (that is 1.21.4+); item models
live in `assets/<ns>/models/item/`.

---

## 3. Core Concepts Cheatsheet

### Sides
- **Physical client / server**: the client JAR vs. dedicated server JAR.
- **Logical client / server**: client thread vs. server (world simulation) thread.
- Keep client-only classes (renderers, screens, `Minecraft`) in separate classes
  registered via `@EventBusSubscriber(value = Dist.CLIENT)` or guarded by
  `FMLEnvironment.dist`. NeoForge discourages `@OnlyIn`; it is not a safety net.

### Registries
Register everything via `DeferredRegister`; never construct registry objects
in static initializers outside a registry supplier.

| Type | Registry |
|---|---|
| Blocks | `DeferredRegister.createBlocks(MODID)` / `Registries.BLOCK` |
| Items | `DeferredRegister.createItems(MODID)` / `Registries.ITEM` |
| Data components | `DeferredRegister.createDataComponents(MODID)` / `Registries.DATA_COMPONENT_TYPE` |
| Entity types | `DeferredRegister.create(Registries.ENTITY_TYPE, MODID)` |
| Block entity types | `Registries.BLOCK_ENTITY_TYPE` |
| Menus | `Registries.MENU` |
| Sounds | `Registries.SOUND_EVENT` |
| Attachment types | `NeoForgeRegistries.ATTACHMENT_TYPES` |

Item state lives in **data components** (`DataComponentType`, `ItemStack#get/set`),
not item NBT, since 1.20.5.

### ResourceLocation
```java
ResourceLocation id = ResourceLocation.fromNamespaceAndPath("remission", "my_block");
```
(`Identifier` is the 26.x name; not in 1.21.1. The `new ResourceLocation(...)`
constructor is gone since 1.21.)

---

## 4. Quick Patterns

```java
@Mod(Remission.MOD_ID)
public class Remission {
    public static final String MOD_ID = "remission";

    public Remission(IEventBus modEventBus, ModContainer container) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // runs after registration
    }
}
```

```java
public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
        DeferredRegister.createBlocks(Remission.MOD_ID);

    public static final DeferredBlock<Block> MY_BLOCK =
        BLOCKS.registerSimpleBlock("my_block",
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .strength(1.5f, 6.0f)
                .sound(SoundType.STONE)
                .requiresCorrectToolForDrops());
}
```

Event subscribers: `@EventBusSubscriber(modid = MOD_ID)` for both mod-bus
(lifecycle/registration, `IModBusEvent`) and game-bus events. NeoForge 21.1.259
routes each listener by event type and deprecates the `bus` attribute for
removal, so omit it (older 21.1 builds and tutorials still show it). See `references/neoforge-api.md`.

---

## 5. JSON Assets & Data

Provide matching JSON for every registered block/item (or generate it). See
`references/common-patterns.md` for blockstate, model, loot table, recipe, tag
and lang templates in 1.21.1 format.

## 6. Data Generation (1.21.1)

```java
@EventBusSubscriber(modid = Remission.MOD_ID)
public class ModDataGen {
    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        DataGenerator gen = event.getGenerator();
        PackOutput output = gen.getPackOutput();
        ExistingFileHelper helper = event.getExistingFileHelper();
        CompletableFuture<HolderLookup.Provider> lookup = event.getLookupProvider();

        gen.addProvider(event.includeClient(), new ModBlockStateProvider(output, helper));
        gen.addProvider(event.includeClient(), new ModItemModelProvider(output, helper));
        gen.addProvider(event.includeServer(), new ModRecipeProvider(output, lookup));
        gen.addProvider(event.includeServer(), new ModLootTableProvider(output, lookup));
        gen.addProvider(event.includeServer(), new ModBlockTagsProvider(output, lookup, helper));
    }
}
```

`GatherDataEvent.Client` / `.Server` and `ModelProvider` replace this in 1.21.4+;
do not use them on 1.21.1. Run `./gradlew runData` and commit `src/generated/`.

---

## 7. Common Tasks Checklist

New **block**:
- [ ] Register in `ModBlocks.BLOCKS`, plus `BlockItem` via `ITEMS.registerSimpleBlockItem(...)`
- [ ] `assets/<ns>/blockstates/<name>.json`, `models/block/<name>.json`, `models/item/<name>.json`
- [ ] Texture `assets/<ns>/textures/block/<name>.png`
- [ ] Loot table `data/<ns>/loot_table/blocks/<name>.json`
- [ ] Tags `data/<ns>/tags/block/` (e.g. `minecraft:mineable/pickaxe`, `needs_iron_tool`)
- [ ] `en_us.json` entry

New **item**:
- [ ] Register in `ModItems.ITEMS`; state in data components, not NBT
- [ ] `models/item/<name>.json` + texture, lang entry
- [ ] Creative tab via `BuildCreativeModeTabContentsEvent` (mod bus)
- [ ] Recipe `data/<ns>/recipe/<name>.json` if craftable

New **entity**:
- [ ] Entity class, `EntityType` registration (`EntityType.Builder...build(String)` on 1.21.1)
- [ ] Attributes via `EntityAttributeCreationEvent` (mod bus) for living entities
- [ ] Renderer/model in a client-only class; register in `EntityRenderersEvent.RegisterRenderers`
- [ ] Spawn rules / biome modifier (`data/<ns>/neoforge/biome_modifier/`) if natural spawning
- [ ] Entity tags under `data/<ns>/tags/entity_type/`

---

## 8. References

- NeoForge API patterns (events, networking, attachments, capabilities): `./references/neoforge-api.md`
- Blocks, items, recipes, commands, datagen, JSON: `./references/common-patterns.md`
- NeoForge 1.21.1 docs: https://docs.neoforged.net/docs/1.21.1/
- NeoForge 1.21.1 sources: https://github.com/neoforged/NeoForge/tree/1.21.1
- Minecraft Wiki (data formats; check the 1.21.1 notes): https://minecraft.wiki/w/Java_Edition_data_values
- Build sanity check: `./scripts/check-build.sh [mod]`
