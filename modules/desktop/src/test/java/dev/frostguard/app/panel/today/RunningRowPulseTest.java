package dev.frostguard.app.panel.today;

import dev.frostguard.app.shared.JavaFxToolkit;
import javafx.animation.Animation;
import javafx.application.Platform;
import javafx.scene.layout.HBox;
import javafx.util.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunningRowPulseTest {

    @BeforeAll
    static void startJavaFx() throws Exception {
        JavaFxToolkit.start();
    }

    private static void onFx(Runnable check) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                check.run();
                done.complete(null);
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        });
        done.get(10, TimeUnit.SECONDS);
    }

    @Test
    void breathesOnlyWhileATaskIsExecuting() throws Exception {
        onFx(() -> {
            RunningRowPulse pulse = new RunningRowPulse();
            HBox row = new HBox();
            assertEquals(Animation.Status.STOPPED, pulse.animation().getStatus());

            pulse.show("gather", row);
            assertEquals(Animation.Status.RUNNING, pulse.animation().getStatus());
            assertSame(row, pulse.highlight().getParent());

            pulse.show(null, null);
            assertEquals(Animation.Status.STOPPED, pulse.animation().getStatus());
            assertNull(pulse.highlight().getParent());
            assertTrue(row.getChildren().isEmpty());
        });
    }

    @Test
    void aRefreshMovesTheOneHighlightAndDoesNotRestartTheBreath() throws Exception {
        onFx(() -> {
            RunningRowPulse pulse = new RunningRowPulse();
            HBox before = new HBox();
            HBox rebuilt = new HBox();
            pulse.show("gather", before);
            pulse.animation().jumpTo(Duration.millis(400));
            Animation animation = pulse.animation();

            pulse.show("gather", rebuilt);

            assertSame(animation, pulse.animation());
            assertEquals(Animation.Status.RUNNING, animation.getStatus());
            assertEquals(400, animation.getCurrentTime().toMillis(), 1);
            assertSame(rebuilt, pulse.highlight().getParent());
            assertTrue(before.getChildren().isEmpty());
        });
    }

    @Test
    void movesToTheNextRunningTaskAndLeavesOnlyThatRowLit() throws Exception {
        onFx(() -> {
            RunningRowPulse pulse = new RunningRowPulse();
            HBox first = new HBox();
            HBox second = new HBox();
            pulse.show("gather", first);

            pulse.show("intel", second);

            assertEquals(Animation.Status.RUNNING, pulse.animation().getStatus());
            assertTrue(first.getChildren().isEmpty());
            assertEquals(1, second.getChildren().size());
            assertTrue(pulse.isShowing("intel"));
            assertFalse(pulse.isShowing("gather"));
        });
    }

    @Test
    void theHighlightTakesNoRowSpaceAndNoClicks() throws Exception {
        onFx(() -> {
            RunningRowPulse pulse = new RunningRowPulse();

            assertFalse(pulse.highlight().isManaged());
            assertTrue(pulse.highlight().isMouseTransparent());
        });
    }
}
