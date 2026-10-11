package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.loot.IsCorruptedCondition;
import com.cubiecode.remission.registry.RemissionItems;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The {@code remission:is_corrupted} loot condition and the {@code remission:host_samples} loot modifier. */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HostLootTests {
    private static final BlockPos MOB_POS = new BlockPos(1, 1, 1);

    private HostLootTests() {
    }

    @GameTest(template = "empty")
    public static void isCorruptedFollowsStage(GameTestHelper helper) {
        Mob pig = helper.spawnWithNoFreeWill(EntityType.PIG, MOB_POS);
        LootContext context = new LootContext.Builder(new LootParams.Builder(helper.getLevel())
                .withParameter(LootContextParams.THIS_ENTITY, pig)
                .withParameter(LootContextParams.ORIGIN, pig.position())
                .create(LootContextParamSets.SELECTOR)).create(Optional.empty());

        for (int level : new int[]{0, 1, 50, 100}) {
            Corruption.set(pig, level);
            int stage = Corruption.get(pig).stage();
            for (int minStage = 1; minStage <= 3; minStage++) {
                boolean result = new IsCorruptedCondition(LootContext.EntityTarget.THIS, minStage).test(context);
                helper.assertValueEqual(result, stage >= minStage, "level " + level + ", min_stage " + minStage);
            }
        }
        Corruption.set(pig, 100);
        helper.assertFalse(new IsCorruptedCondition(LootContext.EntityTarget.ATTACKER, 1).test(context), "absent attacker");

        LootItemCondition parsed = LootItemCondition.DIRECT_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"condition\": \"remission:is_corrupted\", \"entity\": \"this\", \"min_stage\": 2}")).getOrThrow();
        helper.assertValueEqual(parsed, new IsCorruptedCondition(LootContext.EntityTarget.THIS, 2), "decoded condition");
        LootItemCondition defaults = LootItemCondition.DIRECT_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"condition\": \"remission:is_corrupted\"}")).getOrThrow();
        helper.assertValueEqual(defaults, new IsCorruptedCondition(LootContext.EntityTarget.THIS, 1), "default fields");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void hostDropsHostTissue(GameTestHelper helper) {
        Mob pig = helper.spawnWithNoFreeWill(EntityType.PIG, MOB_POS);
        Corruption.set(pig, 50);
        pig.kill();
        helper.assertItemEntityCountIs(RemissionItems.HOST_TISSUE.get(), MOB_POS, 2, 1);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cleanMobDropsNoHostTissue(GameTestHelper helper) {
        Mob pig = helper.spawnWithNoFreeWill(EntityType.PIG, MOB_POS);
        pig.kill();
        helper.assertItemEntityNotPresent(RemissionItems.HOST_TISSUE.get(), MOB_POS, 2);
        helper.succeed();
    }
}
