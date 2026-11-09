package dev.frostguard.app.panel.today;

import java.util.Locale;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Orders one profile's tasks into the Today timeline. Pure, so the ordering rules can be tested. */
public final class TodayTimeline {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_CLOCK = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_CLOCK = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH);
    private static final int WEEKDAY_NAME_RANGE_DAYS = 6;

    public enum Kind { PAST, NOW, RUNNING, UPCOMING, RESET }

    /**
     * @param dueNow    waiting to run with its time already passed
     * @param savedOnly the bot is stopped, so {@code next} is the stored schedule and not a live queue entry
     */
    public record Task(String key, String name, LocalDateTime last, LocalDateTime next, boolean scheduled,
                       boolean executing, boolean dueNow, boolean failing, boolean savedOnly) {
    }

    public record Item(Kind kind, LocalDateTime time, Task task) {
    }

    private static final Duration PAST_WINDOW = Duration.ofHours(12);
    private static final int MAX_PAST = 15;

    private TodayTimeline() {
    }

    public static List<Item> build(List<Task> tasks, LocalDateTime now, LocalDateTime reset) {
        List<Item> items = new ArrayList<>(pastItems(tasks, now));
        items.add(new Item(Kind.NOW, now, null));
        tasks.stream().filter(Task::executing).forEach(task -> items.add(new Item(Kind.RUNNING, now, task)));
        items.addAll(upcomingWithReset(tasks, reset));
        return items;
    }

    /** Executing and due tasks are left out: Run now stamps last-run before the task has actually run. */
    private static List<Item> pastItems(List<Task> tasks, LocalDateTime now) {
        LocalDateTime cutoff = now.minus(PAST_WINDOW);
        List<Task> past = tasks.stream()
                .filter(task -> task.last() != null && !task.executing() && !task.dueNow())
                .filter(task -> !task.last().isBefore(cutoff) && !task.last().isAfter(now))
                .sorted(Comparator.comparing(Task::last))
                .toList();
        return past.subList(Math.max(0, past.size() - MAX_PAST), past.size()).stream()
                .map(task -> new Item(Kind.PAST, task.last(), task))
                .toList();
    }

    private static List<Item> upcomingWithReset(List<Task> tasks, LocalDateTime reset) {
        List<Item> items = new ArrayList<>();
        boolean resetPlaced = false;
        List<Task> upcoming = tasks.stream()
                .filter(task -> task.scheduled() && !task.executing() && task.next() != null)
                .sorted(Comparator.comparing(Task::next))
                .toList();
        for (Task task : upcoming) {
            if (!resetPlaced && !task.next().isBefore(reset)) {
                items.add(new Item(Kind.RESET, reset, null));
                resetPlaced = true;
            }
            items.add(new Item(Kind.UPCOMING, task.next(), task));
        }
        if (!resetPlaced) {
            items.add(new Item(Kind.RESET, reset, null));
        }
        return items;
    }

    /** "in 3h 12m" for a future time and "Due now" for one that has passed. */
    /** Every time on Today is 24-hour; these are the only formats the page uses. */
    public static String clock(LocalDateTime time) {
        return time.format(CLOCK);
    }

    /** A clock time, with the day added when it is not today. */
    public static String clockWithDay(LocalDateTime time, LocalDateTime now) {
        long days = Math.abs(ChronoUnit.DAYS.between(time.toLocalDate(), now.toLocalDate()));
        if (days == 0) {
            return time.format(CLOCK);
        }
        // A weekday name alone would make a schedule that is a week stale look like today's.
        return time.format(days <= WEEKDAY_NAME_RANGE_DAYS ? DAY_CLOCK : DATE_CLOCK);
    }

    public static String relative(LocalDateTime now, LocalDateTime when) {
        long minutes = Duration.between(now, when).toMinutes();
        if (minutes <= 0) {
            return now.isBefore(when) ? "in under 1m" : "Due now";
        }
        long days = minutes / 1440;
        long hours = minutes % 1440 / 60;
        long mins = minutes % 60;
        if (days > 0) {
            return "in " + days + "d " + hours + "h";
        }
        return hours > 0 ? "in " + hours + "h " + mins + "m" : "in " + mins + "m";
    }
}
