package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.cubiecode.remission.registry.RemissionAttachments;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Server-side state of the {@code corruption} attachment: API, staging, persistence and wire format. */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CorruptionStateTests {
    private static final List<Integer> DEFAULT_THRESHOLDS = List.of(1, 34, 67);

    private CorruptionStateTests() {
    }

    @GameTest(template = "empty")
    public static void setGetAndCleanse(GameTestHelper helper) {
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1);
        helper.assertValueEqual(Corruption.get(pig), CorruptionState.NONE, "fresh state");
        helper.assertFalse(Corruption.isHost(pig), "fresh pig is a host");
        helper.assertFalse(pig.hasData(RemissionAttachments.CORRUPTION), "get created an attachment");

        CorruptionState set = Corruption.set(pig, 50);
        helper.assertValueEqual(Corruption.get(pig), set, "state after set");
        helper.assertValueEqual(set.level(), 50, "level");
        helper.assertValueEqual(set.stage(), CorruptionState.stageFor(50, RemissionCommonConfig.STAGE_THRESHOLDS.get()), "stage");
        helper.assertTrue(Corruption.isHost(pig), "infected pig is not a host");

        helper.assertValueEqual(Corruption.set(pig, 500).level(), CorruptionState.MAX_LEVEL, "clamped high level");
        Corruption.set(pig, -3);
        helper.assertFalse(pig.hasData(RemissionAttachments.CORRUPTION), "level 0 kept the attachment");
        helper.assertValueEqual(Corruption.get(pig), CorruptionState.NONE, "state after cleanse");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void stageThresholds(GameTestHelper helper) {
        int[][] cases = {{0, 0}, {1, 1}, {33, 1}, {34, 2}, {66, 2}, {67, 3}, {100, 3}};
        for (int[] c : cases) {
            helper.assertValueEqual(CorruptionState.stageFor(c[0], DEFAULT_THRESHOLDS), c[1], "stage at level " + c[0]);
        }
        helper.assertValueEqual(CorruptionState.stageFor(50, List.of(67, 1, 34)), 2, "unordered thresholds");
        helper.assertValueEqual(CorruptionState.of(9, List.of(10)), new CorruptionState(9, 0), "latent level below first threshold");
        helper.assertFalse(CorruptionState.of(9, List.of(10)).isHost(), "latent level counts as a host");
        helper.assertValueEqual(CorruptionState.of(0, DEFAULT_THRESHOLDS), CorruptionState.NONE, "level 0");
        helper.assertValueEqual(CorruptionState.of(250, DEFAULT_THRESHOLDS), new CorruptionState(CorruptionState.MAX_LEVEL, 3), "clamp");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void persistsThroughSaveAndLoad(GameTestHelper helper) {
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1);
        CorruptionState state = Corruption.set(pig, 70);
        CompoundTag saved = pig.saveWithoutId(new CompoundTag());

        LivingEntity reloaded = EntityType.PIG.create(helper.getLevel());
        helper.assertTrue(reloaded != null, "pig not created");
        reloaded.load(saved);
        helper.assertValueEqual(Corruption.get(reloaded), state, "state after reload");

        Corruption.set(pig, 0);
        CompoundTag clean = pig.saveWithoutId(new CompoundTag());
        CompoundTag attachments = clean.getCompound("neoforge:attachments");
        helper.assertFalse(attachments.contains(Remission.id("corruption").toString()), "level 0 was saved");

        CompoundTag encoded = (CompoundTag) CorruptionState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        helper.assertValueEqual(encoded.getInt("level"), 70, "saved level key");
        helper.assertValueEqual(encoded.getInt("stage"), state.stage(), "saved stage key");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void survivesDimensionChange(GameTestHelper helper) {
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1);
        CorruptionState state = Corruption.set(pig, 40);
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no nether level");

        Entity moved = pig.changeDimension(new DimensionTransition(nether, new Vec3(0.5, 64, 0.5), Vec3.ZERO, 0, 0,
                DimensionTransition.DO_NOTHING));
        helper.assertTrue(moved instanceof LivingEntity && moved != pig, "pig did not change dimension");
        helper.assertValueEqual(Corruption.get((LivingEntity) moved), state, "state after dimension change");
        moved.discard();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void syncWireFormatRoundTrips(GameTestHelper helper) {
        CorruptionState state = new CorruptionState(88, 3);
        ByteBuf buf = Unpooled.buffer();
        try {
            CorruptionState.STREAM_CODEC.encode(buf, state);
            helper.assertValueEqual(CorruptionState.STREAM_CODEC.decode(buf), state, "decoded state");
            helper.assertFalse(buf.isReadable(), "trailing bytes");
        } finally {
            buf.release();
        }
        helper.succeed();
    }
}
