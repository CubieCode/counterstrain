# Common Minecraft Modding Patterns

Patterns for blocks, items, entities, data generation, commands, recipes, and
JSON assets on **Minecraft 1.21.1 + NeoForge 21.1**. Upstream's 26.x / 1.21.2+
variants were replaced with 1.21.1 equivalents.

---

## Blocks

### Simple Full-Cube Block

Files needed:

1. Java class (if custom behavior) or `registerSimpleBlock()` call
2. `assets/<modid>/blockstates/<name>.json`
3. `assets/<modid>/models/block/<name>.json`
4. `assets/<modid>/models/item/<name>.json`
5. `assets/<modid>/textures/block/<name>.png`
6. `data/<modid>/loot_table/blocks/<name>.json`
7. `en_us.json` entry

(No `assets/<modid>/items/` item-definition file: that format is 1.21.4+.)

`assets/mymod/blockstates/my_block.json`:
```json
{
  "variants": {
    "": { "model": "mymod:block/my_block" }
  }
}
```

`assets/mymod/models/block/my_block.json`:
```json
{
  "parent": "minecraft:block/cube_all",
  "textures": {
    "all": "mymod:block/my_block"
  }
}
```

`assets/mymod/models/item/my_block.json` (required in 1.21.1; a block item has
no model without it):
```json
{
  "parent": "mymod:block/my_block"
}
```

`data/mymod/loot_table/blocks/my_block.json`:
```json
{
  "type": "minecraft:block",
  "pools": [{
    "rolls": 1,
    "entries": [{
      "type": "minecraft:item",
      "name": "mymod:my_block"
    }],
    "conditions": [{
      "condition": "minecraft:survives_explosion"
    }]
  }]
}
```

---

### Directional Block (faces a direction when placed)

```java
public class MyDirectionalBlock extends DirectionalBlock {
    public static final DirectionProperty FACING = DirectionalBlock.FACING; // EnumProperty<Direction> in 1.21.2+
    public static final MapCodec<MyDirectionalBlock> CODEC = simpleCodec(MyDirectionalBlock::new);

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() { return CODEC; } // required: DirectionalBlock is abstract

    public MyDirectionalBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getNearestLookingDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}
```

`assets/mymod/blockstates/my_directional_block.json`:
```json
{
  "variants": {
    "facing=north": { "model": "mymod:block/my_directional_block" },
    "facing=south": { "model": "mymod:block/my_directional_block", "y": 180 },
    "facing=west":  { "model": "mymod:block/my_directional_block", "y": 270 },
    "facing=east":  { "model": "mymod:block/my_directional_block", "y": 90 },
    "facing=up":    { "model": "mymod:block/my_directional_block", "x": -90 },
    "facing=down":  { "model": "mymod:block/my_directional_block", "x": 90 }
  }
}
```

---

### Slab Block

```java
public static final DeferredBlock<SlabBlock> MY_SLAB =
    BLOCKS.register("my_slab", () -> new SlabBlock(
        BlockBehaviour.Properties.ofFullCopy(Blocks.STONE_SLAB)));
```

`assets/mymod/models/block/my_slab.json`:
```json
{
  "parent": "minecraft:block/slab",
  "textures": {
    "bottom": "mymod:block/my_block",
    "top": "mymod:block/my_block",
    "side": "mymod:block/my_block"
  }
}
```

`assets/mymod/models/block/my_slab_top.json`:
```json
{
  "parent": "minecraft:block/slab_top",
  "textures": {
    "bottom": "mymod:block/my_block",
    "top": "mymod:block/my_block",
    "side": "mymod:block/my_block"
  }
}
```

`assets/mymod/blockstates/my_slab.json`:
```json
{
  "variants": {
    "type=bottom": { "model": "mymod:block/my_slab" },
    "type=top":    { "model": "mymod:block/my_slab_top" },
    "type=double": { "model": "mymod:block/my_block" }
  }
}
```

---

### Stairs Block

```java
public static final DeferredBlock<StairBlock> MY_STAIRS =
    BLOCKS.register("my_stairs", () -> new StairBlock(
        ModBlocks.MY_BLOCK.get().defaultBlockState(),
        BlockBehaviour.Properties.ofFullCopy(Blocks.STONE_STAIRS)));
```

`assets/mymod/models/block/my_stairs.json`:
```json
{
  "parent": "minecraft:block/stairs",
  "textures": {
    "bottom": "mymod:block/my_block",
    "top": "mymod:block/my_block",
    "side": "mymod:block/my_block"
  }
}
```

Also create `my_stairs_inner.json` and `my_stairs_outer.json`, inheriting from
`minecraft:block/inner_stairs` and `minecraft:block/outer_stairs` respectively.

---

## Items

### Food Item

```java
public static final DeferredItem<Item> MY_FOOD =
    ITEMS.registerSimpleItem("my_food", new Item.Properties()
        .food(new FoodProperties.Builder()
            .nutrition(4)
            .saturationModifier(0.3f)
            .effect(new MobEffectInstance(MobEffects.REGENERATION, 200, 1), 0.8f)
            .build()));
```

`assets/mymod/models/item/my_food.json`:
```json
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "mymod:item/my_food"
  }
}
```

### Tool Item (1.21.1)

`ToolMaterial` / `Item.Properties#sword` are 1.21.2+. On 1.21.1 use a `Tier`
(vanilla `Tiers.*` or NeoForge `SimpleTier`) and the tool item classes.

```java
public static final Tier MY_TIER = new SimpleTier(
    ModBlockTags.INCORRECT_FOR_MY_TOOL,   // blocks this tier cannot mine
    455, 5.0f, 1.5f, 22,                  // uses, speed, attack bonus, enchantability
    () -> Ingredient.of(ModItemTags.REPAIRS_MY_TOOL));

public static final DeferredItem<SwordItem> MY_SWORD =
    ITEMS.register("my_sword", () -> new SwordItem(MY_TIER,
        new Item.Properties().attributes(SwordItem.createAttributes(MY_TIER, 3, -2.4f))));
```

### Armor Set (1.21.1)

On 1.21.1 `ArmorMaterial` **is** a registry entry (`Registries.ARMOR_MATERIAL`)
used via `Holder<ArmorMaterial>`; equipment assets are 1.21.2+.

```java
public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
    DeferredRegister.create(Registries.ARMOR_MATERIAL, MyMod.MOD_ID);

public static final DeferredHolder<ArmorMaterial, ArmorMaterial> MY_ARMOR_MATERIAL =
    ARMOR_MATERIALS.register("my_material", () -> new ArmorMaterial(
        Util.make(new EnumMap<>(ArmorItem.Type.class), map -> {
            map.put(ArmorItem.Type.HELMET, 3);
            map.put(ArmorItem.Type.CHESTPLATE, 8);
            map.put(ArmorItem.Type.LEGGINGS, 6);
            map.put(ArmorItem.Type.BOOTS, 3);
            map.put(ArmorItem.Type.BODY, 8);
        }),
        15,                                   // enchantability
        SoundEvents.ARMOR_EQUIP_IRON,         // Holder<SoundEvent>
        () -> Ingredient.of(ModItemTags.REPAIRS_MY_ARMOR),
        // textures: assets/mymod/textures/models/armor/my_material_layer_1.png / _layer_2.png
        List.of(new ArmorMaterial.Layer(
            ResourceLocation.fromNamespaceAndPath(MyMod.MOD_ID, "my_material"))),
        0.0f, 0.0f));                          // toughness, knockback resistance

public static final DeferredItem<ArmorItem> MY_HELMET =
    ITEMS.register("my_helmet", () -> new ArmorItem(MY_ARMOR_MATERIAL, ArmorItem.Type.HELMET,
        new Item.Properties().durability(ArmorItem.Type.HELMET.getDurability(15))));
```

---

## Entity Types

```java
// ModEntityTypes.java (1.21.1: no DeferredRegister.Entities helper)
public class ModEntityTypes {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(Registries.ENTITY_TYPE, MyMod.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<MyEntity>> MY_ENTITY =
        ENTITY_TYPES.register("my_entity", () -> EntityType.Builder
            .of(MyEntity::new, MobCategory.CREATURE)
            .sized(0.9f, 1.3f)
            .clientTrackingRange(8)
            .updateInterval(3)
            .build("my_entity"));   // 1.21.1 takes a String id (ResourceKey in 1.21.2+)
}

// Living entities need attributes (mod bus):
@SubscribeEvent
public static void onAttributes(EntityAttributeCreationEvent event) {
    event.put(ModEntityTypes.MY_ENTITY.get(), MyEntity.createAttributes().build());
}
```

Register renderers in a client-only `EntityRenderersEvent.RegisterRenderers`
subscriber.

---

## Commands (Brigadier)

```java
// NeoForge — register on GAME bus
@EventBusSubscriber(modid = MyMod.MOD_ID)
public class ModCommands {
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }
}

// Shared implementation
private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
        Commands.literal("mymod")
            .then(Commands.literal("give")
                .requires(src -> src.hasPermission(2))  // op level 2
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> executeGive(ctx,
                            EntityArgument.getPlayer(ctx, "player"),
                            IntegerArgumentType.getInteger(ctx, "count"))))))
    );
}

private static int executeGive(CommandContext<CommandSourceStack> ctx,
        ServerPlayer player, int count) throws CommandSyntaxException {
    ItemStack stack = new ItemStack(ModItems.MY_ITEM.get(), count);
    player.getInventory().add(stack);
    ctx.getSource().sendSuccess(
        () -> Component.translatable("commands.mymod.give.success",
            count, player.getDisplayName()),
        true);
    return count;
}
```

---

## Recipes (Minecraft 1.21.1 JSON)

Folder is `data/<ns>/recipe/` (singular since 1.21). In 1.21.1 ingredients are
objects (`{"item": ...}` or `{"tag": ...}`); bare-string ingredients are 1.21.2+.
Results use `{"id": ..., "count": ...}` (1.20.5+).

### Shaped Crafting Recipe

`data/mymod/recipe/my_item.json`:
```json
{
  "type": "minecraft:crafting_shaped",
  "pattern": [
    "SSS",
    " I ",
    " I "
  ],
  "key": {
    "S": { "item": "minecraft:stone" },
    "I": { "item": "minecraft:iron_ingot" }
  },
  "result": {
    "id": "mymod:my_item",
    "count": 1
  }
}
```

### Shapeless Recipe

```json
{
  "type": "minecraft:crafting_shapeless",
  "ingredients": [
    { "item": "minecraft:diamond" },
    { "item": "minecraft:emerald" }
  ],
  "result": {
    "id": "mymod:my_item",
    "count": 2
  }
}
```

### Smelting / Blasting / Smoking / Campfire

```json
{
  "type": "minecraft:smelting",
  "ingredient": { "item": "mymod:my_ore" },
  "result": { "id": "mymod:my_ingot" },
  "experience": 0.7,
  "cookingtime": 200
}
```

### Custom Recipe Type

```java
// Implement Recipe<RecipeInput> and register RecipeSerializer + RecipeType
public class MyRecipe implements Recipe<SingleRecipeInput> {
    // ...
}
```

---

## Tags

Tags group blocks/items/entity types. 1.21.1 folders are singular:
`tags/block/`, `tags/item/`, `tags/entity_type/`. Vanilla tags such as
`mineable/pickaxe` live in the `minecraft` namespace.

`data/minecraft/tags/block/mineable/pickaxe.json`:
```json
{
  "replace": false,
  "values": ["mymod:my_block"]
}
```

`data/minecraft/tags/block/needs_iron_tool.json`:
```json
{
  "replace": false,
  "values": ["mymod:my_block"]
}
```

`data/mymod/tags/item/my_material.json`:
```json
{
  "replace": false,
  "values": ["mymod:my_ingot", "mymod:my_nugget"]
}
```

---

## Data Generation (NeoForge 1.21.1)

`BlockStateProvider` / `ItemModelProvider` (with `ExistingFileHelper`) are the
1.21.1 model generators; they were replaced by `ModelProvider` in 1.21.4.

### BlockState Provider

```java
public class ModBlockStateProvider extends BlockStateProvider {
    public ModBlockStateProvider(PackOutput output, ExistingFileHelper helper) {
        super(output, MyMod.MOD_ID, helper);
    }

    @Override
    protected void registerStatesAndModels() {
        simpleBlock(ModBlocks.MY_BLOCK.get());
        simpleBlock(ModBlocks.SPECIAL_BLOCK.get(),
            models().cubeAll("special_block", modLoc("block/special_block")));
        // Slab:
        slabBlock((SlabBlock) ModBlocks.MY_SLAB.get(),
            modLoc("block/my_block"), modLoc("block/my_block"));
        // Stairs:
        stairsBlock((StairBlock) ModBlocks.MY_STAIRS.get(), modLoc("block/my_block"));
    }
}
```

### Item Model Provider

```java
public class ModItemModelProvider extends ItemModelProvider {
    public ModItemModelProvider(PackOutput output, ExistingFileHelper helper) {
        super(output, MyMod.MOD_ID, helper);
    }

    @Override
    protected void registerModels() {
        // BlockItem models derived from block models:
        withExistingParent(ModItems.MY_BLOCK_ITEM.getId().getPath(),
            modLoc("block/my_block"));

        // Flat item (generated):
        basicItem(ModItems.MY_ITEM.get());
    }
}
```

### Recipe Provider

```java
public class ModRecipeProvider extends RecipeProvider {
    public ModRecipeProvider(PackOutput output,
            CompletableFuture<HolderLookup.Provider> lookupProvider) {
        super(output, lookupProvider);
    }

    @Override
    protected void buildRecipes(RecipeOutput output) {
        ShapedRecipeBuilder.shaped(RecipeCategory.BUILDING_BLOCKS, ModItems.MY_BLOCK_ITEM.get(), 4)
            .pattern("SS")
            .pattern("SS")
            .define('S', Items.STONE)
            .unlockedBy("has_stone", has(Items.STONE))
            .save(output);

        SimpleCookingRecipeBuilder.smelting(
                Ingredient.of(Tags.Items.ORES_IRON),
                RecipeCategory.MISC,
                ModItems.MY_INGOT.get(),
                0.7f, 200)
            .unlockedBy("has_ore", has(Tags.Items.ORES_IRON))
            .save(output, ResourceLocation.fromNamespaceAndPath(MyMod.MOD_ID, "my_ingot_smelting"));
    }
}
```

### Loot Table Provider

```java
public class ModLootTableProvider extends LootTableProvider {
    public ModLootTableProvider(PackOutput output,
            CompletableFuture<HolderLookup.Provider> lookupProvider) {
        super(output, Set.of(), List.of(
            new SubProviderEntry(ModBlockLootTables::new, LootContextParamSets.BLOCK)
        ), lookupProvider);
    }

    public static class ModBlockLootTables extends BlockLootSubProvider {
        protected ModBlockLootTables(HolderLookup.Provider registries) {
            super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
        }

        @Override
        protected void generate() {
            dropSelf(ModBlocks.MY_BLOCK.get());

            // Drop ore with fortune/silk-touch handling:
            add(ModBlocks.MY_ORE.get(),
                createOreDrop(ModBlocks.MY_ORE.get(), ModItems.MY_GEM.get()));
        }

        @Override
        protected Iterable<Block> getKnownBlocks() {
            return ModBlocks.BLOCKS.getEntries().stream()
                .map(DeferredHolder::get)::iterator;
        }
    }
}
```

---

## Language File (en_us.json)

```json
{
  "block.mymod.my_block": "My Block",
  "item.mymod.my_item": "My Item",
  "item.mymod.my_food": "My Food",
  "entity.mymod.my_entity": "My Entity",
  "container.mymod.my_container": "My Container",
  "itemGroup.mymod.main_tab": "My Mod",
  "commands.mymod.give.success": "Gave %s x%s My Item"
}
```

---

## GitHub Actions CI Workflow

```yaml
# .github/workflows/build.yml
name: Build

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'microsoft'
      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
      - name: Build with Gradle
        run: ./gradlew build
        working-directory: mod
      - name: Run Game Tests
        run: ./gradlew runGameTestServer
        working-directory: mod
      - name: Upload Build Artifacts
        uses: actions/upload-artifact@v4
        with:
          name: mod-jar
          path: mod/build/libs/*.jar
          if-no-files-found: error
```

---

## Modrinth / CurseForge Publishing

```groovy
// build.gradle — Modrinth via Minotaur plugin
modrinth {
    token = System.getenv("MODRINTH_TOKEN")
    projectId = "your-project-id"
    versionNumber = project.mod_version
    versionType = "release"
    uploadFile = jar
    gameVersions = ["1.21.1"]
    loaders = ["neoforge"]
    changelog = rootProject.file("CHANGELOG.md").text
    syncBodyFrom = rootProject.file("README.md").text
}
```

```groovy
// Alternatively, use the official CurseForge Gradle plugin
curseforge {
    apiKey = System.getenv("CURSEFORGE_TOKEN")
    project {
        id = "000000"
        changelogType = "markdown"
        changelog = file("CHANGELOG.md")
        releaseType = "release"
        addGameVersion "1.21.1"
        addGameVersion "NeoForge"
        mainArtifact jar
    }
}
```
