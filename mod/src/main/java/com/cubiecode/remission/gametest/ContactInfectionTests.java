package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CorruptionCause;
import com.cubiecode.remission.api.HostCorruptedEvent;
import com.cubiecode.remission.behaviour.MobProfile;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.infection.Contact;
import com.cubiecode.remission.infection.ContactSettings;
import com.cubiecode.remission.infection.ContactTracker;
import com.cubiecode.remission.infection.ExposureSettings;
import com.cubiecode.remission.infection.ExposureTracker;
import com.cubiecode.remission.infection.Infection;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.world.ChunkCorruption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Contact infection (P4.8): a mob on, in or against a block in {@code #remission:infectious}, or in water in a chunk at or
 * above the festering land minimum stage, becomes a host after {@code contactTicks} of contact (on the first pass at 0)
 * through the corruption API with {@link CorruptionCause#CONTACT}. Each test runs alone in its own batch and drives its
 * own {@link ContactTracker} over mobs that are created but never added to the level (so the level's own tracker never
 * sees them), with synthetic game times, around the test's air box; it puts back every block and the chunk's land.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ContactInfectionTests {
    private static final String BATCH = "contact_infection";
    private static final int CONTACT = 100;
    private static final int MIN_STAGE = 2;
    private static final ContactSettings SETTINGS = new ContactSettings(true, CONTACT, MIN_STAGE, 10, 64, 32);
    private static final long BASE = 20_000_000L;

    private ContactInfectionTests() {
    }

    /** Blocks a test set, put back afterwards, and the test chunk's land. */
    private record Scene(GameTestHelper helper, LevelChunk chunk, List<Runnable> undo) {
        BlockPos pos(int x, int y, int z) {
            return helper.absolutePos(new BlockPos(x, y, z));
        }

        void set(int x, int y, int z, BlockState state) {
            ServerLevel level = helper.getLevel();
            BlockPos at = pos(x, y, z);
            BlockState old = level.getBlockState(at);
            undo.add(0, () -> level.setBlock(at, old, Block.UPDATE_CLIENTS));
            level.setBlock(at, state, Block.UPDATE_CLIENTS);
        }
    }

    private static void inScene(GameTestHelper helper, Consumer<Scene> body) {
        LevelChunk chunk = helper.getLevel().getChunkAt(helper.absolutePos(new BlockPos(1, 1, 1)));
        ChunkCorruption before = chunk.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
        Scene scene = new Scene(helper, chunk, new ArrayList<>());
        try {
            body.accept(scene);
        } finally {
            scene.undo().forEach(Runnable::run);
            if (before == null) {
                chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
            } else {
                chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, before);
            }
        }
    }

    private static void setStage(LevelChunk chunk, int stage) {
        if (stage <= 0) {
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
        } else {
            chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(ExposureSettings.levelForStage(stage), 0L));
        }
    }

    /** A mob of {@code type} with its feet at {@code feet}, not added to the level. */
    private static Mob create(ServerLevel level, EntityType<? extends Mob> type, Vec3 feet) {
        Mob mob = type.create(level);
        if (mob == null) {
            throw new IllegalStateException("Could not create " + type);
        }
        mob.setNoAi(true);
        mob.moveTo(feet.x, feet.y, feet.z, 0, 0);
        return mob;
    }

    private static Vec3 feet(Scene scene, double x, double y, double z) {
        BlockPos origin = scene.pos(0, 0, 0);
        return new Vec3(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
    }

    private static ContactTracker tracker(ServerLevel level, ContactSettings settings, List<? extends Entity> mobs,
                                          Map<Mob, MobProfile> profiles) {
        return new ContactTracker(level, () -> settings, () -> mobs, mob -> Optional.ofNullable(profiles.get(mob)));
    }

    /** Ticks {@code tracker} at every game time from {@code from} to {@code to}, inclusive. */
    private static void run(ContactTracker tracker, long from, long to) {
        for (long t = from; t <= to; t++) {
            tracker.tick(t);
        }
    }

    private static List<HostCorruptedEvent> capture(Runnable body) {
        List<HostCorruptedEvent> events = new ArrayList<>();
        Consumer<HostCorruptedEvent> listener = events::add;
        NeoForge.EVENT_BUS.addListener(HostCorruptedEvent.class, listener);
        try {
            body.run();
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        return events;
    }

    /**
     * Pigs standing on festering grass, inside a festering vein and pressed against a festered stone wall become hosts
     * after the contact time, in clean land; a pig on plain stone beside them does not.
     */
    @GameTest(template = "empty", batch = BATCH + "_blocks")
    public static void mobsTouchingInfectiousBlocksBecomeHosts(GameTestHelper helper) {
        inScene(helper, scene -> {
            ServerLevel level = helper.getLevel();
            setStage(scene.chunk(), 0);
            scene.set(0, 0, 0, RemissionBlocks.FESTERING_GRASS.get().defaultBlockState());
            scene.set(2, 0, 0, Blocks.STONE.defaultBlockState());
            scene.set(2, 1, 0, RemissionBlocks.FESTERING_VEIN.get().defaultBlockState()
                    .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true));
            scene.set(1, 0, 2, Blocks.STONE.defaultBlockState());
            scene.set(2, 1, 2, RemissionBlocks.FESTERED_STONE.get().defaultBlockState());
            scene.set(0, 0, 2, Blocks.STONE.defaultBlockState());
            Mob on = create(level, EntityType.PIG, feet(scene, 0.5, 1.0, 0.5));
            Mob in = create(level, EntityType.PIG, feet(scene, 2.5, 1.0, 0.5));
            // Pig boxes are 0.9 wide: this one's east side is flush with the wall's west face.
            Mob against = create(level, EntityType.PIG, feet(scene, 2.0 - 0.45, 1.5, 2.5));
            Mob clean = create(level, EntityType.PIG, feet(scene, 0.5, 1.0, 2.5));
            helper.assertValueEqual(Contact.of(on), Contact.Kind.BLOCK, "contact standing on festering grass");
            helper.assertValueEqual(Contact.of(in), Contact.Kind.BLOCK, "contact inside a vein");
            helper.assertValueEqual(Contact.of(against), Contact.Kind.BLOCK, "contact against festered stone");
            helper.assertValueEqual(Contact.of(clean), Contact.Kind.NONE, "contact on plain stone");
            ContactTracker tracker = tracker(level, SETTINGS, List.of(on, in, against, clean), Map.of());
            List<HostCorruptedEvent> events = capture(() -> {
                run(tracker, BASE, BASE + CONTACT - 1);
                for (Mob mob : List.of(on, in, against)) {
                    helper.assertFalse(Corruption.isHost(mob), "host before the contact time");
                }
                helper.assertValueEqual(ContactTracker.contact(on), CONTACT - SETTINGS.intervalTicks(), "contact so far");
                run(tracker, BASE + CONTACT, BASE + CONTACT);
            });
            for (Mob mob : List.of(on, in, against)) {
                helper.assertTrue(Corruption.isHost(mob), "touching pig is not a host after the contact time");
                helper.assertValueEqual(Corruption.get(mob).level(), Infection.initialLevel(), "new host level");
                helper.assertValueEqual(ContactTracker.contact(mob), 0, "contact kept by a new host");
            }
            helper.assertFalse(Corruption.isHost(clean), "pig on plain stone became a host");
            helper.assertValueEqual(ContactTracker.contact(clean), 0, "contact of the pig on plain stone");
            helper.assertValueEqual(events.size(), 3, "host corrupted events");
            for (HostCorruptedEvent event : events) {
                helper.assertValueEqual(event.getCause(), CorruptionCause.CONTACT, "event cause");
                helper.assertTrue(event.becameHost(), "event did not turn the mob into a host");
            }
            helper.succeed();
        });
    }

    /** With {@code contactTicks} 0 the first pass that finds a mob in contact infects it; contact out of it decays. */
    @GameTest(template = "empty", batch = BATCH + "_first_touch")
    public static void firstTouchInfectsAtZero(GameTestHelper helper) {
        inScene(helper, scene -> {
            ServerLevel level = helper.getLevel();
            scene.set(0, 0, 0, RemissionBlocks.FESTERING_GRASS.get().defaultBlockState());
            Mob pig = create(level, EntityType.PIG, feet(scene, 0.5, 1.0, 0.5));
            ContactSettings instant = new ContactSettings(true, 0, MIN_STAGE, 10, 64, 32);
            List<HostCorruptedEvent> events = capture(() -> tracker(level, instant, List.of(pig), Map.of()).tick(BASE));
            helper.assertTrue(Corruption.isHost(pig), "pig is not a host on first touch");
            helper.assertValueEqual(events.size(), 1, "host corrupted events");
            helper.assertValueEqual(events.get(0).getCause(), CorruptionCause.CONTACT, "event cause");

            // Contact built up on festering grass falls once the pig is lifted off it.
            Mob sheep = create(level, EntityType.SHEEP, feet(scene, 0.5, 1.0, 0.5));
            ContactTracker tracker = tracker(level, SETTINGS, List.of(sheep), Map.of());
            run(tracker, BASE, BASE + 60);
            helper.assertValueEqual(ContactTracker.contact(sheep), 60, "contact after 60 ticks");
            sheep.moveTo(sheep.getX(), sheep.getY() + 1.0, sheep.getZ());
            run(tracker, BASE + 61, BASE + 100);
            helper.assertValueEqual(ContactTracker.contact(sheep), 20, "contact after 40 ticks without it");
            helper.assertFalse(Corruption.isHost(sheep), "sheep lifted off became a host");
            helper.succeed();
        });
    }

    /**
     * A cod and a squid in water become hosts after the contact time when the chunk is at the minimum stage, and not
     * below it; the water is not replaced.
     */
    @GameTest(template = "empty", batch = BATCH + "_water")
    public static void swimmersInFesteringWaterBecomeHosts(GameTestHelper helper) {
        inScene(helper, scene -> {
            ServerLevel level = helper.getLevel();
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    scene.set(x, 0, z, Blocks.STONE.defaultBlockState());
                    scene.set(x, 1, z, Blocks.WATER.defaultBlockState());
                    scene.set(x, 2, z, Blocks.WATER.defaultBlockState());
                }
            }
            // Both in the centre column: it is in the scene's chunk wherever the grid puts the template, while a corner
            // column can fall in the neighbouring chunk, whose Strain level the test never sets.
            Mob cod = create(level, EntityType.COD, feet(scene, 1.5, 1.2, 1.5));
            Mob squid = create(level, EntityType.SQUID, feet(scene, 1.5, 1.2, 1.5));
            List<Mob> swimmers = List.of(cod, squid);

            setStage(scene.chunk(), MIN_STAGE - 1);
            helper.assertValueEqual(Contact.of(cod, SETTINGS.waterMinLevel()), Contact.Kind.NONE, "water below the minimum stage");
            run(tracker(level, SETTINGS, swimmers, Map.of()), BASE, BASE + 3L * CONTACT);
            for (Mob mob : swimmers) {
                helper.assertFalse(Corruption.isHost(mob), "swimmer became a host below the minimum stage");
            }

            setStage(scene.chunk(), MIN_STAGE);
            helper.assertValueEqual(Contact.of(cod, SETTINGS.waterMinLevel()), Contact.Kind.WATER, "water at the minimum stage");
            ContactTracker tracker = tracker(level, SETTINGS, swimmers, Map.of());
            List<HostCorruptedEvent> events = capture(() -> run(tracker, BASE, BASE + CONTACT));
            for (Mob mob : swimmers) {
                helper.assertTrue(Corruption.isHost(mob), mob.getType() + " in festering water is not a host");
            }
            helper.assertValueEqual(events.size(), 2, "host corrupted events");
            events.forEach(event -> helper.assertValueEqual(event.getCause(), CorruptionCause.CONTACT, "event cause"));
            helper.assertTrue(level.getBlockState(scene.pos(0, 1, 0)).is(Blocks.WATER), "water replaced");
            helper.succeed();
        });
    }

    /**
     * Immune mobs, hosts, a profile with {@code conversion.festering_land} false and players never take contact
     * infection; the contact query still reports the player's contact.
     */
    @GameTest(template = "empty", batch = BATCH + "_ineligible")
    public static void ineligibleNeverBecomeHosts(GameTestHelper helper) {
        inScene(helper, scene -> {
            ServerLevel level = helper.getLevel();
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    scene.set(x, 0, z, RemissionBlocks.FESTERING_GRASS.get().defaultBlockState());
                }
            }
            Vec3 middle = feet(scene, 1.5, 1.0, 1.5);
            Mob wither = create(level, EntityType.WITHER, middle);
            Mob host = create(level, EntityType.PIG, middle);
            Corruption.set(host, 50);
            Mob optedOut = create(level, EntityType.COW, middle);
            MobProfile noFesteringLand = new MobProfile(HolderSet.direct(EntityType.COW.builtInRegistryHolder()), 0,
                    Optional.empty(), Map.of(), Optional.empty(), new MobProfile.Conversion(true, false));
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.moveTo(middle.x, middle.y, middle.z, 0, 0);
            helper.assertTrue(Contact.inContact(player), "contact query misses the player on festering grass");
            List<Entity> all = List.of(wither, host, optedOut, player);
            ContactTracker tracker = tracker(level, new ContactSettings(true, 0, MIN_STAGE, 10, 64, 32), all,
                    Map.of(optedOut, noFesteringLand));
            List<HostCorruptedEvent> events = capture(() -> run(tracker, BASE, BASE + 3L * CONTACT));
            helper.assertFalse(Corruption.isHost(wither), "immune mob became a host");
            helper.assertFalse(Corruption.isHost(optedOut), "conversion.festering_land false became a host");
            helper.assertFalse(Corruption.isHost(player), "player took contact infection");
            helper.assertValueEqual(Corruption.get(host).level(), 50, "existing host level");
            helper.assertTrue(events.isEmpty(), "ineligible entities posted host corrupted events");
            for (Mob mob : List.of(wither, host, optedOut)) {
                helper.assertValueEqual(ContactTracker.contact(mob), 0, "contact of ineligible " + mob.getType());
            }
            helper.succeed();
        });
    }

    /** With contact infection off nothing builds up or infects; festering land exposure is a separate source. */
    @GameTest(template = "empty", batch = BATCH + "_off")
    public static void nothingWhenOff(GameTestHelper helper) {
        inScene(helper, scene -> {
            ServerLevel level = helper.getLevel();
            scene.set(0, 0, 0, RemissionBlocks.FESTERING_GRASS.get().defaultBlockState());
            Mob pig = create(level, EntityType.PIG, feet(scene, 0.5, 1.0, 0.5));
            ContactSettings off = new ContactSettings(false, 0, MIN_STAGE, 10, 64, 32);
            ContactTracker tracker = tracker(level, off, List.of(pig), Map.of());
            for (long t = BASE; t <= BASE + 3L * CONTACT; t++) {
                helper.assertValueEqual(tracker.tick(t), ExposureTracker.TickWork.NONE, "work while off");
            }
            helper.assertFalse(Corruption.isHost(pig), "host with contact infection off");
            helper.assertValueEqual(ContactTracker.contact(pig), 0, "contact with contact infection off");
            // Festering land exposure (P4.7) does not count contact, and contact does not count exposure.
            helper.assertValueEqual(ExposureTracker.exposure(pig), 0, "exposure from contact");
            helper.succeed();
        });
    }

    /** A pig whose profile converts it into a zombie is replaced by a host zombie, with the contact cause. */
    @GameTest(template = "empty", batch = BATCH + "_convert_to")
    public static void convertToApplies(GameTestHelper helper) {
        inScene(helper, scene -> {
            ServerLevel level = helper.getLevel();
            scene.set(1, 0, 1, RemissionBlocks.FESTERING_GRASS.get().defaultBlockState());
            Mob pig = create(level, EntityType.PIG, feet(scene, 1.5, 1.0, 1.5));
            level.addFreshEntity(pig);
            MobProfile toZombie = new MobProfile(HolderSet.direct(EntityType.PIG.builtInRegistryHolder()), 0, Optional.empty(),
                    Map.of(), Optional.of(EntityType.ZOMBIE.builtInRegistryHolder()), MobProfile.Conversion.DEFAULT);
            ContactTracker tracker = tracker(level, new ContactSettings(true, 0, MIN_STAGE, 10, 64, 32), List.of(pig),
                    Map.of(pig, toZombie));
            List<HostCorruptedEvent> events = capture(() -> tracker.tick(BASE));
            helper.assertTrue(pig.isRemoved(), "converted pig was not removed");
            helper.assertValueEqual(events.size(), 1, "host corrupted events");
            HostCorruptedEvent event = events.get(0);
            Entity host = event.getEntity();
            helper.assertValueEqual(host.getType(), EntityType.ZOMBIE, "converted type");
            helper.assertValueEqual(event.getCause(), CorruptionCause.CONTACT, "event cause");
            helper.assertTrue(event.becameHost() && Corruption.isHost((Mob) host), "converted zombie is not a host");
            host.discard();
            helper.succeed();
        });
    }

    /** The shipped infectious blocks, and CONTACT's place among the causes. */
    @GameTest(template = "empty", batch = BATCH + "_defaults")
    public static void shippedDefaults(GameTestHelper helper) {
        List<Block> infectious = BuiltInRegistries.BLOCK.getTag(Contact.INFECTIOUS).orElseThrow().stream()
                .map(holder -> holder.value()).toList();
        helper.assertTrue(infectious.containsAll(List.of(RemissionBlocks.FESTERING_GRASS.get(), RemissionBlocks.FESTERING_SHORT_GRASS.get(),
                RemissionBlocks.FESTERING_TALL_GRASS.get(), RemissionBlocks.FESTERING_FERN.get(),
                RemissionBlocks.FESTERING_LARGE_FERN.get(), RemissionBlocks.FESTERED_STONE.get(),
                RemissionBlocks.FESTERED_DEEPSLATE.get(), RemissionBlocks.FESTERING_VEIN.get())) && infectious.size() == 8,
                "shipped #remission:infectious: " + infectious);
        List<CorruptionCause> causes = List.of(CorruptionCause.values());
        // Later causes (P5.2's PURIFICATION) are inserted before OTHER too, so only the order is fixed.
        helper.assertTrue(causes.indexOf(CorruptionCause.CONTACT) < causes.indexOf(CorruptionCause.OTHER),
                "CONTACT comes before OTHER");
        helper.succeed();
    }
}
