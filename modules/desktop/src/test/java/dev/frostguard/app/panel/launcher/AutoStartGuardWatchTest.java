package dev.frostguard.app.panel.launcher;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoStartGuardWatchTest {

    private static final String PROD_RUNNING = "Production Bearguard is running.";

    private final AtomicReference<String> answer = new AtomicReference<>();
    private final AtomicInteger checks = new AtomicInteger();
    private final AtomicReference<Set<Long>> java = new AtomicReference<>(Set.of(1L));
    private final Deque<Runnable> queued = new ArrayDeque<>();

    private AutoStartGuardWatch watch() {
        return new AutoStartGuardWatch(() -> {
            checks.incrementAndGet();
            return answer.get();
        }, java::get, queued::add);
    }

    private void runQueued() {
        while (!queued.isEmpty()) {
            queued.poll().run();
        }
    }

    @Test
    void theStartNeedsNoFurtherCheckOnceTheCountdownIsUp() {
        AutoStartGuardWatch watch = watch();
        watch.begin();
        runQueued();
        watch.onSecondsRemaining(1);
        runQueued();
        int before = checks.get();

        assertNull(watch.verdict());
        assertEquals(before, checks.get(), "the verdict must come from a check already done");
    }

    @Test
    void theLastCheckStartsCloseToTheEndAndOnlyOnce() {
        AutoStartGuardWatch watch = watch();
        watch.begin();
        runQueued();
        assertEquals(1, checks.get());

        watch.onSecondsRemaining(20);
        runQueued();
        assertEquals(1, checks.get(), "too early for the last check");

        watch.onSecondsRemaining(AutoStartGuardWatch.FINAL_CHECK_MARGIN_SECONDS);
        watch.onSecondsRemaining(1);
        runQueued();
        assertEquals(2, checks.get());
    }

    @Test
    void productionAppearingDuringTheCountdownRefusesTheStart() {
        AutoStartGuardWatch watch = watch();
        watch.begin();
        runQueued();

        answer.set(PROD_RUNNING);
        watch.onSecondsRemaining(1);
        runQueued();

        assertEquals(PROD_RUNNING, watch.verdict());
    }

    @Test
    void aJavaProcessThatAppearsAfterTheLastCheckIsCheckedAgain() {
        AutoStartGuardWatch watch = watch();
        watch.begin();
        runQueued();
        watch.onSecondsRemaining(1);
        runQueued();

        java.set(Set.of(1L, 2L));
        answer.set(PROD_RUNNING);

        assertEquals(PROD_RUNNING, watch.verdict());
        assertEquals(3, checks.get());
    }

    @Test
    void aJavaProcessThatEndedNeedsNoNewCheck() {
        java.set(Set.of(1L, 2L));
        AutoStartGuardWatch watch = watch();
        watch.begin();
        runQueued();

        java.set(Set.of(1L));

        assertNull(watch.verdict());
        assertEquals(1, checks.get());
    }

    @Test
    void anUnfinishedCheckIsWaitedForAndCanStillRefuse() throws Exception {
        AutoStartGuardWatch watch = watch();
        watch.begin();
        answer.set(PROD_RUNNING);

        AtomicReference<String> verdict = new AtomicReference<>("not answered");
        Thread countdownEnd = Thread.ofPlatform().daemon().start(() -> verdict.set(watch.verdict()));
        Thread.sleep(100);
        assertTrue(countdownEnd.isAlive(), "an unfinished check must not read as a pass");

        runQueued();
        countdownEnd.join(2000);

        assertFalse(countdownEnd.isAlive());
        assertEquals(PROD_RUNNING, verdict.get());
    }

    @Test
    void aGuardThatThrowsRefusesTheStart() {
        AutoStartGuardWatch watch = new AutoStartGuardWatch(() -> {
            throw new IllegalStateException("no process table");
        }, java::get, queued::add);
        watch.begin();
        runQueued();

        assertTrue(watch.verdict() != null);
    }

    @Test
    void aStoppedWatchAsksNothingMore() {
        AutoStartGuardWatch watch = watch();
        watch.begin();
        runQueued();
        watch.stop();

        watch.onSecondsRemaining(1);
        runQueued();

        assertEquals(1, checks.get());
    }

    @Test
    void onlyJavaExecutablesCount() {
        assertTrue(AutoStartGuardWatch.isJava("C:\\Frostguard-tools\\jdk-21.0.12+8\\bin\\javaw.exe"));
        assertTrue(AutoStartGuardWatch.isJava("C:\\Tools\\JAVA.EXE"));
        assertFalse(AutoStartGuardWatch.isJava("C:\\Windows\\explorer.exe"));
    }
}
