package dev.frostguard.tasks.shop;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.OptionalInt;
import java.util.concurrent.ThreadLocalRandom;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;

/**
 * Top-right cart-icon Shop panel, "Daily Deals" tab (second tab,
 * next to Custom Armament Chest). Has a genuinely free "Free" chest badge on the
 * left side of the banner, once a day -- everything else on this tab is a paid
 * pack ($0.99-$4.99). This routine ONLY ever taps the free badge.
 *
 * <p>
 * <b>Live-verified 2026-08-13</b>: cart icon -> Daily Deals tab -> Free chest badge
 * tap -> reward reveal (100 gems, confirmed via balance going 47,599 -> 47,699) ->
 * badge instantly replaced by a countdown timer, same silent single-tap-claim
 * pattern as Custom Armament Chest.
 */
public class DailyDealsFreeChestRoutine extends DelayedTask {

    /** Bottom of the screen, clear of every purchase button. */
    private static final PointData REWARD_DISMISS_TAP = new PointData(360, 1270);
    private static final int TAB_STRIP_TAP_Y = 141;
    private static final int MAX_STRIP_SWIPES = 5;
    private static final int POST_RESET_DELAY_MINUTES = 70;
    private static final int PANEL_SETTLE_MS = 1200;
    private static final int ACTION_SETTLE_MS = 900;

    public DailyDealsFreeChestRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }

    // Same fix as CustomArmamentChestRoutine -- a single locatePattern attempt for
    // the cart icon had no recovery if some other task left a popup/panel open, so it silently gave
    // up and waited a full day instead of clearing back to a known screen and retrying.
    private static final int MAX_NAV_RETRIES = 3;

    private ImageSearchResultData locateCartButtonRobust() {
        ImageSearchResultData cartBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.HOME_SHOP_CART_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (cartBtn.isFound()) {
            return cartBtn;
        }

        logInfo(logLine("Cart icon not visible on the first look -- clearing whatever's in the way "
                + "and retrying instead of assuming it's genuinely gone."));
        for (int attempt = 1; attempt <= MAX_NAV_RETRIES; attempt++) {
            pressBack();
            sleepTask(500);
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
            cartBtn = templateSearchHelper.locatePattern(
                    TemplatesEnum.HOME_SHOP_CART_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
            if (cartBtn.isFound()) {
                logInfo(logLine("Cart icon found after " + attempt + " recovery attempt(s)."));
                return cartBtn;
            }
        }
        return cartBtn;
    }

    @Override
    protected void execute() {
        ImageSearchResultData cartBtn = locateCartButtonRobust();
        if (!cartBtn.isFound()) {
            giveUp("cart-icon-missing", "Shop cart icon not found even after clearing/retrying.", false);
            return;
        }
        tapNear(cartBtn.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        ImageSearchResultData header = templateSearchHelper.locatePattern(
                TemplatesEnum.GEM_SHOP_HEADER, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!header.isFound()) {
            giveUp("gem-shop-not-open", "Tapped the cart icon but the Gem Shop did not open.", true);
            return;
        }

        // The strip's tab order changes with the active events, so Daily Deals has no fixed slot:
        // look for its selected look or its label, walking the strip a bounded number of swipes.
        PointData tabPoint = findDailyDealsTab();
        if (tabPoint == null) {
            giveUp("daily-deals-tab-not-found", "Daily Deals tab not found in the tab strip.", true);
            return;
        }
        tapNear(tabPoint);
        sleepTask(PANEL_SETTLE_MS);

        ImageSearchResultData tab = templateSearchHelper.locatePattern(
                TemplatesEnum.SHOP_TAB_DAILY_DEALS, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!tab.isFound()) {
            giveUp("daily-deals-tab-not-selected", "Daily Deals tab is not selected after tapping it.", true);
            return;
        }

        ImageSearchResultData freeChest = templateSearchHelper.locatePattern(
                TemplatesEnum.SHOP_DAILY_DEALS_FREE_CHEST, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (freeChest.isFound()) {
            logInfo(logLine("Free chest badge found. Claiming."));
            tapNear(freeChest.getPoint());
            sleepTask(ACTION_SETTLE_MS);
            tapNear(REWARD_DISMISS_TAP);
            sleepTask(ACTION_SETTLE_MS);

            ImageSearchResultData stillThere = templateSearchHelper.locatePattern(
                    TemplatesEnum.SHOP_DAILY_DEALS_FREE_CHEST, SearchConfigConstants.SINGLE_WITH_RETRIES);
            if (stillThere.isFound()) {
                giveUp("claim-not-registered", "Free chest still showing after the claim tap.", true);
                return;
            }
            StatisticsService.obtain().addToCounter(profile, "Daily Deals Free Chest Claimed", 1);
        } else {
            logInfo(logLine("Free chest already claimed today."));
        }

        pressBack();

        LocalDateTime next = ShopChestSchedule.nextRunAfterSuccess(ZonedDateTime.now(ZoneOffset.UTC), POST_RESET_DELAY_MINUTES);
        logInfo(logLine("Done. Next check " + next + " (70 minutes after the UTC reset)."));
        reschedule(next);
    }

    private PointData locateDailyDealsTab() {
        ImageSearchResultData selected = templateSearchHelper.locatePattern(
                TemplatesEnum.SHOP_TAB_DAILY_DEALS, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (selected.isFound()) {
            return selected.getPoint();
        }
        RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
        if (frame == null || !frame.isValid()) {
            return null;
        }
        OptionalInt x = DailyDealsTabLabel.centerX(DailyDealsTabLabel.read(
                frame, DailyDealsTabLabel.LABEL_TOP_LEFT, DailyDealsTabLabel.LABEL_BOTTOM_RIGHT));
        return x.isPresent() ? new PointData(x.getAsInt(), TAB_STRIP_TAP_Y) : null;
    }

    /** Looks where the strip opened, then walks it toward its end and back toward its start. */
    private PointData findDailyDealsTab() {
        PointData found = locateDailyDealsTab();
        if (found != null) {
            return found;
        }
        for (boolean towardEnd : new boolean[] {true, false}) {
            for (int swipe = 0; swipe < MAX_STRIP_SWIPES; swipe++) {
                if (towardEnd) {
                    swipe(CommonGameAreas.DEALS_TAB_STRIP_SWIPE_FROM, CommonGameAreas.DEALS_TAB_STRIP_SWIPE_TO, 400);
                } else {
                    swipe(CommonGameAreas.DEALS_TAB_STRIP_SWIPE_TO, CommonGameAreas.DEALS_TAB_STRIP_SWIPE_FROM, 400);
                }
                sleepTask(900);
                found = locateDailyDealsTab();
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void giveUp(String reason, String note, boolean closePanel) {
        String snapshot = TaskDiagnosticSnapshots.captureAlways(emuManager, EMULATOR_NUMBER, "dailydealschest", reason);
        if (closePanel) {
            pressBack();
        }
        int minutes = ThreadLocalRandom.current().nextInt(ShopChestSchedule.RETRY_MIN_MINUTES, ShopChestSchedule.RETRY_MAX_MINUTES + 1);
        LocalDateTime next = ShopChestSchedule.nextRetryAfterFailure(ZonedDateTime.now(ZoneOffset.UTC), minutes, POST_RESET_DELAY_MINUTES);
        logInfo(logLine(note + " Retrying at " + next + ". " + snapshot));
        reschedule(next);
    }

    private String logLine(String note) {
        return "DailyDealsFreeChestRoutine | " + note;
    }
}
