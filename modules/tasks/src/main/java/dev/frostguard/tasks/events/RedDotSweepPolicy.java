package dev.frostguard.tasks.events;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

import dev.frostguard.api.configs.TpDailyTaskEnum;

/**
 * What the Red-dot Sweep may visit and tap, and when it runs. Pure rules, so each can be pinned by a test.
 *
 * <p>The sweep is a whitelist: it opens only the Events, Deals and Gem Shop panels and walks the tabs that
 * carry a red dot. Mail, the Storehouse online chest and the VIP chest already have dedicated tasks, so they
 * are listed here as {@link Dedicated} and never visited. Chief Gear is not on the whitelist and is also
 * refused by name wherever a title is read.
 */
final class RedDotSweepPolicy {

    /** The panels the sweep opens itself. */
    enum Panel {
        EVENTS("Events"), DEALS("Deals"), GEM_SHOP("Gem Shop");

        private final String label;

        Panel(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /** A panel whose free claim belongs to another task; the sweep leaves it alone. */
    record Dedicated(String panel, TpDailyTaskEnum task) {
    }

    static final List<Dedicated> DEDICATED_PANELS = List.of(
            new Dedicated("Mail", TpDailyTaskEnum.MAIL_REWARDS),
            new Dedicated("Online chest (Storehouse)", TpDailyTaskEnum.STOREHOUSE_CHEST),
            new Dedicated("VIP chest", TpDailyTaskEnum.VIP_POINTS));

    /** Verdict on the words read from a page banner or tab label. */
    enum LabelVerdict { OK, EXCLUDED, UNREADABLE }

    private static final List<String> EXCLUDED_WORDS = List.of("chief gear", "gear");

    /**
     * Words that mean a tap would spend, reroll, recruit or redeem. A button whose own text (or any text
     * on its row) contains one is never tapped.
     */
    private static final List<String> FORBIDDEN_WORDS = List.of(
            "buy", "purchase", "shoot", "reset", "recruit", "redeem", "shard", "top up", "top-up",
            "refresh", "unlock", "upgrade", "exchange", "gem", "$");

    static final Duration RUN_AFTER_RESET = Duration.ofMinutes(10);
    static final int RESET_JITTER_MAX_MINUTES = 5;
    /** Between the Fortress battle window (ends 10:00) and the noon Labyrinth run, clear of the Bear Trap. */
    static final LocalTime MIDDAY = LocalTime.of(11, 15);
    static final Duration FAILURE_RETRY = Duration.ofMinutes(30);
    static final int MAX_FAILED_ATTEMPTS = 2;

    /** How a visit ended: {@code CLEAN} found nothing wrong, {@code UNKNOWN} met something it would not touch. */
    enum Outcome { CLEAN, UNKNOWN }

    private RedDotSweepPolicy() {
    }

    /**
     * Chief Gear is refused outright. A blank read is refused too, because a page that cannot be named
     * cannot be shown to be something other than Chief Gear.
     */
    static LabelVerdict judgeLabel(String text) {
        if (text == null) {
            return LabelVerdict.UNREADABLE;
        }
        String lower = text.toLowerCase(Locale.ROOT).trim();
        if (lower.replaceAll("[^a-z]", "").length() < 3) {
            return LabelVerdict.UNREADABLE;
        }
        for (String word : EXCLUDED_WORDS) {
            if (lower.contains(word)) {
                return LabelVerdict.EXCLUDED;
            }
        }
        return LabelVerdict.OK;
    }

    static boolean isForbiddenText(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : FORBIDDEN_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The earliest of: ten minutes (plus a few minutes of jitter) after the reset that last happened, the
     * same after the next one, and 11:15 on either calendar day. An unknown visit retries in 30 minutes,
     * at most {@value #MAX_FAILED_ATTEMPTS} times, and never later than the regular slot.
     */
    static LocalDateTime nextRun(LocalDateTime now, LocalDateTime nextReset, int jitterMinutes,
            Outcome outcome, int failedAttempts) {
        LocalDateTime regular = regularSlot(now, nextReset, jitterMinutes);
        if (outcome != Outcome.UNKNOWN || failedAttempts >= MAX_FAILED_ATTEMPTS) {
            return regular;
        }
        LocalDateTime retry = now.plus(FAILURE_RETRY);
        return retry.isBefore(regular) ? retry : regular;
    }

    static LocalDateTime regularSlot(LocalDateTime now, LocalDateTime nextReset, int jitterMinutes) {
        Duration offset = RUN_AFTER_RESET.plusMinutes(Math.max(0, Math.min(jitterMinutes, RESET_JITTER_MAX_MINUTES)));
        LocalDateTime best = null;
        for (LocalDateTime candidate : List.of(
                nextReset.minusDays(1).plus(offset), nextReset.plus(offset),
                now.toLocalDate().atTime(MIDDAY), now.toLocalDate().plusDays(1).atTime(MIDDAY))) {
            if (candidate.isAfter(now) && (best == null || candidate.isBefore(best))) {
                best = candidate;
            }
        }
        return best;
    }
}
