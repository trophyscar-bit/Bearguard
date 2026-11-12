package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.OptionalLong;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.helper.HudGemReader;
import dev.frostguard.tasks.economy.GatherSpeedRoutine.GemReading;
import dev.frostguard.tasks.economy.GatherSpeedRoutine.UseOutcome;

/** The buy decision around the two gem reads, for the 8h boost (250 gems). */
class GatherSpeedRoutineTest {

    @BeforeAll
    static void createTestWorkspace() throws IOException {
        Files.createDirectories(WorkspacePaths.current().root());
    }

    @Test
    void anUnknownGemCountBeforeBuyingTapsNothingAndRetriesInFifteenMinutes() {
        ScriptedRoutine routine = new ScriptedRoutine(UseOutcome.BOUGHT_PURCHASE_CONFIRMED, unknown());
        LocalDateTime before = LocalDateTime.now();

        routine.execute();

        assertEquals(List.of("read", "save:gems-unknown-before", "close"), routine.events);
        assertScheduledWithForwardJitter(before.plusMinutes(15), routine.scheduled(), LocalDateTime.now().plusMinutes(15));
        assertNotNull(routine.failedAttemptReason());
        assertTrue(routine.warnings.get(0).contains("before buying is unknown"), routine.warnings.get(0));
        assertNoPurchaseLogged(routine);
    }

    @Test
    void aMisreadAfterARealBuyWaitsOutTheBoostInsteadOfBuyingAgain() {
        // 12 Nov 2026: 59,293 after a real 250-gem buy was read as 99,293.
        ScriptedRoutine routine = new ScriptedRoutine(UseOutcome.BOUGHT_PURCHASE_CONFIRMED, known(59_543L),
                known(99_293L));
        LocalDateTime before = LocalDateTime.now();

        routine.execute();

        assertEquals(List.of("read", "use", "return", "read", "save:purchase-unverified", "close"), routine.events);
        assertScheduledWithForwardJitter(before.plusHours(8), routine.scheduled(), LocalDateTime.now().plusHours(8));
        assertNotNull(routine.failedAttemptReason());
        String warning = routine.warnings.get(0);
        assertTrue(warning.contains("unconfirmed") && warning.contains("59543") && warning.contains("99293")
                && warning.contains("will not be bought again before"), warning);
        assertTrue(warning.contains("Purchase dialog seen and confirmed"), warning);
        assertNoPurchaseLogged(routine);
    }

    @Test
    void anUnknownGemCountAfterBuyingAlsoWaitsOutTheBoost() {
        ScriptedRoutine routine = new ScriptedRoutine(UseOutcome.BOUGHT_PURCHASE_NOT_SEEN, known(59_543L), unknown());
        LocalDateTime before = LocalDateTime.now();

        routine.execute();

        assertEquals(List.of("read", "use", "return", "read", "save:purchase-unverified", "close"), routine.events);
        assertScheduledWithForwardJitter(before.plusHours(8), routine.scheduled(), LocalDateTime.now().plusHours(8));
        assertNotNull(routine.failedAttemptReason());
        assertTrue(routine.warnings.get(0).contains("Purchase dialog not seen"), routine.warnings.get(0));
        assertNoPurchaseLogged(routine);
    }

    @Test
    void aBuyConfirmedByThePriceSchedulesTheNextBoostWithoutAFailure() {
        ScriptedRoutine routine = new ScriptedRoutine(UseOutcome.BOUGHT_PURCHASE_CONFIRMED, known(59_543L),
                known(59_293L));
        LocalDateTime before = LocalDateTime.now();

        routine.execute();

        assertEquals(List.of("read", "use", "return", "read", "close"), routine.events);
        assertScheduledWithForwardJitter(before.plusHours(8), routine.scheduled(), LocalDateTime.now().plusHours(8));
        assertNull(routine.failedAttemptReason());
        assertTrue(routine.warnings.isEmpty(), routine.warnings::toString);
        assertTrue(routine.infos.stream().anyMatch(line -> line.startsWith("Boost bought and active (gems 59543 -> 59293")),
                routine.infos::toString);
    }

    private static void assertNoPurchaseLogged(ScriptedRoutine routine) {
        assertFalse(routine.infos.stream().anyMatch(line -> line.contains("Boost bought")), routine.infos::toString);
    }

    private static void assertScheduledWithForwardJitter(LocalDateTime earliest, LocalDateTime actual,
            LocalDateTime latestWithoutJitter) {
        long ceilingSeconds = Long.parseLong(
                ConfigurationKeyEnum.SCHEDULE_JITTER_MAX_SECONDS_INT.getDefaultValue().trim());
        assertNotNull(actual);
        assertFalse(actual.isBefore(earliest), () -> "scheduled before " + earliest + ": " + actual);
        assertFalse(actual.isAfter(latestWithoutJitter.plusSeconds(ceilingSeconds)),
                () -> "scheduled past " + latestWithoutJitter + " plus jitter: " + actual);
    }

    private static GemReading known(long gems) {
        return new GemReading(null, new HudGemReader.Reading(OptionalLong.of(gems), "test read"));
    }

    private static GemReading unknown() {
        return new GemReading(null, new HudGemReader.Reading(OptionalLong.empty(), "no gem diamond in the band"));
    }

    /** Plays the screens back from a script; every tap-bearing step is recorded instead of performed. */
    private static final class ScriptedRoutine extends GatherSpeedRoutine {
        private final UseOutcome outcome;
        private final Deque<GemReading> readings;
        private final List<String> events = new ArrayList<>();
        private final List<String> infos = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        private ScriptedRoutine(UseOutcome outcome, GemReading... readings) {
            super(new AccountDescriptor(1L, "GatherSpeedTest", "1", true, 1L, 30L), TpDailyTaskEnum.GATHER_BOOST);
            profile.setConfig(ConfigurationKeyEnum.GATHER_SPEED_BOOST_TYPE_STRING, "8h (250 gems)");
            this.outcome = outcome;
            this.readings = new ArrayDeque<>(List.of(readings));
        }

        @Override
        boolean openGatheringSpeed() {
            return true;
        }

        @Override
        ImageSearchResultData locateBoostRow() {
            return new ImageSearchResultData(true, new PointData(170, 515), 95);
        }

        @Override
        GemReading readGems() {
            events.add("read");
            return readings.removeFirst();
        }

        @Override
        UseOutcome useBoost(ImageSearchResultData row) {
            events.add("use");
            return outcome;
        }

        @Override
        void returnToCityBonus() {
            events.add("return");
        }

        @Override
        void closeMenu() {
            events.add("close");
        }

        @Override
        String saveGemFrame(GemReading reading, String type) {
            events.add("save:" + type);
            return "snapshot=test";
        }

        @Override
        public void logInfo(String message) {
            infos.add(message);
        }

        @Override
        public void logWarning(String message) {
            warnings.add(message);
        }

        private LocalDateTime scheduled() {
            return scheduledTime;
        }
    }
}
