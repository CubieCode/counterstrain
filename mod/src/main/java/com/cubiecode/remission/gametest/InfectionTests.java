package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.behaviour.MobProfile;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.cubiecode.remission.infection.HostAttributes;
import com.cubiecode.remission.infection.Infection;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Infection on kill by a host, {@code convert_to}, {@code #remission:immune} and profile attribute modifiers. */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class InfectionTests {
    private InfectionTests() {
    }

    @GameTest(template = "empty")
    public static void killByHostInfects(GameTestHelper helper) {
        Mob killer = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 1, 1);
        Corruption.set(killer, 50);
        Mob victim = helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1);

        victim.hurt(helper.getLevel().damageSources().mobAttack(killer), 1000);
        helper.assertTrue(victim.isAlive(), "victim killed by a host died");
        helper.assertTrue(Corruption.isHost(victim), "victim killed by a host is not a host");
        helper.assertValueEqual(Corruption.get(victim).level(), Infection.initialLevel(), "new host level");
        helper.assertValueEqual(victim.getHealth(), victim.getMaxHealth(), "new host health");

        // A host killed by a host dies.
        victim.invulnerableTime = 0;
        victim.hurt(helper.getLevel().damageSources().mobAttack(killer), 1000);
        helper.assertFalse(victim.isAlive(), "host killed by a host survived");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void killByCleanMobDoesNotInfect(GameTestHelper helper) {
        Mob killer = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 1, 1);
        Mob victim = helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1);

        victim.hurt(helper.getLevel().damageSources().mobAttack(killer), 1000);
        helper.assertFalse(victim.isAlive(), "victim of a clean killer survived");
        helper.assertFalse(Corruption.isHost(victim), "victim of a clean killer is a host");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void convertToReplacesVictim(GameTestHelper helper) {
        Mob victim = helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1);
        victim.setCustomName(Component.literal("Patient"));
        MobProfile profile = profile(EntityType.PIG, Optional.of(EntityType.ZOMBIE.builtInRegistryHolder()), true);

        Mob host = Infection.infectOnKill(victim, Optional.of(profile));
        helper.assertTrue(host != null, "no conversion");
        helper.assertValueEqual(host.getType(), EntityType.ZOMBIE, "converted type");
        helper.assertTrue(victim.isRemoved(), "victim was not removed");
        helper.assertTrue(host.isAlive() && host.level() == victim.level(), "host is not in the level");
        helper.assertTrue(Corruption.isHost(host), "converted mob is not a host");
        helper.assertValueEqual(host.position(), victim.position(), "converted position");
        helper.assertValueEqual(host.getCustomName(), victim.getCustomName(), "converted name");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void profileCanDisableKillConversion(GameTestHelper helper) {
        Mob victim = helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1);
        MobProfile profile = profile(EntityType.PIG, Optional.empty(), false);

        helper.assertTrue(Infection.infectOnKill(victim, Optional.of(profile)) == null, "on_kill false still infected");
        helper.assertFalse(Corruption.isHost(victim), "on_kill false made a host");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void immuneNeverInfected(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.assertTrue(Corruption.isImmune(player), "player is not immune");
        helper.assertValueEqual(Corruption.set(player, 50), CorruptionState.NONE, "player infected");
        helper.assertFalse(Corruption.isHost(player), "player is a host");

        WitherBoss wither = EntityType.WITHER.create(helper.getLevel());
        helper.assertTrue(wither != null && Corruption.isImmune(wither), "wither (#c:bosses) is not immune");
        Corruption.set(wither, 80);
        helper.assertFalse(Corruption.isHost(wither), "wither became a host");
        helper.assertTrue(Infection.infectOnKill(wither, Optional.empty()) == null, "wither infected on kill");
        helper.assertFalse(Infection.canInfectFromFesteringLand(wither), "wither eligible for festering land");

        // convert_to naming an immune type leaves the victim to die.
        Mob pig = helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1);
        MobProfile toWither = profile(EntityType.PIG, Optional.of(EntityType.WITHER.builtInRegistryHolder()), true);
        helper.assertTrue(Infection.infectOnKill(pig, Optional.of(toWither)) == null, "converted into an immune type");
        helper.assertFalse(pig.isRemoved() || Corruption.isHost(pig), "pig changed by an immune conversion");
        helper.assertTrue(Infection.canInfectFromFesteringLand(pig), "pig not eligible for festering land");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void infectCommandSkipsImmune(GameTestHelper helper) {
        String tag = "remission_test_immune";
        WitherBoss wither = helper.spawnWithNoFreeWill(EntityType.WITHER, 1, 1, 1);
        wither.addTag(tag);
        MinecraftServer server = helper.getLevel().getServer();
        try {
            int result = server.getCommands().getDispatcher().execute("remission infect @e[tag=" + tag + "] 50",
                    server.createCommandSourceStack().withSuppressedOutput());
            helper.fail("infecting only immune targets succeeded with " + result);
        } catch (CommandSyntaxException e) {
            helper.assertTrue(e.getRawMessage() instanceof Component message
                            && message.getContents() instanceof TranslatableContents contents
                            && contents.getKey().equals("commands.remission.failed.immune"),
                    "unexpected error " + e.getRawMessage().getString());
        }
        helper.assertFalse(Corruption.isHost(wither), "wither became a host");

        Mob pig = helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1);
        pig.addTag(tag);
        try {
            int result = server.getCommands().getDispatcher().execute("remission infect @e[tag=" + tag + "] 50",
                    server.createCommandSourceStack().withSuppressedOutput());
            helper.assertValueEqual(result, 1, "infected targets");
        } catch (CommandSyntaxException e) {
            helper.fail("infect failed: " + e.getMessage());
        }
        helper.assertTrue(Corruption.isHost(pig) && !Corruption.isHost(wither), "mixed selection");
        wither.discard();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void profileAttributesFollowHostState(GameTestHelper helper) {
        Mob zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 1, 1);
        AttributeInstance damage = zombie.getAttribute(Attributes.ATTACK_DAMAGE);
        helper.assertTrue(damage != null, "zombie has no attack damage");
        double base = damage.getValue();
        MobProfile profile = new MobProfile(HolderSet.direct(EntityType.ZOMBIE.builtInRegistryHolder()), 0, Optional.empty(),
                Map.of(Attributes.ATTACK_DAMAGE, 4.0), Optional.empty(), MobProfile.Conversion.DEFAULT);

        HostAttributes.update(zombie, Optional.of(profile));
        helper.assertFalse(damage.hasModifier(HostAttributes.MODIFIER_ID), "clean mob got profile modifiers");

        Corruption.set(zombie, 50);
        HostAttributes.update(zombie, Optional.of(profile));
        helper.assertValueEqual(damage.getValue(), base + 4.0, "host attack damage");

        Corruption.set(zombie, 0);
        helper.assertFalse(damage.hasModifier(HostAttributes.MODIFIER_ID), "cleanse kept profile modifiers");
        helper.assertValueEqual(damage.getValue(), base, "cleansed attack damage");
        helper.succeed();
    }

    private static MobProfile profile(EntityType<?> type, Optional<Holder<EntityType<?>>> convertTo, boolean onKill) {
        return new MobProfile(HolderSet.direct(type.builtInRegistryHolder()), 0, Optional.empty(), Map.of(), convertTo,
                new MobProfile.Conversion(onKill, true));
    }
}
