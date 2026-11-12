package dev.frostguard.engine.service;

import dev.frostguard.data.entity.EventScheduleEntry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The battle times an alliance signed a facility up for, as candidates rather than as a schedule.
 *
 * <p>Alliance - Battle - Fortress - Signed up lists three times under each facility ("UTC 2026/11/13
 * 13:00"). They are not three battles: a sign-up picks three of five offered times, and the time
 * most alliances picked becomes the one battle for that facility. So each row here is a time a
 * battle <em>might</em> start, and only one of a facility's candidates turns out to be real.</p>
 *
 * <p>Nothing in the game says in advance which one won, so nothing here pretends to. A caller waits
 * for a candidate and then checks whether a battle is actually live before acting -- the War list
 * and the facility's own panel say that, this class cannot. What it does add is the length: the
 * battle phase runs {@link #BATTLE_LENGTH} from its real start, which is the window a joiner has
 * once it has confirmed one.</p>
 *
 * <p>The times come from that panel rather than from the Task List, which carries times too but
 * hangs them off a date heading that does not always OCR; the Fortress panel prints the date on
 * every line. One source, not two.</p>
 */
public final class FacilityBattleCandidates {

    /** The battle phase, from the in-game rules: hold 30 minutes continuously for full control. */
    public static final Duration BATTLE_LENGTH = Duration.ofHours(2);

    /** Past this, the Fortress read is too old to plan a battle from. */
    private static final Duration FRESH_ENOUGH = Duration.ofHours(6);
    /** Candidate rows written by bg_telemetry's Fortress scan, one per listed time. */
    private static final String CANDIDATE_PREFIX = "FACILITY_BATTLE_CANDIDATE_";

    private FacilityBattleCandidates() {
    }

    /** One time a facility's battle might start, exactly as the panel printed it. */
    public static final class Candidate {

        private final String facility;
        private final LocalDateTime startUtc;

        private Candidate(String facility, LocalDateTime startUtc) {
            this.facility = facility;
            this.startUtc = startUtc;
        }

        /** The card's own title, for example "Fortress No. 9" or "Stronghold No. 2". */
        public String facility() {
            return facility;
        }

        /** The candidate instant in UTC, as stored. */
        public LocalDateTime startUtc() {
            return startUtc;
        }

        /** The same instant in the machine's zone, which is what the scheduler compares against. */
        public LocalDateTime startLocal() {
            return startUtc.atOffset(ZoneOffset.UTC)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime();
        }

        /** When a battle that did start at this candidate would end. */
        public LocalDateTime endsByUtc() {
            return startUtc.plus(BATTLE_LENGTH);
        }
    }

    /** Every candidate still worth waiting for, soonest first. Empty when the panel cannot say. */
    public static List<Candidate> upcoming(LocalDateTime nowUtc, Duration grace) {
        return upcoming(EventScheduleService.obtain().findAll(), nowUtc, grace);
    }

    /**
     * The soonest candidate still worth waiting for, or empty.
     *
     * <p>{@code grace} keeps a candidate that has already passed: a joiner waking a minute late
     * would otherwise skip the time it was waiting for and aim hours ahead. The caller decides how
     * long a battle stays worth joining -- {@link #BATTLE_LENGTH} is the game's own answer.</p>
     *
     * <p>Empty means no time known: nothing signed up, everything past, or a read older than six
     * hours. It is not an error, and it must not be turned into "now".</p>
     */
    public static Optional<Candidate> next(LocalDateTime nowUtc, Duration grace) {
        return upcoming(nowUtc, grace).stream().findFirst();
    }

    /** As {@link #next(LocalDateTime, Duration)} with no grace: only candidates still ahead. */
    public static Optional<Candidate> next(LocalDateTime nowUtc) {
        return next(nowUtc, Duration.ZERO);
    }

    /**
     * The facilities that still have a candidate time ahead, by their card titles.
     *
     * <p>For a caller whose own whitelist is empty: the alliance signed these up, so these are the
     * ones worth watching. An empty set means the panel cannot say -- nothing signed up, everything
     * past, or a stale read -- and must be read as "none known", never as "all of them".</p>
     */
    public static java.util.Set<String> facilities(LocalDateTime nowUtc, Duration grace) {
        return facilities(EventScheduleService.obtain().findAll(), nowUtc, grace);
    }

    static java.util.Set<String> facilities(List<EventScheduleEntry> entries, LocalDateTime nowUtc,
                                            Duration grace) {
        java.util.Set<String> labels = new java.util.LinkedHashSet<>();
        for (Candidate candidate : upcoming(entries, nowUtc, grace)) {
            if (candidate.facility() != null && !candidate.facility().isBlank()) {
                labels.add(candidate.facility());
            }
        }
        return labels;
    }

    static List<Candidate> upcoming(List<EventScheduleEntry> entries, LocalDateTime nowUtc, Duration grace) {
        LocalDateTime earliest = nowUtc.minus(grace == null ? Duration.ZERO : grace);
        List<Candidate> candidates = new ArrayList<>();
        for (EventScheduleEntry entry : entries) {
            if (!isCandidateRow(entry) || !isFresh(entry, nowUtc)) {
                continue;
            }
            LocalDateTime start = entry.getActiveSince();
            if (start == null || start.isBefore(earliest)) {
                continue;
            }
            candidates.add(new Candidate(entry.getEventLabel(), start));
        }
        candidates.sort(Comparator.comparing((Candidate candidate) -> candidate.startUtc)
                .thenComparing(candidate -> candidate.facility == null ? "" : candidate.facility));
        return candidates;
    }

    static Optional<Candidate> next(List<EventScheduleEntry> entries, LocalDateTime nowUtc) {
        return next(entries, nowUtc, Duration.ZERO);
    }

    static Optional<Candidate> next(List<EventScheduleEntry> entries, LocalDateTime nowUtc, Duration grace) {
        return upcoming(entries, nowUtc, grace).stream().findFirst();
    }

    private static boolean isCandidateRow(EventScheduleEntry entry) {
        return entry.getEventKey() != null && entry.getEventKey().startsWith(CANDIDATE_PREFIX);
    }

    private static boolean isFresh(EventScheduleEntry entry, LocalDateTime nowUtc) {
        LocalDateTime read = entry.getLastScannedAt();
        return read != null && read.isAfter(nowUtc.minus(FRESH_ENOUGH));
    }
}
