package dev.frostguard.tasks.events;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TroopSlotPolicy;
import dev.frostguard.engine.service.EventCalendarGate;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.tasks.combat.PolarTerrorHuntingRoutine;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.ImageConverter;

/**
 * Wander Theater's daily Wanderer Missions. It passes by every three hours from the 00:00 UTC reset and at
 * 22:00 and 23:30 UTC ({@link EventMissionSchedule}); each pass opens the event from the City rail,
 * claims every finished mission for Talismans of Ice, and launches the one Polar Terror rally its mission asks for. Alliance donations and troop
 * training fill their missions on their own through the day.
 *
 * <p>Then it plays the theater the way the owner settled on: every round is the one guaranteed Floor-1
 * Protected Exploration (5 Talismans), and the round is cashed out for the Wonderstar Coins of whatever
 * floor it lands on. It never taps Regular Exploration or a Protected Exploration above Floor 1, and it
 * only confirms a Select whose reward reads "Wonderstar Coin".
 *
 * <p>The State calendar (bg_calendar) gives the event's dates: before a window opens the task parks
 * until it does. With no calendar row it falls back to looking for the rail icon each day.
 *
 * <p>Never tapped: any "Go" button (the paid mission's included), Regular Exploration, Protected
 * Exploration above Floor 1, an item reward, and the Bazaar. Android Back is never used inside the event
 * (it would leave through Deals); the in-game back arrow is.
 */
public class WanderTheaterRoutine extends DelayedTask {

    private static final PointData ICON_AREA_TOP_LEFT = new PointData(380, 80);
    private static final PointData ICON_AREA_BOTTOM_RIGHT = new PointData(720, 640);
    private static final PointData TITLE_TOP_LEFT = new PointData(80, 8);
    private static final PointData TITLE_BOTTOM_RIGHT = new PointData(370, 76);
    private static final PointData MISSIONS_TITLE_TOP_LEFT = new PointData(25, 195);
    private static final PointData MISSIONS_TITLE_BOTTOM_RIGHT = new PointData(430, 270);
    private static final PointData MISSIONS_TAB = new PointData(335, 145);
    private static final PointData BACK_ARROW = new PointData(40, 40);
    private static final PointData THEATER_TAB = new PointData(164, 140);

    // The theater page, from live frames on 2026-11-09.
    // The Talisman count is read in a wide and a tight box; both must agree (a lone wide read once took "0" for "6").
    static final PointData TALISMANS_TOP_LEFT = new PointData(565, 268);
    static final PointData TALISMANS_BOTTOM_RIGHT = new PointData(648, 305);
    static final PointData TALISMANS_TIGHT_TOP_LEFT = new PointData(585, 272);
    static final PointData TALISMANS_TIGHT_BOTTOM_RIGHT = new PointData(630, 300);
    static final PointData FLOOR_ONE_TOP_LEFT = new PointData(180, 1070);
    static final PointData FLOOR_ONE_BOTTOM_RIGHT = new PointData(540, 1170);
    static final PointData SELECT_TOP_LEFT = new PointData(40, 420);
    static final PointData SELECT_BOTTOM_RIGHT = new PointData(700, 1000);
    static final PointData DIALOG_TITLE_TOP_LEFT = new PointData(200, 395);
    static final PointData DIALOG_TITLE_BOTTOM_RIGHT = new PointData(520, 470);
    static final PointData REWARD_NAME_TOP_LEFT = new PointData(230, 650);
    static final PointData REWARD_NAME_BOTTOM_RIGHT = new PointData(520, 720);
    private static final PointData FLOOR_ONE_PROTECTED = new PointData(360, 1195);
    private static final PointData DIALOG_CONFIRM = new PointData(520, 810);
    private static final PointData DIALOG_CANCEL = new PointData(200, 810);
    static final OcrSettingsData TALISMAN_DIGITS = OcrSettingsData.configurator()
            .textLayout(OcrSettingsData.TextLayout.SINGLE_WORD)
            .isolateForeground(true)
            .targetColor(Color.WHITE)
            .allowedGlyphs("0123456789")
            .build();
    static final int PROTECTED_COST = 5;
    private static final int MAX_ROUNDS = 12;
    private static final int SELECT_POLLS = 20;
    private static final long CONFIRM_SETTLE_MS = 5000;
    /** The name the State calendar stores this event under, for the gate to find its window. */
    static final String CALENDAR_EVENT_NAME = "Wander Theater";

    // Mission rows: a row's purple title band sits 75 px above the centre of its button.
    private static final PointData ROWS_TOP_LEFT = new PointData(40, 630);
    private static final PointData ROWS_BOTTOM_RIGHT = new PointData(700, 1170);
    static final int BUTTON_BELOW_TITLE = 75;
    static final int BUTTON_X = 582;
    // Sample points inside the button but clear of its white label.
    static final int BUTTON_COLUMN_LEFT = 528;
    static final int BUTTON_COLUMN_RIGHT = 636;
    static final int LIST_TOP = 640;
    /** Where the mission list sits and how it scrolls, for the shared sweep. */
    static final EventMissionSweep.Geometry MISSION_LIST = new EventMissionSweep.Geometry(
            LIST_TOP, 1245, BUTTON_X, BUTTON_COLUMN_LEFT, BUTTON_COLUMN_RIGHT, 40, 520, 77, 24, 1150, 750, 330, 8, 20);
    static final int LIST_BOTTOM = 1245;
    private static final int BUTTON_MIN_HEIGHT = 20;
    private static final PointData LIST_LOW = new PointData(330, 1150);
    private static final PointData LIST_HIGH = new PointData(330, 750);

    private static final int RALLY_SET_MINUTES = 3;
    private static final int RALLY_CHECK_MINUTES = 12;
    private static final int RALLY_RETRY_MINUTES = 6;
    private static final int RALLY_LATE_RETRY_MINUTES = 60;
    private static final int MAX_RALLY_ATTEMPTS = 4;
    private static final int LANE_CLAIM_MINUTES = 30;
    private static final int MISSION_RETRY_MINUTES = 60;
    private static final int RESET_GUARD_MINUTES = 20;

    enum ButtonState { CLAIM, GO, DONE }

    private int claimed;
    private int rallyAttempts;
    private LocalDateTime rallyDayReset;

    public WanderTheaterRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    public LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected void execute() {
        claimed = 0;
        if (parkedUntilCalendarStart()) {
            return;
        }
        if (!openMissions()) {
            return;
        }
        EventMissionSweep.Result sweep = EventMissionSweep.run(new MissionScreen(), MISSION_LIST);
        claimed = sweep.claimed();
        Optional<ButtonState> polar = rowState(TemplatesEnum.WANDER_THEATER_POLAR_ROW);
        logInfo("Wanderer Missions: claimed " + claimed + " over " + sweep.pages() + " page(s); Polar Terror "
                + describe(polar) + "; doable " + titlesOf(sweep.doable()) + "; purchase skipped "
                + titlesOf(sweep.purchase()) + (sweep.stoppedEarly() ? "; the sweep stopped early." : "."));
        // The rally below is this task's own; any other doable mission goes to its own task, if that is on.
        EventMissionTrigger.run(profile.getId() + "|921", sweep, Set.of(TpDailyTaskEnum.EVENT_POLAR_TERROR),
                new EventMissionTrigger.Environment() {
                    @Override
                    public boolean enabled(TpDailyTaskEnum task) {
                        return task.getConfigKey() != null
                                && Boolean.TRUE.equals(profile.getConfig(task.getConfigKey(), Boolean.class));
                    }

                    @Override
                    public void schedule(TpDailyTaskEnum task, LocalDateTime due) {
                        ScheduleService.obtain().applySweptTimer(profile, task, due, true);
                    }

                    @Override
                    public LocalDateTime now() {
                        return LocalDateTime.now();
                    }
                }, WanderTheaterRoutine.this::logInfo);
        if (!Boolean.FALSE.equals(profile.getConfig(ConfigurationKeyEnum.WANDER_THEATER_EXPLORE_BOOL, Boolean.class))) {
            explore();
        }
        tapNear(BACK_ARROW, 4);
        sleepTask(2000);
        if (claimed > 0) {
            StatisticsService.obtain().addToCounter(profile, "Wander Theater Missions Claimed", claimed);
        }

        boolean rallyWanted = Boolean.TRUE.equals(
                profile.getConfig(ConfigurationKeyEnum.WANDER_THEATER_RALLY_BOOL, Boolean.class));
        if (polar.orElse(null) == ButtonState.GO && rallyWanted) {
            Integer flag = profile.getConfig(ConfigurationKeyEnum.WANDER_THEATER_RALLY_FLAG_INT, Integer.class);
            int formation = flag == null ? 8 : flag;
            logInfo("Launching the Polar Terror rally for the mission: formation " + formation + ", "
                    + RALLY_SET_MINUTES + "-minute rally.");
            String failure = new PolarTerrorHuntingRoutine(profile, TpDailyTaskEnum.EVENT_POLAR_TERROR)
                    .launchOneRally(formation, RALLY_SET_MINUTES);
            if (failure == null) {
                rallyAttempts = 0;
                TroopSlotPolicy.release(profile, TpDailyTaskEnum.EVENT_WANDER_THEATER);
                StatisticsService.obtain().addToCounter(profile, "Wander Theater Polar Rallies", 1);
                finish(retryOrNextPass(LocalDateTime.now().plusMinutes(RALLY_CHECK_MINUTES)));
                return;
            }
            LocalDateTime resetAt = GameTimeUtils.dailyResetTime();
            if (!resetAt.equals(rallyDayReset)) {
                rallyDayReset = resetAt;
                rallyAttempts = 0;
            }
            rallyAttempts++;
            logWarning("Polar Terror rally for Wander Theater not launched (attempt " + rallyAttempts + "/"
                    + MAX_RALLY_ATTEMPTS + "): " + failure);
            if (rallyAttempts >= MAX_RALLY_ATTEMPTS) {
                // The lane claim could not free a march (on 2026-11-11 Gather's recall found no controls).
                // Stop pressing Gather and try again hourly until the cutoff: gather marches come home on
                // their own within a few hours.
                TroopSlotPolicy.release(profile, TpDailyTaskEnum.EVENT_WANDER_THEATER);
                finish(retryOrNextPass(LocalDateTime.now().plusMinutes(RALLY_LATE_RETRY_MINUTES)));
                return;
            }
            // Gather holds five lanes and Alliance auto-join the sixth, so every lane is often busy.
            // Claiming one makes Gather recall a single gathering march; the retry then has a free lane.
            TroopSlotPolicy.claimAndMakeRoom(profile, TpDailyTaskEnum.EVENT_WANDER_THEATER, 1,
                    LocalDateTime.now().plusMinutes(LANE_CLAIM_MINUTES));
            finish(retryOrNextPass(LocalDateTime.now().plusMinutes(RALLY_RETRY_MINUTES)));
            return;
        }
        // Troop power and donations fill on their own through the day; the next scheduled pass claims them.
        finish(nextPass());
    }

    private boolean openMissions() {
        ImageSearchResultData icon = templateSearchHelper.locatePattern(TemplatesEnum.WANDER_THEATER_CITY_ICON,
                SearchConfig.builder()
                        .withCoordinates(ICON_AREA_TOP_LEFT, ICON_AREA_BOTTOM_RIGHT)
                        .withMaxAttempts(3)
                        .withDelay(400)
                        .build());
        if (!icon.isFound()) {
            logInfo("Wander Theater icon not on the City rail: the event is not running.");
            finish(nextPass());
            return false;
        }
        tapNear(icon.getPoint(), 6);
        sleepTask(3000);
        if (!found(TemplatesEnum.WANDER_THEATER_TITLE, TITLE_TOP_LEFT, TITLE_BOTTOM_RIGHT)) {
            logWarning("The Wander Theater icon did not open the event.");
            finish(retryOrNextPass(LocalDateTime.now().plusMinutes(MISSION_RETRY_MINUTES)));
            return false;
        }
        tapNear(MISSIONS_TAB, 6);
        sleepTask(2000);
        if (!found(TemplatesEnum.WANDER_THEATER_MISSIONS_TITLE, MISSIONS_TITLE_TOP_LEFT, MISSIONS_TITLE_BOTTOM_RIGHT)) {
            logWarning("Wanderer Missions tab did not open.");
            tapNear(BACK_ARROW, 4);
            sleepTask(2000);
            finish(retryOrNextPass(LocalDateTime.now().plusMinutes(MISSION_RETRY_MINUTES)));
            return false;
        }
        return true;
    }

    /**
     * Before the event's calendar window opens, waits for it instead of looking for the icon every day.
     * Inside a window, or with no calendar row at all, the pass goes ahead.
     */
    private boolean parkedUntilCalendarStart() {
        EventCalendarGate.Verdict verdict =
                EventCalendarGate.check(profile, CALENDAR_EVENT_NAME, LocalDateTime.now(ZoneOffset.UTC));
        if (verdict.shouldRun()) {
            verdict.note().ifPresent(this::logInfo);
            return false;
        }
        // Parked to the first scheduled pass at or after the opening rather than to midnight UTC.
        LocalDateTime start = verdict.nextStartLocal().orElseThrow();
        LocalDateTime firstRun = EventMissionSchedule.nextPassAtOrAfter(start.atZone(ZoneId.systemDefault()));
        logInfo(verdict.reason() + " First pass at " + firstRun.format(DATETIME_FORMATTER) + ".");
        finish(firstRun);
        return true;
    }

    /**
     * Floor-1 rounds while Talismans last: one Protected Exploration from Floor 1 (it always climbs), then
     * the coins of the floor it lands on. A round found mid-way (left by an interrupted pass) is cashed out
     * first. Stops whenever a read is unsure.
     */
    private void explore() {
        tapNear(THEATER_TAB, 6);
        sleepTask(2500);
        if (!found(TemplatesEnum.WANDER_THEATER_TITLE, TITLE_TOP_LEFT, TITLE_BOTTOM_RIGHT)) {
            logWarning("Wander Theater page did not open; skipping exploration.");
            return;
        }
        Integer reserveSetting = profile.getConfig(ConfigurationKeyEnum.WANDER_THEATER_TALISMAN_RESERVE_INT, Integer.class);
        int reserve = reserveSetting == null ? 0 : Math.max(0, reserveSetting);
        int rounds = 0;
        for (int i = 0; i < MAX_ROUNDS; i++) {
            if (!found(TemplatesEnum.WANDER_THEATER_FLOOR_ONE, FLOOR_ONE_TOP_LEFT, FLOOR_ONE_BOTTOM_RIGHT)) {
                if (cashOutCoins()) {
                    rounds++;
                    continue;
                }
                logWarning("Wander Theater: not on Floor 1 and no coin reward to select; leaving the round as it is.");
                break;
            }
            Integer before = readTalismans();
            if (before == null) {
                logWarning("Wander Theater: could not read the Talisman count; no exploration.");
                break;
            }
            if (before < PROTECTED_COST + reserve) {
                logInfo("Wander Theater: " + before + " Talismans, " + (PROTECTED_COST + reserve)
                        + " needed for a Floor-1 roll; done.");
                break;
            }
            tapNear(FLOOR_ONE_PROTECTED, 6);
            boolean landed = awaitSelect();
            if (!landed && rollNeverStarted(before)) {
                // On 2026-11-12 the tap 3 s after a Confirm was swallowed: still Floor 1, no Talismans spent.
                logInfo("Wander Theater: the Floor-1 roll did not start; tapping Protected Exploration again.");
                tapNear(FLOOR_ONE_PROTECTED, 6);
                landed = awaitSelect();
            }
            if (!landed) {
                logWarning("Wander Theater: the Floor-1 Protected Exploration did not land on a floor with a reward.");
                break;
            }
            if (!cashOutCoins()) {
                break;
            }
            rounds++;
        }
        if (rounds > 0) {
            logInfo("Wander Theater: cashed out " + rounds + " round" + (rounds == 1 ? "" : "s") + " for Wonderstar Coins.");
            StatisticsService.obtain().addToCounter(profile, "Wander Theater Rounds Cashed", rounds);
        }
    }

    /** Still on Floor 1 with the same Talisman count: the Protected tap never started a roll. */
    private boolean rollNeverStarted(int before) {
        if (!found(TemplatesEnum.WANDER_THEATER_FLOOR_ONE, FLOOR_ONE_TOP_LEFT, FLOOR_ONE_BOTTOM_RIGHT)) {
            return false;
        }
        Integer now = readTalismans();
        return now != null && now == before;
    }

    /** Two boxes that must agree, so a misread never spends Talismans that are not there. */
    private Integer readTalismans() {
        Integer wide = integerHelper.attemptRecognition(TALISMANS_TOP_LEFT, TALISMANS_BOTTOM_RIGHT, 3, 200L,
                TALISMAN_DIGITS, text -> talismanCount(text) != null, WanderTheaterRoutine::talismanCount);
        Integer tight = integerHelper.attemptRecognition(TALISMANS_TIGHT_TOP_LEFT, TALISMANS_TIGHT_BOTTOM_RIGHT, 3, 200L,
                TALISMAN_DIGITS, text -> talismanCount(text) != null, WanderTheaterRoutine::talismanCount);
        return wide != null && wide.equals(tight) ? wide : null;
    }

    /** Digits only, at most four of them; anything else is no reading. */
    static Integer talismanCount(String text) {
        if (text == null) {
            return null;
        }
        String digits = text.trim();
        return digits.matches("\\d{1,4}") ? Integer.valueOf(digits) : null;
    }

    /**
     * A one-floor climb shows Select in about 5 s; the third roll of 2026-11-11 had none after 18 s but
     * did by the next pass, so the wait now runs to about 35 s and keeps the frame when it still misses.
     */
    private boolean awaitSelect() {
        sleepTask(5000);
        for (int i = 0; i < SELECT_POLLS; i++) {
            if (!selectButtons().isEmpty()) {
                return true;
            }
            sleepTask(1500);
        }
        RawImageData raw = emuManager.captureScreen(EMULATOR_NUMBER);
        if (raw != null) {
            DiagnosticSnapshotStore.alwaysOnForCurrentWorkspace()
                    .write(raw, "wander", "no-select-after-roll", Instant.now())
                    .ifPresent(path -> logWarning("Wander Theater: frame saved: " + path));
        }
        return false;
    }

    private List<ImageSearchResultData> selectButtons() {
        return templateSearchHelper.locateAllPatterns(TemplatesEnum.WANDER_THEATER_SELECT,
                SearchConfig.builder()
                        .withCoordinates(SELECT_TOP_LEFT, SELECT_BOTTOM_RIGHT)
                        .withMaxAttempts(1)
                        .withMaxResults(4)
                        .build()).stream().filter(ImageSearchResultData::isFound).toList();
    }

    /**
     * Taps the current floor's leftmost Select (the coin tile comes first) and confirms only when the
     * dialog names Wonderstar Coins; anything else is cancelled.
     */
    private boolean cashOutCoins() {
        Optional<ImageSearchResultData> leftmost = selectButtons().stream()
                .min(Comparator.comparingInt(r -> r.getPoint().getX()));
        if (leftmost.isEmpty()) {
            return false;
        }
        tapNear(leftmost.get().getPoint(), 4);
        sleepTask(1800);
        if (!found(TemplatesEnum.WANDER_THEATER_SELECT_REWARDS, DIALOG_TITLE_TOP_LEFT, DIALOG_TITLE_BOTTOM_RIGHT)) {
            logWarning("Wander Theater: Select did not open the Select Rewards dialog.");
            return false;
        }
        if (!found(TemplatesEnum.WANDER_THEATER_COIN_REWARD, REWARD_NAME_TOP_LEFT, REWARD_NAME_BOTTOM_RIGHT)) {
            logWarning("Wander Theater: the leftmost reward is not Wonderstar Coins; cancelled.");
            tapNear(DIALOG_CANCEL, 6);
            sleepTask(1500);
            return false;
        }
        tapNear(DIALOG_CONFIRM, 6);
        // The round's reward and the drop back to Floor 1 play out after Confirm; 3 s was not always enough.
        sleepTask(CONFIRM_SETTLE_MS);
        return true;
    }

    /** The state of one mission row, scrolling the list once if the row is below the fold. */
    private Optional<ButtonState> rowState(TemplatesEnum row) {
        Optional<ButtonState> state = visibleRowState(row);
        if (state.isPresent()) {
            return state;
        }
        swipe(LIST_LOW, LIST_HIGH, 600);
        sleepTask(1200);
        state = visibleRowState(row);
        swipe(LIST_HIGH, LIST_LOW, 600);
        sleepTask(1200);
        return state;
    }

    private Optional<ButtonState> visibleRowState(TemplatesEnum row) {
        ImageSearchResultData title = templateSearchHelper.locatePattern(row,
                SearchConfig.builder()
                        .withCoordinates(ROWS_TOP_LEFT, ROWS_BOTTOM_RIGHT)
                        .withMaxAttempts(2)
                        .withDelay(300)
                        .build());
        if (!title.isFound()) {
            return Optional.empty();
        }
        return Optional.of(buttonState(frame(), title.getPoint().getY() + BUTTON_BELOW_TITLE));
    }

    private static String titlesOf(List<EventMissionSweep.Mission> missions) {
        return missions.stream().map(EventMissionSweep.Mission::title).toList().toString();
    }

    /** The emulator, as the shared mission sweep sees it. */
    private final class MissionScreen implements EventMissionSweep.Screen {
        private RawImageData lastRaw;
        private BufferedImage lastImage;

        @Override
        public BufferedImage frame() {
            RawImageData raw = emuManager.captureScreen(EMULATOR_NUMBER);
            lastRaw = raw;
            lastImage = raw == null ? new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB)
                    : ImageConverter.toBufferedImage(raw);
            return lastImage;
        }

        @Override
        public String readTitle(BufferedImage frame, int buttonCenterY) {
            RawImageData raw = frame == lastImage ? lastRaw : emuManager.captureScreen(EMULATOR_NUMBER);
            return raw == null ? null : EventMissionSweep.ocrTitle(raw, MISSION_LIST, buttonCenterY);
        }

        @Override
        public void tap(int x, int y) {
            tapNear(new PointData(x, y), 10);
        }

        @Override
        public void swipe(int fromX, int fromY, int toX, int toY) {
            WanderTheaterRoutine.this.swipe(new PointData(fromX, fromY), new PointData(toX, toY), 600);
        }

        @Override
        public void sleep(long millis) {
            sleepTask(millis);
        }

        @Override
        public boolean onMissionList() {
            return found(TemplatesEnum.WANDER_THEATER_MISSIONS_TITLE, MISSIONS_TITLE_TOP_LEFT, MISSIONS_TITLE_BOTTOM_RIGHT);
        }

        @Override
        public void warn(String message) {
            logWarning(message);
        }

        @Override
        public void saveFrame(String tag, BufferedImage frame) {
            RawImageData raw = emuManager.captureScreen(EMULATOR_NUMBER);
            if (raw != null) {
                DiagnosticSnapshotStore.alwaysOnForCurrentWorkspace().write(raw, "wander", tag, Instant.now())
                        .ifPresent(path -> logWarning("Wander Theater: frame saved: " + path));
            }
        }
    }

    private static String describe(Optional<ButtonState> state) {
        return state.map(s -> s.name().toLowerCase()).orElse("not found");
    }

    static Optional<Integer> firstClaimButton(BufferedImage img) {
        int start = -1;
        for (int y = LIST_TOP; y < LIST_BOTTOM; y++) {
            boolean green = isClaimGreen(img.getRGB(BUTTON_COLUMN_LEFT, y))
                    && isClaimGreen(img.getRGB(BUTTON_COLUMN_RIGHT, y));
            if (green && start < 0) {
                start = y;
            } else if (!green && start >= 0) {
                if (y - start >= BUTTON_MIN_HEIGHT) {
                    return Optional.of((start + y) / 2);
                }
                start = -1;
            }
        }
        return Optional.empty();
    }

    static ButtonState buttonState(BufferedImage img, int y) {
        if (y < 0 || y >= img.getHeight()) {
            return ButtonState.DONE;
        }
        int rgb = img.getRGB(BUTTON_COLUMN_LEFT, y);
        if (isClaimGreen(rgb)) {
            return ButtonState.CLAIM;
        }
        if (isGoBlue(rgb)) {
            return ButtonState.GO;
        }
        return ButtonState.DONE;
    }

    // Claim (37,183,86) and Go (79,165,252) sampled from live frames on 2026-11-09.
    static boolean isClaimGreen(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return g >= 150 && r <= 90 && b <= 130 && g - r >= 80;
    }

    static boolean isGoBlue(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return b >= 220 && g >= 130 && r <= 120;
    }

    private boolean found(TemplatesEnum template, PointData topLeft, PointData bottomRight) {
        return templateSearchHelper.locatePattern(template,
                SearchConfig.builder()
                        .withCoordinates(topLeft, bottomRight)
                        .withMaxAttempts(3)
                        .withDelay(400)
                        .build()).isFound();
    }

    private LocalDateTime nextPass() {
        return EventMissionSchedule.nextPass(ZoneId.systemDefault());
    }

    /**
     * A retry comes before the next scheduled pass or not at all, and never inside the reset guard: a
     * rally launched in the last minutes of the day may return after the missions have reset.
     */
    private LocalDateTime retryOrNextPass(LocalDateTime retry) {
        LocalDateTime next = nextPass();
        LocalDateTime reset = GameTimeUtils.dailyResetTime();
        boolean inResetGuard = !retry.isBefore(reset.minusMinutes(RESET_GUARD_MINUTES)) && retry.isBefore(reset);
        return retry.isBefore(next) && !inResetGuard ? retry : next;
    }

    private void finish(LocalDateTime when) {
        logInfo("Next Wander Theater pass at " + when.format(DATETIME_FORMATTER) + ".");
        rescheduleExact(when);
    }

    private BufferedImage frame() {
        RawImageData raw = emuManager.captureScreen(EMULATOR_NUMBER);
        return raw == null ? new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB) : ImageConverter.toBufferedImage(raw);
    }
}
