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
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

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
    /** Tile and art sizes for an event's icon: the art is the game's own, cut from its bar. */
    /** The tile has to sit inside the bar, not over it: a tile taller than the bar's inner height
     *  overhung the pill top and bottom and covered its coloured edge. */
    private static final int ICON_TILE_SIZE = 30;
    private static final int ICON_TILE_ART = 26;
    /** The strip of events that only carry on through a later day, so they read as a footnote. */
    private static final int ICON_TILE_SMALL = 26;

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
            // Any numbered trap at all is enough. The generic row is a prediction from the
            // configured anchor; the numbered ones are read off the game's own Task List, which
            // says which trap and when. Keeping both showed the trap three times.
            return true;
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
     * Whether this entry covers whole game days rather than a moment in one.
     *
     * <p>A game day is not a calendar day: the chart's own header reads "UTC Time 2026-09-16" while
     * the local clock still says Tuesday the 15th, because the game rolls over at 00:00 UTC, which
     * is 8pm here. So a bar the chart draws on Wed 09/16 genuinely begins on Tuesday evening for
     * the viewer, and that is the day they will call it.</p>
     *
     * <p>These are therefore converted like any other instant, and rendered as dates only. Showing
     * the raw UTC date instead puts every bar a day late; showing the converted instant with its
     * time attached puts a meaningless "8:00 PM" on an all-day event. Both were tried.</p>
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
            LocalDate from = toViewerDate(entry.getActiveSince(), true);
            LocalDate to = toViewerDate(entry.getInactiveSince(), true);
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
     * Seven days down the page, each day carrying its own events.
     *
     * <p>A grid of seven narrow day columns was the obvious shape and the wrong one: names had to
     * live outside it in a fixed column, a one-day event was a stub too small to read, and the page
     * wasted the thing this window has most of -- height. Down the page each event gets a full row
     * on every day it runs, with the game's own icon, so the answer to "what is on today" is the
     * first thing in view instead of a bar to be traced back to a name.</p>
     */
    private void rebuildMonthView() {
        LocalDate from = LocalDate.now(EventScheduleClock.zone());
        LocalDate to = from.plusDays(WEEK_DAYS - 1);
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        List<Run> runs = new ArrayList<>();
        for (EventScheduleEntry entry : latestEntries) {
            Run run = toRun(entry, from, to);
            if (run != null) {
                runs.add(run);
            }
        }
        // Longest first, then by start: the same shape the game draws, and it keeps the week-long
        // bars together at the top instead of interleaving them with one-day events.
        runs.sort(java.util.Comparator
                .comparingInt((Run r) -> -(r.span))
                .thenComparingInt(r -> r.firstColumn)
                .thenComparing(r -> r.entry.getEventLabel() == null ? "" : r.entry.getEventLabel()));

        GridPane chart = new GridPane();
        chart.getStyleClass().add("upcoming-events-chart");
        chart.setMaxWidth(Double.MAX_VALUE);
        for (int day = 0; day < WEEK_DAYS; day++) {
            ColumnConstraints column = new ColumnConstraints();
            // Equal prefs plus ALWAYS rather than percentages: a percentage is taken of the whole
            // grid, so seven of 100/7 each claimed the full width and pushed the last days off.
            column.setPrefWidth(1);
            column.setHgrow(Priority.ALWAYS);
            column.setFillWidth(true);
            chart.getColumnConstraints().add(column);
        }

        // Today's column is washed from the header to the last row, the way the game marks it.
        Region wash = new Region();
        wash.getStyleClass().add("upcoming-events-chart-today-wash");
        wash.setMouseTransparent(true);
        chart.add(wash, 0, 0, 1, Math.max(runs.size() + 1, 2));

        for (int day = 0; day < WEEK_DAYS; day++) {
            LocalDate date = from.plusDays(day);
            Label weekday = new Label(date.getDayOfWeek()
                    .getDisplayName(TextStyle.SHORT, Locale.getDefault()).toUpperCase(Locale.getDefault()));
            weekday.getStyleClass().add("upcoming-events-chart-dow");
            Label number = new Label(String.format("%02d/%02d", date.getMonthValue(), date.getDayOfMonth()));
            number.getStyleClass().add("upcoming-events-chart-daynum");
            VBox head = new VBox(weekday, number);
            head.setAlignment(javafx.geometry.Pos.CENTER);
            head.getStyleClass().add("upcoming-events-chart-dayhead");
            if (day == 0) {
                head.getStyleClass().add("upcoming-events-chart-dayhead-today");
            }
            chart.add(head, day, 0);
        }

        int row = 1;
        for (Run run : runs) {
            HBox bar = buildChartBar(run, nowUtc);
            chart.add(bar, run.firstColumn, row, run.span, 1);
            row++;
        }

        VBox body = new VBox(10);
        body.setMaxWidth(Double.MAX_VALUE);
        if (runs.isEmpty()) {
            Label empty = new Label("Nothing scheduled in the next seven days.");
            empty.getStyleClass().add("upcoming-events-nothing-live");
            body.getChildren().add(empty);
        } else {
            body.getChildren().add(chart);
        }
        monthCalendarHost.getChildren().setAll(body);
    }

    /**
     * One event's bar: its icon, its name, and an arrow when the run carries on past the week.
     *
     * <p>The name rides inside the bar, as the game draws it. A bar one day wide cannot hold a name,
     * so a short event keeps its icon and lets the tooltip carry the rest rather than showing an
     * ellipsis that says nothing.</p>
     */
    private HBox buildChartBar(Run run, LocalDateTime nowUtc) {
        EventScheduleEntry entry = run.entry;
        HBox bar = new HBox(6, buildIconTile(entry, ICON_TILE_SIZE, ICON_TILE_ART));
        if (run.span > 1) {
            Label name = new Label((run.clippedStart ? "◀ " : "") + entry.getEventLabel()
                    + (run.clippedEnd ? " ▶" : ""));
            name.getStyleClass().add("upcoming-events-chart-name");
            HBox.setHgrow(name, Priority.ALWAYS);
            bar.getChildren().add(name);
        }
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().addAll("upcoming-events-chart-bar",
                isAllianceEvent(entry.getEventKey())
                        ? "upcoming-events-chart-bar-alliance"
                        : "upcoming-events-chart-bar-state");
        if (entry.isCurrentlyActive()) {
            bar.getStyleClass().add("upcoming-events-chart-bar-live");
        }
        Tooltip.install(bar, new Tooltip(entry.getEventLabel() + "\n" + describeWindow(entry)
                + "\n" + badgeText(entry, nowUtc)));
        return bar;
    }

    /** The game's own icon for this event, or the family glyph while none has been learned yet. */
    private StackPane buildIconTile(EventScheduleEntry entry, int tileSize, int artSize) {
        Node art;
        Image icon = EventIconLibrary.iconFor(entry.getEventLabel());
        if (icon != null) {
            ImageView view = new ImageView(icon);
            view.setFitWidth(artSize);
            view.setFitHeight(artSize);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            art = view;
        } else {
            art = iconNodeFor(entry.getEventLabel(), "upcoming-events-agenda-glyph");
        }
        StackPane tile = new StackPane(art);
        tile.getStyleClass().add("upcoming-events-chart-icon");
        tile.setMinSize(tileSize, tileSize);
        tile.setPrefSize(tileSize, tileSize);
        tile.setMaxSize(tileSize, tileSize);
        return tile;
    }

    /** Clips one entry to the seven days on screen, or returns null when it does not touch them. */
    private Run toRun(EventScheduleEntry entry, LocalDate from, LocalDate to) {
        boolean allDay = isAllDay(entry);
        LocalDate start = toViewerDate(entry.getActiveSince(), allDay);
        LocalDate end = toViewerDate(entry.getInactiveSince(), allDay);
        if (start == null && end == null) {
            return null;
        }
        LocalDate first = start != null ? start : end;
        // An end the game never showed is one day on its start, never a bar to the edge: drawing an
        // unknown end as a long run would state something that was never read.
        LocalDate last = (end != null && !end.isBefore(first)) ? end : first;
        if (last.isBefore(from) || first.isAfter(to)) {
            return null;
        }
        LocalDate clippedFirst = first.isBefore(from) ? from : first;
        LocalDate clippedLast = last.isAfter(to) ? to : last;

        Run run = new Run();
        run.entry = entry;
        run.firstColumn = (int) java.time.temporal.ChronoUnit.DAYS.between(from, clippedFirst);
        run.span = (int) java.time.temporal.ChronoUnit.DAYS.between(clippedFirst, clippedLast) + 1;
        run.clippedStart = first.isBefore(from);
        run.clippedEnd = last.isAfter(to);
        return run;
    }

    /** One event laid out against the seven columns on screen. */
    private static final class Run {
        private EventScheduleEntry entry;
        private int firstColumn;
        private int span;
        private boolean clippedStart;
        private boolean clippedEnd;
    }

    /** Every stored value is a UTC instant, whole-day ones included, so all of them convert. The
     *  allDay flag is kept only to document that the caller knows which kind it is holding. */
    private LocalDate toViewerDate(LocalDateTime storedUtc, boolean allDay) {
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
