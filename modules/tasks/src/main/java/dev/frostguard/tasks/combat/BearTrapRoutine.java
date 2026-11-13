package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.FormationSlots;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.helper.BearTrapHelper;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.BearTrapParticipationSchedule;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.service.BotOcrEngine;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.RegexNumberParser;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.*;
import static dev.frostguard.api.configs.TemplatesEnum.*;

public class BearTrapRoutine extends DelayedTask {

private final AtomicBoolean ownRallyActive = new AtomicBoolean(false);

private ScheduledExecutorService rallyScheduler;

private ScheduledFuture<?> rallyResetTask;

List<Integer> joinFlags = new ArrayList<>();

List<Integer> troopFlags = new ArrayList<>();

private ResilientOcrExecutor<Duration> durationHelper;

BearMarchTally marchTally = new BearMarchTally();

BearRallyTargets rallyTargets = new BearRallyTargets();

private final Set<String> savedJoinDiagnostics = new HashSet<>();

private static final int MAX_MARCH_HEROES_VALUE = 3;

private static final int JOIN_SESSION_LIMIT_SECONDS_VALUE = 20;

private static final int JOIN_NO_RALLY_REENTRY_SECONDS_VALUE = 5;

private static final int JOIN_SCAN_IDLE_MS_VALUE = 250;

private static final int JOIN_WAR_SCREEN_SETTLE_MS_VALUE = 400;

private static final int JOIN_DEPLOY_APPEAR_ATTEMPTS_VALUE = 4;

private static final long JOIN_DEPLOY_SEARCH_DELAY_MS_VALUE = 100L;

private static final int JOIN_DEPLOY_CLOSE_POLLS_VALUE = 6;

private static final int JOIN_DEPLOY_CLOSE_POLL_MS_VALUE = 250;

private static final int JOIN_PLUS_ICON_MAX_RESULTS_VALUE = 12;

private static final int JOIN_FREE_MARCH_OCR_ATTEMPTS_VALUE = 2;

private static final AreaData JOIN_RALLY_LIST_AREA_VALUE =
            new AreaData(new PointData(0, 120), new PointData(720, 1280));

private static final int TRAP_DURATION_MINUTES_VALUE = 30;

private static final int TRAP_ACTIVATION_OFFSET_MINUTES_VALUE = 30;

private static final int STATUS_LOG_INTERVAL_VALUE = 10;

private static final int OWN_RALLY_MIN_REMAINING_SECONDS_VALUE = 360;
// A failed own-rally attempt waits this long before the next, and the routine stops after this many.
private static final int OWN_RALLY_RETRY_DELAY_SECONDS_VALUE = 20;
private static final int OWN_RALLY_MAX_FAILURES_VALUE = 10;
private static final AreaData WHOLE_MAP_TEXT_AREA = new AreaData(new PointData(0, 150), new PointData(720, 1100));

private static final int RALLY_DURATION_BASE_MINUTES_VALUE = 5;

private static final int RALLY_DURATION_BUFFER_SECONDS_VALUE = 3;

private static final int RALLY_RETURN_BUFFER_MINUTES_VALUE = 5;

private static final int MAX_GATHER_RECALL_ATTEMPTS_LIMIT = 120;

private static final int MARCH_TIME_OCR_MAX_RETRIES_MS = 5;

private static final int TEMPLATE_SEARCH_RETRIES_VALUE = 3;

private static final int TEMPLATE_SEARCH_RETRIES_EXTENDED_VALUE = 5;

private static final int TEMPLATE_SEARCH_RETRIES_MAX_VALUE = 10;

private static final PointData ALLIANCE_BUTTON_TL_VALUE = new PointData(493, 1187);

private static final PointData ALLIANCE_BUTTON_BR_VALUE = new PointData(561, 1240);

private static final PointData SPECIAL_BUILDINGS_BUTTON_TL_VALUE = new PointData(460, 110);

private static final PointData SPECIAL_BUILDINGS_BUTTON_BR_VALUE = new PointData(560, 130);

private static final PointData BEAR_TRAP_1_GO_BUTTON_TL_VALUE = new PointData(570, 350);

private static final PointData BEAR_TRAP_1_GO_BUTTON_BR_VALUE = new PointData(620, 370);

// Measured on a live Special Buildings frame (2026-11-09): Trap 2's Go sits one row, 182 px, below Trap 1's.
private static final PointData BEAR_TRAP_2_GO_BUTTON_TL_VALUE = new PointData(570, 532);

private static final PointData BEAR_TRAP_2_GO_BUTTON_BR_VALUE = new PointData(620, 552);



private static final OcrSettingsData SPARSE_TEXT_OCR = OcrSettingsData.builder()
            .textLayout(OcrSettingsData.TextLayout.SPARSE).build();
// White coordinates drawn over artwork (Special Buildings rows, the Raging Bear popup).
private static final OcrSettingsData WHITE_TEXT_OCR = OcrSettingsData.builder()
            .textLayout(OcrSettingsData.TextLayout.SPARSE)
            .isolateForeground(true).targetColor(java.awt.Color.WHITE).build();

private static final PointData BEAR_CENTER_POINT_VALUE = new PointData(370, 507);
// Where to tap for the bear when the first tap opens nothing: the bear's body and a little around it.
private static final List<PointData> BEAR_TAP_POINTS = List.of(BEAR_CENTER_POINT_VALUE,
        new PointData(395, 470), new PointData(340, 525), new PointData(430, 530));


private static final PointData AUTOJOIN_BUTTON_TL_VALUE = new PointData(260, 1200);

private static final PointData AUTOJOIN_BUTTON_BR_VALUE = new PointData(450, 1240);

private static final PointData AUTOJOIN_STOP_BUTTON_TL_VALUE = new PointData(120, 1070);

private static final PointData AUTOJOIN_STOP_BUTTON_BR_VALUE = new PointData(240, 1110);

private static final PointData RECALL_CONFIRM_BUTTON_TL_VALUE = new PointData(446, 780);

private static final PointData RECALL_CONFIRM_BUTTON_BR_VALUE = new PointData(578, 800);

private static final PointData MARCH_TIME_OCR_TL_MS = new PointData(504, 1134);

private static final PointData MARCH_TIME_OCR_BR_MS = new PointData(622, 1162);

private static final PointData FREE_MARCHES_OCR_TL_VALUE = new PointData(203, 200);

private static final PointData FREE_MARCHES_OCR_BR_VALUE = new PointData(246, 226);


private static final int DEFAULT_PREPARATION_TIME_MINUTES_MS = 15;

private static final int DEFAULT_OWN_RALLY_FLAG_VALUE = 1;

private static final int DEFAULT_JOIN_RALLY_FLAG_VALUE = 1;

private static final boolean DEFAULT_CALL_OWN_RALLY_VALUE = false;

private static final boolean DEFAULT_JOIN_RALLY_VALUE = false;

private static final boolean DEFAULT_USE_PETS_VALUE = false;

private static final boolean DEFAULT_RECALL_TROOPS_VALUE = false;

private boolean callOwnRally;
// Set when the game says this account cannot lead a rally on this bear; no more attempts this event.
private boolean ownRallyNotEligible;
private int ownRallyFailures;
private Instant nextOwnRallyAttempt = Instant.EPOCH;
// The configured trap's coordinates, read from its own Special Buildings row; kept for the event.
private PointData trapCoordinates;
// The camera bar read straight after the latest Go, if it could be read.
private Optional<PointData> cameraAfterGo = Optional.empty();

/** Which of the alliance's two traps this account fights: its Go button decides where the camera lands. */
private int trapNumber = 1;

boolean joinRally;

private boolean usePets;

private boolean recallTroops;

// Changed by pernerch | Date: 2026-07-02 | Why: detect shared-emulator profiles to avoid rally contention across accounts.
boolean sharedEmulator;


private int ownRallyFlag;

private int trapPreparationTime;

private LocalDateTime referenceTrapTime;

private boolean isVisuallyTriggered = false;

private static final OcrSettingsData FREE_MARCHES_OCR_SETTINGS_VALUE = OcrSettingsData.assembler()
            .charWhitelist("0123456789/")
            .stripBackground(true)
            .setTextColor(new Color(253, 253, 253))
            .build();

public BearTrapRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

@Override
    protected boolean acceptsInjections() {
        return false;
    }

@Override
    protected void execute() {
        endShownByGame = null;
        hydrateConfiguration();


        if (!confirmExecutionWindow()) {
            deferToNextWindow();
            return;
        }


        try {
            initializeOCRHelpersFlow();

            TrapTimingShape timing;
            if (isVisuallyTriggered) {
                logInfo(routineLogBearTrapLine("Task was Visually Triggered! Bypassing scheduled configuration and forcing 30-minute Active execution."));
                LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
                LocalDateTime scheduledActivation = null;
                try {
                    scheduledActivation = computeTrapTiming().activationTime;
                } catch (RuntimeException e) {
                    logDebug(routineLogBearTrapLine("No scheduled window to take the end from: " + e.getMessage()));
                }
                // Not "now + 30": a restart mid-bear would then outstay the game's bear.
                timing = new TrapTimingShape(now, now,
                        BearTrapEnd.restartedEnd(now, scheduledActivation, TRAP_DURATION_MINUTES_VALUE));
            } else {
                timing = computeTrapTiming();
                logTrapTimingFlow(timing);
            }

            LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));

            if (now.isBefore(timing.activationTime)) {
                performPreparationPhase(timing.activationTime);
            } else {
                logInfo(routineLogBearTrapLine("Trap is already ACTIVE (preparation time passed)"));


                logInfo(routineLogBearTrapLine("Executing essential setup (pets and navigation)..."));
                if (usePets) {
                    logInfo(routineLogBearTrapLine("Activating pets..."));
                    enablePetsFlow();
                }
                logInfo(routineLogBearTrapLine("Moving camera to Bear Trap"));
                reachBearTrap();
                sleepTask(1000);

            }

            now = LocalDateTime.now(ZoneId.of("UTC"));

            if (now.isBefore(timing.endTime)) {
                performTrapActivePhase(timing.endTime);
            } else {
                logInfo(routineLogBearTrapLine("Trap already ended for this window"));
            }
        } catch (Exception e) {
            logError(routineLogBearTrapLine("Issue while Bear Trap execution: " + e.getMessage()), e);
        } finally {


            cleanupFlow();
            deferToNextWindow();
        }
    }

@Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

@Override
    public boolean consumesStamina() {
        return false;
    }

@Override
    public boolean provideDailyMissionProgress() {
        return false;
    }

private static class TrapTimingShape {
        final LocalDateTime windowStart;
        final LocalDateTime activationTime;
        final LocalDateTime endTime;

        TrapTimingShape(LocalDateTime windowStart, LocalDateTime activationTime, LocalDateTime endTime) {
            this.windowStart = windowStart;
            this.activationTime = activationTime;
            this.endTime = endTime;
        }
    }

private static class MarchStatusShape {
        final boolean hasRecallButton;
        final boolean hasViewButton;
        final boolean hasSpeedupButton;

        MarchStatusShape(boolean hasRecallButton, boolean hasViewButton, boolean hasSpeedupButton) {
            this.hasRecallButton = hasRecallButton;
            this.hasViewButton = hasViewButton;
            this.hasSpeedupButton = hasSpeedupButton;
        }

        boolean noMarchesFound() {
            return !hasRecallButton && !hasViewButton && !hasSpeedupButton;
        }
    }

private void refreshNextWindowDateTime() {
        BearTrapHelper.WindowResult result = resolveWindowState();

        LocalDateTime nextWindowStart = LocalDateTime.ofInstant(
                result.getNextWindowStart(),
                ZoneId.of("UTC"));

        LocalDateTime nextTrapActivation = nextWindowStart.plusMinutes(trapPreparationTime);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
        String formattedDateTime = nextTrapActivation.format(formatter);

        logInfo(routineLogBearTrapLine("Updating next trap activation time to: " + formattedDateTime + " UTC"));

        ConfigService.obtain().writeAccountSetting(
                profile,
                BearTrapParticipationSchedule.SCHEDULE_KEY,
                formattedDateTime);
    }

private void requeueDisabledTasksFlow() {
        logInfo(routineLogBearTrapLine("Re-queueing tasks after Bear Trap event..."));

        TaskQueue queue = dev.frostguard.engine.service.ScheduleService.obtain().getCoordinator().getQueue(profile.getId());

        if (queue == null) {
            logError(routineLogBearTrapLine("Could not access task queue for profile " + profile.getName()));
            return;
        }

        requeueGatherTaskFlow(queue);
        requeueAutojoinTaskFlow(queue);

        sleepTask(1000);

    }

private void initializeOCRHelpersFlow() {
        BotOcrEngine provider = new BotOcrEngine(emuManager, EMULATOR_NUMBER);
        this.durationHelper = new ResilientOcrExecutor<>(provider);
    }

private void requeueAutojoinTaskFlow(TaskQueue queue) {
        logInfo(routineLogBearTrapLine("Inspecting autojoin task..."));

        Boolean autojoinEnabled = profile.getConfig(
                ConfigurationKeyEnum.ALLIANCE_AUTOJOIN_BOOL,
                Boolean.class);

        if (Boolean.TRUE.equals(autojoinEnabled)) {
            queue.runNow(TpDailyTaskEnum.ALLIANCE_AUTOJOIN, true);
            logInfo(routineLogBearTrapLine("Re-queued Alliance Autojoin task"));
        }
    }

private void performPreparationPhase(LocalDateTime activationTime) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
        long secondsUntilActivation = ChronoUnit.SECONDS.between(now, activationTime);

        logInfo(routineLogBearTrapLine("PREPARATION PHASE: " + secondsUntilActivation + " seconds until trap auto-activates"));

        prepareForTrapFlow();

        now = LocalDateTime.now(ZoneId.of("UTC"));
        secondsUntilActivation = ChronoUnit.SECONDS.between(now, activationTime);

        if (secondsUntilActivation > 0) {
            logInfo(routineLogBearTrapLine("Waiting for trap auto-activation in " + secondsUntilActivation + " seconds..."));
            sleepTask((secondsUntilActivation * 1000) + 2000);

        }

        logInfo(routineLogBearTrapLine("Trap has been ACTIVATED automatically!"));
    }

String routineLogBearTrapLine(String note) {
        return "BearTrapRoutine | " + note;
    }

// Recalls every gather march. With turnOffAutojoin, alliance auto-join is switched off as soon as
// the last recall is pressed, while the troops are still walking home, so neither waits on the other.
void recallGatherTroopsFlow(boolean turnOffAutojoin) {
        boolean autojoinOff = !turnOffAutojoin;
        // pernerch/2026-07-02: record recall timestamp in profile config BEFORE recalling.
        // GatherRoutine reads GATHER_LAST_RECALL_TIME_STRING on startup and uses it to wait
        // for troops to return home before re-deploying (checkTroopReturnPending).
        writeProfileSetting(
            dev.frostguard.api.configs.ConfigurationKeyEnum.GATHER_LAST_RECALL_TIME_STRING,
            java.time.LocalDateTime.now().toString());
        logInfo(routineLogBearTrapLine("Gather recall timestamp stored for troop-return tracking."));

        int attempt = 0;
        boolean allHome = false;

        while (attempt < MAX_GATHER_RECALL_ATTEMPTS_LIMIT) {
            attempt++;

            MarchStatusShape status = inspectMarchStatus();

            logDebug(routineLogBearTrapLine(String.format(
                    "recallGatherTroopsFlow status => returning:%b view:%b speedup:%b (attempt %d)",
                    status.hasRecallButton, status.hasViewButton, status.hasSpeedupButton, attempt)));

            if (status.noMarchesFound()) {
                logInfo(routineLogBearTrapLine("Zero march indicators detected. All gather troops are recalled or none present."));
                allHome = true;
                break;
            }

            if (status.hasRecallButton) {
                recallMarchFlow();
                continue;
            }

            if (!autojoinOff) {
                logInfo(routineLogBearTrapLine("All gather marches recalled; disabling autojoin while they return"));
                disableAutojoinFlow();
                autojoinOff = true;
                continue;
            }

            if (status.hasViewButton || status.hasSpeedupButton) {
                logInfo(routineLogBearTrapLine("Troops are still marching - waiting for them to return"));
                sleepTask(1000);

            }

            sleepTask(200);

        }

        if (!allHome) {
            logError(routineLogBearTrapLine("recallGatherTroopsFlow exceeded max attempts (" + MAX_GATHER_RECALL_ATTEMPTS_LIMIT +
                    "), exiting to avoid deadlock"));
        }
        if (!autojoinOff) {
            logInfo(routineLogBearTrapLine("Disabling autojoin..."));
            disableAutojoinFlow();
        }
    }

private void performTrapActivePhase(LocalDateTime trapEndTime) {
        logInfo(routineLogBearTrapLine("=== TRAP IS NOW ACTIVE - Starting strategy execution ==="));

        LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
        long iterationCount = 0;

        while (now.isBefore(endOf(trapEndTime))) {
            checkPreemption();

            iterationCount++;
            long secondsRemaining = ChronoUnit.SECONDS.between(now, endOf(trapEndTime));

            tryStartOwnRallyFlow(secondsRemaining);
            handleJoinRallies2(trapEndTime);

            logPeriodicStatusFlow(iterationCount, secondsRemaining);

            now = LocalDateTime.now(ZoneId.of("UTC"));
            sleepTask(joinRally ? JOIN_SCAN_IDLE_MS_VALUE : 1000);

        }

        logInfo(routineLogBearTrapLine("=== TRAP ENDED - Strategy execution completed ==="));
    }

private boolean confirmExecutionWindow() {


        isVisuallyTriggered = false;


        try {
            ImageSearchResultData result = emuManager.locatePattern(
                    profile.getEmulatorNumber(),
                    TemplatesEnum.BEAR_HUNT_IS_RUNNING,
                    90);
            if (result.isFound()) {
                logInfo(routineLogBearTrapLine("Confirmed: Bear Hunt is VISUALLY ACTIVE. Overriding time window check."));
                isVisuallyTriggered = true;
                return true;
            }
        } catch (Exception e) {
            logWarning(routineLogBearTrapLine("Visual check did not complete in confirmExecutionWindow: " + e.getMessage()));
        }

        if (!hasInsideWindow()) {
            logWarning(routineLogBearTrapLine("Execute called OUTSIDE valid window. Planning next run..."));
            return false;
        }

        logInfo(routineLogBearTrapLine("Confirmed: We are INSIDE a valid execution window"));
        return true;
    }

private LocalDateTime resolveConfigDateTime(ConfigurationKeyEnum key) {
        LocalDateTime value = profile.getConfig(key, LocalDateTime.class);
        if (value == null) {
            logWarning(routineLogBearTrapLine("Reference trap time not configured, using default: now + 1 hour"));
            return LocalDateTime.now(ZoneId.of("UTC")).plusHours(1);
        }
        return value;
    }

private void requeueGatherTaskFlow(TaskQueue queue) {
        logInfo(routineLogBearTrapLine("Inspecting Gather Resources task..."));

        Boolean gatherEnabled = profile.getConfig(
                ConfigurationKeyEnum.GATHER_TASK_BOOL,
                Boolean.class);

        if (Boolean.TRUE.equals(gatherEnabled)) {
            queue.runNow(TpDailyTaskEnum.GATHER_RESOURCES, true);
            logInfo(routineLogBearTrapLine("Re-queued Gather Resources task"));
        }
    }

private void logPeriodicStatusFlow(long iterationCount, long secondsRemaining) {
        if (iterationCount % STATUS_LOG_INTERVAL_VALUE == 0) {
            long minutesRemaining = secondsRemaining / 60;
            logInfo(routineLogBearTrapLine("Trap active - " + minutesRemaining + " minutes " +
                    (secondsRemaining % 60) + " seconds remaining"));
        }
    }

private void tryStartOwnRallyFlow(long secondsRemaining) {
        if (!callOwnRally || ownRallyNotEligible || ownRallyActive.get()
                || secondsRemaining <= OWN_RALLY_MIN_REMAINING_SECONDS_VALUE
                || ownRallyFailures >= OWN_RALLY_MAX_FAILURES_VALUE
                || Instant.now().isBefore(nextOwnRallyAttempt)) {
            return;
        }

        try {
            long marchDurationSeconds = beginOwnRally();

            if (marchDurationSeconds > 0) {
                LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
                LocalDateTime returnTime = now.plusSeconds(marchDurationSeconds * 2 + 3)
                        .plusMinutes(RALLY_RETURN_BUFFER_MINUTES_VALUE);

                logInfo(routineLogBearTrapLine("Own rally started finished cleanly, returning in: " + returnTime.format(TIME_FORMATTER)));
                ownRallyActive.set(true);
                queueRallyFlagReset(marchDurationSeconds);
                sleepTask(500);

            } else if (!ownRallyNotEligible) {
                ownRallyFailures++;
                nextOwnRallyAttempt = Instant.now().plusSeconds(OWN_RALLY_RETRY_DELAY_SECONDS_VALUE);
                logWarning(routineLogBearTrapLine("Could not start own rally (attempt " + ownRallyFailures
                        + " of " + OWN_RALLY_MAX_FAILURES_VALUE + ")"));
                if (ownRallyFailures >= OWN_RALLY_MAX_FAILURES_VALUE) {
                    logError(routineLogBearTrapLine("Own rally failed " + ownRallyFailures
                            + " times; no more own-rally attempts this event"));
                }
            }
        } catch (dev.frostguard.engine.error.ADBConnectionException e) {
            logWarning(routineLogBearTrapLine("ADB connection error during rally startup (emulator may be lagging): " + e.getMessage()));
            logDebug(routineLogBearTrapLine("Skipping this rally startup attempt, will retry on next cycle"));
            ownRallyActive.set(false);


        } catch (Exception e) {
            logError(routineLogBearTrapLine("Unexpected error during rally startup: " + e.getMessage()), e);
            ownRallyActive.set(false);


        }
    }

private boolean resolveConfigBoolean(ConfigurationKeyEnum key, boolean defaultValue) {
        Boolean value = profile.getConfig(key, Boolean.class);
        return (value != null) ? value : defaultValue;
    }

private void hydrateConfiguration() {
        this.referenceTrapTime = resolveConfigDateTime(BearTrapParticipationSchedule.SCHEDULE_KEY);
        this.trapPreparationTime = resolveConfigInt(BEAR_TRAP_PREPARATION_TIME_INT, DEFAULT_PREPARATION_TIME_MINUTES_MS);
        this.callOwnRally = resolveConfigBoolean(BEAR_TRAP_CALL_RALLY_BOOL, DEFAULT_CALL_OWN_RALLY_VALUE);
        this.joinRally = resolveConfigBoolean(BEAR_TRAP_JOIN_RALLY_BOOL, DEFAULT_JOIN_RALLY_VALUE);
        this.usePets = resolveConfigBoolean(BEAR_TRAP_ACTIVE_PETS_BOOL, DEFAULT_USE_PETS_VALUE);
        this.recallTroops = resolveConfigBoolean(BEAR_TRAP_RECALL_TROOPS_BOOL, DEFAULT_RECALL_TROOPS_VALUE);
        this.ownRallyFlag = resolveConfigInt(BEAR_TRAP_RALLY_FLAG_INT, DEFAULT_OWN_RALLY_FLAG_VALUE);
        this.trapNumber = resolveConfigInt(BEAR_TRAP_NUMBER_INT, 1) == 2 ? 2 : 1;


        BearJoinSquadKeys squadKeys = BearJoinSquadKeys.forRole(callOwnRally,
                profile.getConfig(BEAR_TRAP_LEADER_JOIN_FLAG_INT, String.class));
        this.joinFlags = decodeJoinFlags(squadKeys.hero());
        this.troopFlags = parseFlagList(squadKeys.troop());
        this.marchTally = new BearMarchTally();
        this.rallyTargets = new BearRallyTargets();
        this.savedJoinDiagnostics.clear();
        this.ownRallyNotEligible = false;
        this.ownRallyFailures = 0;
        this.nextOwnRallyAttempt = Instant.EPOCH;
        this.trapCoordinates = null;
        this.cameraAfterGo = Optional.empty();
        // Changed by pernerch | Date: 2026-07-02 | Why: resolve shared-emulator state at hydration for deterministic active-phase behavior.
        this.sharedEmulator = isSharedEmulatorProfile();


        logDebug(routineLogBearTrapLine(String.format(
                "Configuration loaded - Trap: %d, PrepTime: %dmin, OwnRally: %s (flag:%d), JoinRally: %s (flags:%s), Pets: %s, Recall: %s, SharedEmulator: %s",
                trapNumber, trapPreparationTime, callOwnRally, ownRallyFlag, joinRally, joinFlags, usePets,
                recallTroops, sharedEmulator)));
    }

private void queueRallyFlagReset(long marchSeconds) {
        long durationSeconds = RALLY_DURATION_BASE_MINUTES_VALUE * 60 +
                marchSeconds * 2 -
                RALLY_DURATION_BUFFER_SECONDS_VALUE;

        if (rallyScheduler == null || rallyScheduler.isShutdown() || rallyScheduler.isTerminated()) {
            rallyScheduler = Executors.newSingleThreadScheduledExecutor();
        }
        rallyResetTask = rallyScheduler.schedule(
                () -> {
                    ownRallyActive.set(false);
                    logInfo(routineLogBearTrapLine("Rally active flag automatically reset after duration"));
                },
                durationSeconds,
                TimeUnit.SECONDS);

        logDebug(routineLogBearTrapLine("Scheduled rally flag reset in " + durationSeconds + " seconds"));
    }

private void disableAutojoinFlow() {
        tapInside(ALLIANCE_BUTTON_TL_VALUE, ALLIANCE_BUTTON_BR_VALUE);
        sleepTask(3000);


        ImageSearchResultData warButton = templateSearchHelper.locatePattern(
                ALLIANCE_WAR_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_EXTENDED_VALUE)
                        .build());

        if (!warButton.isFound()) {
            logError(routineLogBearTrapLine("Alliance War button not detected to disable autojoin"));
            return;
        }

        tapInside(warButton.getPoint(), warButton.getPoint(), 1, 1000);
        sleepTask(1000);


        tapInside(AUTOJOIN_BUTTON_TL_VALUE, AUTOJOIN_BUTTON_BR_VALUE, 1, 1500);
        sleepTask(500);


        tapInside(AUTOJOIN_STOP_BUTTON_TL_VALUE, AUTOJOIN_STOP_BUTTON_BR_VALUE, 1, 500);
        sleepTask(500);


        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
    }

private BearTrapHelper.WindowResult resolveWindowState() {
        Instant referenceUTC = referenceTrapTime.atZone(ZoneId.of("UTC")).toInstant();
        return BearTrapHelper.calculateWindow(referenceUTC, trapPreparationTime);
    }

private void prepareForTrapFlow() {
        logInfo(routineLogBearTrapLine("Preparing for Bear Trap event..."));

        for (BearTrapPreparationPlan.Step step : BearTrapPreparationPlan.atWindowOpen(recallTroops, usePets)) {
            switch (step) {
                case TURN_OFF_AUTOJOIN -> {
                    logInfo(routineLogBearTrapLine("Disabling autojoin..."));
                    disableAutojoinFlow();
                }
                case RECALL_GATHERERS_AND_TURN_OFF_AUTOJOIN -> {
                    logInfo(routineLogBearTrapLine("Recalling all gather troops and disabling autojoin..."));
                    recallGatherTroopsFlow(true);
                }
                case ACTIVATE_PETS -> {
                    logInfo(routineLogBearTrapLine("Activating pets..."));
                    enablePetsFlow();
                }
                case MOVE_TO_TRAP -> {
                    logInfo(routineLogBearTrapLine("Moving camera to Bear Trap"));
                    reachBearTrap();
                    sleepTask(1000);
                }
            }
        }
    }

private MarchStatusShape inspectMarchStatus() {
        ImageSearchResultData returningArrow = templateSearchHelper.locatePattern(
                MARCHES_AREA_RECALL_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_VALUE)
                        .build());

        ImageSearchResultData marchView = templateSearchHelper.locatePattern(
                MARCHES_AREA_VIEW_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_VALUE)
                        .build());

        ImageSearchResultData marchSpeedup = templateSearchHelper.locatePattern(
                MARCHES_AREA_SPEEDUP_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_VALUE)
                        .build());

        return new MarchStatusShape(
                returningArrow != null && returningArrow.isFound(),
                marchView != null && marchView.isFound(),
                marchSpeedup != null && marchSpeedup.isFound());
    }

private OptionalInt readFreeMarches() {
        checkPreemption();

        emuManager.captureScreen(EMULATOR_NUMBER);
        ResilientOcrExecutor<Integer> frameReader =
                new ResilientOcrExecutor<>(provider.reusingLastFrame());

        Integer used = frameReader.attemptRecognition(
                FREE_MARCHES_OCR_TL_VALUE,
                FREE_MARCHES_OCR_BR_VALUE,
                JOIN_FREE_MARCH_OCR_ATTEMPTS_VALUE,
                10L,
                FREE_MARCHES_OCR_SETTINGS_VALUE,
                RegexNumberParser::hasFractionSyntax,
                RegexNumberParser::numerator);

        checkPreemption();


        Integer total = frameReader.attemptRecognition(
                FREE_MARCHES_OCR_TL_VALUE,
                FREE_MARCHES_OCR_BR_VALUE,
                JOIN_FREE_MARCH_OCR_ATTEMPTS_VALUE,
                10L,
                FREE_MARCHES_OCR_SETTINGS_VALUE,
                RegexNumberParser::hasFractionSyntax,
                RegexNumberParser::denominator);

        if (used == null || total == null) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(total - used);
    }

void handleJoinRallies2(LocalDateTime trapEndTime) {
		// Changed by pernerch | Date: 2026-07-02 | Why: skip rally joining on shared emulators while keeping other Bear Trap actions active.
        if (!joinRally || sharedEmulator) {
            if (sharedEmulator) {
                logInfo(routineLogBearTrapLine("Skipping rally joining because this profile shares an emulator with another account."));
            } else {
                logDebug(routineLogBearTrapLine("Skipping rally joining because joining is disabled."));
            }
            return;
        }

        try {
            ImageSearchResultData warButton = templateSearchHelper.locatePattern(
                    GAME_HOME_WAR,
                    SearchConfig.builder()
                            .withThreshold(90)
                            .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_VALUE)
                            .build());

            if (warButton.isFound()) {
                logDebug(routineLogBearTrapLine("Entering war section to check for rallies"));
                tapInside(warButton);
                sleepTask(JOIN_WAR_SCREEN_SETTLE_MS_VALUE);
                runJoinSession(trapEndTime);
                navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
            }
        } catch (dev.frostguard.engine.error.ADBConnectionException e) {
            logWarning(routineLogBearTrapLine("ADB connection error during rally joining (emulator may be lagging): " + e.getMessage()));
            logDebug(routineLogBearTrapLine("Skipping this rally join iteration, will retry on next cycle"));


        } catch (Exception e) {
            logError(routineLogBearTrapLine("Unexpected error during rally joining: " + e.getMessage()), e);
            returnToMapAfterJoinError();
        }
    }

// The end the game itself showed on the bear (UTC), once read; never later than the planned end.
private volatile LocalDateTime endShownByGame;

private LocalDateTime endOf(LocalDateTime planned) {
        return BearTrapEnd.earliest(planned, endShownByGame);
    }

private void learnEndFromScreen(List<dev.frostguard.vision.ocr.TextLine> lines) {
        BearTrapEnd.endsIn(lines).ifPresent(left -> {
            LocalDateTime end = LocalDateTime.now(ZoneId.of("UTC")).plus(left);
            LocalDateTime before = endShownByGame;
            endShownByGame = end;
            if (before == null || Math.abs(Duration.between(before, end).getSeconds()) > 60) {
                logInfo(routineLogBearTrapLine("The game's bear ends at " + end + " UTC (" + left.toMinutes() + " min left)."));
            }
        });
    }

private void returnToMapAfterJoinError() {
        try {
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
        } catch (Exception e) {
            logWarning(routineLogBearTrapLine("Could not return to the map after a join error: " + e.getMessage()));
        }
    }

// Stays on the War screen so a newly opened rally is taken at once. It leaves only to refresh the
// list: after a stretch with no rallies showing, and at the session limit.
private void runJoinSession(LocalDateTime trapEndTime) {
        joinListScroller.reset();
        Instant sessionStart = Instant.now();
        Instant lastRallySeen = sessionStart;

        while (LocalDateTime.now(ZoneId.of("UTC")).isBefore(endOf(trapEndTime))
                && Duration.between(sessionStart, Instant.now()).getSeconds() < JOIN_SESSION_LIMIT_SECONDS_VALUE) {
            checkPreemption();

            OptionalInt freeMarches = readFreeMarches();
            if (freeMarches.isPresent() && freeMarches.getAsInt() <= 0) {
                sleepTask(JOIN_SCAN_IDLE_MS_VALUE);
                continue;
            }

            List<PointData> plusIcons = locateOpenRallies();
            Optional<PointData> target = rallyTargets.next(plusIcons, Instant.now());
            if (target.isPresent()) {
                lastRallySeen = Instant.now();
                if (!rallyAccepted(target.get())) {
                    rallyTargets.markRejected(target.get(), Instant.now());
                    continue;
                }
                attemptRallyJoin(target.get(), freeMarches);
                continue;
            }

            if (plusIcons.isEmpty()
                    && Duration.between(lastRallySeen, Instant.now()).getSeconds() >= JOIN_NO_RALLY_REENTRY_SECONDS_VALUE) {
                return;
            }
            scrollJoinList(joinListScroller.afterNothingToJoin());
            sleepTask(JOIN_SCAN_IDLE_MS_VALUE);
        }
    }

private final BearJoinListScroller joinListScroller = new BearJoinListScroller();

// The War list is a plain vertical list; a swipe up moves it down. The coordinates are the middle of
// the list area; they have not been checked against a War-screen frame (none was saved on Nov 12).
private static final PointData JOIN_LIST_SWIPE_LOW = new PointData(360, 900);
private static final PointData JOIN_LIST_SWIPE_HIGH = new PointData(360, 480);

private void scrollJoinList(BearJoinListScroller.Move move) {
        if (move == BearJoinListScroller.Move.DOWN) {
            swipe(JOIN_LIST_SWIPE_LOW, JOIN_LIST_SWIPE_HIGH, 600);
        } else {
            for (int i = 0; i < BearJoinListScroller.MAX_STEPS_DOWN; i++) {
                swipe(JOIN_LIST_SWIPE_HIGH, JOIN_LIST_SWIPE_LOW, 400);
            }
        }
        sleepTask(500);
    }

/** Whether to join the rally at this row. The Bear Trap joins every one; the Facility joiner overrides this. */
boolean rallyAccepted(PointData plusIcon) {
        return true;
    }

private List<PointData> locateOpenRallies() {
        List<ImageSearchResultData> icons = templateSearchHelper.locateAllPatterns(
                BEAR_JOIN_PLUS_ICON,
                SearchConfig.builder()
                        .withArea(JOIN_RALLY_LIST_AREA_VALUE)
                        .withThreshold(90)
                        .withMaxResults(JOIN_PLUS_ICON_MAX_RESULTS_VALUE)
                        .build());
        return icons == null ? List.of() : icons.stream().map(ImageSearchResultData::getPoint).toList();
    }

private void attemptRallyJoin(PointData plusIcon, OptionalInt freeBefore) {
        tapInside(plusIcon, plusIcon, 1, 100);

        ImageSearchResultData deploy = findJoinDeployButton(JOIN_DEPLOY_APPEAR_ATTEMPTS_VALUE);
        if (!deploy.isFound()) {
            deploymentHelper.isMarchQueueFull();
            recordFailedJoin(plusIcon, "deploy-screen-missing", "the rally filled up or closed before the march screen opened");
            return;
        }

        List<Integer> squadsToTry = marchTally.likelyHome(joinFlags, Instant.now());
        Optional<Integer> heroSquad = BearJoinSquadPicker.firstLoaded(
                squadsToTry,
                squad -> marchHelper.selectFlag(squad),
                this::selectedSquadHasHero,
                squad -> marchTally.markSeenAway(squad, Instant.now()));

        Optional<Integer> troopSquad = Optional.empty();
        if (heroSquad.isEmpty()) {
            if (!squadsToTry.isEmpty()) {
                saveJoinDiagnostic("no-hero-loaded");
            }
            troopSquad = selectTroopSquad();
            if (!clearSelectedHeroes()) {
                pressBack();
                recordFailedJoin(plusIcon, "heroes-not-cleared", "heroes could not be cleared for a troops-only join");
                return;
            }
            if (deploymentHelper.hasNoDeployableTroops()) {
                pressBack();
                recordFailedJoin(plusIcon, "no-troops", "no troops are left to send");
                return;
            }
        }

        tapInside(deploy);
        if (!confirmJoinDeployed(plusIcon)) {
            return;
        }

        OptionalInt freeAfter = readFreeMarches();
        if (freeBefore.isPresent() && freeAfter.isPresent() && freeAfter.getAsInt() >= freeBefore.getAsInt()) {
            recordFailedJoin(plusIcon, "no-march-used", "Deploy closed but no march left, so the rally likely filled up");
            return;
        }

        rallyTargets.markJoined(plusIcon, Instant.now());
        Optional<Integer> sentSquad = heroSquad.isPresent() ? heroSquad : troopSquad;
        sentSquad.ifPresent(squad -> marchTally.markSent(squad, Instant.now()));
        String marchLabel = heroSquad.isPresent() ? "squad #" + heroSquad.get()
                : troopSquad.isPresent() ? "troop squad #" + troopSquad.get()
                : "troops only";
        logInfo(routineLogBearTrapLine("Joined rally with " + marchLabel
                + (freeAfter.isPresent() ? " (" + freeAfter.getAsInt() + " marches free)" : "")));
    }

private Optional<Integer> selectTroopSquad() {
        for (int squad : marchTally.troopSquadsToTry(troopFlags, Instant.now())) {
            if (marchHelper.selectFlag(squad)) {
                return Optional.of(squad);
            }
        }
        return Optional.empty();
    }

private ImageSearchResultData findJoinDeployButton(int attempts) {
        return templateSearchHelper.locatePattern(
                BEAR_DEPLOY_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(attempts)
                        .withDelay(JOIN_DEPLOY_SEARCH_DELAY_MS_VALUE)
                        .build());
    }

// A successful Deploy closes the march screen. Anything else means the game refused the join.
private boolean confirmJoinDeployed(PointData plusIcon) {
        boolean closed = false;
        for (int poll = 0; poll < JOIN_DEPLOY_CLOSE_POLLS_VALUE && !closed; poll++) {
            sleepTask(JOIN_DEPLOY_CLOSE_POLL_MS_VALUE);
            closed = !findJoinDeployButton(1).isFound();
        }

        if (deploymentHelper.isSameTargetDialog()) {
            pressBack();
            pressBack();
            recordFailedJoin(plusIcon, "same-target-dialog", "the game asked to confirm a march to the same target");
            return false;
        }
        if (!closed) {
            deploymentHelper.isMarchQueueFull();
            pressBack();
            recordFailedJoin(plusIcon, "deploy-refused", "the march screen stayed open after Deploy");
            return false;
        }
        return true;
    }

private void recordFailedJoin(PointData plusIcon, String label, String reason) {
        logInfo(routineLogBearTrapLine("Join failed: " + reason + "; moving to the next rally"));
        rallyTargets.markFailed(plusIcon, Instant.now());
        saveJoinDiagnostic(label);
    }

// One screenshot per kind of problem per event, so the first real bear can be reviewed afterward.
void saveJoinDiagnostic(String label) {
        if (!savedJoinDiagnostics.add(label)) {
            return;
        }
        try {
            java.nio.file.Path directory = dev.frostguard.api.runtime.WorkspacePaths.current().root().resolve("temp");
            java.nio.file.Files.createDirectories(directory);
            String profileName = profile.getName() == null ? "profile" : profile.getName();
            java.nio.file.Path output = directory.resolve(
                    "bear-join-" + label + "-" + profileName.replaceAll("[^A-Za-z0-9._-]", "_") + "-latest.png");
            java.awt.image.BufferedImage image = dev.frostguard.vision.convert.ImageConverter.toBufferedImage(
                    emuManager.captureScreen(EMULATOR_NUMBER));
            if (!javax.imageio.ImageIO.write(image, "png", output.toFile())) {
                throw new java.io.IOException("PNG writer unavailable");
            }
            logInfo(routineLogBearTrapLine("Join diagnostic saved: " + output));
        } catch (Exception e) {
            logWarning(routineLogBearTrapLine("Could not save join diagnostic: " + e.getMessage()));
        }
    }

private java.awt.image.BufferedImage capturePetFrame() {
        try {
            return dev.frostguard.vision.convert.ImageConverter.toBufferedImage(
                    emuManager.captureScreen(EMULATOR_NUMBER));
        } catch (Exception e) {
            logWarning(routineLogBearTrapLine("Could not capture the Pet Skill popup: " + e.getMessage()));
            return null;
        }
    }

private boolean selectedSquadHasHero() {
        sleepTask(300);
        return !locateSelectedHeroRemoveButtons().isEmpty();
    }

private boolean clearSelectedHeroes() {
        for (int taps = 0; taps < MAX_MARCH_HEROES_VALUE; taps++) {
            List<ImageSearchResultData> buttons = locateSelectedHeroRemoveButtons();
            if (buttons.isEmpty()) {
                return true;
            }
            tapInside(buttons.getFirst());
            sleepTask(500);
        }
        return locateSelectedHeroRemoveButtons().isEmpty();
    }

private List<ImageSearchResultData> locateSelectedHeroRemoveButtons() {
        return templateSearchHelper.locateAllPatterns(
                RALLY_REMOVE_HERO_BUTTON,
                SearchConfig.builder().withThreshold(90).withMaxResults(MAX_MARCH_HEROES_VALUE).build());
    }

private void logTrapTimingFlow(TrapTimingShape timing) {
        logInfo(routineLogBearTrapLine("Preparation window: " + timing.windowStart.format(DATETIME_FORMATTER) + " to " +
                timing.activationTime.format(DATETIME_FORMATTER)));
        logInfo(routineLogBearTrapLine("Trap will auto-activate at: " + timing.activationTime.format(DATETIME_FORMATTER)));
        logInfo(routineLogBearTrapLine("Trap will end at: " + timing.endTime.format(DATETIME_FORMATTER)));
    }

private void deferToNextWindow() {
        BearTrapHelper.WindowResult result = resolveWindowState();

        LocalDateTime nextWindowStart = LocalDateTime.ofInstant(
                result.getNextWindowStart(),
                ZoneId.systemDefault());

        LocalDateTime nextWindowStartUtc = LocalDateTime.ofInstant(
                result.getNextWindowStart(),
                ZoneId.of("UTC"));

        logInfo(routineLogBearTrapLine("Planning next run Bear Trap for (UTC): " + nextWindowStartUtc.format(DATETIME_FORMATTER)));
        logInfo(routineLogBearTrapLine("Planning next run Bear Trap for (Local): " + nextWindowStart.format(DATETIME_FORMATTER)));

        reschedule(nextWindowStart);
        refreshNextWindowDateTime();
    }

private TrapTimingShape computeTrapTiming() {
        BearTrapHelper.WindowResult window = resolveWindowState();

        LocalDateTime windowStart = LocalDateTime.ofInstant(
                window.getCurrentWindowStart(),
                ZoneId.of("UTC"));
        LocalDateTime windowEnd = LocalDateTime.ofInstant(
                window.getCurrentWindowEnd(),
                ZoneId.of("UTC"));

        LocalDateTime activationTime = windowEnd.minusMinutes(TRAP_ACTIVATION_OFFSET_MINUTES_VALUE);
        LocalDateTime endTime = activationTime.plusMinutes(TRAP_DURATION_MINUTES_VALUE);

        return new TrapTimingShape(windowStart, activationTime, endTime);
    }

private int resolveConfigInt(ConfigurationKeyEnum key, int defaultValue) {
        Integer value = profile.getConfig(key, Integer.class);
        return (value != null) ? value : defaultValue;
    }

boolean isSharedEmulatorProfile() {
        if (profile == null || profile.getEmulatorNumber() == null || profile.getEmulatorNumber().isBlank()) {
            return false;
        }
        return ProfileService.obtain().fetchAllAccounts().stream()
                .filter(other -> other != null && other.getId() != null && !other.getId().equals(profile.getId()))
                .filter(other -> profile.getEmulatorNumber().equals(other.getEmulatorNumber()))
                .anyMatch(other -> Boolean.TRUE.equals(other.getEnabled()));
    }

private void recallMarchFlow() {
        logInfo(routineLogBearTrapLine("Returning arrow detected - attempting to tap recall button"));

        ImageSearchResultData recallButton = templateSearchHelper.locatePattern(
                MARCHES_AREA_RECALL_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_VALUE)
                        .build());

        if (recallButton.isFound()) {
            tapInside(recallButton.getPoint(), recallButton.getPoint(), 1, 300);
            sleepTask(300);


            tapInside(RECALL_CONFIRM_BUTTON_TL_VALUE, RECALL_CONFIRM_BUTTON_BR_VALUE, 1, 200);
            sleepTask(500);

        }
    }

private boolean hasInsideWindow() {
        Instant referenceUTC = referenceTrapTime.atZone(ZoneId.of("UTC")).toInstant();
        BearTrapHelper.WindowResult result = BearTrapHelper.calculateWindow(referenceUTC, trapPreparationTime);
        return result.getState() == BearTrapHelper.WindowState.INSIDE;
    }

private List<Integer> decodeJoinFlags(ConfigurationKeyEnum heroKey) {
        List<Integer> flags = parseFlagList(heroKey);

        if (flags.isEmpty()) {
            flags.add(DEFAULT_JOIN_RALLY_FLAG_VALUE);
        }

        return flags;
    }

private List<Integer> parseFlagList(ConfigurationKeyEnum key) {
        String flagConfig = profile.getConfig(key, String.class);
        List<Integer> flags = new ArrayList<>();

        if (flagConfig != null && !flagConfig.trim().isEmpty()) {
            String[] parts = flagConfig.split(",");
            for (String part : parts) {
                try {
                    int flag = Integer.parseInt(part.trim());
                    if (FormationSlots.supports(flag)) {
                        flags.add(flag);
                    }
                } catch (NumberFormatException e) {
                    logWarning(routineLogBearTrapLine("Invalid join flag value: " + part));
                }
            }
        }


        flags.sort(Integer::compareTo);

        return flags;
    }

// Uses every ready combat (red crossed-swords) pet skill in one go. Selecting a ready combat skill
// shows Quick Use beside Use; Quick Use opens a free "Use all available Battle Skills?" dialog,
// and that dialog's Use is pressed only after its question has been read. Growth skills are never
// touched, and any screen the flow does not recognise is backed out of, never tapped.
private void enablePetsFlow() {
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
        ImageSearchResultData petsButton = templateSearchHelper.locatePattern(
                GAME_HOME_PETS,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_EXTENDED_VALUE)
                        .build());

        if (!petsButton.isFound()) {
            logError(routineLogBearTrapLine("Pets button not detected to enable pets"));
            return;
        }

        tapInside(petsButton.getPoint(), petsButton.getPoint(), 1, 500);
        sleepTask(1200);

        java.awt.image.BufferedImage frame = capturePetFrame();
        if (frame == null || !BearPetCombatSkills.isPopupOpen(frame)) {
            logError(routineLogBearTrapLine("Pet Skill popup did not open; no pet skills used"));
            saveJoinDiagnostic("pets-no-popup");
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
            return;
        }

        List<PointData> combatTiles = BearPetCombatSkills.combatTiles(frame);
        logInfo(routineLogBearTrapLine("Combat pet skills found: " + combatTiles.size()));
        boolean quickUsed = false;
        for (PointData tile : combatTiles) {
            tapInside(tile, tile, 1, 300);
            sleepTask(600);
            frame = capturePetFrame();
            if (frame == null || !BearPetCombatSkills.isPopupOpen(frame)) {
                logWarning(routineLogBearTrapLine("Pet Skill popup lost after selecting a skill; stopping"));
                saveJoinDiagnostic("pets-popup-lost");
                break;
            }
            String at = tile.getX() + "," + tile.getY();
            if (!BearPetCombatSkills.offersQuickUse(frame)) {
                logInfo(routineLogBearTrapLine("Pet skill at " + at + " is on cooldown or running"));
                continue;
            }
            quickUsed = quickUseBattleSkills(at);
            break;
        }

        int stillReady = countReadyCombatSkills(combatTiles);
        if (stillReady >= 0) {
            logInfo(routineLogBearTrapLine("Combat pet skills still ready after Quick Use: " + stillReady
                    + " of " + combatTiles.size() + (quickUsed ? "" : " (Quick Use not pressed)")));
            if (quickUsed && stillReady > 0) {
                saveJoinDiagnostic("pets-still-ready");
            }
        }
        saveJoinDiagnostic("pets-done");

        // Close the popup only if it is still up: an extra Back on Home opens "Quit game?".
        java.awt.image.BufferedImage closing = capturePetFrame();
        if (closing != null && BearPetCombatSkills.isPopupOpen(closing)) {
            pressBack();
            sleepTask(300);
        }


        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
    }

// Presses Quick Use, reads the dialog it opens, and presses that dialog's Use only when it is the
// known free "Use all available Battle Skills?" question with a blue Use.
private boolean quickUseBattleSkills(String at) {
        PointData quick = BearPetCombatSkills.QUICK_USE_CENTER;
        tapInside(quick, quick, 1, 300);
        sleepTask(1200);
        logInfo(routineLogBearTrapLine("Pressed Quick Use from pet skill at " + at));

        java.awt.image.BufferedImage dialog = capturePetFrame();
        boolean asksForBattleSkills = BearTrapScreens.isBattleSkillsConfirmation(
                readScreenText(BearTrapScreens.BATTLE_SKILLS_QUESTION));
        if (dialog == null || !asksForBattleSkills || !BearPetCombatSkills.confirmationUseIsBlue(dialog)) {
            // Not the dialog this flow knows. It could ask for gems, so nothing on it is tapped.
            saveJoinDiagnostic("pets-quick-use-unknown");
            logWarning(routineLogBearTrapLine(
                    "Quick Use did not open the Battle Skills confirmation; pressing Back and stopping pet skills"));
            pressBack();
            sleepTask(800);
            return false;
        }

        PointData use = BearTrapScreens.BATTLE_SKILLS_USE_CENTER;
        tapInside(use, use, 1, 300);
        sleepTask(1500);

        java.awt.image.BufferedImage after = capturePetFrame();
        if (after == null || !BearPetCombatSkills.isPopupOpen(after)) {
            saveJoinDiagnostic("pets-after-use");
            logWarning(routineLogBearTrapLine(
                    "Unknown screen after confirming Battle Skills; pressing Back and stopping pet skills"));
            pressBack();
            sleepTask(800);
            return false;
        }
        logInfo(routineLogBearTrapLine("Confirmed Use all available Battle Skills"));
        return true;
    }

// How many of the combat tiles still show a blue Use, or -1 when the popup is gone.
private int countReadyCombatSkills(List<PointData> combatTiles) {
        int ready = 0;
        for (PointData tile : combatTiles) {
            java.awt.image.BufferedImage frame = capturePetFrame();
            if (frame == null || !BearPetCombatSkills.isPopupOpen(frame)) {
                return -1;
            }
            tapInside(tile, tile, 1, 300);
            sleepTask(600);
            frame = capturePetFrame();
            if (frame == null || !BearPetCombatSkills.isPopupOpen(frame)) {
                saveJoinDiagnostic("pets-popup-lost");
                return -1;
            }
            if (BearPetCombatSkills.liveUseButton(frame).isPresent()) {
                ready++;
            }
        }
        return ready;
    }

List<dev.frostguard.vision.ocr.TextLine> readScreenText(AreaData area) {
        return readScreenText(area, SPARSE_TEXT_OCR);
    }

private List<dev.frostguard.vision.ocr.TextLine> readScreenText(AreaData area, OcrSettingsData settings) {
        try {
            dev.frostguard.api.domain.RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
            return dev.frostguard.vision.ocr.OcrEngine.recognizeLines(
                    frame, area.topLeft(), area.bottomRight(), settings);
        } catch (Exception e) {
            logWarning(routineLogBearTrapLine("Could not read screen text: " + e.getMessage()));
            return List.of();
        }
    }

private long scanMarchTime() {
        Duration marchingTime = durationHelper.attemptRecognition(
                MARCH_TIME_OCR_TL_MS,
                MARCH_TIME_OCR_BR_MS,
                MARCH_TIME_OCR_MAX_RETRIES_MS,
                200L,
                null,
                GameTimeUtils::isAcceptedFormat,
                GameTimeUtils::parseDuration);

        if (marchingTime != null) {
            return marchingTime.getSeconds();
        }

        return 0;
    }

private boolean reachBearTrap() {
        tapInside(ALLIANCE_BUTTON_TL_VALUE, ALLIANCE_BUTTON_BR_VALUE);
        sleepTask(3000);


        ImageSearchResultData territoryButton = templateSearchHelper.locatePattern(
                ALLIANCE_TERRITORY_BUTTON,
                SearchConfig.builder()
                        .withMaxAttempts(1)
                        .build());

        if (!territoryButton.isFound()) {
            logError(routineLogBearTrapLine("Territory button not detected to go to bear trap"));
            return false;
        }

        tapInside(territoryButton.getPoint(), territoryButton.getPoint(), 1, 2000);
        sleepTask(1000);


        tapInside(SPECIAL_BUILDINGS_BUTTON_TL_VALUE, SPECIAL_BUILDINGS_BUTTON_BR_VALUE, 1, 300);
        sleepTask(500);


        sleepTask(500);
        List<dev.frostguard.vision.ocr.TextLine> rows = readScreenText(BearTrapScreens.SPECIAL_BUILDINGS_LIST);
        Optional<PointData> go = BearTrapScreens.trapGoButton(rows, trapNumber);
        Optional<PointData> rowCoordinates = BearTrapScreens.trapRowCoordinatesArea(rows, trapNumber)
                .flatMap(area -> BearTrapScreens.mapCoordinates(readScreenText(area, WHITE_TEXT_OCR)));
        if (rowCoordinates.isPresent()) {
            PointData t = rowCoordinates.get();
            if (trapCoordinates == null || !BearTrapScreens.near(t, trapCoordinates, 0)) {
                logInfo(routineLogBearTrapLine("Hunting Trap " + trapNumber + " is at X:" + t.getX() + " Y:" + t.getY()));
            }
            trapCoordinates = t;
        } else if (go.isPresent() && trapCoordinates == null) {
            saveJoinDiagnostic("trap-row-coords-not-read");
            logWarning(routineLogBearTrapLine("Hunting Trap " + trapNumber + " coordinates not read from its row"));
        }
        if (go.isPresent()) {
            PointData g = go.get();
            logInfo(routineLogBearTrapLine("Going to Bear Trap " + trapNumber + " (Go at " + g.getX() + "," + g.getY() + ")"));
            tapInside(new PointData(g.getX() - 15, g.getY() - 8), new PointData(g.getX() + 15, g.getY() + 8), 1, 300);
        } else {
            // The row title was not read; fall back to where the row usually is. The bear popup
            // check before Rally catches a wrong trap.
            saveJoinDiagnostic("trap-row-not-read");
            PointData[] goButton = goButtonFor(trapNumber);
            logWarning(routineLogBearTrapLine("Hunting Trap " + trapNumber
                    + " row not read; using its usual Go position"));
            tapInside(goButton[0], goButton[1], 1, 300);
        }
        sleepTask(2000);

        cameraAfterGo = BearTrapScreens.mapCoordinates(readScreenText(BearTrapScreens.MAP_COORDINATE_BAR));
        if (cameraAfterGo.isPresent()) {
            PointData c = cameraAfterGo.get();
            if (trapCoordinates != null && !BearTrapScreens.near(c, trapCoordinates, BearTrapScreens.TRAP_TOLERANCE)) {
                logWarning(routineLogBearTrapLine("Camera at X:" + c.getX() + " Y:" + c.getY()
                        + " after Go, but Hunting Trap " + trapNumber + " is at X:" + trapCoordinates.getX()
                        + " Y:" + trapCoordinates.getY()));
            }
            logDebug(routineLogBearTrapLine("Camera at X:" + c.getX() + " Y:" + c.getY()));
        }
        return true;
    }

/** The Special Buildings list shows Trap 1 above Trap 2; anything but 2 is Trap 1. */
static PointData[] goButtonFor(int trapNumber) {
        return trapNumber == 2
                ? new PointData[]{BEAR_TRAP_2_GO_BUTTON_TL_VALUE, BEAR_TRAP_2_GO_BUTTON_BR_VALUE}
                : new PointData[]{BEAR_TRAP_1_GO_BUTTON_TL_VALUE, BEAR_TRAP_1_GO_BUTTON_BR_VALUE};
    }

private long beginOwnRally() {
        if (!ownRallyActive.compareAndSet(false, true)) {
            return 0;

        }

        logInfo(routineLogBearTrapLine("Calling own rally..."));

        // Joining rallies moves the camera, so the trap is found afresh before every attempt.
        if (!reachBearTrap()) {
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
            ownRallyActive.set(false);
            return 0;
        }

        // The popup slides in over about a second and the camera may sit a little off the bear, so
        // the first tap is not trusted: read for the popup, and tap a little around the bear if it
        // is not there (Nov 12: the popup was read before it had finished opening).
        List<dev.frostguard.vision.ocr.TextLine> popup = List.of();
        for (PointData tap : BEAR_TAP_POINTS) {
            tapInside(tap, tap, 1, 200);
            for (int look = 0; look < 4; look++) {
                sleepTask(look == 0 ? 800 : 500);
                popup = readScreenText(BearTrapScreens.MAP_POPUP_TEXT);
                if (BearTrapScreens.isRagingBearPopup(popup) || BearTrapScreens.saysNotEligible(popup)) break;
            }
            if (BearTrapScreens.isRagingBearPopup(popup) || BearTrapScreens.saysNotEligible(popup)) break;
        }
        learnEndFromScreen(popup);
        if (BearTrapScreens.saysNotEligible(popup)) {
            markOwnRallyNotEligible("own-rally-not-eligible");
            return 0;
        }
        if (!BearTrapScreens.isRagingBearPopup(popup)) {
            saveJoinDiagnostic("own-rally-not-bear");
            logWarning(routineLogBearTrapLine("Tapped the trap but no Raging Bear popup opened; read: "
                    + summarise(popup)));
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
            ownRallyActive.set(false);
            return 0;
        }

        // Both traps' popups say "Lv. 1 Raging Bear"; only coordinates say which bear this is.
        Optional<PointData> bearAt = BearTrapScreens.bearPopupCoordinatesArea(popup)
                .flatMap(area -> BearTrapScreens.mapCoordinates(readScreenText(area, WHITE_TEXT_OCR)));
        Optional<PointData> trapAt = Optional.ofNullable(trapCoordinates);
        if (!BearTrapScreens.isConfiguredTrap(trapAt, bearAt, cameraAfterGo)) {
            saveJoinDiagnostic("own-rally-wrong-trap");
            logWarning(routineLogBearTrapLine("Not rallying: cannot confirm this is Hunting Trap " + trapNumber
                    + " (trap " + describe(trapAt) + ", popup " + describe(bearAt)
                    + ", camera " + describe(cameraAfterGo) + ")"));
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
            ownRallyActive.set(false);
            return 0;
        }


        ImageSearchResultData rallyButton = templateSearchHelper.locatePattern(
                BEAR_RALLY_BUTTON,
                SearchConfig.builder()
                        .withThreshold(80)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_MAX_VALUE)
                        .build());

        if (!rallyButton.isFound()) {
            saveJoinDiagnostic("own-rally-no-rally-button");
            if (BearTrapScreens.saysNotEligible(readScreenText(WHOLE_MAP_TEXT_AREA))) {
                markOwnRallyNotEligible("own-rally-not-eligible");
                return 0;
            }
            logError(routineLogBearTrapLine("Rally button not detected on the Raging Bear popup"));
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
            ownRallyActive.set(false);
            return 0;
        }

        logInfo(routineLogBearTrapLine("Entering rally menu..."));
        tapInside(rallyButton.getPoint(), rallyButton.getPoint(), 1, 200);
        sleepTask(500);


        ImageSearchResultData holdRallyButton = templateSearchHelper.locatePattern(
                RALLY_HOLD_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_MAX_VALUE)
                        .build());

        if (!holdRallyButton.isFound()) {
            saveJoinDiagnostic("own-rally-no-hold");
            // Every march busy (joins use them all) leaves the popup up and the Rally tap does nothing.
            if (BearTrapScreens.marchQueueFull(readScreenText(BearTrapScreens.MARCH_PANEL_TEXT))) {
                logWarning(routineLogBearTrapLine("All marches are busy, so the own rally cannot start yet; it will retry when one returns."));
                navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
                ownRallyActive.set(false);
                return 0;
            }
            if (BearTrapScreens.saysNotEligible(readScreenText(WHOLE_MAP_TEXT_AREA))) {
                markOwnRallyNotEligible("own-rally-not-eligible");
                return 0;
            }
            logError(routineLogBearTrapLine("Hold Rally button not detected!"));
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
            ownRallyActive.set(false);
            return 0;
        }

        tapInside(holdRallyButton.getPoint(), holdRallyButton.getPoint(), 1, 200);
        sleepTask(300);


        if (!marchHelper.selectFlag(ownRallyFlag)) {
            logWarning(routineLogBearTrapLine(
                    "Configured rally formation #" + ownRallyFlag + " is unavailable; cancelling own rally"));
            pressBack();
            ownRallyActive.set(false);
            return 0;
        }


        long marchSeconds = scanMarchTime();

        if (marchSeconds == 0) {
            logError(routineLogBearTrapLine("Could not read march time from screen, defaulting to 30 seconds"));
            marchSeconds = 30;

        }

        ImageSearchResultData deploy = templateSearchHelper.locatePattern(
                BEAR_DEPLOY_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_MAX_VALUE)
                        .build());

        if (!deploy.isFound()) {
            logWarning(routineLogBearTrapLine("Deploy button not detected after selecting flag."));
            ownRallyActive.set(false);
            return 0;
        }

        tapInside(deploy);
        sleepTask(500);


        logInfo(routineLogBearTrapLine("Rally deployed finished cleanly. March time: " + marchSeconds + " seconds"));
        return marchSeconds;
    }

private void markOwnRallyNotEligible(String label) {
        saveJoinDiagnostic(label);
        ownRallyNotEligible = true;
        ownRallyActive.set(false);
        logWarning(routineLogBearTrapLine(
                "The game says this account is not eligible to rally this bear; no more own-rally attempts this event"));
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
    }

private static String describe(Optional<PointData> at) {
        return at.map(p -> "X:" + p.getX() + " Y:" + p.getY()).orElse("unread");
    }

private static String summarise(List<dev.frostguard.vision.ocr.TextLine> lines) {
        StringBuilder text = new StringBuilder();
        for (dev.frostguard.vision.ocr.TextLine line : lines) {
            if (text.length() > 120) {
                break;
            }
            text.append(text.length() == 0 ? "" : " | ").append(line.text().strip());
        }
        return text.length() == 0 ? "(nothing)" : text.toString();
    }

private void cleanupFlow() {
        logInfo(routineLogBearTrapLine("Cleaning up Bear Trap state"));

        ownRallyActive.set(false);

        if (rallyResetTask != null && !rallyResetTask.isDone()) {
            rallyResetTask.cancel(false);
            logDebug(routineLogBearTrapLine("Cancelled pending rally reset task"));
        }

        if (rallyScheduler != null && !rallyScheduler.isShutdown()) {
            rallyScheduler.shutdown();
            try {
                if (!rallyScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    rallyScheduler.shutdownNow();
                }
                logDebug(routineLogBearTrapLine("Rally scheduler shutdown finished cleanly"));
            } catch (InterruptedException e) {
                rallyScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        requeueDisabledTasksFlow();
    }
}
