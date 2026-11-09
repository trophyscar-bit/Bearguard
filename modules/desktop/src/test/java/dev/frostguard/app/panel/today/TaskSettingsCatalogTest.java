package dev.frostguard.app.panel.today;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSettingsCatalogTest {

    private static final Path PANELS = Path.of("src/main/java/dev/frostguard/app/panel");
    private static final Pattern KEY = Pattern.compile("ConfigurationKeyEnum\\.([A-Z][A-Z0-9_]+)");

    private static final Set<ConfigurationKeyEnum> KEYS_ON_PAGES = EnumSet.noneOf(ConfigurationKeyEnum.class);

    /** The settings pages themselves are the record of which keys have a control. */
    @BeforeAll
    static void readPages() throws IOException {
        try (Stream<Path> files = Files.walk(PANELS)) {
            for (Path file : files.filter(TaskSettingsCatalogTest::isSettingsPageSource).toList()) {
                Matcher matcher = KEY.matcher(Files.readString(file));
                while (matcher.find()) {
                    KEYS_ON_PAGES.add(ConfigurationKeyEnum.valueOf(matcher.group(1)));
                }
            }
        }
    }

    private static boolean isSettingsPageSource(Path file) {
        return file.toString().endsWith(".java") && !file.startsWith(PANELS.resolve("today"));
    }

    @Test
    void everyTaskIsMappedOrExplicitlyHasNoSettings() {
        List<TpDailyTaskEnum> unaccounted = new ArrayList<>();
        for (TpDailyTaskEnum task : TpDailyTaskEnum.values()) {
            // A custom task's options come from its own card, not from this mapping.
            if (task != TpDailyTaskEnum.CUSTOM_TASK && !TaskSettingsCatalog.hasNoSettings(task)
                    && !TaskSettingsCatalog.isMapped(task, KEYS_ON_PAGES)) {
                unaccounted.add(task);
            }
        }
        assertEquals(List.of(), unaccounted);
    }

    @Test
    void aNoSettingsTaskReallyHasNoSwitchOnAnyPage() {
        for (TpDailyTaskEnum task : TaskSettingsCatalog.NO_SETTINGS) {
            assertFalse(KEYS_ON_PAGES.contains(TaskSettingsCatalog.switchKey(task)), task.name());
        }
    }

    @Test
    void aTaskIsNotMappedByAnOptionNoPageHas() {
        assertFalse(TaskSettingsCatalog.isMapped(TpDailyTaskEnum.LABYRINTH_RAID, Set.of()));
        assertTrue(TaskSettingsCatalog.isMapped(TpDailyTaskEnum.LABYRINTH_RAID,
                Set.of(ConfigurationKeyEnum.LABYRINTH_RESEARCH_INFANTRY_INT)));
    }

    /** Spelled out here because a wrong key still compiles and still shows a working-looking option. */
    @Test
    void eachTaskPointsAtTheKeysItReallyUses() {
        assertEquals(ConfigurationKeyEnum.ALLIANCE_SHOP_ENABLED_BOOL, TaskSettingsCatalog.switchKey(TpDailyTaskEnum.ALLIANCE_SHOP));
        assertEquals(ConfigurationKeyEnum.LABYRINTH_RAID_ENABLED_BOOL, TaskSettingsCatalog.switchKey(TpDailyTaskEnum.LABYRINTH_RAID));

        assertEquals(List.of(ConfigurationKeyEnum.GATHER_SPEED_BOOST_TYPE_STRING),
                TaskSettingsCatalog.exactOptions(TpDailyTaskEnum.GATHER_BOOST));
        List<ConfigurationKeyEnum> training = List.of(ConfigurationKeyEnum.TRAIN_BOOL,
                ConfigurationKeyEnum.TRAIN_PRIORITIZE_PROMOTION_BOOL, ConfigurationKeyEnum.TRAIN_MINISTRY_APPOINTMENT_BOOL);
        assertEquals(training, TaskSettingsCatalog.exactOptions(TpDailyTaskEnum.TRAINING_INFANTRY));
        assertEquals(training, TaskSettingsCatalog.exactOptions(TpDailyTaskEnum.TRAINING_LANCER));
        assertEquals(training, TaskSettingsCatalog.exactOptions(TpDailyTaskEnum.TRAINING_MARKSMAN));
        assertEquals(List.of(ConfigurationKeyEnum.PET_SKILL_GATHERING_BOOL, ConfigurationKeyEnum.PET_SKILL_GATHERING_RESOURCE_STRING),
                TaskSettingsCatalog.exactOptions(TpDailyTaskEnum.PET_SKILLS));
        assertEquals(List.of(ConfigurationKeyEnum.EXPERT_SKILL_TRAINING_PRIORITIES_STRING),
                TaskSettingsCatalog.exactOptions(TpDailyTaskEnum.EXPERT_SKILL_TRAINING));

        List<ConfigurationKeyEnum> raidZones = List.of(ConfigurationKeyEnum.LABYRINTH_RESEARCH_INFANTRY_INT,
                ConfigurationKeyEnum.LABYRINTH_RESEARCH_LANCER_INT, ConfigurationKeyEnum.LABYRINTH_RESEARCH_MARKSMAN_INT,
                ConfigurationKeyEnum.LABYRINTH_GEARFORGE_INFANTRY_INT, ConfigurationKeyEnum.LABYRINTH_GEARFORGE_LANCER_INT,
                ConfigurationKeyEnum.LABYRINTH_GEARFORGE_MARKSMAN_INT);
        assertEquals(raidZones, TaskSettingsCatalog.exactOptions(TpDailyTaskEnum.LABYRINTH_RAID));
        assertEquals(raidZones, TaskSettingsCatalog.excludedOptions(TpDailyTaskEnum.LABYRINTH));

        assertEquals(List.of(ConfigurationKeyEnum.VIP_MONTHLY_BUY_BOOL), TaskSettingsCatalog.addedOptions(TpDailyTaskEnum.VIP_POINTS));
        assertEquals(List.of(ConfigurationKeyEnum.CREATE_CHARACTER_SKIP_TUTORIAL_BOOL), TaskSettingsCatalog.addedOptions(TpDailyTaskEnum.SKIP_TUTORIAL));
        assertEquals(List.of(ConfigurationKeyEnum.ALLIANCE_SPEND_ENABLED_BOOL, ConfigurationKeyEnum.ALLIANCE_SPEND_ANONYMIZE_BOOL,
                ConfigurationKeyEnum.ALLIANCE_SPEND_MAX_SCROLLS_INT), TaskSettingsCatalog.addedOptions(TpDailyTaskEnum.ALLIANCE_CHESTS));

        assertEquals(List.of(ConfigurationKeyEnum.ALLIANCE_HELP_BOOL), TaskSettingsCatalog.excludedOptions(TpDailyTaskEnum.ALLIANCE_AUTOJOIN));
        assertEquals(List.of(ConfigurationKeyEnum.GATHER_SPEED_BOOL, ConfigurationKeyEnum.GATHER_SPEED_BOOST_TYPE_STRING),
                TaskSettingsCatalog.excludedOptions(TpDailyTaskEnum.GATHER_RESOURCES));
    }
}
