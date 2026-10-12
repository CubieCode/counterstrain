package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.commands.RemissionCommands;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * {@code /remission infect|cleanse|inspect}, run through the server's command dispatcher with entity selectors. Each test
 * tags its own entities so selectors never reach entities from tests running alongside it.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RemissionCommandTests {
    private RemissionCommandTests() {
    }

    @GameTest(template = "empty")
    public static void infectSelectsLivingTargets(GameTestHelper helper) {
        String tag = "remission_test_infect";
        LivingEntity first = tagged(helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1), tag);
        LivingEntity second = tagged(helper.spawnWithNoFreeWill(EntityType.COW, 1, 1, 1), tag);
        tagged(helper.spawn(EntityType.MINECART, 1, 1, 1), tag);
        LivingEntity untagged = helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1);
        Feedback feedback = new Feedback();

        int result = run(helper, feedback, "remission infect @e[tag=" + tag + "] 50");
        helper.assertValueEqual(result, 2, "infect result");
        helper.assertValueEqual(Corruption.get(first).level(), 50, "first target level");
        helper.assertValueEqual(Corruption.get(second).level(), 50, "second target level");
        helper.assertTrue(Corruption.isHost(first), "infected target is not a host");
        helper.assertValueEqual(Corruption.get(untagged), CorruptionState.NONE, "unselected pig");
        feedback.assertOnly(helper, "commands.remission.infect.success.multiple", 2, 50);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void infectSingleReportsStage(GameTestHelper helper) {
        String tag = "remission_test_infect_single";
        LivingEntity pig = tagged(helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1), tag);
        Feedback feedback = new Feedback();

        helper.assertValueEqual(run(helper, feedback, "remission infect @e[tag=" + tag + "] 80"), 1, "infect result");
        CorruptionState state = Corruption.get(pig);
        helper.assertValueEqual(state.level(), 80, "level");
        Object[] args = feedback.assertOnly(helper, "commands.remission.infect.success.single");
        helper.assertValueEqual(Arrays.asList(args).subList(1, 3), List.<Object>of(80, state.stage()), "level and stage args");

        run(helper, feedback, "remission infect @e[tag=" + tag + "] 20");
        helper.assertValueEqual(Corruption.get(pig).level(), 20, "infect lowers the level");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cleanseRemovesTheStrain(GameTestHelper helper) {
        String tag = "remission_test_cleanse";
        LivingEntity host = tagged(helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1), tag);
        LivingEntity clean = tagged(helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1), tag);
        LivingEntity untagged = helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1);
        Corruption.set(host, 90);
        Corruption.set(untagged, 30);
        Feedback feedback = new Feedback();

        helper.assertValueEqual(run(helper, feedback, "remission cleanse @e[tag=" + tag + "]"), 2, "cleanse result");
        helper.assertValueEqual(Corruption.get(host), CorruptionState.NONE, "cleansed host");
        helper.assertValueEqual(Corruption.get(clean), CorruptionState.NONE, "already clean target");
        helper.assertValueEqual(Corruption.get(untagged).level(), 30, "unselected host");
        feedback.assertOnly(helper, "commands.remission.cleanse.success.multiple", 2);

        run(helper, feedback, "remission cleanse @e[tag=" + tag + ",limit=1]");
        feedback.assertOnly(helper, "commands.remission.cleanse.success.single");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void inspectReportsState(GameTestHelper helper) {
        String hostTag = "remission_test_inspect_host";
        String cleanTag = "remission_test_inspect_clean";
        LivingEntity host = tagged(helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1), hostTag);
        tagged(helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1), cleanTag);
        CorruptionState state = Corruption.set(host, 40);
        Feedback feedback = new Feedback();

        helper.assertValueEqual(run(helper, feedback, "remission inspect @e[tag=" + hostTag + ",limit=1]"), 40, "inspect result");
        Object[] args = feedback.assertOnly(helper, "commands.remission.inspect.success");
        helper.assertValueEqual(Arrays.asList(args).subList(1, 3), List.<Object>of(40, state.stage()), "level and stage args");

        helper.assertValueEqual(run(helper, feedback, "remission inspect @e[tag=" + cleanTag + ",limit=1]"), 0, "clean result");
        feedback.assertOnly(helper, "commands.remission.inspect.clean");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rejectsInvalidUse(GameTestHelper helper) {
        String tag = "remission_test_invalid";
        Entity minecart = tagged(helper.spawn(EntityType.MINECART, 1, 1, 1), tag);
        minecart.addTag(tag + "_any");
        LivingEntity pig = tagged(tagged(helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1), tag + "_pig"), tag + "_any");
        Feedback feedback = new Feedback();

        assertFails(helper, feedback, "remission infect @e[tag=" + tag + "] 50", "commands.remission.failed.no_living_targets");
        assertFails(helper, feedback, "remission cleanse @e[tag=" + tag + "]", "commands.remission.failed.no_living_targets");
        assertFails(helper, feedback, "remission inspect @e[tag=" + tag + ",limit=1]", "commands.remission.failed.not_living");
        assertFails(helper, feedback, "remission inspect @e[tag=" + tag + "_any]", null);
        assertFails(helper, feedback, "remission infect @e[tag=" + tag + "_pig] 0", null);
        assertFails(helper, feedback, "remission infect @e[tag=" + tag + "_pig] " + (CorruptionState.MAX_LEVEL + 1), null);
        helper.assertValueEqual(Corruption.get(pig), CorruptionState.NONE, "rejected commands changed the pig");
        helper.assertTrue(minecart.isAlive(), "minecart removed");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void requiresPermissionLevel(GameTestHelper helper) {
        String tag = "remission_test_permission";
        LivingEntity pig = tagged(helper.spawnWithNoFreeWill(EntityType.PIG, 1, 1, 1), tag);
        Feedback feedback = new Feedback();
        String command = "remission infect @e[tag=" + tag + "] 50";

        try {
            execute(helper, feedback, RemissionCommands.PERMISSION_LEVEL - 1, command);
            helper.fail("command ran below permission level " + RemissionCommands.PERMISSION_LEVEL);
        } catch (CommandSyntaxException expected) {
            helper.assertValueEqual(Corruption.get(pig), CorruptionState.NONE, "unpermitted command changed the pig");
        }
        helper.assertValueEqual(run(helper, feedback, command), 1, "permitted result");
        helper.succeed();
    }

    private static <T extends Entity> T tagged(T entity, String tag) {
        entity.addTag(tag);
        return entity;
    }

    private static int run(GameTestHelper helper, Feedback feedback, String command) {
        try {
            return execute(helper, feedback, RemissionCommands.PERMISSION_LEVEL, command);
        } catch (CommandSyntaxException e) {
            throw new AssertionError("/" + command + " failed: " + e.getMessage(), e);
        }
    }

    /** Expects the command to fail, with a translated error {@code key} unless it is null (a parse error). */
    private static void assertFails(GameTestHelper helper, Feedback feedback, String command, String key) {
        try {
            execute(helper, feedback, RemissionCommands.PERMISSION_LEVEL, command);
            helper.fail("/" + command + " succeeded");
        } catch (CommandSyntaxException e) {
            if (key != null) {
                helper.assertValueEqual(translationKey(e.getRawMessage() instanceof Component c ? c : null), key,
                        "/" + command + " error");
            }
        }
    }

    private static int execute(GameTestHelper helper, Feedback feedback, int permission, String command)
            throws CommandSyntaxException {
        feedback.messages.clear();
        CommandSourceStack source = new CommandSourceStack(feedback, Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO)),
                Vec2.ZERO, helper.getLevel(), permission, "gametest", Component.literal("gametest"),
                helper.getLevel().getServer(), null);
        return helper.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
    }

    private static String translationKey(Component component) {
        return component != null && component.getContents() instanceof TranslatableContents contents ? contents.getKey() : null;
    }

    /** Collects the command feedback sent to the source. */
    private static final class Feedback implements CommandSource {
        private final List<Component> messages = new ArrayList<>();

        /** Asserts one message with {@code key} whose leading arguments equal {@code args}; returns all its arguments. */
        Object[] assertOnly(GameTestHelper helper, String key, Object... args) {
            helper.assertValueEqual(messages.size(), 1, "feedback messages");
            Component message = messages.getFirst();
            helper.assertValueEqual(translationKey(message), key, "feedback key");
            Object[] actual = ((TranslatableContents) message.getContents()).getArgs();
            helper.assertValueEqual(Arrays.asList(actual).subList(0, args.length), Arrays.asList(args), "feedback args");
            return actual;
        }

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
