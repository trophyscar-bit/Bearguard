package dev.frostguard.tasks.events;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.NavigationHelper.EventMenu;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.ImageConverter;

/**
 * Endless Trial (Events tab): attack the event boss three times a day, then claim the three
 * Daily Rewards tiers ("Attack ... 1/2/3 time(s)").
 *
 * <p>Progress is read from the Daily Rewards panel, not remembered: a row whose threshold is not
 * met has a grey Claim, a met row a green Claim, a claimed row a green tick. Measured on live
 * frames (2026-09-19) as vivid-green pixels inside the 135x40 button box: grey 0, tick ~1250,
 * green Claim ~4030. Green and grey Claim share a shape (template scores 97.7 vs 91.5), so colour
 * is the only reliable separator. One Claim tap collects every claimable tier.
 *
 * <p>Go travels to the boss on the world map; Attack opens the formation screen, whose Deploy
 * button matches the Bear Hunt deploy template (98.3% vs a 41% noise floor). A result popup only
 * appears when the attack sets a new damage record, so it is optional. No troop recall is needed:
 * the formation is filled from troops at home even with five gathering marches out.
 */
public class EndlessTrialRoutine extends DelayedTask {

    private static final int DAILY_ATTACKS = 3;
    private static final Duration RUN_AFTER_RESET = Duration.ofHours(1);

    private static final PointData TITLE_TL = new PointData(30, 205);
    private static final PointData TITLE_BR = new PointData(340, 260);
    private static final PointData GO_BUTTON = new PointData(360, 1180);
    private static final PointData DAILY_REWARDS_BUTTON = new PointData(90, 1180);

    private static final PointData REWARDS_TITLE_TL = new PointData(230, 325);
    private static final PointData REWARDS_TITLE_BR = new PointData(490, 375);
    private static final PointData REWARDS_CLOSE = new PointData(665, 348);
    private static final PointData REWARD_REVEAL_TAP_ANYWHERE = new PointData(360, 1180);
    private static final int[] REWARD_ROW_CENTER_Y = {545, 714, 882};
    private static final int CLAIM_BOX_X1 = 500;
    private static final int CLAIM_BOX_X2 = 635;
    private static final int CLAIM_BOX_HALF_HEIGHT = 20;
    private static final int GREEN_CLAIM_MIN_PIXELS = 3000;
    private static final int TICK_MIN_PIXELS = 800;
    private static final int TICK_MAX_PIXELS = 2000;
    private static final int NOT_REACHED_MAX_PIXELS = 200;

    private static final PointData TRAVEL_TIME_TL = new PointData(300, 1135);
    private static final PointData TRAVEL_TIME_BR = new PointData(420, 1170);
    private static final long MARCH_RETURN_PAD_MS = 5_000L;
    private static final long MAX_TRAVEL_MS = 120_000L;

    private static final AreaData ATTACK_BUTTON_AREA = new AreaData(new PointData(150, 900), new PointData(720, 1150));
    private static final AreaData DEPLOY_BUTTON_AREA = new AreaData(new PointData(150, 1150), new PointData(570, 1280));
    private static final AreaData RESULT_CONFIRM_AREA = new AreaData(new PointData(150, 820), new PointData(570, 1020));

    private static final int PANEL_SETTLE_MS = 1500;
    private static final int ACTION_SETTLE_MS = 1000;

    enum RowState { NOT_REACHED, CLAIMABLE, CLAIMED }

    public EndlessTrialRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    protected void execute() {
        openEndlessTrial();
        List<RowState> rows = readRewardRows();
        int attacksDone = attacksReached(rows);
        logInfo(logLine("Daily Rewards rows " + rows + " -> " + attacksDone + "/" + DAILY_ATTACKS + " attacks done today."));

        while (attacksDone < DAILY_ATTACKS) {
            tapNear(REWARDS_CLOSE);
            sleepTask(ACTION_SETTLE_MS);
            attackOnce(attacksDone + 1);
            openEndlessTrial();
            rows = readRewardRows();
            int after = attacksReached(rows);
            if (after <= attacksDone) {
                throw new IllegalStateException("Endless Trial attack " + (attacksDone + 1)
                        + " did not register: Daily Rewards still shows " + rows);
            }
            attacksDone = after;
            StatisticsService.obtain().addToCounter(profile, "Endless Trial Attacks", 1);
            logInfo(logLine("Attack registered -> " + attacksDone + "/" + DAILY_ATTACKS + "."));
        }

        int claimableRow = rows.indexOf(RowState.CLAIMABLE);
        if (claimableRow >= 0) {
            claimRewards(claimableRow);
            rows = readRewardRows();
            if (rows.contains(RowState.CLAIMABLE)) {
                throw new IllegalStateException("Endless Trial Claim tapped but rows still claimable: " + rows);
            }
            logInfo(logLine("Daily rewards claimed -> " + rows + "."));
        } else {
            logInfo(logLine("Daily rewards already claimed."));
        }

        tapNear(REWARDS_CLOSE);
        sleepTask(ACTION_SETTLE_MS);
        pressBack();

        LocalDateTime next = GameTimeUtils.dailyResetTime().plus(RUN_AFTER_RESET);
        logInfo(logLine("Done for today. Next run one hour after the daily reset: " + next.format(DATETIME_FORMATTER) + "."));
        reschedule(next);
    }

    private void openEndlessTrial() {
        ImageSearchResultData eventsBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.HOME_EVENTS_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!eventsBtn.isFound()) {
            throw new IllegalStateException("Events button not found on the world screen.");
        }
        tapNear(eventsBtn.getPoint());
        sleepTask(PANEL_SETTLE_MS);
        if (titleReads(TITLE_TL, TITLE_BR, "ENDLESS")) {
            return;
        }

        // Events reopens on whichever tab it last showed. A selected tab shows only its icon, so
        // the tab template (cut unselected, icon plus label) is searched only after leaving Events.
        pressBack();
        sleepTask(ACTION_SETTLE_MS);
        navigationHelper.navigateToEventMenu(EventMenu.ENDLESS_TRIAL);
        sleepTask(PANEL_SETTLE_MS);
        if (!titleReads(TITLE_TL, TITLE_BR, "ENDLESS")) {
            throw new IllegalStateException("Endless Trial panel not reached (title did not read 'Endless Trial'); "
                    + "the event may not be running.");
        }
    }

    private List<RowState> readRewardRows() {
        tapNear(DAILY_REWARDS_BUTTON);
        sleepTask(PANEL_SETTLE_MS);
        if (!titleReads(REWARDS_TITLE_TL, REWARDS_TITLE_BR, "DAILY")) {
            throw new IllegalStateException("Endless Trial Daily Rewards panel did not open.");
        }

        List<RowState> rows = classifyRewardRows(
                ImageConverter.toBufferedImage(emuManager.captureScreen(EMULATOR_NUMBER)));
        logDebug(logLine("Reward rows -> " + rows));
        return rows;
    }

    static List<RowState> classifyRewardRows(BufferedImage frame) {
        List<RowState> rows = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (int centerY : REWARD_ROW_CENTER_Y) {
            AreaData box = new AreaData(new PointData(CLAIM_BOX_X1, centerY - CLAIM_BOX_HALF_HEIGHT),
                    new PointData(CLAIM_BOX_X2, centerY + CLAIM_BOX_HALF_HEIGHT));
            int green = PixelStats.count(frame, box, GameColors::isVividGreen);
            counts.add(green);
            if (green >= GREEN_CLAIM_MIN_PIXELS) {
                rows.add(RowState.CLAIMABLE);
            } else if (green >= TICK_MIN_PIXELS && green <= TICK_MAX_PIXELS) {
                rows.add(RowState.CLAIMED);
            } else if (green <= NOT_REACHED_MAX_PIXELS) {
                rows.add(RowState.NOT_REACHED);
            } else {
                throw new IllegalStateException("Endless Trial reward row unreadable: green pixels " + counts
                        + " fall between the measured grey/tick/Claim bands.");
            }
        }
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i) != RowState.NOT_REACHED && rows.get(i - 1) == RowState.NOT_REACHED) {
                throw new IllegalStateException("Endless Trial reward rows out of order: " + rows
                        + " (green pixels " + counts + ")");
            }
        }
        return rows;
    }

    static int attacksReached(List<RowState> rows) {
        return (int) rows.stream().filter(state -> state != RowState.NOT_REACHED).count();
    }

    private void attackOnce(int attackNumber) {
        tapNear(GO_BUTTON);
        sleepTask(PANEL_SETTLE_MS);
        ImageSearchResultData attack = templateSearchHelper.locatePattern(
                TemplatesEnum.ENDLESS_TRIAL_ATTACK_BUTTON, inArea(ATTACK_BUTTON_AREA, 3));
        if (!attack.isFound()) {
            throw new IllegalStateException("Endless Trial boss Attack button not found after Go.");
        }
        tapNear(attack.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        ImageSearchResultData deploy = templateSearchHelper.locatePattern(
                TemplatesEnum.BEAR_DEPLOY_BUTTON, inArea(DEPLOY_BUTTON_AREA, 3));
        if (!deploy.isFound()) {
            pressBack();
            throw new IllegalStateException("Endless Trial formation screen (Deploy) did not open after Attack.");
        }
        Duration travel = readTravelTime();
        tapNear(deploy.getPoint());
        sleepTask(ACTION_SETTLE_MS);
        if (templateSearchHelper.locatePattern(TemplatesEnum.BEAR_DEPLOY_BUTTON, inArea(DEPLOY_BUTTON_AREA, 1)).isFound()) {
            pressBack();
            throw new IllegalStateException("Endless Trial Deploy did not send a march (no free march slot?).");
        }
        logInfo(logLine("Attack " + attackNumber + " deployed; travel " + travel.toSeconds()
                + "s each way, waiting for the round trip."));

        // The damage popup only shows on a new record and appears when the march lands; waiting the
        // full round trip also frees the march slot for the next attack.
        sleepTask(travel.toMillis() * 2 + MARCH_RETURN_PAD_MS);
        ImageSearchResultData confirm = templateSearchHelper.locatePattern(
                TemplatesEnum.ENDLESS_TRIAL_RESULT_CONFIRM_BUTTON, inArea(RESULT_CONFIRM_AREA, 2));
        if (confirm.isFound()) {
            logInfo(logLine("New damage record popup shown; confirming."));
            tapNear(confirm.getPoint());
            sleepTask(ACTION_SETTLE_MS);
        }
    }

    private Duration readTravelTime() {
        String text = readStringValue(TRAVEL_TIME_TL, TRAVEL_TIME_BR, CommonOCRSettings.TRAVEL_TIME_SETTINGS);
        Duration travel;
        try {
            travel = GameTimeUtils.parseDuration(text);
        } catch (IllegalArgumentException e) {
            pressBack();
            throw new IllegalStateException("Endless Trial travel time unreadable: '" + text + "'");
        }
        if (travel.isZero() || travel.toMillis() > MAX_TRAVEL_MS) {
            pressBack();
            throw new IllegalStateException("Endless Trial travel time implausible: '" + text + "' -> " + travel);
        }
        return travel;
    }

    private void claimRewards(int rowIndex) {
        int y = REWARD_ROW_CENTER_Y[rowIndex];
        tapNear(new PointData((CLAIM_BOX_X1 + CLAIM_BOX_X2) / 2, y));
        sleepTask(PANEL_SETTLE_MS);
        tapNear(REWARD_REVEAL_TAP_ANYWHERE);
        sleepTask(PANEL_SETTLE_MS);
        // The panel stays open behind the reveal; reopening it through the button re-anchors the title.
        tapNear(REWARDS_CLOSE);
        sleepTask(ACTION_SETTLE_MS);
    }

    private boolean titleReads(PointData tl, PointData br, String expected) {
        try {
            String text = emuManager.readText(EMULATOR_NUMBER, tl, br);
            boolean match = text != null && text.toUpperCase(Locale.ROOT).contains(expected);
            if (!match) {
                logInfo(logLine("Expected '" + expected + "' in title, read '" + text + "'."));
            }
            return match;
        } catch (Exception e) {
            logWarning(logLine("Title OCR failed: " + e.getMessage()));
            return false;
        }
    }

    private static SearchConfig inArea(AreaData area, int attempts) {
        return SearchConfig.builder()
                .withArea(area)
                .withThreshold(85)
                .withMaxAttempts(attempts)
                .withDelay(500L)
                .build();
    }

    private String logLine(String note) {
        return "EndlessTrialRoutine | " + note;
    }
}
