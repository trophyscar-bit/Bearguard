package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
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
     * One time to work on. A candidate is a vote pick (the sign-up lists three times and the game keeps
     * one battle); a confirmed time is the Task List's real battle, so it is never a lost vote.
     */
    public record Stage(LocalDateTime startUtc, boolean confirmed) {
    }

    /**
     * The stages (UTC), soonest first, including one that began within the window. The calendar's
     * FacilityBattleCandidates.schedule() plugs in here: confirmed times where the Task List has them,
     * candidates only for a facility it has not named. Empty means no time known.
     */
    public interface StartSource {
        List<Stage> stagesUtc(LocalDateTime nowUtc);
    }

    /** How long the battle lasts from its real start: the battle phase is two hours (the in-game rules). */
    static final Duration WINDOW_LENGTH = Duration.ofMinutes(120);

    /**
     * Holding a fort for 30 minutes wins it and ends the battle. From the first live rally that is the
     * rally's march plus the hold, so the session ends then and gathering gets the profile back instead of
     * waiting out the battle's two hours.
     */
    static final Duration HOLD_AFTER_LIVE = Duration.ofMinutes(40);
    /** A confirmed battle with no joinable rally this long after its start is given up, so gathering resumes. */
    static final Duration WAIT_FOR_FIRST_RALLY = Duration.ofMinutes(30);

    private StartSource startSource = nowUtc -> FacilityBattleCandidates
            .schedule(nowUtc, FacilityBattleCandidates.BATTLE_LENGTH).stream()
            .collect(java.util.stream.Collectors.toMap(
                    FacilityBattleCandidates.Candidate::startUtc,
                    FacilityBattleCandidates.Candidate::confirmed,
                    Boolean::logicalOr))
            .entrySet().stream()
            .map(e -> new Stage(e.getKey(), e.getValue()))
            .sorted(java.util.Comparator.comparing(Stage::startUtc)).toList();
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
    private boolean currentConfirmed;
    /** A candidate that was watched and showed no facility rally: it lost the vote, so it is not offered again. */
    private LocalDateTime lostVoteUtc;
    /** Set by the whitelist the first time a signed-up facility rally is seen: the battle is live. */
    private volatile boolean battleLive;
    private volatile LocalDateTime liveSinceUtc;

    /**
     * The framework navigates here before execute() runs, so an idle pass (no candidate time known, or
     * not yet due) must not ask for the world map: it would move the game for nothing.
     */
    @Override
    protected LaunchPoint getRequiredStartLocation() {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        return FacilityJoinWindow.decide(nowUtc, pickStart(nowUtc).map(Stage::startUtc), WINDOW_LENGTH).kind() == FacilityJoinWindow.Kind.RUN
                ? LaunchPoint.WORLD : LaunchPoint.ANY;
    }

    /** The stage to work on: the current one until its window closes, else the next candidate that has not lost the vote. */
    private Optional<Stage> pickStart(LocalDateTime nowUtc) {
        return currentStartUtc != null && nowUtc.isBefore(currentStartUtc.plus(WINDOW_LENGTH))
                ? Optional.of(new Stage(currentStartUtc, currentConfirmed))
                : startSource.stagesUtc(nowUtc).stream()
                        .filter(c -> lostVoteUtc == null || c.startUtc().isAfter(lostVoteUtc))
                        .findFirst();
    }

    @Override
    protected void execute() {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        // A stage already being worked on stays the stage until its window has closed.
        Optional<Stage> picked = pickStart(nowUtc);
        Optional<LocalDateTime> start = picked.map(Stage::startUtc);
        currentStartUtc = start.orElse(null);
        currentConfirmed = picked.map(Stage::confirmed).orElse(false);
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
        // The facilities come from the game's own schedule, never from a typed list: an unknown or stale
        // schedule is empty and joins nothing.
        signedUp = RallyTargetFilter.parseSignedUp(String.join(",", FacilityBattleCandidates
                .schedule(nowUtc, FacilityBattleCandidates.BATTLE_LENGTH).stream()
                .map(FacilityBattleCandidates.Candidate::facility).distinct().toList()));
        if (signedUp.isEmpty()) {
            logWarning(routineLogBearTrapLine(
                    "Facility rally joiner: no facility battle is on the schedule, so no rally will be joined."));
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
        Duration watch = FacilityJoinWindow.PROBE;
        LocalDateTime stage = currentStartUtc;
        boolean confirmed = currentConfirmed;
        LocalDateTime battleEnd = decision.at();
        LocalDateTime probeEnd = FacilityJoinWindow.probeEnd(LocalDateTime.now(ZoneOffset.UTC), stage, WINDOW_LENGTH, watch);
        battleLive = false;
        liveSinceUtc = null;
        LocalDateTime sessionStart = LocalDateTime.now(ZoneOffset.UTC);
        if (!signedUp.isEmpty()
                && Boolean.TRUE.equals(profile.getConfig(ConfigurationKeyEnum.FACILITY_RALLY_JOIN_RECALL_BOOL, Boolean.class))) {
            // Every gatherer comes home when the battle starts, whether or not the target text is calibrated,
            // so every march is free the moment a rally can be joined. Free every march before joining. Autojoin is left as it is: only the Bear Trap switches it off
            // and back on. The gather task waits for the recalled troops to return before it deploys again.
            logInfo(routineLogBearTrapLine("Facility rally joiner: recalling gatherers so every march is free to join."));
            recallGatherTroopsFlow(false);
        }
        while (true) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            // A confirmed time is a real battle: keep watching it to the end rather than give it up as a lost vote.
            LocalDateTime end = sessionEnd(battleLive, liveSinceUtc, confirmed, stage, sessionStart, battleEnd, probeEnd);
            if (!now.isBefore(end)) break;
            checkPreemption();
            handleJoinRallies2(end);
            sleepTask(5_000);
        }
        if (!battleLive) {
            lostVoteUtc = stage;
            currentStartUtc = null;
            saveJoinDiagnostic("facility-no-rally-" + stage.toString().replaceAll("[^0-9]", ""));
            logInfo(routineLogBearTrapLine(confirmed
                    ? "Facility rally joiner: the confirmed battle at " + stage + " UTC showed no joinable facility rally."
                    : "Facility rally joiner: no facility rally at " + stage
                    + " UTC, so that time did not run; moving to the next candidate."));
        } else {
            lostVoteUtc = stage;
            currentStartUtc = null;
        }
        reschedule(LocalDateTime.now().plus(watch.dividedBy(3)));
    }

    /** When this session stops: after the hold once a rally is live, else at the end of the wait for one. */
    static LocalDateTime sessionEnd(boolean live, LocalDateTime liveSinceUtc, boolean confirmed,
                                    LocalDateTime stageUtc, LocalDateTime sessionStartUtc,
                                    LocalDateTime battleEnd, LocalDateTime probeEnd) {
        LocalDateTime end;
        if (live && liveSinceUtc != null) {
            end = liveSinceUtc.plus(HOLD_AFTER_LIVE);
        } else if (confirmed) {
            // Counted from when the session began if that is later than the start, so a late wake or a
            // relaunch mid-battle still gets its full wait instead of giving up at once.
            end = (sessionStartUtc.isAfter(stageUtc) ? sessionStartUtc : stageUtc).plus(WAIT_FOR_FIRST_RALLY);
        } else {
            end = probeEnd;
        }
        return end.isBefore(battleEnd) ? end : battleEnd;
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
        // Any Fortress or Stronghold rally is worth joining while the schedule has a battle: the alliance
        // fights all of them, so the number does not matter. An empty schedule joins nothing.
        boolean join = !signedUp.isEmpty() && RallyTargetFilter.isFacilityRally(readScreenText(area.get()));
        if (join && !battleLive) {
            battleLive = true;
            liveSinceUtc = LocalDateTime.now(ZoneOffset.UTC);
            logInfo(routineLogBearTrapLine("Facility rally joiner: a facility rally is live; joining it, then handing the profile back after the hold."));
        }
        logDebug(routineLogBearTrapLine("Facility rally joiner: "
                + (join ? "joining a facility rally." : "skipping a rally that is not a facility rally.")));
        return join;
    }

    /** Whether {@link #targetTextArea(PointData)} knows where to read. */
    boolean targetCalibrated() {
        return true;
    }

    /**
     * During a facility battle the World map shows a red crossed-swords War icon with a countdown, not
     * the Bear Trap's skull. It pulses, so it matches at about 75 against a noise floor near 40 on
     * saved frames; the threshold sits between them. The skull stays as a fallback.
     */
    @Override
    ImageSearchResultData locateWarButton() {
        ImageSearchResultData battle = templateSearchHelper.locatePattern(
                dev.frostguard.api.configs.TemplatesEnum.GAME_HOME_WAR_BATTLE,
                dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig.builder()
                        .withThreshold(65)
                        .withMaxAttempts(3)
                        .build());
        return battle != null && battle.isFound() ? battle : super.locateWarButton();
    }

    /**
     * Where the facility name is for the rally whose join icon is here: the shield row under the leader's
     * troops, in dark text on the card's pink panel. Measured on a live War list (720x1280): for a
     * join icon at (641, 390) the row's text spans x 290-476, y 443-470, and a second card read the same
     * offsets. The row is clear of the leader's name and the avatars.
     */
    Optional<dev.frostguard.api.domain.AreaData> targetTextArea(PointData plusIcon) {
        return Optional.of(targetTextAreaFor(plusIcon));
    }

    static dev.frostguard.api.domain.AreaData targetTextAreaFor(PointData plusIcon) {
        return new dev.frostguard.api.domain.AreaData(
                new PointData(plusIcon.getX() - 365, plusIcon.getY() + 48),
                new PointData(plusIcon.getX() - 140, plusIcon.getY() + 88));
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
