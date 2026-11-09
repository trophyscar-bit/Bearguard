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
}
