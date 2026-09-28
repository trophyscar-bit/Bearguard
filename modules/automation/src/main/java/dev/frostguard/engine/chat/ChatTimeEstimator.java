package dev.frostguard.engine.chat;

import dev.frostguard.api.chat.ChatMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Gives messages recovered after the fact a time to be filed under.
 *
 * <p>A message in the game carries no time of its own. The transcript's time is when the pass
 * happened to read it, which is right for a message read minutes after it was said and wrong for
 * one recovered hours later: stamped with the moment of recovery it lands in the wrong day's
 * file, and a digest built from that day would be missing it while the next day's contained it.
 *
 * <p>What is known is order. Read back through a channel the messages come out in the order they
 * were said, and some of them are already stored with a time. Those are anchors, and a run of
 * recovered messages between two of them is spread evenly across the gap. The estimate is only as
 * good as the anchors -- a stored time is when a pass read the message, a little after it was
 * said -- so the result can run late by up to one pass interval. That is well inside what a daily
 * digest can tell apart, and the lines are flagged as estimates rather than presented as fact.
 */
public final class ChatTimeEstimator {

    /**
     * How far apart to assume messages were where there is no anchor to measure from.
     *
     * <p>Only used for a run at the far old end of what was read, with nothing stored before it. A
     * measured spacing would be better, but stored times are coarse -- a pass stamps everything it
     * reads with roughly the same instant -- so the gaps between them measure the passes, not the
     * conversation.
     */
    static final Duration FALLBACK_SPACING = Duration.ofSeconds(30);

    /**
     * Estimates never reach further back than this from the anchor they were counted from.
     *
     * <p>Seven days rather than two: two capped a long reconcile of a multi-day outage at the last
     * forty-eight hours, which put every message it recovered on the newest two days.
     */
    static final Duration MAX_REACH = Duration.ofDays(7);

    /**
     * Typical gap between messages, measured 2026-09-28 from stored days: about 990 Alliance and
     * 556 World messages a day.
     *
     * <p>A fixed thirty seconds treated every message as a burst. A run of two thousand recovered
     * messages counted back at that rate spans seventeen hours, so a three-day walk landed entirely
     * on the newest afternoon. These are the channel's real average pace, quiet nights included.
     */
    public static final Duration ALLIANCE_SPACING = Duration.ofSeconds(87);
    public static final Duration WORLD_SPACING = Duration.ofSeconds(155);

    /** The spacing for a channel, or the fallback when it is not one of these. */
    public static Duration spacingFor(String channel) {
        return "alliance".equals(channel) ? ALLIANCE_SPACING
                : "world".equals(channel) ? WORLD_SPACING : FALLBACK_SPACING;
    }

    private ChatTimeEstimator() {
    }

    /**
     * One message in the order it was said.
     *
     * @param message  what was read
     * @param storedAt the time the transcript already holds for it, or null when it is new
     */
    public record Candidate(ChatMessage message, Instant storedAt) {
    }

    /**
     * Places the messages the transcript does not hold.
     *
     * @param chronological every message read, oldest first, known and unknown together
     * @param now           the moment of this pass, the upper bound for anything newer than every
     *                      stored message
     * @return only the unknown messages, oldest first, each filed under an estimated time
     */
    public static List<ChatMessage> place(List<Candidate> chronological, Instant now) {
        return place(chronological, now, FALLBACK_SPACING, null);
    }

    /**
     * Places the messages the transcript does not hold.
     *
     * @param spacing    how far apart this channel's messages typically fall, used to count a run
     *                   back from the oldest stored message it reached
     * @param lowerBound the newest time the transcript holds that is older than everything this
     *                   walk reached, or null when it holds nothing that old. Nothing recovered can
     *                   be older than it, and a run that would not fit between it and the oldest
     *                   anchor is spread across that whole gap instead.
     */
    public static List<ChatMessage> place(List<Candidate> chronological, Instant now,
                                          Duration spacing, Instant lowerBound) {
        int size = chronological.size();
        Instant[] anchor = new Instant[size];
        Instant running = null;
        for (int i = 0; i < size; i++) {
            Instant stored = chronological.get(i).storedAt();
            if (stored == null) {
                continue;
            }
            // Stored times are not guaranteed to rise with the conversation: an earlier
            // reconcile may have filed an estimate later than a neighbour's real reading. Taken
            // as given they would put a message before the one it answers, so the sequence is
            // held non-decreasing.
            running = running == null || stored.isAfter(running) ? stored : running;
            anchor[i] = running;
        }

        List<ChatMessage> placed = new ArrayList<>();
        int i = 0;
        while (i < size) {
            if (anchor[i] != null) {
                i++;
                continue;
            }
            int end = i;
            while (end + 1 < size && anchor[end + 1] == null) {
                end++;
            }
            int run = end - i + 1;
            Instant before = i > 0 ? anchor[i - 1] : null;
            Instant after = end + 1 < size ? anchor[end + 1] : null;
            // Only a run at the very old end has nothing before it in this walk. The transcript
            // may still hold something older that the walk never reached, and that is a bound.
            Instant floor = before != null ? null : lowerBound;
            for (int k = 0; k < run; k++) {
                placed.add(chronological.get(i + k).message()
                        .withCapturedAt(timeFor(k, run, before, after, now, spacing, floor)));
            }
            i = end + 1;
        }
        return placed;
    }

    private static Instant timeFor(int index, int run, Instant before, Instant after, Instant now,
                                   Duration spacing, Instant floor) {
        if (before != null) {
            // Nothing stored after this run means it is the newest thing said, bounded by now.
            Instant upper = after != null ? after : now;
            if (upper.isBefore(before)) {
                upper = before;
            }
            Duration span = Duration.between(before, upper);
            return before.plus(span.multipliedBy(index + 1).dividedBy(run + 1));
        }
        // Nothing stored before this run: it reaches back past everything the transcript holds,
        // so it is counted backwards from what is known -- the first stored message, or now.
        Instant top = after != null ? after : now;
        if (floor != null && floor.isBefore(top)
                && spacing.multipliedBy(run).compareTo(Duration.between(floor, top)) >= 0) {
            // At the channel's usual rate this run would reach back past the newest thing the
            // transcript holds from before it, so it cannot all lie after that. It is the whole
            // hole, and is spread across it.
            return floor.plus(Duration.between(floor, top).multipliedBy(index + 1).dividedBy(run + 1));
        }
        Duration back = spacing.multipliedBy(run - index);
        if (back.compareTo(MAX_REACH) > 0) {
            back = MAX_REACH;
        }
        return top.minus(back);
    }
}
