package dev.frostguard.tasks.events;

import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
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
 * <p>Never tapped: any "Go" button (the paid mission's included), the exploration buttons, Select on a
 * floor, and the Bazaar. The only taps inside the event are the Missions tab, green Claim buttons, and
 * the in-game back arrow (Android Back would leave the event through Deals).
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
    private static final int RALLY_RETRY_MINUTES = 30;
    private static final int MISSION_RETRY_MINUTES = 60;
    private static final int RESET_GUARD_MINUTES = 20;

    enum ButtonState { CLAIM, GO, DONE }

    private int claimed;

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
        if (!openMissions()) {
            return;
        }
        claimAll();
        Optional<ButtonState> polar = rowState(TemplatesEnum.WANDER_THEATER_POLAR_ROW);
        Optional<ButtonState> troops = rowState(TemplatesEnum.WANDER_THEATER_TROOP_ROW);
        Optional<ButtonState> donations = rowState(TemplatesEnum.WANDER_THEATER_DONATE_ROW);
        logInfo("Wanderer Missions: claimed " + claimed + "; Polar Terror " + describe(polar)
                + ", troop power " + describe(troops) + ", alliance contributions " + describe(donations) + ".");
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
                StatisticsService.obtain().addToCounter(profile, "Wander Theater Polar Rallies", 1);
                finish(withinWindow(LocalDateTime.now().plusMinutes(RALLY_CHECK_MINUTES)));
            } else {
                logWarning("Polar Terror rally for Wander Theater not launched: " + failure);
                finish(withinWindow(LocalDateTime.now().plusMinutes(RALLY_RETRY_MINUTES)));
            }
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
