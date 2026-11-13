package dev.frostguard.app.panel.today;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import atlantafx.base.theme.PrimerDark;
import dev.frostguard.app.shared.JavaFxToolkit;
import javafx.application.ConditionalFeature;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/** The running marker is a spinning globe (or the flat dot without 3D) and only spins while visible. */
class RunningIndicatorUxTest {

    @BeforeAll
    static void startJavaFx() throws Exception {
        JavaFxToolkit.start();
        onFx(() -> {
            Platform.setImplicitExit(false);
            Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
            return null;
        });
    }

    @Test
    void buildsTheGlobeOrTheFlatDotAtTheRightSize() throws Exception {
        onFx(() -> {
            RunningIndicator indicator = new RunningIndicator();
            boolean has3d = Platform.isSupported(ConditionalFeature.SCENE3D);
            assertEquals(has3d, indicator.isGlobe());
            assertEquals(has3d ? RunningIndicator.SIZE : RunningIndicator.FALLBACK_SIZE, indicator.getMaxWidth());
            assertEquals(1, indicator.getChildren().size());
            return null;
        });
    }

    @Test
    void spinsOnlyWhileShownAndVisible() throws Exception {
        onFx(() -> {
            RunningIndicator indicator = new RunningIndicator();
            if (!indicator.isGlobe()) {
                assertFalse(indicator.isSpinning());
                return null;
            }
            assertFalse(indicator.isSpinning(), "not in a scene yet");
            HBox host = new HBox(indicator);
            Stage stage = new Stage();
            stage.setScene(new Scene(host, 60, 40));
            stage.show();
            try {
                assertTrue(indicator.isSpinning(), "showing and visible");
                indicator.setVisible(false);
                assertFalse(indicator.isSpinning(), "hidden");
                indicator.setVisible(true);
                host.setVisible(false);
                assertFalse(indicator.isSpinning(), "ancestor hidden");
                host.setVisible(true);
                assertTrue(indicator.isSpinning());
                host.getChildren().clear();
                assertFalse(indicator.isSpinning(), "removed from the scene");
            } finally {
                stage.hide();
            }
            return null;
        });
    }

    /** Writes the preview PNGs when -Dglobe.preview=path is given; always checks the snapshot is not blank. */
    @Test
    void rendersAVisibleGlobe() throws Exception {
        String preview = System.getProperty("globe.preview");
        onFx(() -> {
            RunningIndicator indicator = new RunningIndicator();
            HBox host = new HBox(indicator);
            host.setStyle("-fx-background-color: #1b1f27; -fx-padding: 4;");
            Scene scene = new Scene(host, 20, 20);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            try {
                WritableImage image = scene.snapshot(null);
                int lit = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        Color c = image.getPixelReader().getColor(x, y);
                        if (c.getBlue() > 0.3 && c.getBlue() > c.getRed()) {
                            lit++;
                        }
                    }
                }
                if (indicator.isGlobe()) {
                    assertTrue(lit > 20, "globe pixels drawn: " + lit);
                }
                if (preview != null) {
                    ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", new File(preview));
                    // Scaled 8x with nearest-neighbour so the real pixels are visible.
                    int w = (int) image.getWidth();
                    int h = (int) image.getHeight();
                    WritableImage big = new WritableImage(w * 8, h * 8);
                    for (int y = 0; y < h * 8; y++) {
                        for (int x = 0; x < w * 8; x++) {
                            big.getPixelWriter().setArgb(x, y, image.getPixelReader().getArgb(x / 8, y / 8));
                        }
                    }
                    ImageIO.write(SwingFXUtils.fromFXImage(big, null), "png", new File(preview.replace(".png", "-8x.png")));
                }
            } finally {
                stage.hide();
            }
            return null;
        });
    }

    private interface FxCall<T> {
        T call() throws Exception;
    }

    private static <T> T onFx(FxCall<T> call) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(call.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }
}
