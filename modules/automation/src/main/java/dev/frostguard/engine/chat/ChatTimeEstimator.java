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

    /** Estimates never reach further back than this from the anchor they were counted from. */
    static final Duration MAX_REACH = Duration.ofHours(48);

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
            for (int k = 0; k < run; k++) {
                placed.add(chronological.get(i + k).message()
                        .withCapturedAt(timeFor(k, run, before, after, now)));
            }
            i = end + 1;
        }
        return placed;
    }

    private static Instant timeFor(int index, int run, Instant before, Instant after, Instant now) {
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
        Duration back = FALLBACK_SPACING.multipliedBy(run - index);
        if (back.compareTo(MAX_REACH) > 0) {
            back = MAX_REACH;
        }
        return top.minus(back);
    }
}
