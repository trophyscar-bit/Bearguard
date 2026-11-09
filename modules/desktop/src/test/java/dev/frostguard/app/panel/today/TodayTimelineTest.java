package dev.frostguard.app.panel.today;

import dev.frostguard.app.panel.today.TodayTimeline.Item;
import dev.frostguard.app.panel.today.TodayTimeline.Kind;
import dev.frostguard.app.panel.today.TodayTimeline.Task;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodayTimelineTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 22, 14);
    private static final LocalDateTime RESET = NOW.plusHours(2);

    private static Task task(String key, LocalDateTime last, LocalDateTime next, boolean executing, boolean due) {
        return new Task(key, key, last, next, next != null, executing, due, false, false);
    }

    private static List<Kind> kinds(List<Item> items) {
        return items.stream().map(Item::kind).toList();
    }

    @Test
    void everyClockOnThePageIsTwentyFourHour() {
        LocalDateTime afternoon = LocalDateTime.of(2026, 11, 8, 13, 5);
        assertEquals("13:05", TodayTimeline.clock(afternoon));
        assertEquals("13:05", TodayTimeline.clockWithDay(afternoon, afternoon.plusHours(2)));
        assertEquals("Nov 8 13:05", TodayTimeline.clockWithDay(afternoon, afternoon.plusDays(2)));
        assertEquals("Now 13:05", TodayTimeline.nowText(afternoon));
        assertEquals("Nov 8 13:05", TodayTimeline.clockWithDay(afternoon, afternoon.plusDays(20)));
    }

    @Test
    void ordersPastThenNowThenRunningThenUpcomingAroundReset() {
        List<Item> items = TodayTimeline.build(List.of(
                task("later", null, NOW.plusHours(3), false, false),
                task("soon", null, NOW.plusMinutes(5), false, false),
                task("old", NOW.minusHours(2), null, false, false),
                task("newer", NOW.minusHours(1), null, false, false),
                task("run", null, null, true, false)), NOW, RESET);

        assertEquals(List.of(Kind.PAST, Kind.PAST, Kind.NOW, Kind.RUNNING, Kind.UPCOMING, Kind.RESET, Kind.UPCOMING),
                kinds(items));
        assertEquals("old", items.get(0).task().key());
        assertEquals("newer", items.get(1).task().key());
        assertEquals("soon", items.get(4).task().key());
        assertEquals("later", items.get(6).task().key());
    }

    @Test
    void resetMarkerStillAppearsWhenNothingRunsAfterIt() {
        List<Item> items = TodayTimeline.build(List.of(task("soon", null, NOW.plusMinutes(5), false, false)), NOW, RESET);
        assertEquals(Kind.RESET, items.getLast().kind());
    }

    @Test
    void runNowStampDoesNotCountAsAPastRun() {
        List<Item> items = TodayTimeline.build(List.of(
                task("queued", NOW.minusMinutes(1), NOW.minusMinutes(1), false, true),
                task("running", NOW.minusMinutes(1), null, true, false)), NOW, RESET);
        assertFalse(kinds(items).contains(Kind.PAST));
    }

    @Test
    void pastShowsTheNewestTenRunsHoweverOldTheyAre() {
        assertTrue(kinds(TodayTimeline.build(List.of(task("old", NOW.minusDays(3), null, false, false)), NOW, RESET))
                .contains(Kind.PAST));

        List<Task> many = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> task("t" + i, NOW.minusMinutes(i + 1L), null, false, false))
                .toList();
        List<Item> items = TodayTimeline.build(many, NOW, RESET);
        assertEquals(10, kinds(items).stream().filter(Kind.PAST::equals).count());
        assertEquals("t9", items.getFirst().task().key());
        assertEquals("t0", items.get(9).task().key());
    }

    @Test
    void relativeTimesAreReadable() {
        assertEquals("Due now", TodayTimeline.relative(NOW, NOW.minusMinutes(1)));
        assertEquals("in 45m", TodayTimeline.relative(NOW, NOW.plusMinutes(45)));
        assertEquals("in 3h 12m", TodayTimeline.relative(NOW, NOW.plusMinutes(192)));
        assertEquals("in 2d 1h", TodayTimeline.relative(NOW, NOW.plusHours(49)));
    }

    @Test
    void runNowGuards() {
        RunNowPolicy.Decision stopped = RunNowPolicy.decide(false, false, false, true);
        assertFalse(stopped.enabled());
        assertEquals("Start the bot first", stopped.note());

        RunNowPolicy.Decision running = RunNowPolicy.decide(true, false, true, true);
        assertFalse(running.enabled());

        RunNowPolicy.Decision paused = RunNowPolicy.decide(true, true, false, true);
        assertTrue(paused.enabled());
        assertNull(paused.note());
        assertEquals("Queued, bot is paused", paused.afterRun());

        assertEquals("Run once", RunNowPolicy.decide(true, false, false, false).label());
        assertEquals("Run now", RunNowPolicy.decide(true, false, false, true).label());
    }

    @Test
    void lastRunIsNotShownWhileTheTaskIsQueuedOrRunning() {
        LocalDateTime stamped = NOW.minusSeconds(5);
        assertEquals("Running now", TodayTimeline.lastRunText(task("a", stamped, NOW, true, false), NOW));
        assertEquals("Waiting to run", TodayTimeline.lastRunText(task("a", stamped, NOW, false, true), NOW));
    }

    @Test
    void lastRunIsShownOnceTheTaskHasFinished() {
        assertEquals("21:00", TodayTimeline.lastRunText(task("a", NOW.withHour(21).withMinute(0), NOW.plusHours(1), false, false), NOW));
        assertEquals("Never", TodayTimeline.lastRunText(task("a", null, NOW.plusHours(1), false, false), NOW));
    }

    @Test
    void aStaleRunTimeReadsOverdueWithItsRealDate() {
        LocalDateTime lastSunday = LocalDateTime.of(2026, 11, 1, 23, 40);

        assertEquals("Overdue", TodayTimeline.nextRunTime(lastSunday, NOW));
        assertEquals("was due Nov 1 23:40", TodayTimeline.nextRunDetail(lastSunday, NOW));
    }

    @Test
    void justPassedIsDueNowUntilItIsAQuarterHourLate() {
        assertEquals("Due now", TodayTimeline.nextRunTime(NOW.minusMinutes(1), NOW));
        assertEquals("Due now", TodayTimeline.nextRunTime(NOW.minusMinutes(15), NOW));
        assertEquals("Overdue", TodayTimeline.nextRunTime(NOW.minusMinutes(16), NOW));
    }

    @Test
    void comingRunsReadAsTodayOrAClearDate() {
        assertEquals("22:44", TodayTimeline.nextRunTime(NOW.plusMinutes(30), NOW));
        assertEquals("Nov 9 13:00", TodayTimeline.nextRunTime(LocalDateTime.of(2026, 11, 9, 13, 0), NOW));
        assertEquals("Nov 15 08:30", TodayTimeline.nextRunTime(LocalDateTime.of(2026, 11, 15, 8, 30), NOW));
    }

    @Test
    void aPlaceholderDateIsNotAnUpcomingRun() {
        Task placeholder = task("never", null, LocalDateTime.of(2098, 12, 31, 0, 0), false, false);

        assertTrue(TodayTimeline.build(List.of(placeholder), NOW, RESET).stream()
                .noneMatch(item -> item.kind() == Kind.UPCOMING));
        assertEquals("Not scheduled", TodayTimeline.scheduledText(placeholder, NOW));
    }

    @Test
    void anUnscheduledTaskIsNotAnUpcomingRun() {
        Task off = new Task("off", "off", null, NOW.plusHours(1), false, false, false, false, false);

        assertTrue(TodayTimeline.build(List.of(off), NOW, RESET).stream()
                .noneMatch(item -> item.kind() == Kind.UPCOMING));
    }

    @Test
    void upcomingRunsAreInTrueOrderWithOverdueFirst() {
        List<Item> items = TodayTimeline.build(List.of(
                task("week", null, NOW.plusDays(7), false, false),
                task("soon", null, NOW.plusMinutes(5), false, false),
                task("stale", null, NOW.minusDays(1), false, false),
                task("tomorrow", null, NOW.plusDays(1), false, false)), NOW, RESET);

        assertEquals(List.of("stale", "soon", "tomorrow", "week"), items.stream()
                .filter(item -> item.kind() == Kind.UPCOMING).map(item -> item.task().key()).toList());
    }

    @Test
    void scheduledTextExplainsOverdueAndFutureRuns() {
        assertEquals("Overdue, was due Nov 7 22:14", TodayTimeline.scheduledText(task("a", null, NOW.minusDays(1), false, false), NOW));
        assertEquals("23:14 (in 1h 0m)", TodayTimeline.scheduledText(task("a", null, NOW.plusHours(1), false, false), NOW));
    }

    @Test
    void doneListsWhatRanSinceTheResetNewestFirst() {
        LocalDateTime lastReset = NOW.minusHours(10);

        assertEquals(List.of("new", "older"), TodayTimeline.doneSinceReset(List.of(
                task("older", NOW.minusHours(9), null, false, false),
                task("before-reset", NOW.minusHours(11), null, false, false),
                task("new", NOW.minusHours(1), null, false, false),
                task("queued", NOW.minusMinutes(1), NOW, false, true),
                task("never", null, null, false, false)), lastReset, NOW).stream().map(Task::key).toList());
    }

    @Test
    void failureSummaryNamesTheFirstThree() {
        assertEquals("No tasks failing", TodayTimeline.failureHeadline(0));
        assertEquals("1 task failing", TodayTimeline.failureHeadline(1));
        assertEquals("2 tasks failing", TodayTimeline.failureHeadline(2));
        assertEquals("A, B, C and 1 more", TodayTimeline.failureNames(List.of("A", "B", "C", "D")));
    }

    /** Fails if a 12-hour pattern or a locale-chosen clock comes back anywhere on the page. */
    @Test
    void nothingOnThePageCanFormatATwelveHourClock() throws java.io.IOException {
        java.util.regex.Pattern twelveHour = java.util.regex.Pattern.compile(
                "\"[^\"]*\\bh{1,2}:mm|ofLocalized|SimpleDateFormat|toLocaleString|LocalTime\\.now|\\bHH:mm a\\b");
        try (java.util.stream.Stream<java.nio.file.Path> sources = java.nio.file.Files.list(
                java.nio.file.Path.of("src/main/java/dev/frostguard/app/panel/today"))) {
            for (java.nio.file.Path source : sources.toList()) {
                assertFalse(twelveHour.matcher(java.nio.file.Files.readString(source)).find(), source.toString());
            }
        }
    }
}
