package dev.frostguard.app.panel.events;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.frostguard.api.runtime.WorkspacePaths;
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
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
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
    /** Month-Gantt day column. Wide enough for a two-day bar to still show a readable label; the
     *  chart scrolls horizontally rather than squeezing a whole month into the page width. */
    private static final int DAY_COLUMN_WIDTH = 58;
    private static final int BAR_ROW_MIN_HEIGHT = 30;
    private static final int BAR_ROW_MAX_HEIGHT = 58;

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
    /** Label to artwork, including misses, so a 30-second poll does not re-walk the icon folder. */
    private final Map<String, Image> iconCache = new HashMap<>();

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
        Node icon = iconNodeFor(entry.getEventLabel(), 28, "upcoming-events-icon");

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
        Node icon = iconNodeFor(entry.getEventLabel(), 16, "upcoming-events-ended-icon");

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
     * The event's own in-game icon where the calendar scan has collected one, falling back to a
     * per-family glyph.
     *
     * <p>The scan already crops each bar's icon to name truncated events, so the real artwork is
     * sitting in the workspace; showing it beats a stand-in emoji, and it is the same picture the
     * game shows, which is what makes a row recognisable at a glance.</p>
     */
    private Node iconNodeFor(String label, double size, String fallbackStyleClass) {
        Image artwork = gameIcon(label);
        if (artwork != null) {
            ImageView view = new ImageView(artwork);
            view.setFitWidth(size);
            view.setFitHeight(size);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            return view;
        }
        Label glyph = new Label(iconFor(label));
        glyph.getStyleClass().add(fallbackStyleClass);
        return glyph;
    }

    /**
     * Icon artwork for an event label, or null when none is stored.
     *
     * <p>Labels carry qualifiers the file names do not ("Fortress Battles (Fortress No. 12)", or a
     * trailing ellipsis on a truncated name), so an exact match is tried first and then the longest
     * stored name that starts the label. Longest wins so "Alliance Championship" is not beaten by a
     * shorter entry that happens to share its opening words.</p>
     */
    private Image gameIcon(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        if (iconCache.containsKey(label)) {
            return iconCache.get(label);
        }
        Image found = null;
        try {
            Path dir = WorkspacePaths.current().root().resolve("data").resolve("calendar-icons");
            if (Files.isDirectory(dir)) {
                String wanted = normaliseIconName(label);
                Path best = null;
                int bestLength = -1;
                try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".png")).toList()) {
                        String name = file.getFileName().toString();
                        String candidate = normaliseIconName(
                                name.substring(0, name.length() - 4).replace('_', ' '));
                        if (wanted.equals(candidate)
                                || (wanted.startsWith(candidate) && candidate.length() > bestLength)) {
                            best = file;
                            bestLength = wanted.equals(candidate) ? Integer.MAX_VALUE : candidate.length();
                        }
                    }
                }
                if (best != null) {
                    found = new Image(best.toUri().toString(), 0, 0, true, true);
                }
            }
        } catch (IOException | RuntimeException unavailable) {
            found = null;
        }
        iconCache.put(label, found);
        return found;
    }

    private static String normaliseIconName(String value) {
        return value.replaceAll("[^A-Za-z0-9 ]", "").replaceAll("\\s+", " ").trim().toLowerCase();
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

        // One continuous run of bars, as the game draws it. The bar colour plus the legend above
        // the chart already say state from alliance, so no banding rows in between.
        List<Bar> bars = new ArrayList<>(stateBars);
        bars.addAll(allianceBars);
        int rowCount = 1 + bars.size();

        // The day header keeps its natural height; the bar rows share whatever is left of the page,
        // capped so a month holding two events does not draw them as two enormous slabs.
        chart.getRowConstraints().add(new RowConstraints());
        for (int bar = 0; bar < bars.size(); bar++) {
            RowConstraints barRow = new RowConstraints();
            barRow.setMinHeight(BAR_ROW_MIN_HEIGHT);
            barRow.setPrefHeight(BAR_ROW_MIN_HEIGHT);
            barRow.setMaxHeight(BAR_ROW_MAX_HEIGHT);
            barRow.setVgrow(Priority.ALWAYS);
            chart.getRowConstraints().add(barRow);
        }

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
        for (Bar bar : bars) {
            chart.add(bar.chip, bar.firstColumn, row, bar.span, 1);
            // The name is a separate node spanning to the end of the month rather than text inside
            // the chip: a one-day bar is 58px and would render every label as "B...". Nothing else
            // occupies this row, so the text is free to run past the chip and stay readable.
            chart.add(bar.label, bar.firstColumn, row, dayCount - bar.firstColumn, 1);
            GridPane.setValignment(bar.label, VPos.CENTER);
            row++;
        }

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
        javafx.scene.Node content = bars.isEmpty() ? empty : scroller;
        VBox.setVgrow(content, Priority.ALWAYS);
        body.getChildren().add(content);
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

    /** Clips one entry to the displayed month, or returns null when it does not touch it. An entry
     *  whose end the game never showed is a single-day bar on its start, never a bar to the edge --
     *  drawing an unknown end as a long run would state something that was never read. */
    private Bar toBar(EventScheduleEntry entry, LocalDate monthStart, LocalDate monthEnd) {
        boolean allDay = isAllDay(entry);
        LocalDate start = toViewerDate(entry.getActiveSince(), allDay);
        LocalDate end = toViewerDate(entry.getInactiveSince(), allDay);
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

        // The icon rides inside the coloured bar, the way the game draws it, so a one-day bar is
        // still identifiable without its name.
        HBox chip = new HBox(iconNodeFor(entry.getEventLabel(), 18, "upcoming-events-gantt-bar-glyph"));
        chip.setAlignment(javafx.geometry.Pos.CENTER);
        chip.setMaxWidth(Double.MAX_VALUE);
        chip.setMaxHeight(Double.MAX_VALUE);
        chip.getStyleClass().addAll("upcoming-events-gantt-bar",
                isAllianceEvent(entry.getEventKey())
                        ? "upcoming-events-gantt-bar-alliance"
                        : "upcoming-events-gantt-bar-state");
        if (entry.isCurrentlyActive()) {
            chip.getStyleClass().add("upcoming-events-gantt-bar-active");
        }
        // The bar is clipped to the displayed month, so the full window stays reachable on hover.
        Tooltip.install(chip, new Tooltip(entry.getEventLabel() + "\n" + describeWindow(entry)));

        // Set outside the bar in plain text rather than styled like it: a one-day bar is 58px and
        // a bold label across the next four days reads as though the event ran for five.
        Label label = new Label(entry.getEventLabel());
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
        private HBox chip;
        private Label label;
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
