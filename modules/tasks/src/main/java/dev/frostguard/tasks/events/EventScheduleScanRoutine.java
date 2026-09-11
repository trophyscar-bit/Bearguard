package dev.frostguard.tasks.events;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.engine.helper.BearTrapHelper;
import dev.frostguard.engine.helper.TimeWindowHelper.WindowResult;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.BearTrapParticipationSchedule;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.EventScheduleService;

/**
 * Detection-only sweep feeding the "Upcoming Events" sidebar calendar. Never taps Claim --
 * {@link EventClaimRoutine} still owns that -- this only records what is currently visible.
 *
 * <p>The rotating Events-tab set ({@link EventKind}) has no on-screen countdown, only "is this
 * tab currently showing," so each {@link dev.frostguard.data.repository.EventScheduleRepository}
 * entry for those is a presence/absence transition timestamp, accurate only to this routine's
 * scan interval -- not an exact game clock reading. Bear Trap is different: its window is
 * already fully deterministic from a configured anchor via {@link BearTrapHelper}, so that
 * entry is recomputed and overwritten every scan instead of inferred from screen state.</p>
 */
public class EventScheduleScanRoutine extends DelayedTask {

    private static final String BEAR_TRAP_EVENT_KEY = "BEAR_TRAP";
    private static final String BEAR_TRAP_LABEL = "Bear Trap";

    private static final int PANEL_SETTLE_MS = 1200;
    private static final int DEFAULT_SCAN_INTERVAL_HOURS = 3;
    private static final int DEFAULT_TRAP_NUMBER = 1;
    private static final int DEFAULT_TRAP_PREPARATION_MINUTES = 10;

    public EventScheduleScanRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    protected void execute() {
        scanRotatingEvents();
        scanBearTrapWindow();
        reschedule(LocalDateTime.now().plusHours(DEFAULT_SCAN_INTERVAL_HOURS));
    }

    private void scanRotatingEvents() {
        ImageSearchResultData eventsBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.HOME_EVENTS_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!eventsBtn.isFound()) {
            logInfo("EventScheduleScanRoutine | Events icon not found; leaving rotating-event entries as last observed.");
            return;
        }

        tapNear(eventsBtn.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        for (EventKind kind : EventKind.values()) {
            ImageSearchResultData tab = templateSearchHelper.locatePattern(
                    kind.getTabTemplate(), SearchConfigConstants.QUICK_SEARCH);
            boolean active = tab.isFound();
            EventScheduleService.obtain().recordObservation(kind.name(), kind.getLabel(), active);
            logInfo("EventScheduleScanRoutine | " + kind.getLabel() + ": " + (active ? "active" : "not showing"));
        }

        pressBack();
        sleepTask(PANEL_SETTLE_MS);
    }

    private void scanBearTrapWindow() {
        try {
            int trapNumber = resolveConfigInt(ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT, DEFAULT_TRAP_NUMBER);
            LocalDateTime referenceTrapTime = profile.getConfig(
                    BearTrapParticipationSchedule.scheduleKey(trapNumber), LocalDateTime.class);
            if (referenceTrapTime == null) {
                logInfo("EventScheduleScanRoutine | Bear Trap reference time not configured; skipping its calendar entry.");
                return;
            }
            int prepMinutes = resolveConfigInt(
                    ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT, DEFAULT_TRAP_PREPARATION_MINUTES);

            Instant referenceUTC = referenceTrapTime.atZone(ZoneId.of("UTC")).toInstant();
            WindowResult window = BearTrapHelper.calculateWindow(referenceUTC, prepMinutes);

            boolean active = window.getState() == dev.frostguard.engine.helper.TimeWindowHelper.WindowState.INSIDE;
            Instant windowStart = active ? window.getCurrentWindowStart() : window.getNextWindowStart();
            Instant windowEnd = active
                    ? window.getCurrentWindowEnd()
                    : window.getNextWindowStart().plusSeconds(window.getCurrentWindowDurationMinutes() * 60L);

            EventScheduleService.obtain().recordWindow(BEAR_TRAP_EVENT_KEY, BEAR_TRAP_LABEL, active,
                    LocalDateTime.ofInstant(windowStart, ZoneId.of("UTC")),
                    LocalDateTime.ofInstant(windowEnd, ZoneId.of("UTC")));
            logInfo("EventScheduleScanRoutine | Bear Trap: " + window);
        } catch (Exception ex) {
            logWarning("EventScheduleScanRoutine | Could not compute Bear Trap's window this scan: " + ex.getMessage());
        }
    }

    private int resolveConfigInt(ConfigurationKeyEnum key, int defaultValue) {
        Integer value = profile.getConfig(key, Integer.class);
        return value == null ? defaultValue : value;
    }
}
