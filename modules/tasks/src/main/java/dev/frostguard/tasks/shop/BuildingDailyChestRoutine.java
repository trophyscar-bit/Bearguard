package dev.frostguard.tasks.shop;

import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;

/**
 * Bakery, Milk Tea and Vinyl shop daily rewards. The three buildings stand on the city map and
 * each shows the same golden chest bubble while the day's reward is unclaimed; tapping any one
 * opens "Daily Building Rewards", which pays all three at once.
 *
 * <p>The city camera keeps wherever the last routine left it and the buildings are not on a
 * fixed screen position, so the routine looks at the current view and then pans in a bounded
 * pattern. A bubble that is not found is unknown, never claimed: the next check is soon, not at
 * the next reset. See docs/task/building-daily-chest.md.
 */
public class BuildingDailyChestRoutine extends DelayedTask {

    // Measured on saved frames: 93-97 on the other buildings' bubbles, 51-60 on frames without one.
    private static final SearchConfig BUBBLE_SEARCH =
            SearchConfig.builder().withMaxAttempts(2).withThreshold(85).withDelay(250L).build();

    private static final PointData EXIT_POPUP_TAP = new PointData(360, 1000);
    private static final int TAP_SETTLE_MS = 2500;
    private static final int PAN_SETTLE_MS = 1500;
    private static final int CLAIM_ATTEMPTS = 2;

    // Panning stays clear of the HUD and the bottom bar, and each pan moves about two thirds of a screen
    // so consecutive views overlap.
    private static final int PAN_DISTANCE = 420;
    private static final int PAN_Y = 640;
    private static final int PAN_X_NEAR = 150;
    private static final int PAN_X_FAR = PAN_X_NEAR + PAN_DISTANCE;

    private enum Pan {
        LEFT(PAN_X_FAR, PAN_Y, PAN_X_NEAR, PAN_Y),
        RIGHT(PAN_X_NEAR, PAN_Y, PAN_X_FAR, PAN_Y),
        UP(360, 800, 360, 800 - PAN_DISTANCE),
        DOWN(360, 380, 360, 380 + PAN_DISTANCE);

        final PointData from;
        final PointData to;

        Pan(int fx, int fy, int tx, int ty) {
            this.from = new PointData(fx, fy);
            this.to = new PointData(tx, ty);
        }
    }

    // Expanding square spiral around the starting view, then back toward it: 12 pans, each view overlapping.
    private static final Pan[] SCAN = {
            Pan.LEFT, Pan.DOWN, Pan.RIGHT, Pan.RIGHT, Pan.UP, Pan.UP,
            Pan.LEFT, Pan.LEFT, Pan.LEFT, Pan.DOWN, Pan.DOWN, Pan.DOWN
    };

    public BuildingDailyChestRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }

    @Override
    protected void execute() {
        ImageSearchResultData bubble = findBubbleWithScan();
        if (!bubble.isFound()) {
            logWarning(logLine("No daily chest bubble found in the current view or after " + SCAN.length
                    + " pans. Unknown, not claimed: this is either already claimed today or the shops are outside the area searched."
                    + " Rechecking in " + BuildingDailyChestSchedule.UNKNOWN_RECHECK.toHours() + " hours."));
            reschedule(BuildingDailyChestSchedule.recheck(
                    Instant.now(), BuildingDailyChestSchedule.UNKNOWN_RECHECK, ZoneId.systemDefault()));
            return;
        }

        if (claim(bubble)) {
            int pad = ThreadLocalRandom.current().nextInt(
                    BuildingDailyChestSchedule.MIN_PAD_MINUTES, BuildingDailyChestSchedule.MAX_PAD_MINUTES + 1);
            var next = BuildingDailyChestSchedule.nextClaim(Instant.now(), pad, ZoneId.systemDefault());
            StatisticsService.obtain().addToCounter(profile, "City Shops Daily Chest Claimed", 1);
            logInfo(logLine("Claimed; the bubble is gone. Next claim " + next + " (UTC reset + " + pad + " min)."));
            reschedule(next);
        } else {
            logWarning(logLine("Tapped the chest bubble " + CLAIM_ATTEMPTS
                    + " times but it is still there. Not counted as claimed; retrying in "
                    + BuildingDailyChestSchedule.FAILED_CLAIM_RECHECK.toHours() + " hour."));
            reschedule(BuildingDailyChestSchedule.recheck(
                    Instant.now(), BuildingDailyChestSchedule.FAILED_CLAIM_RECHECK, ZoneId.systemDefault()));
        }
    }

    private ImageSearchResultData findBubbleWithScan() {
        ImageSearchResultData hit = templateSearchHelper.locatePattern(TemplatesEnum.BUILDING_DAILY_CHEST, BUBBLE_SEARCH);
        if (hit.isFound()) {
            logInfo(logLine("Chest bubble in the starting view (" + hit.getMatchScore() + "%)."));
            return hit;
        }
        for (int step = 0; step < SCAN.length; step++) {
            Pan pan = SCAN[step];
            swipe(pan.from, pan.to, 500);
            sleepTask(PAN_SETTLE_MS);
            hit = templateSearchHelper.locatePattern(TemplatesEnum.BUILDING_DAILY_CHEST, BUBBLE_SEARCH);
            if (hit.isFound()) {
                logInfo(logLine("Chest bubble found after pan " + (step + 1) + "/" + SCAN.length + " "
                        + pan + " (" + hit.getMatchScore() + "%)."));
                return hit;
            }
        }
        return hit;
    }

    /** True only when the bubble is gone after the reward popup is dismissed. */
    private boolean claim(ImageSearchResultData bubble) {
        ImageSearchResultData target = bubble;
        for (int attempt = 1; attempt <= CLAIM_ATTEMPTS; attempt++) {
            tapNear(target.getPoint());
            sleepTask(TAP_SETTLE_MS);
            // "Tap anywhere to exit" on the Daily Building Rewards popup.
            tapNear(EXIT_POPUP_TAP);
            sleepTask(TAP_SETTLE_MS);

            ImageSearchResultData again = templateSearchHelper.locatePattern(TemplatesEnum.BUILDING_DAILY_CHEST, BUBBLE_SEARCH);
            if (!again.isFound()) {
                return true;
            }
            logInfo(logLine("Bubble still present after attempt " + attempt + "; retrying."));
            target = again;
        }
        return false;
    }

    private String logLine(String note) {
        return "BuildingDailyChestRoutine | " + note;
    }
}
