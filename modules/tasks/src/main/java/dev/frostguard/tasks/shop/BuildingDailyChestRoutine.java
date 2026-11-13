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
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;

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

    /** Directory under logs/snapshot and the situation type of the frame kept when nothing was found. */
    static final String SNAPSHOT_ACTIVITY = "buildingchest";
    static final String SNAPSHOT_NO_BUBBLE = "nobubble";

    private static final PointData EXIT_POPUP_TAP = new PointData(360, 1000);
    private static final int TAP_SETTLE_MS = 2500;
    private static final int PAN_SETTLE_MS = 1500;
    private static final int CLAIM_ATTEMPTS = 2;

    // The city map has hard edges (north shows static fog, south a clamp), so enough drags toward the
    // north-east corner always land on the same view. The three shops stand together a few steps west of it.
    // Drags repeat to about 100 px, so each stop is searched rather than assumed.
    private static final int ANCHOR_DRAGS_NORTH = 26;
    private static final int ANCHOR_DRAGS_EAST = 30;
    private static final int ANCHOR_DRAG_MS = 250;
    private static final int STEP_DRAG_MS = 1000;

    private static final PointData NORTH_FROM = new PointData(360, 250);
    private static final PointData NORTH_TO = new PointData(360, 1000);
    private static final PointData EAST_FROM = new PointData(650, 640);
    private static final PointData EAST_TO = new PointData(70, 640);

    // One step is a 400 px drag: view south, view west, view north.
    private static final PointData SOUTH_FROM = new PointData(360, 900);
    private static final PointData SOUTH_TO = new PointData(360, 500);
    private static final PointData WEST_FROM = new PointData(200, 640);
    private static final PointData WEST_TO = new PointData(600, 640);

    private record Step(PointData from, PointData to, boolean search) {}

    // From the corner: two south, three west puts the Bakery and Milk Tea at the top-left; then north,
    // west, west, north walks past the Bakery to the Vinyl Shop (frames saved 2026-11-13).
    private static final Step[] ROUTE = {
            new Step(SOUTH_FROM, SOUTH_TO, false), new Step(SOUTH_FROM, SOUTH_TO, false),
            new Step(WEST_FROM, WEST_TO, false), new Step(WEST_FROM, WEST_TO, false),
            new Step(WEST_FROM, WEST_TO, true),
            new Step(SOUTH_TO, SOUTH_FROM, true),
            new Step(WEST_FROM, WEST_TO, true),
            new Step(WEST_FROM, WEST_TO, true),
            new Step(SOUTH_TO, SOUTH_FROM, true)
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
            // The frame at the last stop shows whether the route reached the shops at all, which a
            // log line cannot: without it an already-claimed day and a missed cluster read the same.
            String frame = TaskDiagnosticSnapshots.captureAlways(
                    emuManager, EMULATOR_NUMBER, SNAPSHOT_ACTIVITY, SNAPSHOT_NO_BUBBLE);
            logWarning(logLine("No daily chest bubble found in the current view or after the "
                    + ROUTE.length + "-step route from the north-east corner. Unknown, not claimed: this is either already claimed today or the shops are outside the area searched."
                    + " Final stop saved as " + (frame == null || frame.isEmpty() ? "nothing (snapshot failed)" : frame) + "."
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
        logInfo(logLine("No bubble in the starting view; dragging to the north-east corner of the city map."));
        for (int i = 0; i < ANCHOR_DRAGS_NORTH; i++) {
            swipe(NORTH_FROM, NORTH_TO, ANCHOR_DRAG_MS);
            sleepTask(400);
        }
        for (int i = 0; i < ANCHOR_DRAGS_EAST; i++) {
            swipe(EAST_FROM, EAST_TO, ANCHOR_DRAG_MS);
            sleepTask(400);
        }
        sleepTask(PAN_SETTLE_MS);
        for (int step = 0; step < ROUTE.length; step++) {
            Step s = ROUTE[step];
            swipe(s.from(), s.to(), STEP_DRAG_MS);
            sleepTask(PAN_SETTLE_MS);
            if (!s.search()) {
                continue;
            }
            hit = templateSearchHelper.locatePattern(TemplatesEnum.BUILDING_DAILY_CHEST, BUBBLE_SEARCH);
            if (hit.isFound()) {
                logInfo(logLine("Chest bubble found at route step " + (step + 1) + "/" + ROUTE.length
                        + " (" + hit.getMatchScore() + "%)."));
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
