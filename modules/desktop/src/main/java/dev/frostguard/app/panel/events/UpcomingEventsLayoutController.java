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
 * Full "Upcoming Events" page, opened from its own pinned sidebar button (mirrors Chat/Config)
 * rather than crammed into the sidebar footer. Shows every {@link EventScheduleEntry} the
 * various scan routines have recorded, with a relative/absolute time rendered through
 * {@link EventScheduleClock}.
 *
 * <p>Polls rather than listens, same reasoning as the compact widget this replaced: the cache is
 * a cheap local read and every row's countdown text needs to re-render every tick regardless of
 * whether the underlying data changed, so a push listener buys nothing here. Mirrors
 * {@code TaskGanttOverviewController}'s {@link Timeline}/{@link KeyFrame} polling idiom -- already
 * FX-thread native, no {@code Platform.runLater} needed.</p>
 */
public class UpcomingEventsLayoutController {

    private static final int REFRESH_SECONDS = 30;
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("EEE HH:mm");

    @FXML
    private VBox cardsContainer;

    @FXML
    private Label labelEmpty;

    private Timeline refreshTimer;

    @FXML
    private void initialize() {
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

    private void refresh() {
        List<EventScheduleEntry> entries = EventScheduleService.obtain().findAll();
        cardsContainer.getChildren().clear();

        boolean empty = entries.isEmpty();
        labelEmpty.setVisible(empty);
        labelEmpty.setManaged(empty);
        if (empty) {
            return;
        }

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        for (EventScheduleEntry entry : entries) {
            cardsContainer.getChildren().add(buildCard(entry, nowUtc));
        }
    }

    private VBox buildCard(EventScheduleEntry entry, LocalDateTime nowUtc) {
        Label label = new Label(entry.getEventLabel());
        label.getStyleClass().add("upcoming-events-label");
        Label status = new Label(statusText(entry, nowUtc));
        status.getStyleClass().add("upcoming-events-status");
        status.setWrapText(true);
        Label lastScanned = new Label("Last scanned "
                + toViewerZone(entry.getLastScannedAt()));
        lastScanned.getStyleClass().add("upcoming-events-empty");

        VBox card = new VBox(4, label, status, lastScanned);
        card.getStyleClass().add("upcoming-events-card");
        return card;
    }

    private String statusText(EventScheduleEntry entry, LocalDateTime nowUtc) {
        if (entry.isCurrentlyActive()) {
            LocalDateTime endsAt = entry.getInactiveSince();
            if (endsAt != null && endsAt.isAfter(nowUtc)) {
                return "Active -- ends in " + formatDuration(Duration.between(nowUtc, endsAt))
                        + " (" + toViewerZone(endsAt) + ")";
            }
            return "Active";
        }
        LocalDateTime nextStart = entry.getActiveSince();
        if (nextStart != null && nextStart.isAfter(nowUtc)) {
            return "Starts in " + formatDuration(Duration.between(nowUtc, nextStart))
                    + " (" + toViewerZone(nextStart) + ")";
        }
        return "Not active";
    }

    private String toViewerZone(LocalDateTime storedUtc) {
        if (storedUtc == null) {
            return "--";
        }
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
