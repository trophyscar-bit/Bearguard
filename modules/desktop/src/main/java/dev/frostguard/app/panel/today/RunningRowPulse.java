package dev.frostguard.app.panel.today;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * The breathing highlight behind the running task's row. There is one animation and one highlight
 * node for the whole page; a refresh rebuilds the rows, so the highlight is moved onto the new row
 * and the animation carries on without restarting.
 */
final class RunningRowPulse {

    private static final Duration HALF_BREATH = Duration.millis(900);
    private static final double FAINTEST = 0.05;
    private static final double STRONGEST = 0.32;

    private final Region highlight = new Region();
    private final Timeline breathing = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(highlight.opacityProperty(), FAINTEST)),
            new KeyFrame(HALF_BREATH, new KeyValue(highlight.opacityProperty(), STRONGEST, Interpolator.EASE_BOTH)));
    private final ChangeListener<Bounds> followRowSize =
            (observable, previous, bounds) -> highlight.resize(bounds.getWidth(), bounds.getHeight());

    private Pane row;
    private String key;

    RunningRowPulse() {
        highlight.getStyleClass().add("today-row-pulse");
        highlight.setManaged(false);
        highlight.setMouseTransparent(true);
        breathing.setAutoReverse(true);
        breathing.setCycleCount(Animation.INDEFINITE);
    }

    /** Puts the pulse on this row, or removes it when {@code runningRow} is null. */
    void show(String runningKey, Pane runningRow) {
        if (runningRow == null) {
            clear();
            return;
        }
        moveOnto(runningRow);
        if (!runningKey.equals(key)) {
            key = runningKey;
            breathing.playFromStart();
        }
    }

    void clear() {
        breathing.stop();
        key = null;
        detach();
    }

    /** True if {@code endedKey} is the task currently pulsing. */
    boolean isShowing(String endedKey) {
        return endedKey.equals(key);
    }

    private void moveOnto(Pane runningRow) {
        if (runningRow == row) {
            return;
        }
        detach();
        row = runningRow;
        row.getChildren().add(0, highlight);
        row.layoutBoundsProperty().addListener(followRowSize);
        highlight.resize(row.getLayoutBounds().getWidth(), row.getLayoutBounds().getHeight());
    }

    private void detach() {
        if (row != null) {
            row.layoutBoundsProperty().removeListener(followRowSize);
            row.getChildren().remove(highlight);
            row = null;
        }
    }

    Animation animation() {
        return breathing;
    }

    Region highlight() {
        return highlight;
    }
}
