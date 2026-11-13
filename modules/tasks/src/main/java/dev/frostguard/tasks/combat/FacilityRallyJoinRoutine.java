package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.FacilityBattleCandidates;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Joins Fortress and Stronghold rallies, and only those. It never hosts. It has no schedule of its
 * own: when it runs comes from the calendar's facility start, and a start the calendar cannot give
 * means "ask again later", never "now".
 *
 * <p>It is the Bear Trap's join session and nothing else: the same War-screen loop, scroller, "all
 * marches busy" wait and end time. The one difference is {@link #rallyAccepted(PointData)}, a whitelist
 * that joins a rally only when its target text names a facility.</p>
 *
 * <p>The target text's position on the War list is not calibrated yet (no frame of it exists), so the
 * whitelist fails closed: until {@link #targetTextArea(PointData)} knows where to read, nothing is
 * joined.</p>
 */
public class FacilityRallyJoinRoutine extends BearTrapRoutine {

    /**
     * The candidate start times (UTC), soonest first, including one that began within the window. They
     * are only candidates: the sign-up picks three times and the game keeps one battle. The calendar's
     * FacilityBattleCandidates plugs in here.
     */
    public interface StartSource {
        List<LocalDateTime> candidatesUtc(LocalDateTime nowUtc);
    }

    /** How long the battle lasts from its real start: the battle phase is two hours (the in-game rules). */
    static final Duration WINDOW_LENGTH = Duration.ofMinutes(120);

    private StartSource startSource = nowUtc -> FacilityBattleCandidates
            .upcoming(nowUtc, FacilityBattleCandidates.BATTLE_LENGTH).stream()
            .map(FacilityBattleCandidates.Candidate::startUtc).distinct().sorted().toList();
    private boolean warnedUncalibrated;
    /** War-list frames saved while uncalibrated, so the first live rally leaves frames to calibrate from. */
    private int calibrationFrames;
    private static final int MAX_CALIBRATION_FRAMES = 12;
    /** The facilities the alliance signed up for, "Fortress 12, Stronghold 2". Empty joins nothing. */
    private Set<RallyTargetFilter.Facility> signedUp = Set.of();

    public FacilityRallyJoinRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    /** For the calendar wiring and for tests. */
    public void setStartSource(StartSource source) {
        this.startSource = source;
    }

    /**
     * The start the routine is working on, kept so a wake-up after the stage has begun still joins it
     * (the calendar gives only stages that start after "now").
     */
    private LocalDateTime currentStartUtc;
    /** A candidate that was watched and showed no facility rally: it lost the vote, so it is not offered again. */
    private LocalDateTime lostVoteUtc;
    /** Set by the whitelist the first time a signed-up facility rally is seen: the battle is live. */
    private volatile boolean battleLive;

    /**
     * The framework navigates here before execute() runs, so an idle pass (no candidate time known, or
     * not yet due) must not ask for the world map: it would move the game for nothing.
     */
    @Override
    protected LaunchPoint getRequiredStartLocation() {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        return FacilityJoinWindow.decide(nowUtc, pickStart(nowUtc), WINDOW_LENGTH).kind() == FacilityJoinWindow.Kind.RUN
                ? LaunchPoint.WORLD : LaunchPoint.ANY;
    }

    /** The stage to work on: the current one until its window closes, else the next candidate that has not lost the vote. */
    private Optional<LocalDateTime> pickStart(LocalDateTime nowUtc) {
        return currentStartUtc != null && nowUtc.isBefore(currentStartUtc.plus(WINDOW_LENGTH))
                ? Optional.of(currentStartUtc)
                : startSource.candidatesUtc(nowUtc).stream()
                        .filter(c -> lostVoteUtc == null || c.isAfter(lostVoteUtc))
                        .findFirst();
    }

    @Override
    protected void execute() {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        // A stage already being worked on stays the stage until its window has closed.
        Optional<LocalDateTime> start = pickStart(nowUtc);
        currentStartUtc = start.orElse(null);
        FacilityJoinWindow.Decision decision = FacilityJoinWindow.decide(nowUtc, start, WINDOW_LENGTH);

        if (decision.kind() != FacilityJoinWindow.Kind.RUN) {
            LocalDateTime again = decision.at().atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
            logInfo(routineLogBearTrapLine(decision.kind() == FacilityJoinWindow.Kind.UNKNOWN
                    ? "Facility rally joiner: no facility battle time known; asking again at " + again
                    : "Facility rally joiner: waiting for the facility battle; starting at " + again));
            reschedule(again);
            return;
        }

        // The Bear Trap's session, set up for this task's squads. Never hosts: joinRally only.
        joinFlags = flags(ConfigurationKeyEnum.FACILITY_RALLY_JOIN_FLAG_STRING);
        troopFlags = new ArrayList<>();
        marchTally = new BearMarchTally();
        rallyTargets = new BearRallyTargets();
        joinRally = true;
        signedUp = RallyTargetFilter.parseSignedUp(
                profile.getConfig(ConfigurationKeyEnum.FACILITY_RALLY_JOIN_TARGETS_STRING, String.class));
        if (signedUp.isEmpty()) {
            // An empty list means "the alliance's sign-up": an unknown or stale sign-up stays empty and joins nothing.
            signedUp = RallyTargetFilter.parseSignedUp(String.join(",", FacilityBattleCandidates.facilities(
                    nowUtc, FacilityBattleCandidates.BATTLE_LENGTH)));
        }
        if (signedUp.isEmpty()) {
            logWarning(routineLogBearTrapLine(
                    "Facility rally joiner: no signed-up facilities are set, so no rally will be joined."));
        } else {
            logInfo(routineLogBearTrapLine("Facility rally joiner: only these facilities: " + signedUp));
        }
        sharedEmulator = isSharedEmulatorProfile();
        logInfo(routineLogBearTrapLine("Facility rally joiner: joining facility rallies with flags " + joinFlags
                + " until " + decision.at() + " UTC"));
        // One War-screen session ends when no rally shows for a while; the window is longer than that,
        // so go back in until the window closes. The stage is not asked about again, because the
        // calendar only returns stages that have not started.
        // Watch the candidate for a live rally first; once one is seen the battle is live and the
        // window runs to the battle's end. No rally in the probe means this time lost the vote.
        Integer watchMinutes = profile.getConfig(ConfigurationKeyEnum.FACILITY_RALLY_JOIN_WATCH_MINUTES_INT, Integer.class);
        Duration watch = watchMinutes == null || watchMinutes < 5 || watchMinutes > 60
                ? FacilityJoinWindow.PROBE : Duration.ofMinutes(watchMinutes);
        LocalDateTime stage = currentStartUtc;
        LocalDateTime battleEnd = decision.at();
        LocalDateTime probeEnd = FacilityJoinWindow.probeEnd(LocalDateTime.now(ZoneOffset.UTC), stage, WINDOW_LENGTH, watch);
        battleLive = false;
        if (targetCalibrated() && !signedUp.isEmpty()
                && Boolean.TRUE.equals(profile.getConfig(ConfigurationKeyEnum.FACILITY_RALLY_JOIN_RECALL_BOOL, Boolean.class))) {
            // Only when a rally can actually be joined: pulling marches home for nothing costs gathering.
            // Free every march before joining. Autojoin is left as it is: only the Bear Trap switches it off
            // and back on. The gather task waits for the recalled troops to return before it deploys again.
            logInfo(routineLogBearTrapLine("Facility rally joiner: recalling gatherers so every march is free to join."));
            recallGatherTroopsFlow(false);
        }
        while (true) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime end = battleLive ? battleEnd : probeEnd;
            if (!now.isBefore(end)) break;
            checkPreemption();
            handleJoinRallies2(end);
            sleepTask(5_000);
        }
        if (!battleLive) {
            lostVoteUtc = stage;
            currentStartUtc = null;
            saveJoinDiagnostic("facility-no-rally-" + stage.toString().replaceAll("[^0-9]", ""));
            logInfo(routineLogBearTrapLine("Facility rally joiner: no facility rally at " + stage
                    + " UTC, so that time did not run; moving to the next candidate."));
        } else {
            lostVoteUtc = stage;
            currentStartUtc = null;
        }
        reschedule(LocalDateTime.now().plus(watch.dividedBy(3)));
    }

    @Override
    boolean rallyAccepted(PointData plusIcon) {
        Optional<dev.frostguard.api.domain.AreaData> area = targetTextArea(plusIcon);
        if (area.isEmpty()) {
            if (!warnedUncalibrated) {
                warnedUncalibrated = true;
                logWarning(routineLogBearTrapLine(
                        "Facility rally joiner: the rally target text area is not calibrated, so no rally is joined."));
            }
            if (calibrationFrames < MAX_CALIBRATION_FRAMES) {
                saveJoinDiagnostic("facility-war-list-" + (++calibrationFrames));
            }
            return false;
        }
        RallyTargetFilter.Facility facility = RallyTargetFilter.facilityOf(readScreenText(area.get()));
        boolean join = facility != null && signedUp.contains(facility);
        if (join && !battleLive) {
            battleLive = true;
            logInfo(routineLogBearTrapLine("Facility rally joiner: " + facility + " is live; joining until its battle ends."));
        }
        logDebug(routineLogBearTrapLine("Facility rally joiner: "
                + (facility == null ? "skipping a rally that is not a numbered facility."
                : join ? "joining " + facility + "." : "skipping " + facility + ", not signed up.")));
        return join;
    }

    /** Whether {@link #targetTextArea(PointData)} knows where to read; false until measured on a real frame. */
    boolean targetCalibrated() {
        return false;
    }

    /**
     * Where the target text is for the rally whose join icon is here. Empty until measured on a real
     * War-list frame: the whitelist then joins nothing.
     */
    Optional<dev.frostguard.api.domain.AreaData> targetTextArea(PointData plusIcon) {
        return Optional.empty();
    }

    private List<Integer> flags(ConfigurationKeyEnum key) {
        List<Integer> out = new ArrayList<>();
        String csv = profile.getConfig(key, String.class);
        if (csv != null) {
            for (String part : csv.split(",")) {
                try {
                    out.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException ignored) {
                    // a blank or stray entry is not a flag
                }
            }
        }
        return out;
    }
}
