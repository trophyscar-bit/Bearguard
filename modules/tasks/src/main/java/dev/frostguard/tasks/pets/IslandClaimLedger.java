package dev.frostguard.tasks.pets;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.frostguard.api.domain.PointData;

/**
 * Remembers which My Island claim bubbles were tapped in one run and decides what each new scan
 * should do with the bubbles it finds.
 *
 * <p>A bubble found again where one was already tapped is a real, claimable Life Essence bubble
 * whose tap did not take. Live frames from 2026-11-13 show it happening to either bubble, never
 * to a decoy: at 12:25 the station bubble at the top of the screen vanished after its tap and was
 * back a second later, just as the tree's claim landed (+581, the only credit that run); at 14:00
 * the station cleared and the tree bubble stayed. The very next hourly run claims the leftover
 * with a single tap, so the bubble itself is fine; the tap was lost.
 *
 * <p>So a leftover earns exactly one more tap, once the reward from the earlier taps has had time
 * to land, and is then given up on for this run. That keeps the routine bounded: no bubble is ever
 * tapped more than {@link #MAX_TAPS_PER_BADGE} times, and a given-up bubble is reported once and
 * then ignored instead of being re-reported on every remaining scan.
 *
 * <p>Every tap still goes through the routine's own gates (island screen proven, crystal shape,
 * held still across two captures), so a retap can only ever land on the same kind of bubble the
 * first tap did.
 */
final class IslandClaimLedger {

    /** A claimed bubble disappears, so one found within this distance of a tapped one is that same bubble. */
    static final int SAME_BADGE_RADIUS = 40;

    /** The first tap and one retry. */
    static final int MAX_TAPS_PER_BADGE = 2;

    /**
     * How long after a tap a bubble must still be showing before it is retried. Long enough for the
     * reward flight of that claim and the one tapped after it to land (the 12:25 bubble came back
     * about two seconds after its tap), so the retry is not itself sent into an animation.
     */
    static final long RETAP_MIN_AGE_MILLIS = 2_000;

    /** What to do with the bubbles one scan found. */
    record Plan(List<PointData> firstTaps, List<PointData> retaps, List<PointData> newlyGivenUp,
            List<PointData> waiting) {

        boolean tapsAnything() {
            return !firstTaps.isEmpty() || !retaps.isEmpty();
        }
    }

    private static final class Entry {
        private final PointData at;
        private int taps;
        private long lastTapMillis;
        private boolean givenUp;

        private Entry(PointData at) {
            this.at = at;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /**
     * Sorts a scan's bubbles into first taps, retries, bubbles to wait on, and bubbles to give up.
     * Giving up is recorded here so it is reported once; taps are recorded by {@link #recordTap}
     * only after they are actually sent.
     */
    Plan plan(List<PointData> badges, long nowMillis) {
        List<PointData> firstTaps = new ArrayList<>();
        List<PointData> retaps = new ArrayList<>();
        List<PointData> newlyGivenUp = new ArrayList<>();
        List<PointData> waiting = new ArrayList<>();

        for (PointData badge : badges) {
            Optional<Entry> known = find(badge);
            if (known.isEmpty()) {
                firstTaps.add(badge);
                continue;
            }
            Entry entry = known.get();
            if (entry.givenUp) {
                continue;
            }
            if (entry.taps >= MAX_TAPS_PER_BADGE) {
                entry.givenUp = true;
                newlyGivenUp.add(badge);
                continue;
            }
            if (nowMillis - entry.lastTapMillis < RETAP_MIN_AGE_MILLIS) {
                waiting.add(badge);
                continue;
            }
            retaps.add(badge);
        }
        return new Plan(List.copyOf(firstTaps), List.copyOf(retaps), List.copyOf(newlyGivenUp),
                List.copyOf(waiting));
    }

    /** Records a tap that was sent, whether the first one on this bubble or its retry. */
    void recordTap(PointData badge, long nowMillis) {
        Entry entry = find(badge).orElseGet(() -> {
            Entry created = new Entry(badge);
            entries.add(created);
            return created;
        });
        entry.taps++;
        entry.lastTapMillis = nowMillis;
    }

    /** Bubbles tapped this run that did not end up given up on. */
    int claimedCount() {
        return (int) entries.stream().filter(entry -> entry.taps > 0 && !entry.givenUp).count();
    }

    List<PointData> tapped() {
        return entries.stream().map(entry -> entry.at).toList();
    }

    List<PointData> givenUp() {
        return entries.stream().filter(entry -> entry.givenUp).map(entry -> entry.at).toList();
    }

    private Optional<Entry> find(PointData badge) {
        return entries.stream()
                .filter(entry -> entry.at.manhattanDistanceTo(badge) <= SAME_BADGE_RADIUS)
                .findFirst();
    }
}
