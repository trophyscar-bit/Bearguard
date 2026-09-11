package dev.frostguard.tasks.events;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.NavigationHelper;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.EventScheduleService;

/**
 * Reads Alliance -&gt; Battle -&gt; Fortress -- occupied Fortress/Stronghold facilities and their
 * "Reward expires in" countdown -- feeding the sidebar's "Upcoming Events" calendar. Read-only:
 * never taps Occupation Income, a facility card, or anything else on this screen.
 *
 * <p>Live-verified panel shape (2026-09-11): a "Signed up" / "Not signed up" pair, then repeating
 * cards each reading {@code "Fortress No. 9"} or {@code "Stronghold No. 2"}, an
 * {@code "Occupied"} status icon, {@code "Controlled by [INF] Infinity"}, and
 * {@code "Reward expires in 12:53:54"}. Card order/count is dynamic (however many facilities the
 * alliance currently holds), so this OCR-reads the whole panel as one text block and pairs each
 * facility name with the next "Reward expires in" duration that follows it, rather than assuming
 * fixed row positions.</p>
 */
public class AllianceFortressScanRoutine extends DelayedTask {

    private static final int PANEL_SETTLE_MS = 1200;
    private static final int SCAN_INTERVAL_HOURS = 1;

    /** Below the "Signed up" / phase header, covering the facility card list. */
    private static final PointData PANEL_TOP_LEFT = new PointData(10, 580);
    private static final PointData PANEL_BOTTOM_RIGHT = new PointData(710, 1270);

    private static final PointData SCROLL_FROM = new PointData(360, 1100);
    private static final PointData SCROLL_TO = new PointData(360, 650);

    private static final Pattern FACILITY_NAME = Pattern.compile(
            "(Fortress No\\.?\\s*\\d+|Stronghold No\\.?\\s*\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROLLED_BY = Pattern.compile(
            "Controlled by\\s*([^\\n]{1,40})", Pattern.CASE_INSENSITIVE);
    private static final Pattern REWARD_EXPIRES = Pattern.compile(
            "Reward expires in\\s*(?:(\\d+)\\s*d\\s*)?(\\d{1,2}):(\\d{2}):(\\d{2})", Pattern.CASE_INSENSITIVE);

    public AllianceFortressScanRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    protected void execute() {
        if (!navigationHelper.navigateToAllianceMenu(NavigationHelper.AllianceMenu.BATTLE)) {
            logInfo("AllianceFortressScanRoutine | Could not open Alliance > Battle; rechecking in "
                    + SCAN_INTERVAL_HOURS + "h.");
            reschedule(LocalDateTime.now().plusHours(SCAN_INTERVAL_HOURS));
            return;
        }
        sleepTask(PANEL_SETTLE_MS);

        ImageSearchResultData fortressTab = templateSearchHelper.locatePattern(
                TemplatesEnum.ALLIANCE_BATTLE_FORTRESS_TAB, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (fortressTab.isFound()) {
            tapNear(fortressTab.getPoint());
            sleepTask(PANEL_SETTLE_MS);
        } else {
            logInfo("AllianceFortressScanRoutine | Fortress tab not found (may already be selected); "
                    + "reading whatever is on screen.");
        }

        int recorded = recordFacilitiesFrom(readPanelBlock());

        // One scroll to pick up facilities beyond the first screenful; dedupes naturally since
        // recordFacilitiesFrom writes by facility-name key, so a re-seen card just overwrites itself.
        swipe(SCROLL_FROM, SCROLL_TO);
        sleepTask(PANEL_SETTLE_MS);
        recorded += recordFacilitiesFrom(readPanelBlock());

        logInfo("AllianceFortressScanRoutine | Recorded " + recorded + " facility window(s).");

        pressBack();
        sleepTask(600);
        pressBack();

        reschedule(LocalDateTime.now().plusHours(SCAN_INTERVAL_HOURS));
    }

    private int recordFacilitiesFrom(String panelText) {
        if (panelText == null || panelText.isBlank()) {
            return 0;
        }

        int recorded = 0;
        Matcher nameMatcher = FACILITY_NAME.matcher(panelText);
        int previousEnd = -1;
        String previousName = null;

        while (nameMatcher.find()) {
            if (previousName != null) {
                recorded += recordOneFacility(previousName, panelText.substring(previousEnd, nameMatcher.start()));
            }
            previousName = nameMatcher.group(1).replaceAll("\\s+", " ").trim();
            previousEnd = nameMatcher.end();
        }
        if (previousName != null) {
            recorded += recordOneFacility(previousName, panelText.substring(previousEnd));
        }
        return recorded;
    }

    private int recordOneFacility(String facilityName, String followingText) {
        Matcher expires = REWARD_EXPIRES.matcher(followingText);
        if (!expires.find()) {
            // Occupiable/unoccupied cards show no "Reward expires in" line -- nothing to schedule.
            return 0;
        }

        long days = expires.group(1) == null ? 0 : Long.parseLong(expires.group(1));
        int hours = Integer.parseInt(expires.group(2));
        int minutes = Integer.parseInt(expires.group(3));
        int seconds = Integer.parseInt(expires.group(4));
        Duration remaining = Duration.ofDays(days).plusHours(hours).plusMinutes(minutes).plusSeconds(seconds);

        Matcher controller = CONTROLLED_BY.matcher(followingText);
        String controlledBy = controller.find() ? controller.group(1).trim() : null;
        String label = controlledBy == null ? facilityName : facilityName + " (" + controlledBy + ")";

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime expiresAtUtc = nowUtc.plus(remaining);

        EventScheduleService.obtain().recordWindow(
                "FORTRESS_" + facilityName.toUpperCase().replaceAll("[^A-Z0-9]+", "_"),
                label, true, nowUtc, expiresAtUtc);
        logInfo("AllianceFortressScanRoutine | " + label + ": reward expires in " + remaining + ".");
        return 1;
    }

    private String readPanelBlock() {
        try {
            return stringHelper.attemptRecognition(
                    PANEL_TOP_LEFT, PANEL_BOTTOM_RIGHT, 2, 200L,
                    OcrSettingsData.forTextBlock(),
                    s -> s != null && !s.isBlank(),
                    String::trim);
        } catch (Exception ex) {
            return null;
        }
    }
}
