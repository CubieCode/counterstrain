package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.behaviour.MobBehaviour;
import com.cubiecode.remission.behaviour.MobProfile;
import com.cubiecode.remission.behaviour.MobProfiles;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.HolderSet;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The {@code remission:mob_profile} datapack registry: schema, lookup and priority. */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MobProfileTests {
    private MobProfileTests() {
    }

    @GameTest(template = "empty")
    public static void registryLoadsEmpty(GameTestHelper helper) {
        helper.assertTrue(helper.getLevel().registryAccess().registry(MobProfiles.KEY).isPresent(), "no mob_profile registry");
        helper.assertTrue(MobProfiles.find(helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1)).isEmpty(),
                "Remission ships a default profile");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void documentedExampleDecodes(GameTestHelper helper) {
        // The example in the remission-data-formats skill.
        String json = """
                {
                  "entities": "#minecraft:undead",
                  "priority": 0,
                  "behaviour": "melee",
                  "attributes": { "minecraft:generic.attack_damage": 4.0, "minecraft:generic.movement_speed": 0.3 },
                  "convert_to": "minecraft:zombie",
                  "conversion": { "on_kill": true, "festering_land": true }
                }""";
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        MobProfile profile = MobProfile.CODEC.parse(ops, JsonParser.parseString(json)).getOrThrow();
        helper.assertTrue(profile.appliesTo(EntityType.SKELETON), "undead tag does not match a skeleton");
        helper.assertFalse(profile.appliesTo(EntityType.PIG), "undead tag matches a pig");
        helper.assertValueEqual(profile.behaviour(), Optional.of(MobBehaviour.MELEE), "behaviour");
        helper.assertValueEqual(profile.attributes().get(Attributes.ATTACK_DAMAGE), 4.0, "attack damage");
        helper.assertValueEqual(profile.convertTo().map(h -> h.value()), Optional.<EntityType<?>>of(EntityType.ZOMBIE),
                "convert_to");

        MobProfile minimal = MobProfile.CODEC.parse(ops, JsonParser.parseString("{\"entities\": \"minecraft:pig\"}"))
                .getOrThrow();
        helper.assertValueEqual(minimal.priority(), 0, "default priority");
        helper.assertTrue(minimal.behaviour().isEmpty() && minimal.attributes().isEmpty() && minimal.convertTo().isEmpty(),
                "optional fields not empty");
        helper.assertValueEqual(minimal.conversion(), MobProfile.Conversion.DEFAULT, "default conversion");
        helper.assertTrue(MobProfile.CODEC.parse(ops, JsonParser.parseString("{\"entities\": \"minecraft:pig\","
                + " \"behaviour\": \"teleporting\"}")).isError(), "unknown behaviour accepted");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void highestPriorityWins(GameTestHelper helper) {
        MobProfile pigLow = profile(EntityType.PIG, 0);
        MobProfile pigHigh = profile(EntityType.PIG, 5);
        MobProfile pigTie = profile(EntityType.PIG, 5);
        MobProfile cow = profile(EntityType.COW, 10);

        helper.assertValueEqual(MobProfiles.select(List.of(pigLow, pigHigh, cow), EntityType.PIG), Optional.of(pigHigh),
                "pig profile");
        helper.assertTrue(MobProfiles.select(List.of(pigHigh, pigTie), EntityType.PIG).get() == pigHigh, "tie break");
        helper.assertTrue(MobProfiles.select(List.of(pigTie, pigHigh), EntityType.PIG).get() == pigTie, "tie break order");
        helper.assertValueEqual(MobProfiles.select(List.of(pigLow, cow), EntityType.SHEEP), Optional.empty(), "unmatched");
        helper.succeed();
    }

    private static MobProfile profile(EntityType<?> type, int priority) {
        return new MobProfile(HolderSet.direct(type.builtInRegistryHolder()), priority, Optional.empty(), Map.of(),
                Optional.empty(), MobProfile.Conversion.DEFAULT);
    }
}
