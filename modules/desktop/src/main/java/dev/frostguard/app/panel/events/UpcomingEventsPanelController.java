package dev.frostguard.app.panel.events;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import dev.frostguard.data.entity.EventScheduleEntry;
import dev.frostguard.engine.service.EventScheduleService;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Small always-on calendar docked in the nav sidebar's footer, showing each tracked event's
 * current/next window from {@link EventScheduleService}'s cache.
 *
 * <p>Polls rather than listens -- the cache is a cheap local read and rows need to re-render
 * their own countdown text every tick regardless of whether the underlying data changed, so a
 * push listener would not save anything. Mirrors {@code TaskGanttOverviewController}'s
 * {@link Timeline}/{@link KeyFrame} polling idiom; runs on the FX thread already, no
 * {@code Platform.runLater} needed.</p>
 */
public class UpcomingEventsPanelController {

    private static final int REFRESH_SECONDS = 30;
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    @FXML
    private VBox rowsContainer;

    private Timeline refreshTimer;

    @FXML
    private void initialize() {
        refresh();
        refreshTimer = new Timeline(new KeyFrame(
                javafx.util.Duration.seconds(REFRESH_SECONDS), event -> refresh()));
        refreshTimer.setCycleCount(Animation.INDEFINITE);
        refreshTimer.play();
    }

    /** Stops the polling timer; call when the sidebar itself is torn down. */
    public void stopAutoRefresh() {
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
    }

    private void refresh() {
        List<EventScheduleEntry> entries = EventScheduleService.obtain().findAll();
        rowsContainer.getChildren().clear();

        if (entries.isEmpty()) {
            Label empty = new Label("No data yet -- enable Event Schedule Scan.");
            empty.getStyleClass().add("upcoming-events-empty");
            empty.setWrapText(true);
            rowsContainer.getChildren().add(empty);
            return;
        }

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        for (EventScheduleEntry entry : entries) {
            rowsContainer.getChildren().add(buildRow(entry, nowUtc));
        }
    }

    private VBox buildRow(EventScheduleEntry entry, LocalDateTime nowUtc) {
        Label label = new Label(entry.getEventLabel());
        label.getStyleClass().add("upcoming-events-label");
        Label status = new Label(statusText(entry, nowUtc));
        status.getStyleClass().add("upcoming-events-status");
        VBox row = new VBox(label, status);
        row.getStyleClass().add("upcoming-events-row");
        return row;
    }

    private String statusText(EventScheduleEntry entry, LocalDateTime nowUtc) {
        if (entry.isCurrentlyActive()) {
            return "Active";
        }
        LocalDateTime nextStart = entry.getActiveSince();
        if (nextStart != null && nextStart.isAfter(nowUtc)) {
            return "Starts in " + formatDuration(Duration.between(nowUtc, nextStart))
                    + " (" + toViewerZone(nextStart) + ")";
        }
        return "Not active (checked " + formatDuration(Duration.between(entry.getLastScannedAt(), nowUtc))
                + " ago)";
    }

    private String toViewerZone(LocalDateTime storedUtc) {
        return storedUtc.atZone(ZoneOffset.UTC)
                .withZoneSameInstant(EventScheduleClock.zone())
                .format(CLOCK_FORMAT);
    }

    private static String formatDuration(Duration duration) {
        long totalMinutes = Math.max(0, duration.toMinutes());
        long days = totalMinutes / (24 * 60);
        long hours = (totalMinutes % (24 * 60)) / 60;
        long minutes = totalMinutes % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }
}
