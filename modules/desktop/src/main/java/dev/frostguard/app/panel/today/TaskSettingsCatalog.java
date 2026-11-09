package dev.frostguard.app.panel.today;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.frostguard.api.configs.ConfigurationKeyEnum.*;

/**
 * The cases where a task's options cannot be read from how its page is laid out: an option on
 * another page, a list a task shares with others, or a task that has no options at all.
 */
final class TaskSettingsCatalog {

    /** Tasks with no setting anywhere in the app. Their on/off keys, if any, have no control either. */
    static final Set<TpDailyTaskEnum> NO_SETTINGS = EnumSet.of(
            TpDailyTaskEnum.GAME_ANALYTICS_LABYRINTH,
            TpDailyTaskEnum.GAME_ANALYTICS_POWER,
            TpDailyTaskEnum.SQUAD_SCAN,
            TpDailyTaskEnum.INITIALIZE,
            TpDailyTaskEnum.TREK_AUTOMATION,
            TpDailyTaskEnum.TREK_SUPPLIES,
            TpDailyTaskEnum.TIMER_SWEEP);

    /** Tasks whose settings are a whole page: the page has several switches but only one job. */
    private static final Map<TpDailyTaskEnum, String> WHOLE_PAGE = Map.of(
            TpDailyTaskEnum.GATHER_RESOURCES, "Gather",
            TpDailyTaskEnum.ALLIANCE_SHOP, "Alliance Shop",
            TpDailyTaskEnum.TRAINING_TROOPS, "Training");

    /**
     * Pages holding unrelated features. A task on one of these lists only its own section; on
     * every other page the panel lists the task's own options and then the rest of the page.
     */
    private static final Set<String> SHARED_PAGES = Set.of(
            "City Events", "Extra City Events", "Alliance", "Deals", "General Shop", "Gem Shop");

    /** The task's own switch where {@link TpDailyTaskEnum#getConfigKey()} has none. */
    private static final Map<TpDailyTaskEnum, ConfigurationKeyEnum> SWITCH_OVERRIDES = Map.of(
            TpDailyTaskEnum.ALLIANCE_SHOP, ALLIANCE_SHOP_ENABLED_BOOL);

    private static final List<ConfigurationKeyEnum> TRAINING_SHARED = List.of(
            TRAIN_BOOL, TRAIN_PRIORITIZE_PROMOTION_BOOL, TRAIN_MINISTRY_APPOINTMENT_BOOL);

    private static final List<ConfigurationKeyEnum> LABYRINTH_RAID_ZONES = List.of(
            LABYRINTH_RESEARCH_INFANTRY_INT, LABYRINTH_RESEARCH_LANCER_INT, LABYRINTH_RESEARCH_MARKSMAN_INT,
            LABYRINTH_GEARFORGE_INFANTRY_INT, LABYRINTH_GEARFORGE_LANCER_INT, LABYRINTH_GEARFORGE_MARKSMAN_INT);

    /** Tasks that share a page with others and own exactly these options on it. */
    private static final Map<TpDailyTaskEnum, List<ConfigurationKeyEnum>> EXACT_OPTIONS = Map.ofEntries(
            Map.entry(TpDailyTaskEnum.RESOURCE_STOCKPILE_SCAN, List.of()),
            Map.entry(TpDailyTaskEnum.GATHER_BOOST, List.of(GATHER_SPEED_BOOST_TYPE_STRING)),
            Map.entry(TpDailyTaskEnum.TRAINING_INFANTRY, TRAINING_SHARED),
            Map.entry(TpDailyTaskEnum.TRAINING_LANCER, TRAINING_SHARED),
            Map.entry(TpDailyTaskEnum.TRAINING_MARKSMAN, TRAINING_SHARED),
            Map.entry(TpDailyTaskEnum.PET_SKILLS, List.of(PET_SKILL_GATHERING_BOOL, PET_SKILL_GATHERING_RESOURCE_STRING)),
            Map.entry(TpDailyTaskEnum.PET_SKILL_STAMINA, List.of()),
            Map.entry(TpDailyTaskEnum.PET_SKILL_TREASURE, List.of()),
            Map.entry(TpDailyTaskEnum.PET_SKILL_FOOD, List.of()),
            Map.entry(TpDailyTaskEnum.PET_SKILL_NATURAL_INTUITION, List.of()),
            Map.entry(TpDailyTaskEnum.PET_SKILL_MYSTICAL_FINDING, List.of()),
            Map.entry(TpDailyTaskEnum.EXPERT_SKILL_TRAINING, List.of(EXPERT_SKILL_TRAINING_PRIORITIES_STRING)),
            Map.entry(TpDailyTaskEnum.LABYRINTH_RAID, LABYRINTH_RAID_ZONES));

    /** Options a task reads that live on a different page, or outside its block on its own page. */
    private static final Map<TpDailyTaskEnum, List<ConfigurationKeyEnum>> ADDED_OPTIONS = Map.of(
            TpDailyTaskEnum.ALLIANCE_CHESTS,
            List.of(ALLIANCE_SPEND_ENABLED_BOOL, ALLIANCE_SPEND_ANONYMIZE_BOOL, ALLIANCE_SPEND_MAX_SCROLLS_INT),
            TpDailyTaskEnum.SKIP_TUTORIAL, List.of(CREATE_CHARACTER_SKIP_TUTORIAL_BOOL),
            TpDailyTaskEnum.VIP_POINTS, List.of(VIP_MONTHLY_BUY_BOOL));

    /** Options the page layout would give a task that actually belong to something else. */
    private static final Map<TpDailyTaskEnum, List<ConfigurationKeyEnum>> EXCLUDED_OPTIONS = Map.of(
            TpDailyTaskEnum.ALLIANCE_AUTOJOIN, List.of(ALLIANCE_HELP_BOOL),
            TpDailyTaskEnum.GATHER_RESOURCES, List.of(GATHER_SPEED_BOOL, GATHER_SPEED_BOOST_TYPE_STRING),
            TpDailyTaskEnum.LABYRINTH, LABYRINTH_RAID_ZONES);

    private TaskSettingsCatalog() {
    }

    static boolean hasNoSettings(TpDailyTaskEnum task) {
        return NO_SETTINGS.contains(task);
    }

    static boolean isSharedPage(String title) {
        return SHARED_PAGES.contains(title);
    }

    static String wholePage(TpDailyTaskEnum task) {
        return WHOLE_PAGE.get(task);
    }

    static ConfigurationKeyEnum switchKey(TpDailyTaskEnum task) {
        return SWITCH_OVERRIDES.getOrDefault(task, task.getConfigKey());
    }

    /** The task's complete option list, or null when it follows from the page layout. */
    static List<ConfigurationKeyEnum> exactOptions(TpDailyTaskEnum task) {
        return EXACT_OPTIONS.get(task);
    }

    static List<ConfigurationKeyEnum> addedOptions(TpDailyTaskEnum task) {
        return ADDED_OPTIONS.getOrDefault(task, List.of());
    }

    static List<ConfigurationKeyEnum> excludedOptions(TpDailyTaskEnum task) {
        return EXCLUDED_OPTIONS.getOrDefault(task, List.of());
    }

    /** Whether at least one control the panel can show exists for this task. */
    static boolean isMapped(TpDailyTaskEnum task, Collection<ConfigurationKeyEnum> keysWithControls) {
        if (WHOLE_PAGE.containsKey(task) || keysWithControls.contains(switchKey(task))) {
            return true;
        }
        List<ConfigurationKeyEnum> exact = exactOptions(task);
        return (exact != null && exact.stream().anyMatch(keysWithControls::contains))
                || addedOptions(task).stream().anyMatch(keysWithControls::contains);
    }
}
