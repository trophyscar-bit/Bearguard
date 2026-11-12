package dev.frostguard.tasks.heroes;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;
import dev.frostguard.vision.ocr.OcrException;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Task responsible for managing hero recruitment rewards.
 * 
 * <p>
 * This task handles two types of hero recruitment:
 * <ul>
 * <li><b>Advanced Recruitment:</b> Resets daily at game reset time</li>
 * <li><b>Epic Recruitment:</b> Resets after a certain duration from last
 * claim</li>
 * </ul>
 * 
 * <p>
 * <b>Task Flow:</b>
 * <ol>
 * <li>Navigate to hero recruitment interface</li>
 * <li>Check and claim advanced recruitment if available</li>
 * <li>Extract next advanced recruitment time (capped at game reset)</li>
 * <li>Check and claim epic recruitment if available</li>
 * <li>Extract next epic recruitment time</li>
 * <li>Reschedule to earliest of the two times</li>
 * </ol>
 * 
 * <p>
 * <b>Scheduling Strategy:</b>
 * Advanced recruitment is capped at game reset (won't schedule beyond daily
 * reset),
 * while epic recruitment can schedule beyond reset based on its own timer.
 * The task runs at whichever time comes first.
 */
public class HeroRecruitmentRoutine extends DelayedTask {

    // ===============================
    // CONSTANTS
    // ===============================

    /** Retry delay when OCR or navigation fails (minutes) */
    private static final int OCR_FAILURE_RETRY_MINUTES = 5;

    /** Maximum OCR retry attempts for time extraction */
    private static final int MAX_OCR_RETRIES = 3;

    /** Delay between OCR retry attempts (milliseconds) */
    private static final long OCR_RETRY_DELAY_MS = 200L;

    /**
     * How long a claim may take to reach its Rewards screen, or the Hero Recruitment screen,
     * after the tap (milliseconds). In a live run the chest opened about 5 s after the tap.
     */
    static final long SCREEN_CONFIRM_TIMEOUT_MS = 10000L;

    /** How long the Hero Recruitment screen may take to return after the single Back (milliseconds) */
    static final long AFTER_REWARDS_BACK_TIMEOUT_MS = 3000L;

    /** Pause between Hero Recruitment screen checks (milliseconds) */
    private static final long SCREEN_CONFIRM_POLL_MS = 500L;

    /** Pause after a claim tap for the claim animation (milliseconds) */
    private static final long CLAIM_ANIMATION_MS = 1000L;

    /** Fixed labels that must all read {@link #SCREEN_LABEL} for the screen to count as shown */
    static final List<AreaData> SCREEN_LABEL_AREAS = List.of(
            CommonGameAreas.HERO_RECRUIT_TITLE_LABEL_AREA,
            CommonGameAreas.HERO_RECRUIT_ADVANCED_BANNER_LABEL_AREA,
            CommonGameAreas.HERO_RECRUIT_EPIC_BANNER_LABEL_AREA);

    private static final String SCREEN_LABEL = "recruitment";

    /** The Rewards screen's exit hint, compared with spaces removed */
    private static final String REWARDS_EXIT_LABEL = "tapanywhere";

    // Navigation coordinates
    private static final PointData NAV_TAP_1_MIN = new PointData(160, 1190);
    private static final PointData NAV_TAP_1_MAX = new PointData(217, 1250);
    private static final PointData NAV_TAP_2_MIN = new PointData(400, 1190);
    private static final PointData NAV_TAP_2_MAX = new PointData(660, 1250);

    // Advanced recruitment coordinates
    private static final PointData ADVANCED_SEARCH_MIN = new PointData(40, 800);
    private static final PointData ADVANCED_SEARCH_MAX = new PointData(340, 1100);
    private static final PointData ADVANCED_CLAIM_MIN = new PointData(80, 827);
    private static final PointData ADVANCED_CLAIM_MAX = new PointData(315, 875);

    // Epic recruitment coordinates
    private static final PointData EPIC_SEARCH_MIN = new PointData(40, 1160);
    private static final PointData EPIC_SEARCH_MAX = new PointData(340, 1255);
    private static final PointData EPIC_CLAIM_MIN = new PointData(70, 1180);
    private static final PointData EPIC_CLAIM_MAX = new PointData(315, 1230);

    // ===============================
    // FIELDS
    // ===============================

    /** Helper for flexible OCR-based time recognition */
    private final ResilientOcrExecutor<Duration> timeHelper;

    /** Recruitment types claimed during the current run, for the OCR failure log */
    private String claimedThisRun = "";

    /** When the latest claim tap was sent, from {@link #nowMillis()} */
    private long lastClaimAtMillis;

    // ===============================
    // CONSTRUCTOR
    // ===============================

    /**
     * Constructs a new HeroRecruitmentRoutine.
     * 
     * @param profile     The game profile this task operates on
     * @param tpDailyTask The task type enum from the daily task registry
     */
    public HeroRecruitmentRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
        this.timeHelper = new ResilientOcrExecutor<>(provider);
    }

    // ===============================
    // MAIN EXECUTION
    // ===============================

    /**
     * Main execution method for the hero recruitment task.
     * 
     * <p>
     * <b>Execution Flow:</b>
     * <ol>
     * <li>Navigate to hero recruitment interface</li>
     * <li>Process advanced recruitment (claim + extract time)</li>
     * <li>Process epic recruitment (claim + extract time)</li>
     * <li>Calculate next execution time (earliest of both)</li>
     * <li>Reschedule task</li>
     * </ol>
     * 
     * <p>
     * OCR failures trigger retry in {@value #OCR_FAILURE_RETRY_MINUTES} minutes.
     * Navigation is handled automatically by DelayedTask framework after execution.
     */
    @Override
    protected void execute() {

        claimedThisRun = "";
        navigateToHeroRecruitment();

        Optional<LocalDateTime> advancedTime = processAdvancedRecruitment();
        if (advancedTime.isEmpty()) {
            scheduleNext(getFallbackTime());
            return;
        }
        Optional<LocalDateTime> epicTime = processEpicRecruitment();
        if (epicTime.isEmpty()) {
            scheduleNext(getFallbackTime());
            return;
        }

        scheduleNext(calculateNextExecution(advancedTime.get(), epicTime.get()));
    }

    private void scheduleNext(LocalDateTime nextExecution) {
        logInfo("Next hero recruitment check scheduled for: " + nextExecution.format(DATETIME_FORMATTER));
        reschedule(nextExecution);
    }

    // ===============================
    // NAVIGATION
    // ===============================

    /**
     * Navigates to the hero recruitment interface.
     * 
     * <p>
     * Performs two sequential taps to open the hero recruitment menu.
     * The framework will handle returning to home screen after task completion.
     */
    void navigateToHeroRecruitment() {
        logInfo("Navigating to hero recruitment interface");

        tapInside(NAV_TAP_1_MIN, NAV_TAP_1_MAX);
        sleepTask(500); // Wait for first menu to open

        tapInside(NAV_TAP_2_MIN, NAV_TAP_2_MAX);
        sleepTask(500); // Wait for hero recruitment interface to load
    }

    // ===============================
    // RECRUITMENT PROCESSING
    // ===============================

    /**
     * Processes advanced recruitment rewards and extracts next availability time.
     * 
     * <p>
     * Advanced recruitment resets daily at game reset time, so the extracted
     * time is capped at the next game reset to avoid scheduling beyond it.
     * 
     * @return Next advanced recruitment time (capped at game reset), the fallback
     *         if extraction fails, or empty if a claim left the screen unconfirmed
     */
    private Optional<LocalDateTime> processAdvancedRecruitment() {
        logInfo("Processing advanced recruitment");

        return processRecruitmentType(
                "Advanced",
                ADVANCED_SEARCH_MIN,
                ADVANCED_SEARCH_MAX,
                ADVANCED_CLAIM_MIN,
                ADVANCED_CLAIM_MAX,
                CommonGameAreas.HERO_RECRUIT_ADVANCED_TIMER_OCR_AREA)
                .map(result -> capAtGameReset(result.nextTime()));
    }

    /**
     * Processes epic recruitment rewards and extracts next availability time.
     * 
     * <p>
     * Epic recruitment resets after a certain duration from last claim,
     * so it's not capped at game reset and can schedule into the next day.
     * 
     * @return Next epic recruitment time, the fallback if extraction fails, or
     *         empty if a claim left the screen unconfirmed
     */
    private Optional<LocalDateTime> processEpicRecruitment() {
        logInfo("Processing epic recruitment");

        return processRecruitmentType(
                "Epic",
                EPIC_SEARCH_MIN,
                EPIC_SEARCH_MAX,
                EPIC_CLAIM_MIN,
                EPIC_CLAIM_MAX,
                CommonGameAreas.HERO_RECRUIT_EPIC_TIMER_OCR_AREA)
                .map(RecruitmentResult::nextTime);
    }

    /**
     * Processes a recruitment type (generic method for both advanced and epic).
     * 
     * <p>
     * <b>Process Flow:</b>
     * <ol>
     * <li>Search for claim button in specified region</li>
     * <li>If found, claim the reward and wait for the Hero Recruitment screen to come back</li>
     * <li>Extract next availability time via OCR</li>
     * <li>Return result with next time or fallback</li>
     * </ol>
     * 
     * @param type           Recruitment type name for logging
     * @param searchMin      Top-left corner of claim button search region
     * @param searchMax      Bottom-right corner of claim button search region
     * @param claimMin       Top-left corner of claim button tap region
     * @param claimMax       Bottom-right corner of claim button tap region
     * @param timerArea      "Next free:" countdown OCR region
     * @return RecruitmentResult containing next time or fallback, or empty when a
     *         claim did not hand the screen back; nothing more may be searched,
     *         tapped or read in that case
     */
    private Optional<RecruitmentResult> processRecruitmentType(
            String type,
            PointData searchMin,
            PointData searchMax,
            PointData claimMin,
            PointData claimMax,
            AreaData timerArea) {

        if (checkAndClaimRecruitment(type, searchMin, searchMax, claimMin, claimMax)) {
            claimedThisRun = claimedThisRun.isEmpty() ? type : claimedThisRun + ", " + type;
            if (!awaitRecruitmentScreen()) {
                logWarning(type + " recruitment was claimed but the Hero Recruitment screen did not come back"
                        + " (Rewards screen within " + SCREEN_CONFIRM_TIMEOUT_MS + " ms, then one Back). "
                        + "No further search, tap or timer read this run; "
                        + dumpFrame(type + "-screen-unconfirmed", timerArea));
                return Optional.empty();
            }
        }
        LocalDateTime nextTime = extractNextRecruitmentTime(type, timerArea);

        if (nextTime != null) {
            return Optional.of(new RecruitmentResult(nextTime, true));
        } else {
            logWarning(type + " recruitment time extraction failed. Using fallback.");
            return Optional.of(new RecruitmentResult(getFallbackTime(), false));
        }
    }

    /**
     * Brings the Hero Recruitment screen back after a claim. The claim plays a chest
     * animation and then holds a Rewards screen until it is left, with a paid
     * "Recruit once" key button on it. So this waits up to {@value #SCREEN_CONFIRM_TIMEOUT_MS} ms
     * after the claim tap for that Rewards screen, leaves it with exactly one Back (never a tap),
     * and then waits up to {@value #AFTER_REWARDS_BACK_TIMEOUT_MS} ms for the Hero Recruitment screen.
     */
    private boolean awaitRecruitmentScreen() {
        long deadline = lastClaimAtMillis + SCREEN_CONFIRM_TIMEOUT_MS;
        while (true) {
            if (recruitmentScreenShown()) {
                return true;
            }
            if (rewardsScreenShown()) {
                logInfo("Recruit rewards shown; leaving them with one Back");
                leaveRewardsScreen();
                return pollRecruitmentScreen(nowMillis() + AFTER_REWARDS_BACK_TIMEOUT_MS);
            }
            if (nowMillis() >= deadline) {
                return false;
            }
            pause(SCREEN_CONFIRM_POLL_MS);
        }
    }

    private boolean pollRecruitmentScreen(long deadline) {
        while (true) {
            pause(SCREEN_CONFIRM_POLL_MS);
            if (recruitmentScreenShown()) {
                return true;
            }
            if (nowMillis() >= deadline) {
                return false;
            }
        }
    }

    /** The one Back that leaves a claim's Rewards screen. */
    void leaveRewardsScreen() {
        pressBack();
    }

    /** Reads the Rewards screen's exit hint once. */
    boolean rewardsScreenShown() {
        return showsRewardsScreen(readLabel(CommonOCRSettings.HERO_RECRUIT_REWARDS_EXIT_SETTINGS));
    }

    /**
     * True when {@link CommonGameAreas#HERO_RECRUIT_REWARDS_EXIT_LABEL_AREA} reads
     * "Tap anywhere to exit".
     *
     * @param readLabel OCR of one area; null when unreadable
     */
    static boolean showsRewardsScreen(Function<AreaData, String> readLabel) {
        String text = readLabel.apply(CommonGameAreas.HERO_RECRUIT_REWARDS_EXIT_LABEL_AREA);
        return text != null && text.toLowerCase(Locale.ROOT).replace(" ", "").contains(REWARDS_EXIT_LABEL);
    }

    /** Reads the fixed screen labels once. */
    boolean recruitmentScreenShown() {
        return showsRecruitmentScreen(readLabel(CommonOCRSettings.HERO_RECRUIT_SCREEN_LABEL_SETTINGS));
    }

    private Function<AreaData, String> readLabel(OcrSettingsData settings) {
        return area -> {
            try {
                return provider.extractText(settings, area.topLeft(), area.bottomRight());
            } catch (IOException | OcrException unreadable) {
                return null;
            }
        };
    }

    /**
     * True only when every one of {@link #SCREEN_LABEL_AREAS} reads "Recruitment".
     *
     * @param readLabel OCR of one area; null when unreadable
     */
    static boolean showsRecruitmentScreen(Function<AreaData, String> readLabel) {
        for (AreaData area : SCREEN_LABEL_AREAS) {
            String text = readLabel.apply(area);
            if (text == null || !text.toLowerCase(Locale.ROOT).contains(SCREEN_LABEL)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks for and claims recruitment reward if available.
     * 
     * @param type      Recruitment type name for logging
     * @param searchMin Top-left corner of search region
     * @param searchMax Bottom-right corner of search region
     * @param claimMin  Top-left corner of claim tap region
     * @param claimMax  Bottom-right corner of claim tap region
     * @return true if reward was claimed, false if not available
     */
    private boolean checkAndClaimRecruitment(
            String type,
            PointData searchMin,
            PointData searchMax,
            PointData claimMin,
            PointData claimMax) {

        if (freeButtonShown(searchMin, searchMax)) {
            logInfo(type + " recruitment reward available. Claiming now.");
            tapClaim(claimMin, claimMax);
            lastClaimAtMillis = nowMillis();
            pause(CLAIM_ANIMATION_MS);
            return true;
        }
        logInfo(type + " recruitment reward not available yet.");
        return false;
    }

    boolean freeButtonShown(PointData searchMin, PointData searchMax) {
        ImageSearchResultData claimResult = templateSearchHelper.locatePattern(
                TemplatesEnum.HERO_RECRUIT_CLAIM,
                TemplateSearchHelper.SearchConfig.builder()
                        .withMaxAttempts(1)
                        .withThreshold(95)
                        .withDelay(300L)
                        .withCoordinates(searchMin, searchMax)
                        .build());
        return claimResult.isFound();
    }

    void tapClaim(PointData claimMin, PointData claimMax) {
        tapInside(claimMin, claimMax);
    }

    /**
     * Extracts the next recruitment time from the UI via OCR.
     * 
     * <p>
     * Uses {@link ResilientOcrExecutor} for robust OCR with validation
     * and retry logic. Attempts up to {@value #MAX_OCR_RETRIES} times.
     * 
     * <p>
     * When every attempt fails, the frame and the OCR crop are saved under
     * {@code logs/snapshot/herorecruitment} so the failure can be checked offline.
     *
     * @param type      Recruitment type name for logging
     * @param timerArea "Next free:" countdown OCR region
     * @return Next recruitment time, or null if extraction failed
     */
    private LocalDateTime extractNextRecruitmentTime(String type, AreaData timerArea) {

        logInfo("Extracting " + type + " recruitment next availability time");

        Duration nextTime = readTimer(timerArea);

        if (nextTime != null) {
            LocalDateTime nextClaimTime = LocalDateTime.now().plus(nextTime);
            logInfo(type + " recruitment next time: " + nextClaimTime.format(DATETIME_FORMATTER));
            return nextClaimTime;
        } else {
            String claimed = claimedThisRun.isEmpty() ? "none" : claimedThisRun;
            logWarning("Failed to extract " + type + " recruitment time after " +
                    MAX_OCR_RETRIES + " attempts; claimed this run: " + claimed + "; " +
                    dumpFrame(type + "-timer-unread", timerArea));
            return null;
        }
    }

    Duration readTimer(AreaData timerArea) {
        return timeHelper.attemptRecognition(
                timerArea,
                MAX_OCR_RETRIES,
                OCR_RETRY_DELAY_MS,
                CommonOCRSettings.HERO_RECRUIT_TIMER_SETTINGS,
                GameTimeUtils::isAcceptedFormat,
                GameTimeUtils::parseDuration);
    }

    /** Saves the frame and {@code region} of it under {@code logs/snapshot/herorecruitment}. */
    String dumpFrame(String type, AreaData region) {
        return TaskDiagnosticSnapshots.captureAlwaysWithRegion(emuManager, EMULATOR_NUMBER,
                "herorecruitment", type, region);
    }

    void pause(long millis) {
        sleepTask(millis);
    }

    long nowMillis() {
        return System.currentTimeMillis();
    }

    // ===============================
    // SCHEDULING LOGIC
    // ===============================

    /**
     * Calculates the next execution time based on both recruitment times.
     * 
     * <p>
     * Returns the earliest of the two recruitment times to ensure
     * we don't miss either opportunity.
     * 
     * @param advancedTime Next advanced recruitment time
     * @param epicTime     Next epic recruitment time
     * @return Earliest time between the two
     */
    private LocalDateTime calculateNextExecution(LocalDateTime advancedTime, LocalDateTime epicTime) {
        return getEarliest(advancedTime, epicTime);
    }

    /**
     * Caps a time at the next game reset.
     * 
     * <p>
     * Used for advanced recruitment since it resets daily and shouldn't
     * be scheduled beyond the game reset time.
     * 
     * @param time Time to cap
     * @return Original time if before reset, otherwise game reset time
     */
    private LocalDateTime capAtGameReset(LocalDateTime time) {
        LocalDateTime gameReset = GameTimeUtils.dailyResetTime();
        return getEarliest(time, gameReset);
    }

    /**
     * Returns the earliest of two LocalDateTime values, handling nulls safely.
     * 
     * <p>
     * Null handling:
     * <ul>
     * <li>If both null: returns fallback time (retry in 5 minutes)</li>
     * <li>If one null: returns the non-null value</li>
     * <li>If neither null: returns the earlier time</li>
     * </ul>
     * 
     * @param dt1 First datetime
     * @param dt2 Second datetime
     * @return Earliest datetime, or fallback if both null
     */
    private LocalDateTime getEarliest(LocalDateTime dt1, LocalDateTime dt2) {
        if (dt1 == null && dt2 == null) {
            return getFallbackTime();
        }
        if (dt1 == null) {
            return dt2;
        }
        if (dt2 == null) {
            return dt1;
        }
        return dt1.isBefore(dt2) ? dt1 : dt2;
    }

    /**
     * Gets the fallback time for when OCR extraction fails.
     * 
     * @return Current time plus {@value #OCR_FAILURE_RETRY_MINUTES} minutes
     */
    private LocalDateTime getFallbackTime() {
        return LocalDateTime.now().plusMinutes(OCR_FAILURE_RETRY_MINUTES);
    }

    // ===============================
    // TASK FRAMEWORK OVERRIDES
    // ===============================

    /**
     * Indicates that this task provides progress toward daily missions.
     * 
     * <p>
     * Hero recruitment typically counts toward "Recruit heroes" daily missions.
     * 
     * @return true to trigger daily mission progress checks
     */
    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }

    /**
     * Specifies the required starting screen location for this task.
     * 
     * <p>
     * This task can start from any screen as it performs its own navigation.
     * 
     * @return ANY as the required start location
     */
    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.ANY;
    }

    // ===============================
    // INNER CLASSES
    // ===============================

    /**
     * Record containing recruitment processing result.
     * 
     * @param nextTime Next availability time (or fallback if extraction failed)
     * @param success  Whether OCR extraction was successful
     */
    private record RecruitmentResult(LocalDateTime nextTime, boolean success) {
    }
}
