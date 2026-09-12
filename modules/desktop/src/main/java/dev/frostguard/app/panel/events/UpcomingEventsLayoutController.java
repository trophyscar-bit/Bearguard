package dev.frostguard.app.panel.events;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
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

    /** Active/Upcoming rows shown at full size, capped so the list stays a glance, not a scroll. */
    private static final int MAX_CURRENT_ROWS = 5;
    /** Ended rows shown as a smaller, separate "Recently Ended" tail. */
    private static final int MAX_ENDED_ROWS = 5;
    /** How far ahead an event still counts as "starting soon" and gets highlighted. */
    private static final int HIGHLIGHT_WINDOW_HOURS = 8;
    /** Month-Gantt day column. Wide enough for a two-day bar to still show a readable label; the
     *  chart scrolls horizontally rather than squeezing a whole month into the page width. */
    private static final int DAY_COLUMN_WIDTH = 58;

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
    private YearMonth monthAnchor = YearMonth.now(EventScheduleClock.zone());
    private Double monthScroll;

    @FXML
    private void initialize() {
        ToggleGroup viewToggle = new ToggleGroup();
        toggleListView.setToggleGroup(viewToggle);
        toggleMonthView.setToggleGroup(viewToggle);
        toggleListView.setSelected(true);
        toggleListView.selectedProperty().addListener((obs, was, isSelected) -> showListView(isSelected));

        refresh();
        refreshTimer = new Timeline(new KeyFrame(
                javafx.util.Duration.seconds(REFRESH_SECONDS), event -> refresh()));
        refreshTimer.setCycleCount(Animation.INDEFINITE);
        refreshTimer.play();
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
        latestEntries = EventScheduleService.obtain().findAll();

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
            return Long.MIN_VALUE;
        }
        LocalDateTime end = entry.getInactiveSince();
        return end != null ? Long.MAX_VALUE - end.toEpochSecond(ZoneOffset.UTC) : Long.MAX_VALUE;
    }

    /** Full-size row: family icon, status pill, title, and a bold date line. "Last scanned" lives
     *  in the hover tooltip rather than taking a line of its own. */
    private VBox buildRow(EventScheduleEntry entry, LocalDateTime nowUtc) {
        Label icon = new Label(iconFor(entry.getEventLabel()));
        icon.getStyleClass().add("upcoming-events-icon");

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
        Label icon = new Label(iconFor(entry.getEventLabel()));
        icon.getStyleClass().add("upcoming-events-ended-icon");

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

    /** Only ever states what is actually known. An event whose end the game never showed says when
     *  it starts and stops there, rather than printing "Unknown" as if that were a fact. */
    private String describeWindow(EventScheduleEntry entry) {
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

    // Month Gantt

    private void rebuildMonthView() {
        YearMonth month = monthAnchor;
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        LocalDate today = LocalDate.now(EventScheduleClock.zone());
        int dayCount = month.lengthOfMonth();

        List<Bar> stateBars = new ArrayList<>();
        List<Bar> allianceBars = new ArrayList<>();
        for (EventScheduleEntry entry : latestEntries) {
            Bar bar = toBar(entry, monthStart, monthEnd);
            if (bar != null) {
                (isAllianceEvent(entry.getEventKey()) ? allianceBars : stateBars).add(bar);
            }
        }
        stateBars.sort(java.util.Comparator.comparingInt(b -> b.firstColumn));
        allianceBars.sort(java.util.Comparator.comparingInt(b -> b.firstColumn));

        GridPane chart = new GridPane();
        chart.getStyleClass().add("upcoming-events-gantt");
        for (int day = 0; day < dayCount; day++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setMinWidth(DAY_COLUMN_WIDTH);
            column.setPrefWidth(DAY_COLUMN_WIDTH);
            chart.getColumnConstraints().add(column);
        }

        int rowCount = 1
                + (stateBars.isEmpty() ? 0 : 1 + stateBars.size())
                + (allianceBars.isEmpty() ? 0 : 1 + allianceBars.size());

        // Today's column is washed behind everything, the way the game marks the current day.
        // Added first so later children paint on top of it.
        if (!today.isBefore(monthStart) && !today.isAfter(monthEnd)) {
            Region wash = new Region();
            wash.getStyleClass().add("upcoming-events-gantt-today-wash");
            chart.add(wash, today.getDayOfMonth() - 1, 0, 1, Math.max(rowCount, 1));
        }

        for (int day = 0; day < dayCount; day++) {
            LocalDate date = monthStart.plusDays(day);
            Label dow = new Label(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault()));
            dow.getStyleClass().add("upcoming-events-gantt-dow");
            Label number = new Label(String.valueOf(date.getDayOfMonth()));
            number.getStyleClass().add("upcoming-events-gantt-daynum");
            VBox header = new VBox(dow, number);
            header.setAlignment(javafx.geometry.Pos.CENTER);
            header.getStyleClass().add("upcoming-events-gantt-daycell");
            if (date.equals(today)) {
                header.getStyleClass().add("upcoming-events-gantt-today");
            }
            chart.add(header, day, 0);
        }

        int row = 1;
        row = addSection(chart, row, dayCount, "State Events", stateBars);
        addSection(chart, row, dayCount, "Alliance Events", allianceBars);

        ScrollPane scroller = new ScrollPane(chart);
        scroller.setFitToHeight(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.getStyleClass().add("upcoming-events-gantt-scroll");
        // Open on today rather than the 1st, so the useful part of the month is what is on screen.
        // The chart is rebuilt from scratch on every poll, so whatever the viewer scrolled to is
        // carried across; without this the view would snap back to today every 30 seconds.
        if (monthScroll == null) {
            monthScroll = dayCount <= 1 ? 0d : (double) (today.getDayOfMonth() - 1) / (dayCount - 1);
        }
        scroller.setHvalue(monthScroll);
        scroller.hvalueProperty().addListener((obs, was, now) -> monthScroll = now.doubleValue());

        Label empty = new Label("Nothing scheduled in " + monthLabel(month) + ".");
        empty.getStyleClass().add("upcoming-events-nothing-live");

        VBox body = new VBox(10, buildMonthNav(month));
        body.getChildren().add(stateBars.isEmpty() && allianceBars.isEmpty() ? empty : scroller);
        monthCalendarHost.getChildren().setAll(body);
    }

    private HBox buildMonthNav(YearMonth month) {
        Button previous = new Button("◀");
        previous.getStyleClass().add("upcoming-events-gantt-nav");
        previous.setOnAction(event -> {
            monthAnchor = monthAnchor.minusMonths(1);
            monthScroll = 0d;
            rebuildMonthView();
        });

        Button next = new Button("▶");
        next.getStyleClass().add("upcoming-events-gantt-nav");
        next.setOnAction(event -> {
            monthAnchor = monthAnchor.plusMonths(1);
            monthScroll = 0d;
            rebuildMonthView();
        });

        Label title = new Label(monthLabel(month));
        title.getStyleClass().add("upcoming-events-gantt-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox nav = new HBox(8, previous, title, spacer, next);
        nav.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return nav;
    }

    private String monthLabel(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.getYear();
    }

    /** Lays one titled block of bars into the chart and returns the next free row. */
    private int addSection(GridPane chart, int startRow, int dayCount, String title, List<Bar> bars) {
        if (bars.isEmpty()) {
            return startRow;
        }
        Label header = new Label(title);
        header.getStyleClass().add("upcoming-events-gantt-section");
        header.setMaxWidth(Double.MAX_VALUE);
        chart.add(header, 0, startRow, dayCount, 1);

        int row = startRow + 1;
        for (Bar bar : bars) {
            chart.add(bar.chip, bar.firstColumn, row, bar.span, 1);
            // The name is a separate node spanning to the end of the month rather than text inside
            // the chip: a one-day bar is 58px and would render every label as "B...". Nothing else
            // occupies this row, so the text is free to run past the chip and stay readable.
            chart.add(bar.label, bar.firstColumn, row, dayCount - bar.firstColumn, 1);
            GridPane.setValignment(bar.label, VPos.CENTER);
            row++;
        }
        return row;
    }

    /** Clips one entry to the displayed month, or returns null when it does not touch it. An entry
     *  whose end the game never showed is a single-day bar on its start, never a bar to the edge --
     *  drawing an unknown end as a long run would state something that was never read. */
    private Bar toBar(EventScheduleEntry entry, LocalDate monthStart, LocalDate monthEnd) {
        LocalDate start = toViewerDate(entry.getActiveSince());
        LocalDate end = toViewerDate(entry.getInactiveSince());
        if (start == null && end == null) {
            return null;
        }
        LocalDate from = start != null ? start : end;
        LocalDate to = (end != null && !end.isBefore(from)) ? end : from;
        if (to.isBefore(monthStart) || from.isAfter(monthEnd)) {
            return null;
        }
        LocalDate clippedFrom = from.isBefore(monthStart) ? monthStart : from;
        LocalDate clippedTo = to.isAfter(monthEnd) ? monthEnd : to;

        Region chip = new Region();
        chip.setMaxWidth(Double.MAX_VALUE);
        chip.getStyleClass().addAll("upcoming-events-gantt-bar",
                isAllianceEvent(entry.getEventKey())
                        ? "upcoming-events-gantt-bar-alliance"
                        : "upcoming-events-gantt-bar-state");
        if (entry.isCurrentlyActive()) {
            chip.getStyleClass().add("upcoming-events-gantt-bar-active");
        }
        // The bar is clipped to the displayed month, so the full window stays reachable on hover.
        Tooltip.install(chip, new Tooltip(entry.getEventLabel() + "\n" + describeWindow(entry)));

        Label label = new Label(iconFor(entry.getEventLabel()) + "  " + entry.getEventLabel());
        label.getStyleClass().add("upcoming-events-gantt-bar-label");
        label.setMouseTransparent(true);

        Bar bar = new Bar();
        bar.chip = chip;
        bar.label = label;
        bar.firstColumn = clippedFrom.getDayOfMonth() - 1;
        bar.span = clippedTo.getDayOfMonth() - clippedFrom.getDayOfMonth() + 1;
        return bar;
    }

    /** One laid-out event bar: the coloured chip, its name, and where both sit in the day grid. */
    private static final class Bar {
        private Region chip;
        private Label label;
        private int firstColumn;
        private int span;
    }

    private LocalDate toViewerDate(LocalDateTime storedUtc) {
        if (storedUtc == null) {
            return null;
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
