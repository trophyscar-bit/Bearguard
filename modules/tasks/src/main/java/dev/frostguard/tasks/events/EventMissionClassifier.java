package dev.frostguard.tasks.events;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.TpDailyTaskEnum;

/**
 * Reads a mission's title and says what the sweep may do about it. Pure text rules, pinned by tests.
 *
 * <ul>
 *   <li>{@link Kind#EXCLUDED}: Chief Gear. Never tapped, not even a finished one.</li>
 *   <li>{@link Kind#PURCHASE}: asks for a payment or gems. Its Go is never tapped; only a finished one's
 *       green Claim is.</li>
 *   <li>{@link Kind#DOABLE}: an existing bot task does the work ({@link Verdict#task()}). The sweep never
 *       taps its Go; the caller schedules that task, and only when it is switched on.</li>
 *   <li>{@link Kind#PASSIVE}: everything else. Other tasks or the day itself fill it; the sweep claims it
 *       when it is ready.</li>
 *   <li>{@link Kind#UNREADABLE}: the title could not be read, so nothing on the row is tapped.</li>
 * </ul>
 */
final class EventMissionClassifier {

    enum Kind { EXCLUDED, PURCHASE, DOABLE, PASSIVE, UNREADABLE }

    record Verdict(Kind kind, TpDailyTaskEnum task) {
        static Verdict of(Kind kind) {
            return new Verdict(kind, null);
        }
    }

    private record TaskRule(Pattern pattern, TpDailyTaskEnum task) {
    }

    private static final Pattern GEAR = Pattern.compile("\\bgear\\b");
    private static final Pattern PURCHASE = Pattern.compile(
            "\\b(payment|payments|pay|purchase|purchases|buy|bought|recharge|top ?up|deposit|gems?|diamonds?)\\b|\\$");

    /** Order matters: the first rule that matches wins. */
    private static final List<TaskRule> TASKS = List.of(
            rule("\\bpolar\\b", TpDailyTaskEnum.EVENT_POLAR_TERROR),
            rule("\\b(gather|gathering)\\b|collect\\w* (resources|meat|wood|coal|iron)", TpDailyTaskEnum.GATHER_RESOURCES),
            rule("\\bbeasts?\\b", TpDailyTaskEnum.BEAST_HUNTING),
            rule("\\bintel\\b", TpDailyTaskEnum.INTEL),
            rule("\\barena\\b", TpDailyTaskEnum.ARENA),
            rule("\\bpet adventure", TpDailyTaskEnum.PET_ADVENTURE),
            rule("\\bexplor\\w*", TpDailyTaskEnum.DO_EXPLORATION),
            rule("\\brecruit\\w*", TpDailyTaskEnum.HERO_RECRUITMENT),
            rule("\\bresearch\\w*", TpDailyTaskEnum.RESEARCH),
            rule("\\b(donat\\w*|contribut\\w*)\\b", TpDailyTaskEnum.ALLIANCE_TECH));

    private EventMissionClassifier() {
    }

    static Verdict classify(String title) {
        String text = normalise(title);
        if (text.replace(" ", "").length() < 4) {
            return Verdict.of(Kind.UNREADABLE);
        }
        if (GEAR.matcher(text).find()) {
            return Verdict.of(Kind.EXCLUDED);
        }
        if (PURCHASE.matcher(text).find()) {
            return Verdict.of(Kind.PURCHASE);
        }
        for (TaskRule rule : TASKS) {
            if (rule.pattern().matcher(text).find()) {
                return new Verdict(Kind.DOABLE, rule.task());
            }
        }
        return Verdict.of(Kind.PASSIVE);
    }

    /** The same title read twice with different digits or spacing still names one mission. */
    static String key(String title) {
        return normalise(title).replaceAll("[0-9 ]", "");
    }

    static Optional<TpDailyTaskEnum> taskFor(String title) {
        Verdict verdict = classify(title);
        return verdict.kind() == Kind.DOABLE ? Optional.of(verdict.task()) : Optional.empty();
    }

    private static String normalise(String title) {
        if (title == null) {
            return "";
        }
        return title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9$ ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static TaskRule rule(String regex, TpDailyTaskEnum task) {
        return new TaskRule(Pattern.compile(regex), task);
    }
}
