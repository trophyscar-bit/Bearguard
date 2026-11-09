package dev.frostguard.tasks.events;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

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
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.ImageConverter;

/**
 * Sign-in &amp; Earn It, the 7-day calendar on the City rail. The rail icon is only there while a day's
 * reward is waiting, and the tap that claims it is on the day's glowing tile in the Free column. The
 * $0.99 Epic column to its right is never touched: the only tap on this page is inside the Free column.
 */
public class SignInEarnRoutine extends DelayedTask {

    private static final PointData ICON_AREA_TOP_LEFT = new PointData(380, 80);
    private static final PointData ICON_AREA_BOTTOM_RIGHT = new PointData(720, 640);
    private static final int ICON_THRESHOLD = 85;
    private static final PointData TITLE_TOP_LEFT = new PointData(30, 195);
    private static final PointData TITLE_BOTTOM_RIGHT = new PointData(400, 275);
    private static final PointData BACK_ARROW = new PointData(40, 40);

    // The day column between the two reward columns: a drag starting here lands on no reward.
    private static final PointData LIST_LOW = new PointData(300, 1150);
    private static final PointData LIST_HIGH = new PointData(300, 700);

    static final int FREE_TILE_X = 140;
    private static final int TRACK_TOP = 525;
    private static final int TRACK_BOTTOM = 1258;
    private static final int[][] GLOW_STRIPS = {{92, 97}, {182, 186}};
    private static final int GLOW_MIN_PIXELS = 60;
    private static final int GLOW_MIN_HEIGHT = 40;
    private static final int GLOW_MAX_GAP = 6;
    private static final int GLOW_LOOKS = 3;

    private static final Duration ICON_RETRY = Duration.ofMinutes(30);
    private static final Duration ICON_RETRY_WINDOW = Duration.ofHours(2);
    private static final Duration FAILURE_RETRY = Duration.ofHours(1);
    private static final int RESET_PAD_MIN_MINUTES = 3;
    private static final int RESET_PAD_MAX_MINUTES = 10;

    public SignInEarnRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    public LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected void execute() {
        ImageSearchResultData icon = templateSearchHelper.locatePattern(TemplatesEnum.SIGN_IN_EARN_CITY_ICON,
                SearchConfig.builder()
                        .withCoordinates(ICON_AREA_TOP_LEFT, ICON_AREA_BOTTOM_RIGHT)
                        .withThreshold(ICON_THRESHOLD)
                        .withMaxAttempts(3)
                        .withDelay(400)
                        .build());
        if (!icon.isFound()) {
            logInfo("Sign-in & Earn icon not on the City rail: nothing waiting today.");
            finish(sinceLastReset().compareTo(ICON_RETRY_WINDOW) < 0
                    ? LocalDateTime.now().plus(ICON_RETRY) : afterNextReset());
            return;
        }
        tapNear(icon.getPoint(), 6);
        sleepTask(3000);
        if (!titleShown()) {
            logWarning("The Sign-in & Earn icon did not open its page.");
            finish(failureRetry());
            return;
        }
        Optional<Integer> tile = findTile();
        if (tile.isEmpty()) {
            logWarning("Sign-in & Earn page open but no glowing Free tile on any scroll position.");
            tapNear(BACK_ARROW, 4);
            sleepTask(2000);
            finish(failureRetry());
            return;
        }
        tapNear(new PointData(FREE_TILE_X, tile.get()), 8);
        sleepTask(2500);
        boolean claimed = titleShown() && claimableTileY(frame()).isEmpty();
        tapNear(BACK_ARROW, 4);
        sleepTask(2000);
        if (!claimed) {
            logWarning("Tapped the glowing Sign-in & Earn tile but it still glows.");
            finish(failureRetry());
            return;
        }
        StatisticsService.obtain().addToCounter(profile, "Sign-in & Earn Claimed", 1);
        logInfo("Claimed today's free Sign-in & Earn reward.");
        finish(afterNextReset());
    }

    /** Looks for the glow where the page opened, then one screen later in the list, then one earlier. */
    private Optional<Integer> findTile() {
        Optional<Integer> tile = lookForGlow();
        if (tile.isPresent()) {
            return tile;
        }
        swipe(LIST_LOW, LIST_HIGH, 500);
        sleepTask(1200);
        tile = lookForGlow();
        if (tile.isPresent()) {
            return tile;
        }
        for (int i = 0; i < 2; i++) {
            swipe(LIST_HIGH, LIST_LOW, 500);
            sleepTask(1200);
        }
        return lookForGlow();
    }

    private Optional<Integer> lookForGlow() {
        for (int look = 0; look < GLOW_LOOKS; look++) {
            Optional<Integer> tile = claimableTileY(frame());
            if (tile.isPresent()) {
                return tile;
            }
            sleepTask(500);
        }
        return Optional.empty();
    }

    private boolean titleShown() {
        return templateSearchHelper.locatePattern(TemplatesEnum.SIGN_IN_EARN_TITLE,
                SearchConfig.builder()
                        .withCoordinates(TITLE_TOP_LEFT, TITLE_BOTTOM_RIGHT)
                        .withMaxAttempts(3)
                        .withDelay(400)
                        .build()).isFound();
    }

    /**
     * The centre row of the Free-column tile with the bright yellow claim frame. Only the thin strips on
     * the tile's two sides are read, so the art inside a tile cannot count.
     */
    static Optional<Integer> claimableTileY(BufferedImage img) {
        int start = -1;
        int last = -1;
        int pixels = 0;
        for (int y = TRACK_TOP; y < TRACK_BOTTOM; y++) {
            int row = glowPixels(img, y);
            if (row > 0) {
                if (start >= 0 && y - last > GLOW_MAX_GAP) {
                    if (isGlow(start, last, pixels)) {
                        return Optional.of((start + last) / 2);
                    }
                    start = -1;
                }
                if (start < 0) {
                    start = y;
                    pixels = 0;
                }
                last = y;
                pixels += row;
            }
        }
        return start >= 0 && isGlow(start, last, pixels) ? Optional.of((start + last) / 2) : Optional.empty();
    }

    private static boolean isGlow(int start, int last, int pixels) {
        return pixels >= GLOW_MIN_PIXELS && last - start >= GLOW_MIN_HEIGHT;
    }

    private static int glowPixels(BufferedImage img, int y) {
        int count = 0;
        for (int[] strip : GLOW_STRIPS) {
            for (int x = strip[0]; x < strip[1]; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r >= 225 && g >= 170 && b <= 170 && r - b >= 70) {
                    count++;
                }
            }
        }
        return count;
    }

    private Duration sinceLastReset() {
        return Duration.between(GameTimeUtils.dailyResetTime().minusDays(1), LocalDateTime.now());
    }

    private LocalDateTime afterNextReset() {
        return GameTimeUtils.dailyResetTime().plusMinutes(
                ThreadLocalRandom.current().nextInt(RESET_PAD_MIN_MINUTES, RESET_PAD_MAX_MINUTES + 1));
    }

    private LocalDateTime failureRetry() {
        LocalDateTime soon = LocalDateTime.now().plus(FAILURE_RETRY);
        LocalDateTime reset = afterNextReset();
        return soon.isBefore(reset) ? soon : reset;
    }

    private void finish(LocalDateTime when) {
        logInfo("Next Sign-in & Earn pass at " + when.format(DATETIME_FORMATTER) + ".");
        rescheduleExact(when);
    }

    private BufferedImage frame() {
        RawImageData raw = emuManager.captureScreen(EMULATOR_NUMBER);
        return raw == null ? new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB) : ImageConverter.toBufferedImage(raw);
    }
}
