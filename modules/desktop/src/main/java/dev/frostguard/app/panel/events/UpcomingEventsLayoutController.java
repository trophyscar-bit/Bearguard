package dev.frostguard.app.panel.events;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.frostguard.data.entity.EventScheduleEntry;
import dev.frostguard.engine.service.EventScheduleService;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Full "Upcoming Events" page, opened from its own pinned sidebar button (mirrors Chat/Config).
 * Splits every recorded {@link EventScheduleEntry} into State Events (the rotating Events-tab set
 * plus the Events -&gt; Calendar Gantt read) and Alliance Events (Bear Trap, Alliance -&gt; Battle
 * -&gt; Fortress, and the header-clock Task List). Rows show the real dates, rendered through
 * {@link EventScheduleClock}.
 *
 * <p>Two views, toggled: the card List, and a Month Gantt that mirrors the game's own Events -&gt;
 * Calendar tab -- day columns across the top, one row per event, each a bar spanning the days it
 * actually runs. A conventional month grid was tried first and rejected: it can only say "something
 * happens on this day", which loses the single fact the Gantt exists to show, namely how far each
 * event reaches.</p>
 *
 * <p>Polls rather than listens: the cache is a cheap local read and every row's relative-time
 * context needs re-rendering each tick regardless of whether the data changed, so a push listener
 * buys nothing. Mirrors {@code TaskGanttOverviewController}'s {@link Timeline}/{@link KeyFrame}
 * idiom -- FX-thread native, no {@code Platform.runLater} needed. The month grid is rebuilt fresh
 * each tick rather than mutated, since AtlantaFX's {@code Calendar} exposes no cell-refresh hook
 * and a 6x7 grid is cheap enough to just replace.</p>
 */
public class UpcomingEventsLayoutController {

    private static final int REFRESH_SECONDS = 30;
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("MMM d, h:mm a");
    private static final DateTimeFormatter DATE_ONLY_FORMAT = DateTimeFormatter.ofPattern("EEE MMM d");

    /** Active/Upcoming rows shown at full size, capped so the list stays a glance, not a scroll. */
    private static final int MAX_CURRENT_ROWS = 5;
    /** Ended rows shown as a smaller, separate "Recently Ended" tail. */
    private static final int MAX_ENDED_ROWS = 5;
    /** How far ahead an event still counts as "starting soon" and gets highlighted. */
    private static final int HIGHLIGHT_WINDOW_HOURS = 8;
    private static final int WEEK_DAYS = 7;
    /** The name column. Fixed, so the seven day columns stay equal and a long event name cannot
     *  squeeze the grid it belongs to. */
    private static final int NAME_COLUMN_WIDTH = 230;

    /** Alliance Events: Bear Trap, every Fortress/Stronghold entry, and the Task List reads. */
    private static boolean isAllianceEvent(String eventKey) {
        return "BEAR_TRAP".equals(eventKey)
                || eventKey.startsWith("FORTRESS_")
                || eventKey.startsWith("TASKLIST_");
    }

    @FXML
    private ToggleButton toggleListView;
    @FXML
    private ToggleButton toggleMonthView;
    @FXML
    private VBox listView;
    @FXML
    private VBox monthView;
    @FXML
    private VBox stateCardsContainer;
    @FXML
    private VBox allianceCardsContainer;
    @FXML
    private Label labelStateEmpty;
    @FXML
    private Label labelAllianceEmpty;
    @FXML
    private StackPane monthCalendarHost;

    private Timeline refreshTimer;
    private List<EventScheduleEntry> latestEntries = List.of();
    private LocalDate weekStart = startOfWeek(LocalDate.now(EventScheduleClock.zone()));

    @FXML
    private void initialize() {
        ToggleGroup viewToggle = new ToggleGroup();
        toggleListView.setToggleGroup(viewToggle);
        toggleMonthView.setToggleGroup(viewToggle);
        // Week is the default: it answers "what is on and when" at a glance, which is what the
        // page is for; the list is the detail view behind it.
        toggleMonthView.setSelected(true);
        toggleListView.selectedProperty().addListener((obs, was, isSelected) -> showListView(isSelected));
        showListView(toggleListView.isSelected());

        refresh();
        refreshTimer = new Timeline(new KeyFrame(
                javafx.util.Duration.seconds(REFRESH_SECONDS), event -> refresh()));
        refreshTimer.setCycleCount(Animation.INDEFINITE);
        refreshTimer.play();
    }

    /**
     * Collapses the same event seen by two different scans into one row.
     *
     * <p>The Task List, the Fortress read and the calendar chart all legitimately report the same
     * fight, under different keys, so the raw cache holds "Castle Battle" three times over. Rows are
     * keyed here by name and start date, and the survivor is the one that knows when the event ends
     * -- an entry with a real window beats one that only knows it started.</p>
     */
    private List<EventScheduleEntry> dedupe(List<EventScheduleEntry> entries) {
        List<EventScheduleEntry> kept = new ArrayList<>();
        for (EventScheduleEntry entry : entries) {
            boolean merged = false;
            for (int i = 0; i < kept.size(); i++) {
                EventScheduleEntry other = kept.get(i);
                if (!sameEvent(entry, other) || !windowsOverlap(entry, other)) {
                    continue;
                }
                // Same event, same stretch of time, two scans. Keep whichever states a real end:
                // the chart gives both edges, while a presence check only ever knows it started.
                if (other.getInactiveSince() == null && entry.getInactiveSince() != null) {
                    kept.set(i, entry);
                }
                merged = true;
                break;
            }
            if (!merged) {
                kept.add(entry);
            }
        }
        dropGenericBearTrapWhenNumbered(kept);
        return kept;
    }

    /**
     * Removes the computed "Bear Trap" row when a numbered "Bear Hunt - Trap N" covers the same
     * window.
     *
     * <p>Both describe the same fight: one is derived from the configured anchor, the other read
     * off the Task List. The numbered one wins because it says which trap it is. Trap 1 and Trap 2
     * are different facilities and both stay.</p>
     */
    private void dropGenericBearTrapWhenNumbered(List<EventScheduleEntry> entries) {
        List<EventScheduleEntry> numbered = new ArrayList<>();
        for (EventScheduleEntry entry : entries) {
            String label = entry.getEventLabel() == null ? "" : entry.getEventLabel().toLowerCase();
            if (label.contains("bear hunt") && label.contains("trap")) {
                numbered.add(entry);
            }
        }
        if (numbered.isEmpty()) {
            return;
        }
        entries.removeIf(entry -> {
            String label = entry.getEventLabel() == null ? "" : entry.getEventLabel().trim();
            if (!label.equalsIgnoreCase("Bear Trap")) {
                return false;
            }
            for (EventScheduleEntry specific : numbered) {
                if (windowsOverlap(entry, specific)) {
                    return true;
                }
            }
            return false;
        });
    }

    private boolean sameEvent(EventScheduleEntry a, EventScheduleEntry b) {
        String left = a.getEventLabel() == null ? "" : a.getEventLabel().trim().toLowerCase();
        String right = b.getEventLabel() == null ? "" : b.getEventLabel().trim().toLowerCase();
        return !left.isEmpty() && left.equals(right);
    }

    /** Overlap, not an identical start: the two scans time the same event differently, so the same
     *  fight can be recorded as starting at midnight by one and at 08:46 by the other. Distinct
     *  occurrences on different days do not overlap and stay as separate rows. */
    private boolean windowsOverlap(EventScheduleEntry a, EventScheduleEntry b) {
        LocalDateTime aStart = a.getActiveSince();
        LocalDateTime bStart = b.getActiveSince();
        if (aStart == null || bStart == null) {
            return false;
        }
        LocalDateTime aEnd = a.getInactiveSince() == null ? aStart : a.getInactiveSince();
        LocalDateTime bEnd = b.getInactiveSince() == null ? bStart : b.getInactiveSince();
        return !aStart.isAfter(bEnd) && !bStart.isAfter(aEnd);
    }

    /** Stops the polling timer; call if this page is ever torn down independently of the app. */
    public void stopAutoRefresh() {
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
    }

    private void showListView(boolean showList) {
        listView.setVisible(showList);
        listView.setManaged(showList);
        monthView.setVisible(!showList);
        monthView.setManaged(!showList);
        if (!showList) {
            rebuildMonthView();
        }
    }

    private void refresh() {
        latestEntries = dedupe(EventScheduleService.obtain().findAll());

        stateCardsContainer.getChildren().clear();
        allianceCardsContainer.getChildren().clear();

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        List<EventScheduleEntry> stateEntries = new ArrayList<>();
        List<EventScheduleEntry> allianceEntries = new ArrayList<>();
        for (EventScheduleEntry entry : latestEntries) {
            (isAllianceEvent(entry.getEventKey()) ? allianceEntries : stateEntries).add(entry);
        }
        stateEntries.sort(java.util.Comparator.comparingLong(e -> sortKey(e, nowUtc)));
        allianceEntries.sort(java.util.Comparator.comparingLong(e -> sortKey(e, nowUtc)));

        boolean anyState = populateSection(stateCardsContainer, stateEntries, nowUtc);
        boolean anyAlliance = populateSection(allianceCardsContainer, allianceEntries, nowUtc);

        labelStateEmpty.setVisible(!anyState);
        labelStateEmpty.setManaged(!anyState);
        labelAllianceEmpty.setVisible(!anyAlliance);
        labelAllianceEmpty.setManaged(!anyAlliance);

        if (toggleMonthView.isSelected()) {
            rebuildMonthView();
        }
    }

    /**
     * Splits an already-sorted section into full-size Active/Upcoming rows (capped) and a smaller
     * "Recently Ended" tail (also capped). sortKey already orders ended entries most-recent-first
     * among themselves, so a straight filter plus limit is enough; no re-sort.
     *
     * <p>When nothing is live or upcoming, the section still says so before the ended tail. Without
     * that line the first thing on screen is the "Recently Ended" header, which reads as history
     * being promoted to the top of the page.</p>
     */
    private boolean populateSection(VBox container, List<EventScheduleEntry> sorted, LocalDateTime nowUtc) {
        int shown = 0;
        int currentShown = 0;
        for (EventScheduleEntry entry : sorted) {
            if (isEnded(entry, nowUtc) || currentShown >= MAX_CURRENT_ROWS) {
                continue;
            }
            container.getChildren().add(buildRow(entry, nowUtc));
            currentShown++;
            shown++;
        }

        List<EventScheduleEntry> ended = new ArrayList<>();
        for (EventScheduleEntry entry : sorted) {
            if (isEnded(entry, nowUtc) && ended.size() < MAX_ENDED_ROWS) {
                ended.add(entry);
            }
        }

        if (currentShown == 0 && !ended.isEmpty()) {
            Label nothingLive = new Label("Nothing running or scheduled right now.");
            nothingLive.getStyleClass().add("upcoming-events-nothing-live");
            container.getChildren().add(nothingLive);
            shown++;
        }

        if (!ended.isEmpty()) {
            Label endedHeader = new Label("Recently Ended");
            endedHeader.getStyleClass().add("upcoming-events-ended-header");
            container.getChildren().add(endedHeader);
            for (EventScheduleEntry entry : ended) {
                container.getChildren().add(buildEndedRow(entry));
                shown++;
            }
        }
        return shown > 0;
    }

    /** How long this entry's window runs, in minutes; a window with no recorded end counts as the
     *  longest there is, so it never outranks an event whose short window is actually known. */
    private long windowMinutes(EventScheduleEntry entry) {
        LocalDateTime start = entry.getActiveSince();
        LocalDateTime end = entry.getInactiveSince();
        if (start == null || end == null || end.isBefore(start)) {
            return Integer.MAX_VALUE;
        }
        return Math.min(java.time.Duration.between(start, end).toMinutes(), Integer.MAX_VALUE);
    }

    private boolean isEnded(EventScheduleEntry entry, LocalDateTime nowUtc) {
        return "upcoming-events-badge-ended".equals(badgeStyleClass(entry, nowUtc));
    }

    /** Active first (always smallest), then soonest-upcoming start (ascending epoch seconds), then
     *  most-recently-ended (Long.MAX_VALUE minus its epoch seconds, so a more recent end sorts
     *  earlier within that group -- and the group as a whole always sorts after every real upcoming
     *  start, since that subtraction stays astronomically larger). */
    private long sortKey(EventScheduleEntry entry, LocalDateTime nowUtc) {
        LocalDateTime start = entry.getActiveSince();
        if (start != null && start.isAfter(nowUtc)) {
            return start.toEpochSecond(ZoneOffset.UTC);
        }
        if (entry.isCurrentlyActive()) {
            // Running events are ordered shortest-window first, so a one-day fight sits above a
            // week-long grind. The week-long one will still be there tomorrow; the short one is
            // what there is any point acting on.
            return Long.MIN_VALUE + windowMinutes(entry);
        }
        LocalDateTime end = entry.getInactiveSince();
        return end != null ? Long.MAX_VALUE - end.toEpochSecond(ZoneOffset.UTC) : Long.MAX_VALUE;
    }

    /** Full-size row: family icon, status pill, title, and a bold date line. "Last scanned" lives
     *  in the hover tooltip rather than taking a line of its own. */
    private VBox buildRow(EventScheduleEntry entry, LocalDateTime nowUtc) {
        Node icon = iconNodeFor(entry.getEventLabel(), "upcoming-events-icon");

        Label badge = new Label(badgeText(entry, nowUtc));
        badge.getStyleClass().add(badgeStyleClass(entry, nowUtc));

        Label title = new Label(entry.getEventLabel());
        title.getStyleClass().add("upcoming-events-label");

        HBox header = new HBox(10, icon, badge, title);
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Label dates = new Label(describeWindow(entry));
        dates.getStyleClass().add("upcoming-events-dates");
        dates.setWrapText(true);

        VBox row = new VBox(6, header, dates);
        row.getStyleClass().add("upcoming-events-card");
        if (startsWithinHighlightWindow(entry, nowUtc)) {
            row.getStyleClass().add("upcoming-events-card-soon");
        }
        Tooltip.install(row, new Tooltip("Last scanned " + toViewerZone(entry.getLastScannedAt())));
        return row;
    }

    /** "Recently Ended" tail row: one compact muted line, deliberately smaller than the live rows
     *  above it so current and historical never compete for attention. */
    private HBox buildEndedRow(EventScheduleEntry entry) {
        Node icon = iconNodeFor(entry.getEventLabel(), "upcoming-events-ended-icon");

        Label badge = new Label("Ended");
        badge.getStyleClass().add("upcoming-events-badge-ended-small");

        Label title = new Label(entry.getEventLabel());
        title.getStyleClass().add("upcoming-events-ended-label");

        Label dates = new Label(entry.getInactiveSince() == null ? "" : toViewerZone(entry.getInactiveSince()));
        dates.getStyleClass().add("upcoming-events-ended-dates");

        HBox row = new HBox(8, icon, badge, title, dates);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.getStyleClass().add("upcoming-events-ended-row");
        Tooltip.install(row, new Tooltip(describeWindow(entry)
                + "\nLast scanned " + toViewerZone(entry.getLastScannedAt())));
        return row;
    }

    private boolean startsWithinHighlightWindow(EventScheduleEntry entry, LocalDateTime nowUtc) {
        if (entry.isCurrentlyActive()) {
            return true;
        }
        LocalDateTime start = entry.getActiveSince();
        return start != null && start.isAfter(nowUtc) && start.isBefore(nowUtc.plusHours(HIGHLIGHT_WINDOW_HOURS));
    }

    /**
     * Whether this entry is a whole-day window rather than a moment.
     *
     * <p>The calendar chart gives dates, not times, and they are stored as midnight-to-23:59 UTC.
     * Rendering those through a timezone moved every one of them: an event on the 12th displayed
     * as "Sep 11, 8:00 PM" in New York, a day out and an invented time. A date has no timezone, so
     * these are shown as the dates they are.</p>
     */
    private boolean isAllDay(EventScheduleEntry entry) {
        LocalDateTime start = entry.getActiveSince();
        if (start == null || !start.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            return false;
        }
        LocalDateTime end = entry.getInactiveSince();
        return end == null || end.toLocalTime().equals(LocalTime.of(23, 59));
    }

    /** Only ever states what is actually known. An event whose end the game never showed says when
     *  it starts and stops there, rather than printing "Unknown" as if that were a fact. */
    private String describeWindow(EventScheduleEntry entry) {
        if (isAllDay(entry)) {
            LocalDate from = entry.getActiveSince().toLocalDate();
            LocalDate to = entry.getInactiveSince() == null ? null : entry.getInactiveSince().toLocalDate();
            if (to == null || to.equals(from)) {
                return from.format(DATE_ONLY_FORMAT) + "   ·   all day";
            }
            return from.format(DATE_ONLY_FORMAT) + "   →   " + to.format(DATE_ONLY_FORMAT);
        }
        String start = entry.getActiveSince() == null ? null : toViewerZone(entry.getActiveSince());
        String end = entry.getInactiveSince() == null ? null : toViewerZone(entry.getInactiveSince());
        if (start != null && end != null) {
            return start + "   →   " + end;
        }
        if (start != null) {
            return "Starts " + start;
        }
        if (end != null) {
            return "Ends " + end;
        }
        return "No times recorded yet";
    }

    /**
     * A glanceable marker per event family.
     *
     * <p>Deliberately a glyph, not the game's own icon. Those were tried: the artwork is a busy
     * 50px sprite drawn to sit on a saturated bar, and shrunk into a dark list row it reads as
     * mud.</p>
     */
    private Node iconNodeFor(String label, String styleClass) {
        Label glyph = new Label(iconFor(label));
        glyph.getStyleClass().add(styleClass);
        return glyph;
    }

    /** A glanceable marker per event family, so the eye can sort the list without reading it. */
    private String iconFor(String label) {
        String name = label == null ? "" : label.toLowerCase();
        if (name.contains("bear")) {
            return "🐻";
        }
        if (name.contains("fortress") || name.contains("stronghold")) {
            return "🏰";
        }
        if (name.contains("castle")) {
            return "🏯";
        }
        if (name.contains("foundry")) {
            return "⚙";
        }
        if (name.contains("hall of chiefs")) {
            return "👑";
        }
        if (name.contains("brothers in arms")) {
            return "⚔";
        }
        if (name.contains("beast") || name.contains("gina")) {
            return "🐾";
        }
        if (name.contains("alliance") || name.contains("championship")) {
            return "🏆";
        }
        if (name.contains("snow")) {
            return "❄";
        }
        return "📅";
    }

    /** A start still in the future outranks the stored active flag. The scan marks an entry active
     *  when the game showed it -- the Task List only lists an event once it is close -- but the row
     *  beside it then prints a start hours away, and "Active / Starts 8:00 PM" contradicts itself. */
    private String badgeText(EventScheduleEntry entry, LocalDateTime nowUtc) {
        LocalDateTime start = entry.getActiveSince();
        if (start != null && start.isAfter(nowUtc)) {
            return "Upcoming";
        }
        return entry.isCurrentlyActive() ? "Active" : "Ended";
    }

    private String badgeStyleClass(EventScheduleEntry entry, LocalDateTime nowUtc) {
        LocalDateTime start = entry.getActiveSince();
        if (start != null && start.isAfter(nowUtc)) {
            return "upcoming-events-badge-upcoming";
        }
        return entry.isCurrentlyActive()
                ? "upcoming-events-badge-active"
                : "upcoming-events-badge-ended";
    }

    // Week timeline

    /**
     * One week at a time, with the event names in a column of their own to the left of the day grid.
     *
     * <p>Three layouts were tried before this one, and the reason they failed is arithmetic: a month
     * is 31 columns, and 31 readable day columns do not fit the page. Putting the name inside the
     * bar truncated every short event to an initial; letting it overflow to the right made a one-day
     * event look like it ran for five. Once the name has its own column, the bar only has to say
     * when -- which is the one thing a bar is good at -- and nothing is truncated at all.</p>
     */
    private void rebuildMonthView() {
        LocalDate weekStart = this.weekStart;
        LocalDate weekEnd = weekStart.plusDays(WEEK_DAYS - 1);
        LocalDate today = LocalDate.now(EventScheduleClock.zone());

        List<Bar> stateBars = new ArrayList<>();
        List<Bar> allianceBars = new ArrayList<>();
        for (EventScheduleEntry entry : latestEntries) {
            Bar bar = toBar(entry, weekStart, weekEnd);
            if (bar != null) {
                (isAllianceEvent(entry.getEventKey()) ? allianceBars : stateBars).add(bar);
            }
        }
        java.util.Comparator<Bar> byRun = java.util.Comparator
                .comparingInt((Bar b) -> b.span)
                .thenComparingInt(b -> b.firstColumn);
        stateBars.sort(byRun);
        allianceBars.sort(byRun);
        List<Bar> bars = new ArrayList<>(stateBars);
        bars.addAll(allianceBars);

        GridPane chart = new GridPane();
        chart.getStyleClass().add("upcoming-events-week");

        ColumnConstraints nameColumn = new ColumnConstraints();
        nameColumn.setMinWidth(NAME_COLUMN_WIDTH);
        nameColumn.setPrefWidth(NAME_COLUMN_WIDTH);
        chart.getColumnConstraints().add(nameColumn);
        for (int day = 0; day < WEEK_DAYS; day++) {
            ColumnConstraints column = new ColumnConstraints();
            // Equal prefs plus ALWAYS, not percentages: a percentage is taken of the whole grid,
            // so seven of 100/7 each claimed the entire width and pushed the last two days off the
            // page. Sharing the leftover space after the fixed name column keeps all seven on screen.
            column.setPrefWidth(1);
            column.setHgrow(Priority.ALWAYS);
            column.setFillWidth(true);
            chart.getColumnConstraints().add(column);
        }
        chart.setMaxWidth(Double.MAX_VALUE);

        int rowCount = 1 + bars.size();
        if (!today.isBefore(weekStart) && !today.isAfter(weekEnd)) {
            Region wash = new Region();
            wash.getStyleClass().add("upcoming-events-week-today-wash");
            wash.setMouseTransparent(true);
            chart.add(wash, 1 + (int) java.time.temporal.ChronoUnit.DAYS.between(weekStart, today),
                    0, 1, Math.max(rowCount, 1));
        }

        for (int day = 0; day < WEEK_DAYS; day++) {
            LocalDate date = weekStart.plusDays(day);
            Label dow = new Label(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault()));
            dow.getStyleClass().add("upcoming-events-week-dow");
            Label number = new Label(String.valueOf(date.getDayOfMonth()));
            number.getStyleClass().add("upcoming-events-week-daynum");
            VBox header = new VBox(dow, number);
            header.setAlignment(javafx.geometry.Pos.CENTER);
            header.getStyleClass().add("upcoming-events-week-daycell");
            if (date.equals(today)) {
                header.getStyleClass().add("upcoming-events-week-today");
            }
            chart.add(header, 1 + day, 0);
        }

        int row = 1;
        for (Bar bar : bars) {
            chart.add(bar.name, 0, row);
            GridPane.setValignment(bar.name, VPos.CENTER);
            chart.add(bar.chip, 1 + bar.firstColumn, row, bar.span, 1);
            GridPane.setValignment(bar.chip, VPos.CENTER);
            row++;
        }

        Label empty = new Label("Nothing scheduled this week.");
        empty.getStyleClass().add("upcoming-events-nothing-live");

        VBox body = new VBox(12, buildWeekNav(weekStart, weekEnd));
        body.getChildren().add(bars.isEmpty() ? empty : chart);
        monthCalendarHost.getChildren().setAll(body);
    }

    private HBox buildWeekNav(LocalDate weekStart, LocalDate weekEnd) {
        Button previous = new Button("◀");
        previous.getStyleClass().add("upcoming-events-week-nav");
        previous.setOnAction(event -> {
            this.weekStart = this.weekStart.minusWeeks(1);
            rebuildMonthView();
        });

        Button next = new Button("▶");
        next.getStyleClass().add("upcoming-events-week-nav");
        next.setOnAction(event -> {
            this.weekStart = this.weekStart.plusWeeks(1);
            rebuildMonthView();
        });

        Button thisWeek = new Button("Today");
        thisWeek.getStyleClass().add("upcoming-events-week-nav");
        thisWeek.setOnAction(event -> {
            this.weekStart = startOfWeek(LocalDate.now(EventScheduleClock.zone()));
            rebuildMonthView();
        });

        Label title = new Label(weekStart.format(DATE_ONLY_FORMAT) + "  –  "
                + weekEnd.format(DATE_ONLY_FORMAT) + ", " + weekEnd.getYear());
        title.getStyleClass().add("upcoming-events-week-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox nav = new HBox(8, previous, next, title, spacer, thisWeek);
        nav.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return nav;
    }

    /** The seven-day window the game itself shows: two days of context behind today, four ahead.
     *  Anchoring to Monday instead pushed today to the far edge on a Saturday, which is exactly
     *  when the week matters most. */
    private static LocalDate startOfWeek(LocalDate date) {
        return date.minusDays(2);
    }

    /** Clips one entry to the displayed week, or returns null when it does not touch it. An entry
     *  whose end the game never showed is a single-day bar on its start, never a bar to the edge --
     *  drawing an unknown end as a long run would state something that was never read. */
    private Bar toBar(EventScheduleEntry entry, LocalDate weekStart, LocalDate weekEnd) {
        boolean allDay = isAllDay(entry);
        LocalDate start = toViewerDate(entry.getActiveSince(), allDay);
        LocalDate end = toViewerDate(entry.getInactiveSince(), allDay);
        if (start == null && end == null) {
            return null;
        }
        LocalDate from = start != null ? start : end;
        LocalDate to = (end != null && !end.isBefore(from)) ? end : from;
        if (to.isBefore(weekStart) || from.isAfter(weekEnd)) {
            return null;
        }
        LocalDate clippedFrom = from.isBefore(weekStart) ? weekStart : from;
        LocalDate clippedTo = to.isAfter(weekEnd) ? weekEnd : to;

        Label icon = new Label(iconFor(entry.getEventLabel()));
        icon.getStyleClass().add("upcoming-events-week-glyph");
        Label text = new Label(entry.getEventLabel());
        text.getStyleClass().add("upcoming-events-week-name");
        HBox name = new HBox(8, icon, text);
        name.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        name.getStyleClass().add("upcoming-events-week-name-cell");

        Region chip = new Region();
        chip.setMaxWidth(Double.MAX_VALUE);
        chip.getStyleClass().addAll("upcoming-events-week-bar",
                isAllianceEvent(entry.getEventKey())
                        ? "upcoming-events-week-bar-alliance"
                        : "upcoming-events-week-bar-state");
        if (entry.isCurrentlyActive()) {
            chip.getStyleClass().add("upcoming-events-week-bar-active");
        }
        // Arrows mark a run that carries on outside this week, so a clipped bar is not read as an
        // event that starts on Monday or finishes on Sunday when it does neither.
        String continues = (from.isBefore(weekStart) ? "◀ " : "")
                + describeWindow(entry) + (to.isAfter(weekEnd) ? " ▶" : "");
        Tooltip tooltip = new Tooltip(entry.getEventLabel() + "\n" + continues);
        Tooltip.install(chip, tooltip);
        Tooltip.install(name, tooltip);

        Bar bar = new Bar();
        bar.name = name;
        bar.chip = chip;
        bar.firstColumn = (int) java.time.temporal.ChronoUnit.DAYS.between(weekStart, clippedFrom);
        bar.span = (int) java.time.temporal.ChronoUnit.DAYS.between(clippedFrom, clippedTo) + 1;
        return bar;
    }

    /** One laid-out event row: its name cell, its coloured bar, and where the bar sits in the week. */
    private static final class Bar {
        private HBox name;
        private Region chip;
        private int firstColumn;
        private int span;
    }

    /** Converts for a real instant; passes a whole-day date through untouched, since shifting one
     *  by a timezone is what put every calendar bar on the wrong day. */
    private LocalDate toViewerDate(LocalDateTime storedUtc, boolean allDay) {
        if (storedUtc == null) {
            return null;
        }
        if (allDay) {
            return storedUtc.toLocalDate();
        }
        return storedUtc.atZone(ZoneOffset.UTC)
                .withZoneSameInstant(EventScheduleClock.zone())
                .toLocalDate();
    }

    private String toViewerZone(LocalDateTime storedUtc) {
        if (storedUtc == null) {
            return "Unknown";
        }
        return storedUtc.atZone(ZoneOffset.UTC)
                .withZoneSameInstant(EventScheduleClock.zone())
                .format(DATE_TIME_FORMAT);
    }

}
