package dev.frostguard.tasks.lifecycle;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.engine.nav.SidebarViewportChangeDetector;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.ImageConverter;

/**
 * Reads every on-screen countdown and records it, without performing any activity.
 *
 * <p>on watching the bot start: <em>"it's just launching into actually trying
 * to do tasks... It's not taking its time and refreshing all of the timers. It's just blindly
 * trying to rush through every task it has. That's incorrect. That's not the order of operations
 * you and I talked about."</em> He was right, and this is the missing phase.</p>
 *
 * <p>The distinction that matters: the earlier "startup rescan" forced every task due, which made
 * each routine run its <em>whole workload</em> to arrive at a new timer. This reads the timers
 * directly and touches nothing else, so the bot learns what is due before deciding what to do.
 * Once the sweep has run, ordinary dispatch only fires what is genuinely due — a camp with two
 * hours left is simply known about, not visited.</p>
 *
 * <p>Runs first at startup and again every {@link ConfigurationKeyEnum#TIMER_SWEEP_INTERVAL_MINUTES_INT}
 * minutes, the repeat doubling as drift detection against what was recorded earlier.</p>
 */
public class TimerSweepRoutine extends DelayedTask {

    /**
     * City queue panel rows, calibrated from a live 720x1280 frame captured mid-routine showing
     * Furnace 7d 06:05:31, Barricade 05:27:46, Infantry 00:40:30, Lancer 00:41:07,
     * Marksman 00:23:18 and Research 1d 16:44:43 together in one panel.
     *
     * <p>Each entry maps the countdown's screen row to the task whose schedule it governs, so a
     * single panel read updates six separate timers.</p>
     */
    private static final Map<String, PointData[]> CITY_PANEL_ROWS = new LinkedHashMap<>();

    /** Every row shares this x band; only the y differs. Verified against a live panel read. */
    static {
        CITY_PANEL_ROWS.put("Furnace",   row(366));
        CITY_PANEL_ROWS.put("Barricade", row(439));
        CITY_PANEL_ROWS.put("Infantry",  row(553));
        CITY_PANEL_ROWS.put("Lancer",    row(626));
        CITY_PANEL_ROWS.put("Marksman",  row(699));
        CITY_PANEL_ROWS.put("Research",  row(812));
    }

    private static PointData[] row(int top) {
        return new PointData[] { new PointData(150, top), new PointData(305, top + 28) };
    }

    /**
     * Daily-tab scan bounds. The tab keeps whatever scroll offset it was left at, so the scan first
     * rolls it back to the top and then pages down, reading each viewport.
     *
     * <p>The page step is under half the ~570 px viewport, so even with some drag momentum
     * consecutive viewports overlap by more than a whole card and no row can fall between them.</p>
     */
    private static final PointData DAILY_PAGE_DOWN_FROM = new PointData(360, 780);
    private static final PointData DAILY_PAGE_DOWN_TO = new PointData(360, 520);
    private static final PointData DAILY_PAGE_UP_FROM = new PointData(360, 420);
    private static final PointData DAILY_PAGE_UP_TO = new PointData(360, 820);
    private static final int DAILY_SWIPE_MS = 500;
    private static final int DAILY_MAX_TOP_SWIPES = 6;
    private static final int DAILY_MAX_PAGES = 8;

    /**
     * Cover regions of the three tracked Chief Orders on the 2x3 shelf, calibrated from a live
     * 720x1280 capture: Urgent Mobilization top-left, Rush Job top-right, Productivity Day
     * bottom-left. The banner ("On cooldown hh:mm:ss" / "Active hh:mm:ss") prints across these.
     */
    /** Smallest gap between a timer hitting zero and the bot acting on it. */
    private static final int SWEPT_PAD_MIN_SECONDS = 90;

    /** Extra random spread on top of that pad, so two tasks never share an arrival pattern. */
    private static final int SWEPT_PAD_SPREAD_SECONDS = 150;

    private static final java.util.Random PAD_RANDOM = new java.util.Random();

    /** The "Chief Order" heading, used to confirm the shelf actually opened before reading it. */
    private static final PointData[] CHIEF_SHELF_TITLE_AREA =
            { new PointData(230, 105), new PointData(495, 155) };

    private static final Map<TpDailyTaskEnum, PointData[]> CHIEF_ORDER_SLOTS = new LinkedHashMap<>();

    static {
        CHIEF_ORDER_SLOTS.put(TpDailyTaskEnum.CHIEF_ORDER_URGENT_MOBILIZATION,
                new PointData[] { new PointData(120, 290), new PointData(320, 380) });
        CHIEF_ORDER_SLOTS.put(TpDailyTaskEnum.CHIEF_ORDER_RUSH_JOB,
                new PointData[] { new PointData(395, 290), new PointData(600, 380) });
        CHIEF_ORDER_SLOTS.put(TpDailyTaskEnum.CHIEF_ORDER_PRODUCTIVITY_DAY,
                new PointData[] { new PointData(120, 930), new PointData(320, 1020) });
    }

    /**
     * Pulls the timestamp out of a row read.
     *
     * <p>The panel's own iconography bleeds into the crop — a live sweep produced
     * {@code "Jd 01-33-51"} for a 7-day furnace and {@code "~y00:56:06"} for the barricade — so
     * the value is extracted rather than parsed whole, and the day separator is normalised back
     * from whatever the glyph was misread as.</p>
     */
    static Duration extractTimer(String raw) {
        if (raw == null) {
            return null;
        }
        String normalised = normaliseDayGlyphs(raw).replace('-', ':');

        java.util.regex.Matcher m = TIMESTAMP.matcher(normalised);
        if (!m.find()) {
            return null;
        }

        // GameTimeUtils insists on a two-digit hour: "2:41:07" throws where "02:41:07" parses.
        // The panel renders short timers without the leading zero, so pad before handing it over.
        String timestamp = m.group(2);
        if (timestamp.indexOf(':') == 1) {
            timestamp = "0" + timestamp;
        }

        Duration base;
        try {
            base = GameTimeUtils.parseDuration(timestamp);
        } catch (RuntimeException ex) {
            // One unreadable row must never abort the sweep — that turned a single odd value into
            // a completely empty result, which is exactly the silent no-op this sweep must avoid.
            return null;
        }
        if (base == null) {
            return null;
        }
        java.util.regex.Matcher days = DAYS.matcher(normalised);
        return days.find() ? base.plusDays(Long.parseLong(days.group(1))) : base;
    }

    /**
     * Repairs the day prefix before it is parsed.
     *
     * <p>Observed live: a 7-day furnace read cleanly as {@code "7d"}, but a one-day research read
     * as {@code "Jd"} — the digit 1 rendered in this font is routinely mistaken for I, l or J.
     * A digit-only pattern therefore silently dropped the day and understated the timer by a full
     * 24 hours, which is worse than failing outright because it looks like a valid answer. Only
     * characters immediately preceding a lone {@code d} are touched, so timestamps are untouched.</p>
     */
    private static String normaliseDayGlyphs(String raw) {
        return raw.replaceAll("(?i)\\b[iljt|!]\\s*d\\b", "1d");
    }

    private static final java.util.regex.Pattern TIMESTAMP =
            java.util.regex.Pattern.compile("(\\d+\\s*[dD]\\s*)?(\\d{1,2}:\\d{2}:\\d{2})");
    private static final java.util.regex.Pattern DAYS =
            java.util.regex.Pattern.compile("(\\d+)\\s*[dD]\\b");

    private static final int DEFAULT_INTERVAL_MINUTES = 60;

    /**
     * Contribution count at which a trip to Alliance Tech is worth making.
     *
     * <p><em>"When you see fifteen out of twenty five, go ahead and do the
     * contribute... you should only be doing it when it says contribute fifteen out of
     * twenty five."</em></p>
     */
    private static final int ALLIANCE_CONTRIBUTION_TRIGGER = 15;

    /**
     * How fast contributions refill — one roughly every ten minutes.
     *
     * <p><em>"say I contribute all of them, a new contribution [comes back in
     * about] eight minutes... every ten minutes you get a contribution attempt."</em> So the wait
     * to reach the trigger is {@code (trigger - current) * 10} minutes rather than a blind poll.</p>
     */
    private static final int ALLIANCE_MINUTES_PER_CONTRIBUTION = 10;

    /** Never defer Alliance Tech more than this, so a bad read cannot park it all day. */
    private static final int ALLIANCE_MAX_DEFER_MINUTES = 240;

    /** Tries at getting the city queue panel on screen before giving up on a sweep. */
    private static final int PANEL_OPEN_ATTEMPTS = 3;

    /** Time for the panel to slide in and paint its countdowns. */
    private static final long PANEL_SETTLE_MS = 1500L;

    /** Never trust a sweep read that lands absurdly far out; a garbled OCR must not park a task. */
    private static final Duration SANITY_CEILING = Duration.ofDays(8);

    public TimerSweepRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    public LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected void execute() {
        logInfo("Timer sweep starting — reading every visible countdown. No activities will run.");
        // Surface the phase so the launcher shows what it is doing instead of a generic "Active".
        dev.frostguard.engine.schedule.BotPhaseState.set("Refreshing timers");

        if (!openCityPanelWithRetries()) {
            dev.frostguard.engine.schedule.BotPhaseState.clear();
            // Thrown rather than rescheduled: the queue then records a failure and retries in
            // minutes, where a quiet return showed a sweep that read nothing as "Completed".
            throw new IllegalStateException("City queue panel never rendered after " + PANEL_OPEN_ATTEMPTS
                    + " attempts; no timers were refreshed; " + TaskDiagnosticSnapshots.capture(
                            emuManager, EMULATOR_NUMBER, "timersweep", "panel-not-readable"));
        }

        int recorded = 0;
        try {
            for (Map.Entry<String, PointData[]> row : CITY_PANEL_ROWS.entrySet()) {
                String label = row.getKey();
                Duration remaining = readRowTimer(row.getValue());

                if (remaining == null) {
                    logDebug("No countdown readable for " + label + " — leaving its schedule alone.");
                    continue;
                }
                if (remaining.compareTo(SANITY_CEILING) > 0) {
                    logWarning("Discarding implausible " + label + " read of " + remaining + ".");
                    continue;
                }

                logInfo(String.format("%s: %s remaining.", label,
                        GameTimeUtils.formatCountdown(LocalDateTime.now().plus(remaining))));

                switch (label) {
                    // Each camp now schedules off its OWN read time, not the
                    // soonest of the three — that "shared Training task" design was exactly
                    // why the timing looked wrong for camps that weren't the soonest one.
                    case "Infantry" -> {
                        applySchedule(TpDailyTaskEnum.TRAINING_INFANTRY,
                                LocalDateTime.now().plus(remaining));
                        recorded++;
                    }
                    case "Lancer" -> {
                        applySchedule(TpDailyTaskEnum.TRAINING_LANCER,
                                LocalDateTime.now().plus(remaining));
                        recorded++;
                    }
                    case "Marksman" -> {
                        applySchedule(TpDailyTaskEnum.TRAINING_MARKSMAN,
                                LocalDateTime.now().plus(remaining));
                        recorded++;
                    }
                    case "Furnace" -> {
                        applySchedule(TpDailyTaskEnum.CITY_UPGRADE_FURNACE,
                                LocalDateTime.now().plus(remaining));
                        recorded++;
                    }
                    case "Research" -> {
                        applySchedule(TpDailyTaskEnum.RESEARCH, LocalDateTime.now().plus(remaining));
                        recorded++;
                    }
                    // Barricade has no task of its own; read for visibility only.
                    default -> { }
                }
            }

            recorded += sweepDailyPanel();

            marchHelper.closeLeftMenu();

            recorded += sweepChiefOrders();
        } catch (Exception ex) {
            logError("Timer sweep failed partway through; keeping whatever it managed to read.", ex);
        }

        dev.frostguard.engine.schedule.BotPhaseState.clear();
        logInfo("Timer sweep finished — " + recorded + " timer(s) refreshed from the screen.");
        reschedule(LocalDateTime.now().plusMinutes(resolveIntervalMinutes()));
    }

    /**
     * Opens the city queue panel and confirms it actually rendered before reading.
     *
     * <p>The sweep read nothing on two of four runs and finished in half the
     * usual time. Cause was that it opened the menu, waited a fixed 1200ms and started reading
     * regardless — so whenever the game sat on a screen where the menu did not open (right after
     * a relaunch, for instance) every row came back blank and the sweep reported success having
     * done nothing. Silence looked identical to "no timers running".</p>
     *
     * <p>The panel confirms itself: if any row yields a timestamp the panel is up. That avoids
     * needing a new template and validates the exact thing the sweep depends on.</p>
     *
     * @return {@code true} once a readable panel is on screen
     */
    private boolean openCityPanelWithRetries() {
        return new CityPanelOpener(new CityPanelOpener.Steps() {
            @Override
            public void closeSidebar() {
                marchHelper.closeLeftMenu();
            }

            @Override
            public void openCitySection() {
                marchHelper.openLeftMenuCitySection(true);
            }

            @Override
            public boolean panelReadable() {
                sleepTask(PANEL_SETTLE_MS);
                return CITY_PANEL_ROWS.values().stream().anyMatch(area -> readRowTimer(area) != null);
            }

            @Override
            public void returnHome() {
                navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
            }
        }, this::logWarning).open(PANEL_OPEN_ATTEMPTS);
    }

    /**
     * Reads Alliance Contribution, Hero Recruit, Pet Adventure and the Tree of Life off the
     * sidebar's Daily tab.
     *
     * <p>These rows used to sit below the fold of the City tab, which is where this sweep still
     * looked for them by swiping and reading fixed boxes. The redesigned sidebar moved them to
     * Daily, so every pass landed on the City tab's Training rows, found neither anchor, and threw
     * the timers away (or, worse, read a camp countdown out of the old Pet Adventure box). Each row
     * is now found by its own title on a frame that measures as the Daily tab, so a value is only
     * ever written under the label printed above it.</p>
     *
     * @return how many schedules were written from the Daily tab
     */
    private int sweepDailyPanel() {
        if (!navigationHelper.openSidebarSection(SidebarSection.DAILY)) {
            logWarning("Daily sidebar tab did not open — Hero Recruit, Pet Adventure and Alliance "
                    + "Contribution are left unscheduled this pass.");
            return 0;
        }
        sleepTask(PANEL_SETTLE_MS);

        Map<DailyPanelReader.Card, DailyPanelReader.Reading> cards = scanDailyPanel();
        if (cards == null) {
            return 0;
        }
        if (cards.isEmpty()) {
            logWarning("Daily tab was open but none of its tracked rows could be read — nothing "
                    + "scheduled from it this pass; "
                    + TaskDiagnosticSnapshots.captureAlways(emuManager, EMULATOR_NUMBER, "timersweep", "daily-unreadable"));
            return 0;
        }

        int recorded = 0;

        DailyPanelReader.Reading contribution = cards.get(DailyPanelReader.Card.ALLIANCE_CONTRIBUTION);
        if (contribution != null) {
            Optional<int[]> counts = DailyPanelReader.contribution(contribution.status());
            if (counts.isPresent()) {
                logInfo("Alliance contribution: " + counts.get()[0] + "/" + counts.get()[1]);
                gateAllianceTech(counts.get()[0], counts.get()[1]);
            } else {
                logDebug("Alliance Contribution row found but its counter read '" + contribution.status() + "'.");
            }
        }

        DailyPanelReader.Reading tree = cards.get(DailyPanelReader.Card.TREE_OF_LIFE);
        if (tree != null && tree.status() != null) {
            logInfo("Tree of Life: " + tree.status());
        }

        Duration advanced = plausibleTimer(cards.get(DailyPanelReader.Card.HERO_RECRUIT_ADVANCED));
        Duration epic = plausibleTimer(cards.get(DailyPanelReader.Card.HERO_RECRUIT_EPIC));
        if (advanced != null) {
            logInfo("Hero Recruit (Advanced): "
                    + GameTimeUtils.formatCountdown(LocalDateTime.now().plus(advanced)) + " remaining.");
        }
        if (epic != null) {
            logInfo("Hero Recruit (Epic): "
                    + GameTimeUtils.formatCountdown(LocalDateTime.now().plus(epic)) + " remaining.");
        }
        // Recruitment is due as soon as either free pull is. A row with no countdown is "Free" or
        // unread, and in both cases pushing the task out to the other row's timer would skip a pull
        // that is available now, so a schedule is only written when both countdowns were read.
        if (advanced != null && epic != null) {
            Duration earliest = advanced.compareTo(epic) <= 0 ? advanced : epic;
            applySchedule(TpDailyTaskEnum.HERO_RECRUITMENT, LocalDateTime.now().plus(earliest));
            recorded++;
        }

        Duration pet = plausibleTimer(cards.get(DailyPanelReader.Card.PET_ADVENTURE));
        if (pet != null) {
            logInfo("Pet Adventure: " + GameTimeUtils.formatCountdown(LocalDateTime.now().plus(pet)) + " remaining.");
            // Caught live -- this countdown is the chest-completion timer, not a daily-attempts
            // timer, so it has nothing to do with when fresh attempts become available. A read
            // taken before daily reset (e.g. "14h36m remaining" at 19:34) got projected straight
            // through reset into the next day (~10 AM), even though reset itself refreshes attempts
            // hours sooner. Same class of bug already fixed for Intel's own refresh countdown (see
            // IntelligenceRoutine's MAX_INTEL_REFRESH_MINUTES) -- clamp at the next daily reset
            // (+ a couple minutes' buffer for the reset to actually land) so a stale pre-reset read
            // can never push this past a reset that would have made it moot.
            LocalDateTime naiveNext = LocalDateTime.now().plus(pet);
            LocalDateTime resetCeiling = GameTimeUtils.dailyResetTime().plusMinutes(2);
            LocalDateTime next = naiveNext.isAfter(resetCeiling) ? resetCeiling : naiveNext;
            if (next.isBefore(naiveNext)) {
                logInfo(String.format(
                        "Pet Adventure countdown (%s) read before daily reset would run past reset "
                                + "(%s) -- capping there instead, since reset refreshes attempts anyway.",
                        GameTimeUtils.formatCountdown(naiveNext), GameTimeUtils.formatCountdown(resetCeiling)));
            }
            applyScheduleAllowingEarlier(TpDailyTaskEnum.PET_ADVENTURE, next);
            recorded++;
        }

        return recorded;
    }

    /**
     * Rolls the Daily list to its top, then pages down to its foot, reading every viewport.
     *
     * @return the tracked rows by card, or {@code null} when a frame stopped measuring as the
     *         Daily tab partway through (nothing read from that scan is trusted)
     */
    private Map<DailyPanelReader.Card, DailyPanelReader.Reading> scanDailyPanel() {
        RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
        for (int i = 0; i < DAILY_MAX_TOP_SWIPES && frame != null; i++) {
            swipe(DAILY_PAGE_UP_FROM, DAILY_PAGE_UP_TO, DAILY_SWIPE_MS);
            sleepTask(PANEL_SETTLE_MS);
            RawImageData after = emuManager.captureScreen(EMULATOR_NUMBER);
            boolean moved = after != null && viewportMoved(frame, after);
            frame = after;
            if (!moved) {
                break;
            }
        }

        List<List<DailyPanelReader.Reading>> viewports = new ArrayList<>();
        for (int page = 0; page < DAILY_MAX_PAGES; page++) {
            Optional<List<DailyPanelReader.Reading>> viewport = DailyPanelReader.read(frame);
            if (viewport.isEmpty()) {
                logWarning("Sidebar did not show the Daily tab while scanning it — nothing scheduled "
                        + "from it this pass; " + (frame == null ? "no frame"
                                : TaskDiagnosticSnapshots.saveAlways(frame, "timersweep", "daily-not-proven")));
                return null;
            }
            viewports.add(viewport.get());
            logDebug("Daily viewport " + (page + 1) + ": " + viewport.get());

            swipe(DAILY_PAGE_DOWN_FROM, DAILY_PAGE_DOWN_TO, DAILY_SWIPE_MS);
            sleepTask(PANEL_SETTLE_MS);
            RawImageData after = emuManager.captureScreen(EMULATOR_NUMBER);
            if (after == null || !viewportMoved(frame, after)) {
                break;
            }
            frame = after;
        }
        return DailyPanelReader.merge(viewports);
    }

    private static boolean viewportMoved(RawImageData before, RawImageData after) {
        try {
            return SidebarViewportChangeDetector.materiallyChanged(
                    ImageConverter.toBufferedImage(before), ImageConverter.toBufferedImage(after));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private Duration plausibleTimer(DailyPanelReader.Reading reading) {
        if (reading == null || reading.timer() == null) {
            return null;
        }
        if (reading.timer().compareTo(SANITY_CEILING) > 0) {
            logWarning("Discarding implausible " + reading.card() + " read of " + reading.timer() + ".");
            return null;
        }
        return reading.timer();
    }

    /**
     * Holds Alliance Tech back until contributions are worth a trip.
     *
     * <p>the operator: <em>"it takes hours to get close to twenty five... when it's twenty, then I do it
     * once."</em> Contributions accrue slowly, so visiting at 3/25 spends a whole navigation
     * cycle to donate almost nothing. This pushes the task out until the counter is near its cap,
     * scaling the wait to how far away it actually is.</p>
     */
    private void gateAllianceTech(int current, int cap) {
        if (cap <= 0 || current >= ALLIANCE_CONTRIBUTION_TRIGGER) {
            return;
        }

        int shortfall = ALLIANCE_CONTRIBUTION_TRIGGER - current;
        int waitMinutes = Math.min(ALLIANCE_MAX_DEFER_MINUTES,
                shortfall * ALLIANCE_MINUTES_PER_CONTRIBUTION);

        LocalDateTime next = LocalDateTime.now().plusMinutes(waitMinutes);
        applySchedule(TpDailyTaskEnum.ALLIANCE_TECH, next);
        logInfo(String.format("Alliance contributions at %d/%d — deferring Alliance Tech %d min "
                + "(until nearer the %d trigger) rather than donating a handful now.",
                current, cap, waitMinutes, ALLIANCE_CONTRIBUTION_TRIGGER));
    }

    /**
     * Reads the three tracked Chief Orders off the shelf and schedules each from what it says.
     *
     * <p><em>"from the chief's house, here's what you're gonna do... so as soon
     * as I hit zero, motherfucker, you know what you're doing, you're hitting it."</em> The
     * cooldowns were printed on screen the whole time and nothing read them, so these three ran
     * off hard-coded 8/12/24-hour guesses that drifted out of step with reality.</p>
     *
     * <p>Three states, all handled: an <em>On cooldown hh:mm:ss</em> banner gives the exact wait;
     * an <em>Active hh:mm:ss</em> banner means it is currently running, so come back when it ends
     * and re-read; and a clean cover means available now.</p>
     *
     * @return how many order schedules were written
     */
    private int sweepChiefOrders() {
        ImageSearchResultData menuButton = templateSearchHelper.locatePattern(
                TemplatesEnum.CHIEF_ORDER_MENU_BUTTON, SearchConfigConstants.DEFAULT_SINGLE);
        if (!menuButton.isFound()) {
            logDebug("Chief Order menu button not visible — skipping its timers this pass.");
            return 0;
        }

        tapNear(menuButton.getPoint());
        sleepTask(2200);

        // Prove the shelf is actually open before believing a blank cover.
        // The first version treated "read nothing" as "available now" and duly scheduled all
        // three orders immediately — while the screen showed Urgent active for 19 minutes and
        // Rush on a 10-hour cooldown. Blank means unknown, not available; the shelf title is the
        // anchor that tells the two apart.
        String shelfTitle = readPanelText(CHIEF_SHELF_TITLE_AREA);
        if (shelfTitle == null || !shelfTitle.toLowerCase().contains("chief")) {
            logWarning("Chief Order shelf did not open — leaving its schedules alone.");
            pressBack();
            sleepTask(800);
            return 0;
        }

        int recorded = 0;
        for (Map.Entry<TpDailyTaskEnum, PointData[]> order : CHIEF_ORDER_SLOTS.entrySet()) {
            String cover = readPanelBlock(order.getValue());
            String label = chiefOrderLabel(order.getKey());

            if (cover == null || cover.isBlank()) {
                // Shelf is confirmed open, so an empty cover really is the available state.
                logInfo(label + ": available now.");
                applySchedule(order.getKey(), LocalDateTime.now());
                recorded++;
                continue;
            }

            java.util.regex.Matcher m = TIMESTAMP.matcher(normaliseDayGlyphs(cover).replace('-', ':'));
            if (!m.find()) {
                logInfo(label + ": available now.");
                applySchedule(order.getKey(), LocalDateTime.now());
                recorded++;
                continue;
            }

            Duration remaining = extractTimer(m.group());
            if (remaining == null) {
                continue;
            }

            boolean active = cover.toLowerCase().contains("active");
            // Landing exactly on zero catches the shelf mid-flip: the cover still reads
            // "Active 00:00:01" and the order looks free while the game still refuses it. Half a
            // minute past the buzzer costs nothing and lands on a settled banner.
            LocalDateTime next = LocalDateTime.now().plus(remaining).plusSeconds(active ? 30 : 0);
            applySchedule(order.getKey(), next);
            recorded++;

            logInfo(String.format("%s: %s %s — next check %s.", label,
                    active ? "active for" : "on cooldown for",
                    GameTimeUtils.formatCountdown(next), next));
        }

        pressBack();
        sleepTask(800);
        return recorded;
    }

    private String chiefOrderLabel(TpDailyTaskEnum task) {
        return switch (task) {
            case CHIEF_ORDER_URGENT_MOBILIZATION -> "Chief Order: Urgent Mobilization";
            case CHIEF_ORDER_RUSH_JOB -> "Chief Order: Rush Job";
            case CHIEF_ORDER_PRODUCTIVITY_DAY -> "Chief Order: Productivity Day";
            default -> task.getName();
        };
    }

    private String readPanelText(PointData[] area) {
        try {
            return stringHelper.attemptRecognition(
                    area[0], area[1], 2, 200L, null,
                    s -> s != null && !s.isBlank(),
                    String::trim);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Reads a region that holds more than one line of text.
     *
     * <p>The default OCR path runs page-segmentation mode 7 — <em>single line</em> — and a Chief
     * Order cover carries two: the state ("On cooldown") above the countdown ("10:45:19"). Fed a
     * two-line crop, mode 7 returned an empty string for Urgent and Rush and {@code "015339. )"}
     * for Productivity, which the sweep then read as "no timer, so it must be available" and
     * scheduled all three to run at once — while the shelf on screen showed one active and two
     * cooling down. The same crops read perfectly under a uniform-block mode, so the region was
     * never the problem; the segmentation mode was.</p>
     */
    private String readPanelBlock(PointData[] area) {
        try {
            return stringHelper.attemptRecognition(
                    area[0], area[1], 2, 200L,
                    OcrSettingsData.forTextBlock(),
                    s -> s != null && !s.isBlank(),
                    String::trim);
        } catch (Exception ex) {
            return null;
        }
    }

    private Duration readRowTimer(PointData[] area) {
        String raw = stringHelper.attemptRecognition(
                area[0], area[1],
                3, 200L, null,
                s -> s != null && TIMESTAMP.matcher(s.replace('-', ':')).find(),
                String::trim);
        return extractTimer(raw);
    }

    /**
     * Writes a task's next run without executing it.
     *
     * <p>Only ever pushes a schedule <em>later</em>. A sweep saying "this camp is busy for two
     * hours" is authoritative about the earliest it could be worth visiting, but it must never
     * drag a task forward and cause the very rushing this routine exists to prevent.</p>
     */
    private void applySchedule(TpDailyTaskEnum task, LocalDateTime due) {
        try {
            ScheduleService.obtain().applySweptTimer(profile, task, padPastZero(due));
        } catch (Exception ex) {
            logWarning("Could not record the swept timer for " + task.getName() + ": " + ex.getMessage());
        }
    }

    /**
     * Like {@link #applySchedule} but permits pulling the task earlier as well as later. Reserved
     * for work waiting to be collected, where a stale later fallback would strand rewards.
     */
    private void applyScheduleAllowingEarlier(TpDailyTaskEnum task, LocalDateTime due) {
        try {
            ScheduleService.obtain().applySweptTimer(profile, task, padPastZero(due), true);
        } catch (Exception ex) {
            logWarning("Could not record the swept timer for " + task.getName() + ": " + ex.getMessage());
        }
    }

    /**
     * Nudges a swept deadline a little past the moment it actually expires.
     *
     * <p><em>"Productive day, cooldown of one hour and thirty three minutes...
     * I'm gonna turn on productive day in one hour and thirty five minutes just so it's not super
     * exact."</em> Arriving on the exact second a timer hits zero, every time, across a dozen
     * different tasks, is a pattern nothing human produces. The pad also clears the moment where
     * the game is still mid-flip and would refuse the action anyway.</p>
     */
    private LocalDateTime padPastZero(LocalDateTime due) {
        if (due == null) {
            return null;
        }
        LocalDateTime floor = LocalDateTime.now().plusSeconds(SWEPT_PAD_MIN_SECONDS);
        LocalDateTime padded = due.plusSeconds(
                SWEPT_PAD_MIN_SECONDS + PAD_RANDOM.nextInt(SWEPT_PAD_SPREAD_SECONDS + 1));
        return padded.isBefore(floor) ? floor : padded;
    }

    private int resolveIntervalMinutes() {
        try {
            Integer configured = profile.getConfig(
                    ConfigurationKeyEnum.TIMER_SWEEP_INTERVAL_MINUTES_INT, Integer.class);
            return (configured == null || configured < 5) ? DEFAULT_INTERVAL_MINUTES : configured;
        } catch (Exception ex) {
            return DEFAULT_INTERVAL_MINUTES;
        }
    }
}
