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
    private static final int ICON_TILE_SIZE = 42;
    private static final int ICON_TILE_ART = 34;

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
        LocalDate from = this.weekStart;
        LocalDate to = from.plusDays(WEEK_DAYS - 1);
        LocalDate today = LocalDate.now(EventScheduleClock.zone());
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        VBox days = new VBox(4);
        days.setMaxWidth(Double.MAX_VALUE);
        int shown = 0;
        for (int offset = 0; offset < WEEK_DAYS; offset++) {
            LocalDate date = from.plusDays(offset);
            List<EventScheduleEntry> onDay = new ArrayList<>();
            for (EventScheduleEntry entry : latestEntries) {
                if (runsOn(entry, date)) {
                    onDay.add(entry);
                }
            }
            onDay.sort(java.util.Comparator
                    .comparing((EventScheduleEntry e) -> !e.isCurrentlyActive())
                    .thenComparing(e -> e.getEventLabel() == null ? "" : e.getEventLabel()));
            shown += onDay.size();
            days.getChildren().add(buildDaySection(date, today, onDay, nowUtc));
        }

        VBox body = new VBox(12, buildWeekNav(from, to));
        if (shown == 0) {
            Label empty = new Label("Nothing scheduled in these seven days.");
            empty.getStyleClass().add("upcoming-events-nothing-live");
            body.getChildren().add(empty);
        } else {
            body.getChildren().add(days);
        }
        monthCalendarHost.getChildren().setAll(body);
    }

    /** True when the event's run, in the viewer's own dates, covers that day. */
    private boolean runsOn(EventScheduleEntry entry, LocalDate date) {
        boolean allDay = isAllDay(entry);
        LocalDate start = toViewerDate(entry.getActiveSince(), allDay);
        LocalDate end = toViewerDate(entry.getInactiveSince(), allDay);
        if (start == null && end == null) {
            return false;
        }
        LocalDate first = start != null ? start : end;
        // An end the game never showed is not drawn as a run to the horizon: the event occupies the
        // day it starts and nothing more, because nothing more was ever read.
        LocalDate last = (end != null && !end.isBefore(first)) ? end : first;
        return !date.isBefore(first) && !date.isAfter(last);
    }

    private VBox buildDaySection(LocalDate date, LocalDate today,
                                 List<EventScheduleEntry> entries, LocalDateTime nowUtc) {
        Label weekday = new Label(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault())
                .toUpperCase(Locale.getDefault()));
        weekday.getStyleClass().add("upcoming-events-agenda-dow");
        Label number = new Label(String.valueOf(date.getDayOfMonth()));
        number.getStyleClass().add("upcoming-events-agenda-daynum");
        Label month = new Label(date.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault()));
        month.getStyleClass().add("upcoming-events-agenda-month");

        HBox stamp = new HBox(6, weekday, number, month);
        stamp.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        HBox header = new HBox(8, stamp);
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        header.getStyleClass().add("upcoming-events-agenda-dayheader");
        if (date.equals(today)) {
            header.getStyleClass().add("upcoming-events-agenda-dayheader-today");
            Label pill = new Label("TODAY");
            pill.getStyleClass().add("upcoming-events-agenda-todaypill");
            header.getChildren().add(pill);
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label count = new Label(entries.isEmpty() ? "" : entries.size() + (entries.size() == 1 ? " event" : " events"));
        count.getStyleClass().add("upcoming-events-agenda-count");
        header.getChildren().addAll(spacer, count);

        VBox section = new VBox(4, header);
        section.setMaxWidth(Double.MAX_VALUE);
        section.getStyleClass().add("upcoming-events-agenda-day");
        if (date.isBefore(today)) {
            section.getStyleClass().add("upcoming-events-agenda-day-past");
        }
        if (entries.isEmpty()) {
            Label quiet = new Label("Nothing scheduled");
            quiet.getStyleClass().add("upcoming-events-agenda-quiet");
            section.getChildren().add(quiet);
        }
        for (EventScheduleEntry entry : entries) {
            section.getChildren().add(buildAgendaRow(entry, date, today, nowUtc));
        }
        return section;
    }

    private HBox buildAgendaRow(EventScheduleEntry entry, LocalDate date, LocalDate today,
                                LocalDateTime nowUtc) {
        Label name = new Label(entry.getEventLabel());
        name.getStyleClass().add("upcoming-events-agenda-name");
        Label when = new Label(describeOnDay(entry, date, today));
        when.getStyleClass().add("upcoming-events-agenda-when");
        VBox text = new VBox(1, name, when);
        text.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);

        Label badge = new Label(badgeText(entry, nowUtc));
        badge.getStyleClass().addAll("upcoming-events-agenda-badge", badgeStyleClass(entry, nowUtc));

        HBox row = new HBox(10, buildIconTile(entry), text, badge);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        row.getStyleClass().addAll("upcoming-events-agenda-row",
                isAllianceEvent(entry.getEventKey())
                        ? "upcoming-events-agenda-row-alliance"
                        : "upcoming-events-agenda-row-state");
        if (entry.isCurrentlyActive() && date.equals(today)) {
            row.getStyleClass().add("upcoming-events-agenda-row-live");
        }
        Tooltip.install(row, new Tooltip(entry.getEventLabel() + "\n" + describeWindow(entry)));
        return row;
    }

    /** The game's own icon for this event, or the family glyph while none has been learned yet. */
    private StackPane buildIconTile(EventScheduleEntry entry) {
        Node art;
        Image icon = EventIconLibrary.iconFor(entry.getEventLabel());
        if (icon != null) {
            ImageView view = new ImageView(icon);
            view.setFitWidth(ICON_TILE_ART);
            view.setFitHeight(ICON_TILE_ART);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            // Shown whole, on the game's own badge, with the corners rounded off: the badge is part
            // of the icon, not a background behind it.
            Rectangle rounded = new Rectangle(ICON_TILE_ART, ICON_TILE_ART);
            rounded.setArcWidth(12);
            rounded.setArcHeight(12);
            view.setClip(rounded);
            art = view;
        } else {
            art = iconNodeFor(entry.getEventLabel(), "upcoming-events-agenda-glyph");
        }
        StackPane tile = new StackPane(art);
        tile.getStyleClass().add("upcoming-events-agenda-icon");
        tile.setMinSize(ICON_TILE_SIZE, ICON_TILE_SIZE);
        tile.setPrefSize(ICON_TILE_SIZE, ICON_TILE_SIZE);
        tile.setMaxSize(ICON_TILE_SIZE, ICON_TILE_SIZE);
        return tile;
    }

    /**
     * What this event is doing on this particular day: which day of its run, and the hours when it
     * is not a whole-day event. A multi-day event says the same thing on each of its days otherwise,
     * which reads as a repeat rather than a run.
     */
    private String describeOnDay(EventScheduleEntry entry, LocalDate date, LocalDate today) {
        boolean allDay = isAllDay(entry);
        LocalDate start = toViewerDate(entry.getActiveSince(), allDay);
        LocalDate end = toViewerDate(entry.getInactiveSince(), allDay);
        LocalDate first = start != null ? start : end;
        LocalDate last = (end != null && first != null && !end.isBefore(first)) ? end : first;

        StringBuilder text = new StringBuilder();
        if (!allDay) {
            text.append(describeWindow(entry));
        } else if (first != null && last != null && last.isAfter(first)) {
            long dayNumber = java.time.temporal.ChronoUnit.DAYS.between(first, date) + 1;
            long total = java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1;
            text.append("All day - day ").append(dayNumber).append(" of ").append(total);
        } else {
            text.append("All day");
        }
        if (date.equals(first) && !date.equals(last)) {
            text.append("  |  starts today");
        } else if (date.equals(last) && !date.equals(first)) {
            text.append("  |  last day");
        }
        return text.toString();
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
