package dev.frostguard.app.panel.today;

import java.util.Locale;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Orders one profile's tasks into the Today timeline. Pure, so the ordering rules can be tested. */
public final class TodayTimeline {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_CLOCK = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH);

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

    /** The one wording for a task that is executing, on its row, in search and in the panel. */
    public static final String RUNNING_NOW = "Running now";

    private static final int MAX_PAST = 10;
    private static final Duration OVERDUE_AFTER = Duration.ofMinutes(15);
    /** Tasks with no real next run hold a far-future placeholder such as 2098-12-31. */
    private static final Duration PLACEHOLDER_BEYOND = Duration.ofDays(366);

    private TodayTimeline() {
    }

    public static List<Item> build(List<Task> tasks, LocalDateTime now, LocalDateTime reset) {
        List<Item> items = new ArrayList<>(pastItems(tasks, now));
        items.add(new Item(Kind.NOW, now, null));
        tasks.stream().filter(Task::executing).forEach(task -> items.add(new Item(Kind.RUNNING, now, task)));
        items.addAll(upcomingWithReset(tasks, now, reset));
        return items;
    }

    /** Run now stamps last-run before the task has actually run, so it means nothing until the run ends. */
    private static boolean lastRunIsReal(Task task) {
        return !task.executing() && !task.dueNow();
    }

    /** The "Last run" text, held to the same rule as the timeline's past section. */
    public static String lastRunText(Task task, LocalDateTime now) {
        if (task.executing()) {
            return RUNNING_NOW;
        }
        if (task.dueNow()) {
            return "Waiting to run";
        }
        return task.last() == null ? "Never" : clockWithDay(task.last(), now);
    }

    private static List<Item> pastItems(List<Task> tasks, LocalDateTime now) {
        List<Task> past = tasks.stream()
                .filter(task -> task.last() != null && lastRunIsReal(task) && !task.last().isAfter(now))
                .sorted(Comparator.comparing(Task::last))
                .toList();
        return past.subList(Math.max(0, past.size() - MAX_PAST), past.size()).stream()
                .map(task -> new Item(Kind.PAST, task.last(), task))
                .toList();
    }

    private static List<Item> upcomingWithReset(List<Task> tasks, LocalDateTime now, LocalDateTime reset) {
        List<Item> items = new ArrayList<>();
        boolean resetPlaced = false;
        List<Task> upcoming = tasks.stream()
                .filter(task -> !task.executing() && hasNextRun(task, now))
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

    /** The tasks whose last run falls after the last daily reset, newest first. */
    public static List<Task> doneSinceReset(List<Task> tasks, LocalDateTime lastReset, LocalDateTime now) {
        return tasks.stream()
                .filter(task -> task.last() != null && lastRunIsReal(task))
                .filter(task -> !task.last().isBefore(lastReset) && !task.last().isAfter(now))
                .sorted(Comparator.comparing(Task::last).reversed())
                .toList();
    }

    /** The scroll fraction that leaves the view at the same pixel offset after the content height changed. */
    public static double sameScrollOffset(double fraction, double overflowBefore, double overflowAfter) {
        return overflowAfter <= 0 ? 0 : Math.min(1, fraction * overflowBefore / overflowAfter);
    }

    public static boolean hasNextRun(Task task, LocalDateTime now) {
        return task.scheduled() && task.next() != null && !task.next().isAfter(now.plus(PLACEHOLDER_BEYOND));
    }

    /** The time column of a waiting task: a stale date must never read as a coming day. */
    public static String nextRunTime(LocalDateTime next, LocalDateTime now) {
        if (next.isAfter(now)) {
            return clockWithDay(next, now);
        }
        return next.isBefore(now.minus(OVERDUE_AFTER)) ? "Overdue" : "Due now";
    }

    /** The note beside a waiting task. */
    public static String nextRunDetail(LocalDateTime next, LocalDateTime now) {
        return next.isAfter(now) ? relative(now, next) : "was due " + clockWithDay(next, now);
    }

    public static String scheduledText(Task task, LocalDateTime now) {
        if (!hasNextRun(task, now)) {
            return "Not scheduled";
        }
        String detail = nextRunDetail(task.next(), now);
        return task.next().isAfter(now)
                ? nextRunTime(task.next(), now) + " (" + detail + ")"
                : nextRunTime(task.next(), now) + ", " + detail;
    }

    public static String nowText(LocalDateTime now) {
        return "Now " + clock(now);
    }

    public static String failureHeadline(int count) {
        return count == 0 ? "No tasks failing" : count + (count == 1 ? " task failing" : " tasks failing");
    }

    /** Names the first three, so a long streak list cannot push the timeline away. */
    public static String failureNames(List<String> names) {
        String shown = String.join(", ", names.subList(0, Math.min(3, names.size())));
        return names.size() > 3 ? shown + " and " + (names.size() - 3) + " more" : shown;
    }

    /** Every time on Today is 24-hour; these are the only formats the page uses. */
    public static String clock(LocalDateTime time) {
        return time.format(CLOCK);
    }

    /** A clock time, with the date added when it is not today. */
    public static String clockWithDay(LocalDateTime time, LocalDateTime now) {
        return time.format(time.toLocalDate().equals(now.toLocalDate()) ? CLOCK : DATE_CLOCK);
    }

    /** "in 3h 12m" for a future time and "Due now" for one that has passed. */
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
