package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;

/**
 * Every pet skill the user can switch on must be reachable from some task.
 *
 * <p>This is not a hypothetical invariant. FOOD was dropped from the legacy PET_SKILLS task on the
 * grounds that it ran as its own task, but that task was never added, so the skill fell through both
 * paths and could not fire at all. Nothing failed: the checkbox saved PET_SKILL_FOOD_BOOL, the
 * routine loaded it into {@code foodEnabled}, and {@code isSkillEnabled} would have returned true --
 * but no task ever asked for the skill, so the list was always built without it. The only visible
 * symptom was a log line reading "Pet Skills for 1 skill(s)" that nobody had reason to question.
 *
 * <p>Asserting the wiring rather than the behaviour is deliberate: the gap was in which tasks exist,
 * not in what any routine does once it runs.
 */
class PetSkillTaskReachabilityTest {

    /**
     * The per-skill toggle each skill is driven by.
     *
     * <p>GATHERING is deliberately absent: it has no dedicated task and runs under the legacy
     * PET_SKILLS task, gated on PET_SKILLS_BOOL. Every other skill needs a task of its own.
     */
    private static Map<PetSkillsRoutine.PetSkill, ConfigurationKeyEnum> perSkillTasks() {
        Map<PetSkillsRoutine.PetSkill, ConfigurationKeyEnum> expected =
                new EnumMap<>(PetSkillsRoutine.PetSkill.class);
        expected.put(PetSkillsRoutine.PetSkill.STAMINA, ConfigurationKeyEnum.PET_SKILL_STAMINA_BOOL);
        expected.put(PetSkillsRoutine.PetSkill.FOOD, ConfigurationKeyEnum.PET_SKILL_FOOD_BOOL);
        expected.put(PetSkillsRoutine.PetSkill.TREASURE, ConfigurationKeyEnum.PET_SKILL_TREASURE_BOOL);
        return expected;
    }

    @Test
    void everySkillWithItsOwnToggleHasATaskThatRunsIt() {
        perSkillTasks().forEach((skill, toggle) -> {
            boolean hasTask = Arrays.stream(TpDailyTaskEnum.values())
                    .anyMatch(task -> task.activationSwitch() == toggle);
            assertTrue(hasTask, skill + " has a toggle (" + toggle
                    + ") but no task drives it, so switching it on does nothing");
        });
    }

    @Test
    void theGatheringSkillIsTheOnlyOneWithoutADedicatedTask() {
        // Guards the reverse mistake: giving GATHERING its own task without removing it from the
        // legacy PET_SKILLS path would double-drive it.
        boolean gatheringHasOwnTask = Arrays.stream(TpDailyTaskEnum.values())
                .anyMatch(task -> task.activationSwitch()
                        == ConfigurationKeyEnum.PET_SKILL_GATHERING_BOOL);
        assertTrue(!gatheringHasOwnTask,
                "GATHERING gained a dedicated task; drop it from buildEnabledSkillsList or it runs twice");
    }

    @Test
    void everyPetSkillIsAccountedForByThisTest() {
        // Keeps the test honest as skills are added: a new PetSkill must be classified as either
        // per-skill-task or the gathering exception, rather than silently escaping coverage.
        Arrays.stream(PetSkillsRoutine.PetSkill.values()).forEach(skill -> {
            boolean classified = perSkillTasks().containsKey(skill)
                    || skill == PetSkillsRoutine.PetSkill.GATHERING;
            assertTrue(classified, skill + " is not covered; add it to perSkillTasks or document why not");
        });
    }
}
