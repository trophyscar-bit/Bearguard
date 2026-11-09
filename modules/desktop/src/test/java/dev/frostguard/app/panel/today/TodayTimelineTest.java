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
        assertEquals("Sun 13:05", TodayTimeline.clockWithDay(afternoon, afternoon.plusDays(2)));
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
    void pastIsLimitedToTwelveHoursAndTheNewestFifteen() {
        assertFalse(kinds(TodayTimeline.build(List.of(task("stale", NOW.minusHours(13), null, false, false)), NOW, RESET))
                .contains(Kind.PAST));

        List<Task> many = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> task("t" + i, NOW.minusMinutes(i + 1L), null, false, false))
                .toList();
        List<Item> items = TodayTimeline.build(many, NOW, RESET);
        assertEquals(15, kinds(items).stream().filter(Kind.PAST::equals).count());
        assertEquals("t14", items.getFirst().task().key());
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
}
