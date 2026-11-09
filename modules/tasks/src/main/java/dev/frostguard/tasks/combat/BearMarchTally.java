package dev.frostguard.tasks.combat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Running tally of which hero squads are away during a Bear Trap event. It only saves taps: a squad
 * the tally believes is home is still confirmed on the formation screen before it is deployed.
 */
final class BearMarchTally {

    /** A squad that just joined a rally cannot be back this soon, so it is not worth a tap. */
    static final Duration SENT_AWAY = Duration.ofSeconds(60);

    /** A squad whose hero did not load is out now, but could land at any moment. */
    static final Duration SEEN_AWAY = Duration.ofSeconds(15);

    private final Map<Integer, Instant> awayUntil = new HashMap<>();

    void markSent(int squad, Instant now) {
        awayUntil.put(squad, now.plus(SENT_AWAY));
    }

    void markSeenAway(int squad, Instant now) {
        awayUntil.put(squad, now.plus(SEEN_AWAY));
    }

    /** The squads worth trying now, keeping the configured priority order. */
    List<Integer> likelyHome(List<Integer> squads, Instant now) {
        return squads.stream()
                .filter(squad -> {
                    Instant until = awayUntil.get(squad);
                    return until == null || !now.isBefore(until);
                })
                .toList();
    }
}
