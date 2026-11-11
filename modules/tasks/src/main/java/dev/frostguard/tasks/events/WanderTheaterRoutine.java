package dev.frostguard.tasks.events;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TroopSlotPolicy;
import dev.frostguard.engine.service.EventCalendarGate;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.tasks.combat.PolarTerrorHuntingRoutine;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.ImageConverter;

/**
 * Wander Theater's daily Wanderer Missions. Once a day (4 PM local by default, three hours before the
 * 00:00 UTC reset) it opens the event from the City rail, claims every finished mission for Talismans
 * of Ice, and launches the one Polar Terror rally its mission asks for. Alliance donations and troop
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
    static final int LIST_BOTTOM = 1245;
    private static final int BUTTON_MIN_HEIGHT = 20;
    private static final PointData LIST_LOW = new PointData(330, 1150);
    private static final PointData LIST_HIGH = new PointData(330, 750);

    private static final int MAX_CLAIMS = 6;
    private static final int RALLY_SET_MINUTES = 3;
    private static final int RALLY_CHECK_MINUTES = 12;
    private static final int RALLY_RETRY_MINUTES = 6;
    private static final int MAX_RALLY_ATTEMPTS = 4;
    private static final int LANE_CLAIM_MINUTES = 30;
    private static final int MISSION_RETRY_MINUTES = 60;
    private static final int RESET_GUARD_MINUTES = 20;

    enum ButtonState { CLAIM, GO, DONE }

    private int claimed;
    private int rallyAttempts;

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
        claimAll();
        Optional<ButtonState> polar = rowState(TemplatesEnum.WANDER_THEATER_POLAR_ROW);
        Optional<ButtonState> troops = rowState(TemplatesEnum.WANDER_THEATER_TROOP_ROW);
        Optional<ButtonState> donations = rowState(TemplatesEnum.WANDER_THEATER_DONATE_ROW);
        logInfo("Wanderer Missions: claimed " + claimed + "; Polar Terror " + describe(polar)
                + ", troop power " + describe(troops) + ", alliance contributions " + describe(donations) + ".");
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
                finish(beforeCutoff(LocalDateTime.now().plusMinutes(RALLY_CHECK_MINUTES)));
                return;
            }
            rallyAttempts++;
            logWarning("Polar Terror rally for Wander Theater not launched (attempt " + rallyAttempts + "/"
                    + MAX_RALLY_ATTEMPTS + "): " + failure);
            if (rallyAttempts >= MAX_RALLY_ATTEMPTS) {
                rallyAttempts = 0;
                TroopSlotPolicy.release(profile, TpDailyTaskEnum.EVENT_WANDER_THEATER);
                finish(nextRunHour());
                return;
            }
            // Gather holds five lanes and Alliance auto-join the sixth, so every lane is often busy.
            // Claiming one makes Gather recall a single gathering march; the retry then has a free lane.
            TroopSlotPolicy.claimAndMakeRoom(profile, TpDailyTaskEnum.EVENT_WANDER_THEATER, 1,
                    LocalDateTime.now().plusMinutes(LANE_CLAIM_MINUTES));
            finish(beforeCutoff(LocalDateTime.now().plusMinutes(RALLY_RETRY_MINUTES)));
            return;
        }
        if (troops.orElse(ButtonState.DONE) == ButtonState.GO || donations.orElse(ButtonState.DONE) == ButtonState.GO) {
            finish(withinWindow(LocalDateTime.now().plusMinutes(MISSION_RETRY_MINUTES)));
            return;
        }
        finish(nextRunHour());
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
            finish(nextRunHour());
            return false;
        }
        tapNear(icon.getPoint(), 6);
        sleepTask(3000);
        if (!found(TemplatesEnum.WANDER_THEATER_TITLE, TITLE_TOP_LEFT, TITLE_BOTTOM_RIGHT)) {
            logWarning("The Wander Theater icon did not open the event.");
            finish(withinWindow(LocalDateTime.now().plusMinutes(MISSION_RETRY_MINUTES)));
            return false;
        }
        tapNear(MISSIONS_TAB, 6);
        sleepTask(2000);
        if (!found(TemplatesEnum.WANDER_THEATER_MISSIONS_TITLE, MISSIONS_TITLE_TOP_LEFT, MISSIONS_TITLE_BOTTOM_RIGHT)) {
            logWarning("Wanderer Missions tab did not open.");
            tapNear(BACK_ARROW, 4);
            sleepTask(2000);
            finish(withinWindow(LocalDateTime.now().plusMinutes(MISSION_RETRY_MINUTES)));
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
        // Parked to this task's own run hour on the opening day rather than to midnight UTC: the
        // event's first rounds are no more use at 19:00 the evening before than they are at any
        // other hour, and the rest of this routine assumes it runs on its hour.
        LocalDateTime start = verdict.nextStartLocal().orElseThrow();
        LocalDateTime firstRun = start.with(LocalTime.of(runHour(), 0));
        if (firstRun.isBefore(start)) {
            firstRun = firstRun.plusDays(1);
        }
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
            if (!awaitSelect()) {
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

    private boolean awaitSelect() {
        sleepTask(5000);
        for (int i = 0; i < 8; i++) {
            if (!selectButtons().isEmpty()) {
                return true;
            }
            sleepTask(1500);
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
        sleepTask(3000);
        return true;
    }

    /** Claimed rows sink to the bottom, so a finished mission is always among the first rows. */
    private void claimAll() {
        for (int i = 0; i < MAX_CLAIMS; i++) {
            Optional<Integer> button = firstClaimButton(frame());
            if (button.isEmpty()) {
                return;
            }
            tapNear(new PointData(BUTTON_X, button.get()), 10);
            sleepTask(2500);
            claimed++;
        }
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

    private int runHour() {
        Integer hour = profile.getConfig(ConfigurationKeyEnum.WANDER_THEATER_RUN_HOUR_INT, Integer.class);
        return hour == null ? 16 : Math.max(0, Math.min(23, hour));
    }

    private LocalDateTime nextRunHour() {
        LocalDateTime today = LocalDateTime.now().with(LocalTime.of(runHour(), 0));
        return LocalDateTime.now().isBefore(today) ? today : today.plusDays(1);
    }

    /** A rally retry may come any time before the reset guard, since the mission's day runs to the reset. */
    private LocalDateTime beforeCutoff(LocalDateTime retry) {
        LocalDateTime cutoff = GameTimeUtils.dailyResetTime().minusMinutes(RESET_GUARD_MINUTES);
        return retry.isBefore(cutoff) ? retry : nextRunHour();
    }

    /**
     * Retries stay inside the day's window, from the run hour to 20 minutes before the reset. A pass
     * outside it (a first run right after enabling, say) waits for the next run hour instead.
     */
    private LocalDateTime withinWindow(LocalDateTime retry) {
        LocalDateTime reset = GameTimeUtils.dailyResetTime();
        LocalDateTime cutoff = reset.minusMinutes(RESET_GUARD_MINUTES);
        LocalDateTime windowStart = reset.toLocalDate().atTime(runHour(), 0);
        if (windowStart.isAfter(reset)) {
            windowStart = windowStart.minusDays(1);
        }
        boolean inWindow = !LocalDateTime.now().isBefore(windowStart) && retry.isBefore(cutoff);
        return inWindow ? retry : nextRunHour();
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
