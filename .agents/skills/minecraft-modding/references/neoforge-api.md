# NeoForge API Patterns (Minecraft 1.21.1)

NeoForge 21.1.x patterns for Minecraft 1.21.1 with Java 21. Verify against
https://docs.neoforged.net/docs/1.21.1/ when unsure; APIs from 1.21.2+ are
called out where upstream used them.

---

## Mod Entry Point

```java
// MyMod.java
@Mod(MyMod.MOD_ID)
public class MyMod {
    public static final String MOD_ID = "mymod";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MyMod(IEventBus modEventBus) {
        // Register deferred registers with the mod event bus
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenuTypes.MENUS.register(modEventBus);
        ModEntityTypes.ENTITY_TYPES.register(modEventBus);
        ModSounds.SOUNDS.register(modEventBus);

        // Register mod event listeners
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreativeTabItems);

        // Game-bus listeners: prefer @EventBusSubscriber classes. If you do
        // NeoForge.EVENT_BUS.register(this), `this` must have @SubscribeEvent
        // instance methods or registration throws.
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // thread-safe registration calls go here, e.g. CompostingChanceRegistry
        });
    }

    private void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(ModItems.MY_ITEM);
        }
    }
}
```

---

## neoforge.mods.toml (META-INF/neoforge.mods.toml)

> File renamed from `mods.toml` to `neoforge.mods.toml` in NeoForge 1.20.5+.
> The MDK template fills these values from `gradle.properties` placeholders.

```toml
modLoader="javafml"
loaderVersion="[1,)"
license="MIT"

[[mods]]
modId="mymod"
version="${file.jarVersion}"
displayName="My Mod"
description='''
A brief description of what my mod does.
'''
logoFile="mymod.png"

[[dependencies.mymod]]
modId="neoforge"
type="required"
versionRange="[21.1,)"
ordering="NONE"
side="BOTH"

[[dependencies.mymod]]
modId="minecraft"
type="required"
versionRange="[1.21.1]"
ordering="NONE"
side="BOTH"
```

---

## DeferredRegister Patterns

```java
// ModBlocks.java
public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
        DeferredRegister.createBlocks(MyMod.MOD_ID);

    // Simple full-cube block
    public static final DeferredBlock<Block> MY_BLOCK =
        BLOCKS.registerSimpleBlock("my_block",
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .instrument(NoteBlockInstrument.BASEDRUM)
                .strength(1.5f, 6.0f)
                .sound(SoundType.STONE)
                .requiresCorrectToolForDrops());

    // Custom block class
    public static final DeferredBlock<MySpecialBlock> SPECIAL_BLOCK =
        BLOCKS.registerBlock("special_block", MySpecialBlock::new,
            BlockBehaviour.Properties.of().strength(2.0f));
}
```

```java
// ModItems.java
public class ModItems {
    public static final DeferredRegister.Items ITEMS =
        DeferredRegister.createItems(MyMod.MOD_ID);

    // BlockItem for a block
    public static final DeferredItem<BlockItem> MY_BLOCK_ITEM =
        ITEMS.registerSimpleBlockItem(ModBlocks.MY_BLOCK);

    // Simple item
    public static final DeferredItem<Item> MY_ITEM =
        ITEMS.registerSimpleItem("my_item", new Item.Properties().stacksTo(16));

    // Custom item class
    public static final DeferredItem<MyCustomItem> MY_CUSTOM_ITEM =
        ITEMS.registerItem("my_custom_item", MyCustomItem::new);
}
```

For 1.21.1 tool (`SimpleTier`/`SwordItem`) and armor (registered
`ArmorMaterial`/`ArmorItem`) patterns, see `common-patterns.md`.

## Data Components (item state)

```java
public static final DeferredRegister.DataComponents COMPONENTS =
    DeferredRegister.createDataComponents(MyMod.MOD_ID);

public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> CHARGE =
    COMPONENTS.registerComponentType("charge", b -> b
        .persistent(Codec.INT)                 // saved to disk
        .networkSynchronized(ByteBufCodecs.VAR_INT)); // synced to clients

int charge = stack.getOrDefault(CHARGE, 0);
stack.set(CHARGE, charge + 1);
```

Component values must be immutable (records/primitives) with proper
`equals`/`hashCode`. Do not store item state in NBT.

---

## Block Entity

```java
// MyBlockEntity.java
public class MyBlockEntity extends BlockEntity {
    private int processingTicks;

    public MyBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MY_BLOCK_ENTITY.get(), pos, state);
    }

    // 1.21.1 signatures (ValueInput/ValueOutput are 1.21.6+)
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("processing_ticks", processingTicks);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        processingTicks = tag.getInt("processing_ticks");
    }
}

// ModBlockEntities.java
public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, MyMod.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MyBlockEntity>>
        MY_BLOCK_ENTITY = BLOCK_ENTITIES.register("my_block_entity",
            () -> BlockEntityType.Builder
                .of(MyBlockEntity::new, ModBlocks.MY_BLOCK.get())
                .build(null));
}
```

---

## Event Bus System

NeoForge has two event buses:

- **MOD bus** (`IEventBus` injected into constructor): lifecycle events, registration events
- **GAME bus** (`NeoForge.EVENT_BUS`): in-game events
  Import: `net.neoforged.neoforge.common.NeoForge`

`@EventBusSubscriber` is `net.neoforged.fml.common.EventBusSubscriber`; in
NeoForge 21.1.259 the `bus` attribute is deprecated for removal: listeners are
routed by event type (mod-bus events implement `IModBusEvent`), so omit it.
Listener methods must be `static`. Datagen wiring is shown in `SKILL.md`.

```java
// Client-only mod-bus events go in a Dist.CLIENT subscriber
@EventBusSubscriber(modid = MyMod.MOD_ID, value = Dist.CLIENT)
public class ClientModEvents {
    @SubscribeEvent
    public static void onRegisterEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntityTypes.MY_ENTITY.get(), MyEntityRenderer::new);
    }
}

@EventBusSubscriber(modid = MyMod.MOD_ID)
public class GameEvents {
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        // runs every tick for every player
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingIncomingDamageEvent event) {
        // fires when an entity is about to take damage; cancellable
    }
}
```

---

## Menu / GUI (Container)

```java
// MyMenu.java (server + client)
public class MyMenu extends AbstractContainerMenu {
    private final ContainerLevelAccess access;

    public MyMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, ContainerLevelAccess.NULL);
    }

    public MyMenu(int containerId, Inventory playerInventory, ContainerLevelAccess access) {
        super(ModMenuTypes.MY_MENU.get(), containerId);
        this.access = access;
        addPlayerInventory(playerInventory);
        addPlayerHotbar(playerInventory);
    }

    @Override
    public boolean stillValid(Player player) {
        return access.evaluate(
            (level, pos) -> player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5,
                pos.getZ() + 0.5) < 64, true);
    }
}

// MyScreen.java (client-only class; only referenced from client code)
public class MyScreen extends AbstractContainerScreen<MyMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(MyMod.MOD_ID, "textures/gui/my_gui.png");

    public MyScreen(MyMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);
    }
}

// Register on MOD bus (client-only):
@EventBusSubscriber(modid = MyMod.MOD_ID, value = Dist.CLIENT)
public class ClientModEvents {
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.MY_MENU.get(), MyScreen::new);
    }
}
```

---

## Capabilities (NeoForge 1.21.1)

NeoForge registers providers for concrete block entities, blocks, entity types, or
items on the mod event bus. Its capability lookup returns the implementation or
`null`. Do not use Forge's `AttachCapabilitiesEvent`, `ICapabilityProvider`, or
`LazyOptional` patterns. Capabilities expose behaviour (inventories, energy);
for storing per-entity/level/chunk data use Data Attachments below.

```java
@EventBusSubscriber(modid = MyMod.MOD_ID)
public final class ModCapabilities {
    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            Capabilities.ItemHandler.BLOCK,
            ModBlockEntities.MY_BLOCK_ENTITY.get(),
            (blockEntity, side) -> blockEntity.getItemHandler(side)
        );
    }
}

// Query from a level. A null result means this side has no item handler.
// (1.21.9+ replaces IItemHandler with ResourceHandler<ItemResource>; not in 1.21.1)
IItemHandler handler =
    level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.NORTH);
if (handler != null) {
    // Use handler.
}
```

For a custom capability, create a static `BlockCapability`, `EntityCapability`,
or `ItemCapability` and register its provider with the same event. See the
[NeoForge 1.21.1 capability guide](https://docs.neoforged.net/docs/1.21.1/inventories/capabilities/).

---

## Data Attachments (per-entity / level / chunk / block-entity data)

```java
public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
    DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MyMod.MOD_ID);

public static final Supplier<AttachmentType<Integer>> STRAIN =
    ATTACHMENT_TYPES.register("strain", () -> AttachmentType.builder(() -> 0)
        .serialize(Codec.INT)   // persist to disk; omit for transient data
        .copyOnDeath()          // players: keep across respawn (needs serialize)
        .build());

int strain = player.getData(STRAIN);   // creates default if absent
player.setData(STRAIN, strain + 1);
player.hasData(STRAIN);
```

Attachments are server-side state; sync to clients with your own payload.
(Recent 21.1.x builds also have `AttachmentType.Builder#sync(...)`; check it
exists in the pinned version before relying on it.)

---

## Network Packets (CustomPacketPayload; SimpleChannel is gone)

```java
// Register a payload (packet) type
public record MyPayload(int value) implements CustomPacketPayload {
    public static final Type<MyPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(MyMod.MOD_ID, "my_payload"));

    public static final StreamCodec<ByteBuf, MyPayload> STREAM_CODEC =
        StreamCodec.composite(ByteBufCodecs.INT, MyPayload::value, MyPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

// Register on MOD bus. Each payload TYPE may be registered only once:
// use playToClient OR playToServer OR playBidirectional (with a
// DirectionalPayloadHandler for separate client/server handlers).
@SubscribeEvent
public static void registerPayloads(RegisterPayloadHandlersEvent event) {
    PayloadRegistrar registrar = event.registrar("1");
    // Handlers run on the main thread by default (HandlerThread.MAIN);
    // registrar.executesOn(HandlerThread.NETWORK) opts out, then use ctx.enqueueWork.
    registrar.playToClient(MyPayload.TYPE, MyPayload.STREAM_CODEC,
        (payload, ctx) -> ClientPayloadHandler.handle(payload, ctx)); // client-only class
    registrar.playToServer(OtherPayload.TYPE, OtherPayload.STREAM_CODEC,
        (payload, ctx) -> handleOnServer(payload, (ServerPlayer) ctx.player()));
}

// Send from server to client
PacketDistributor.sendToPlayer(serverPlayer, new MyPayload(42));

// Send from client to server (1.21.1 API)
PacketDistributor.sendToServer(new OtherPayload(42));
```

---

## Biome Modifier (World Gen Integration)

`data/mymod/neoforge/biome_modifier/add_spawn.json`:
```json
{
  "type": "neoforge:add_spawns",
  "biomes": "#minecraft:is_overworld",
  "spawners": [
    {
      "type": "mymod:my_entity",
      "weight": 10,
      "minCount": 2,
      "maxCount": 4
    }
  ]
}
```

---

## gradle.properties (NeoForge 1.21.1 MDG MDK shape)

```properties
parchment_minecraft_version=1.21.1
parchment_mappings_version=2024.11.17
minecraft_version=1.21.1
minecraft_version_range=[1.21.1]
neo_version=21.1.259
loader_version_range=[1,)

mod_id=mymod
mod_name=My Mod
mod_license=MIT
mod_version=1.0.0
mod_group_id=com.example.mymod
mod_authors=YourName
mod_description=A cool Minecraft mod.

## Dependencies (optional)
# patchouli_version=...
```

---

## Useful Classes (1.21.1 Quick Reference)

|Need|Class|
|---|---|
|Block properties|`BlockBehaviour.Properties`|
|Item properties|`Item.Properties`|
|Map colours|`MapColor.*`|
|Block sounds|`SoundType.*`|
|Tool tiers|`Tiers.*`, NeoForge `SimpleTier` (`ToolMaterial` is 1.21.2+)|
|Item data|`DataComponents.*`, `DataComponentType`|
|Rarity|`Rarity.*` (COMMON, UNCOMMON, RARE, EPIC)|
|Block tags|`BlockTags.*`|
|Item tags|`ItemTags.*`|
|Entity categories|`MobCategory.*`|
|Directions|`Direction.*`|
|Block positions|`BlockPos`, `BlockPos.MutableBlockPos`|
|Level access|`Level`, `ServerLevel`|
|Registry access|`BuiltInRegistries.*`, `Registries.*`|
