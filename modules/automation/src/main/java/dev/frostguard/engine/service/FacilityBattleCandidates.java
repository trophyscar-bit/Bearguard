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
    /**
     * A resolved battle time outlives that. Candidates change as alliances vote, so a read has to be
     * recent; a time the Task List has settled is one fixed instant that does not drift, and the panel
     * that carries it only opens when an alliance event is close, so six hours could expire it
     * overnight with nothing to replace it before the battle.
     */
    private static final Duration CONFIRMED_FRESH_ENOUGH = Duration.ofHours(24);
    /** Candidate rows written by bg_telemetry's Fortress scan, one per listed time. */
    private static final String CANDIDATE_PREFIX = "FACILITY_BATTLE_CANDIDATE_";
    /** Task List rows written by bg_telemetry, which carry the resolved time once the vote is in. */
    private static final String TASK_LIST_PREFIX = "TASKLIST_";
    private static final String TASK_LIST_BATTLE_LABEL = "fortress battles (";

    private FacilityBattleCandidates() {
    }

    /** One time a facility's battle might start, exactly as the panel printed it. */
    public static final class Candidate {

        private final String facility;
        private final LocalDateTime startUtc;
        private final boolean confirmed;

        private Candidate(String facility, LocalDateTime startUtc, boolean confirmed) {
            this.facility = facility;
            this.startUtc = startUtc;
            this.confirmed = confirmed;
        }

        /**
         * True when the in-game Task List named this time for the facility, meaning the vote has
         * resolved and this is the battle, not one of three possibilities.
         */
        public boolean confirmed() {
            return confirmed;
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

    /**
     * The battle times the in-game Task List has settled, soonest first.
     *
     * <p>Once the sign-up closes and the vote resolves, the Fortress panel stops listing candidates and
     * the Task List lists one "Fortress Battles (&lt;facility&gt;)" row per facility with the real time.
     * On 12 Nov, four facilities each had three candidates and the Task List then gave 08:00 local for
     * all four: 13:00 UTC, one of each facility's candidates.</p>
     *
     * <p>Only a row that carries a real clock time counts. The scanner stores "Ended" as a closed
     * window at midnight of its date heading, the highlighted in-progress row as active with no end,
     * and a row whose clock mode did not read as a whole day, so a finished or running battle never
     * comes back as a start. A row stays usable for 24 hours, not six, because the time it carries is
     * settled, and it is dropped once its battle window has run its {@link #BATTLE_LENGTH}, whatever
     * grace the caller asked for.</p>
     */
    public static List<Candidate> confirmed(LocalDateTime nowUtc, Duration grace) {
        return confirmed(EventScheduleService.obtain().findAll(), nowUtc, grace);
    }

    /**
     * What a joiner should wait on: for each facility the confirmed time when the Task List has one,
     * and its candidates only when it has none.
     *
     * <p>A facility the Task List names at all is settled, whatever state its row is in: a battle
     * that has ended or is running must not bring its losing candidates back, or a joiner would
     * watch 14:00 and 17:00 for a battle that already happened at 13:00.</p>
     */
    public static List<Candidate> schedule(LocalDateTime nowUtc, Duration grace) {
        return schedule(EventScheduleService.obtain().findAll(), nowUtc, grace);
    }

    static List<Candidate> confirmed(List<EventScheduleEntry> entries, LocalDateTime nowUtc, Duration grace) {
        LocalDateTime earliest = nowUtc.minus(grace == null ? Duration.ZERO : grace);
        List<Candidate> confirmed = new ArrayList<>();
        for (EventScheduleEntry entry : entries) {
            String facility = taskListFacility(entry);
            if (facility == null || !isFresh(entry, nowUtc, CONFIRMED_FRESH_ENOUGH)) {
                continue;
            }
            boolean hasRealTime = !entry.isCurrentlyActive()
                    && entry.getInactiveSince() == null
                    && entry.getActiveSince() != null;
            if (!hasRealTime || entry.getActiveSince().isBefore(earliest)) {
                continue;
            }
            // The battle has run its two hours: nothing left to join, however generous the grace.
            if (!entry.getActiveSince().plus(BATTLE_LENGTH).isAfter(nowUtc)) {
                continue;
            }
            confirmed.add(new Candidate(facility, entry.getActiveSince(), true));
        }
        sortByStart(confirmed);
        return confirmed;
    }

    static List<Candidate> schedule(List<EventScheduleEntry> entries, LocalDateTime nowUtc, Duration grace) {
        java.util.Set<String> settled = new java.util.HashSet<>();
        for (EventScheduleEntry entry : entries) {
            String facility = taskListFacility(entry);
            if (facility != null && isFresh(entry, nowUtc, CONFIRMED_FRESH_ENOUGH)) {
                settled.add(facility.toLowerCase());
            }
        }
        List<Candidate> plan = new ArrayList<>(confirmed(entries, nowUtc, grace));
        for (Candidate candidate : upcoming(entries, nowUtc, grace)) {
            if (candidate.facility() == null || !settled.contains(candidate.facility().toLowerCase())) {
                plan.add(candidate);
            }
        }
        sortByStart(plan);
        return plan;
    }

    /** "Fortress Battles (Fortress No. 9)" gives "Fortress No. 9"; any other row gives null. */
    private static String taskListFacility(EventScheduleEntry entry) {
        if (entry.getEventKey() == null || !entry.getEventKey().startsWith(TASK_LIST_PREFIX)
                || entry.getEventLabel() == null) {
            return null;
        }
        String label = entry.getEventLabel().trim();
        if (!label.toLowerCase().startsWith(TASK_LIST_BATTLE_LABEL) || !label.endsWith(")")) {
            return null;
        }
        String facility = label.substring(TASK_LIST_BATTLE_LABEL.length(), label.length() - 1)
                .replaceAll("\s+", " ").trim();
        return facility.isEmpty() ? null : facility;
    }

    private static void sortByStart(List<Candidate> list) {
        list.sort(Comparator.comparing((Candidate candidate) -> candidate.startUtc)
                .thenComparing(candidate -> candidate.facility == null ? "" : candidate.facility));
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
            candidates.add(new Candidate(entry.getEventLabel(), start, false));
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
        return isFresh(entry, nowUtc, FRESH_ENOUGH);
    }

    private static boolean isFresh(EventScheduleEntry entry, LocalDateTime nowUtc, Duration window) {
        LocalDateTime read = entry.getLastScannedAt();
        return read != null && read.isAfter(nowUtc.minus(window));
    }
}
