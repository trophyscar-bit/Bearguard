package dev.frostguard.tasks.heroes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.nav.CommonGameAreas;

class HeroRecruitmentRoutineTest {

    private static final AreaData ADVANCED_TIMER = CommonGameAreas.HERO_RECRUIT_ADVANCED_TIMER_OCR_AREA;
    private static final AreaData EPIC_TIMER = CommonGameAreas.HERO_RECRUIT_EPIC_TIMER_OCR_AREA;

    @BeforeAll
    static void createTestWorkspace() throws IOException {
        Files.createDirectories(WorkspacePaths.current().root());
    }

    @Test
    void unconfirmedScreenAfterAdvancedClaimStopsEverythingAndRetriesSoon() {
        ScriptedRoutine routine = new ScriptedRoutine(true, true);
        LocalDateTime before = LocalDateTime.now();

        routine.execute();

        assertEquals(List.of("free:Advanced", "tap:Advanced"), routine.screenActions);
        assertEquals(List.of(), routine.timerReads);
        assertEquals(List.of("Advanced-screen-unconfirmed"), routine.dumps);
        assertTrue(routine.screenChecks >= 2, "kept checking until the timeout");
        assertTrue(routine.clock >= HeroRecruitmentRoutine.SCREEN_CONFIRM_TIMEOUT_MS);
        assertTrue(routine.clock <= HeroRecruitmentRoutine.SCREEN_CONFIRM_TIMEOUT_MS + 500,
                "gave up about three seconds after the claim tap");
        assertFallbackRetry(before, routine);
    }

    @Test
    void unconfirmedScreenAfterEpicClaimSkipsTheEpicTimerRead() {
        ScriptedRoutine routine = new ScriptedRoutine(false, true);
        LocalDateTime before = LocalDateTime.now();

        routine.execute();

        assertEquals(List.of("free:Advanced", "free:Epic", "tap:Epic"), routine.screenActions);
        assertEquals(List.of(ADVANCED_TIMER), routine.timerReads);
        assertEquals(List.of("Epic-screen-unconfirmed"), routine.dumps);
        assertFallbackRetry(before, routine);
    }

    @Test
    void screenThatComesBackLetsTheRunContinue() {
        ScriptedRoutine routine = new ScriptedRoutine(true, false);
        routine.screenAnswers.addAll(List.of(false, false, true));

        routine.execute();

        assertEquals(List.of("free:Advanced", "tap:Advanced", "free:Epic"), routine.screenActions);
        assertEquals(List.of(ADVANCED_TIMER, EPIC_TIMER), routine.timerReads);
        assertEquals(3, routine.screenChecks);
        assertEquals(List.of(), routine.dumps);
        assertTrue(routine.scheduledTime().isAfter(LocalDateTime.now().plusMinutes(50)),
                "scheduled from the real timers, not the fallback");
    }

    @Test
    void noClaimMeansNoScreenCheck() {
        ScriptedRoutine routine = new ScriptedRoutine(false, false);

        routine.execute();

        assertEquals(0, routine.screenChecks);
        assertEquals(List.of(ADVANCED_TIMER, EPIC_TIMER), routine.timerReads);
    }

    private static void assertFallbackRetry(LocalDateTime before, ScriptedRoutine routine) {
        assertTrue(routine.scheduledTime().isAfter(before.plusMinutes(4)), routine.scheduledTime().toString());
        assertTrue(routine.scheduledTime().isBefore(LocalDateTime.now().plusMinutes(6)),
                routine.scheduledTime().toString());
    }

    /** Stands in for the device: scripted Free buttons, screen checks and timers, a fake clock. */
    private static final class ScriptedRoutine extends HeroRecruitmentRoutine {
        private final boolean advancedFree;
        private final boolean epicFree;
        private final Deque<Boolean> screenAnswers = new ArrayDeque<>();
        private final List<String> screenActions = new ArrayList<>();
        private final List<AreaData> timerReads = new ArrayList<>();
        private final List<String> dumps = new ArrayList<>();
        private int screenChecks;
        private long clock;

        private ScriptedRoutine(boolean advancedFree, boolean epicFree) {
            super(new AccountDescriptor(1L, "RecruitTest", "1", true, 1L, 30L),
                    TpDailyTaskEnum.HERO_RECRUITMENT);
            this.advancedFree = advancedFree;
            this.epicFree = epicFree;
        }

        @Override
        void navigateToHeroRecruitment() {
        }

        @Override
        boolean freeButtonShown(PointData searchMin, PointData searchMax) {
            boolean advanced = searchMin.getY() < 1000;
            screenActions.add("free:" + (advanced ? "Advanced" : "Epic"));
            return advanced ? advancedFree : epicFree;
        }

        @Override
        void tapClaim(PointData claimMin, PointData claimMax) {
            screenActions.add("tap:" + (claimMin.getY() < 1000 ? "Advanced" : "Epic"));
        }

        @Override
        boolean recruitmentScreenShown() {
            screenChecks++;
            Boolean answer = screenAnswers.poll();
            return answer != null && answer;
        }

        @Override
        Duration readTimer(AreaData timerArea) {
            timerReads.add(timerArea);
            return Duration.ofHours(1);
        }

        @Override
        String dumpFrame(String type, AreaData region) {
            dumps.add(type);
            return "snapshot=test";
        }

        @Override
        void pause(long millis) {
            clock += millis;
        }

        @Override
        long nowMillis() {
            return clock;
        }

        private LocalDateTime scheduledTime() {
            return scheduledTime;
        }
    }
}
