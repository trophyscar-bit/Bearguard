package dev.frostguard.app.panel.heroes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.app.shared.JavaFxToolkit;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;

/**
 * The Squads tab must fill every table from the scan saved on disk the moment a profile is
 * loaded. The fixture is the snapshot of a real live scan (21 heroes, 12 squad slots).
 */
class SquadsLayoutUxTest {

    @TempDir
    Path workspace;

    private String previousWorkspace;

    @BeforeAll
    static void startJavaFx() throws Exception {
        JavaFxToolkit.start();
    }

    @AfterEach
    void restoreWorkspace() {
        if (previousWorkspace == null) System.clearProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        else System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, previousWorkspace);
    }

    @Test
    void everyTableFillsFromTheSavedScan() throws Exception {
        View view = onFx(this::load);

        assertTrue(view.status().getText().startsWith("Last scan"), view.status().getText());
        // The fixture scan (run5, Oct 31) logged one problem; it is shown, not hidden.
        assertTrue(view.problems().getText().startsWith("Could not read: Expedition stats incomplete for Jessie"),
                view.problems().getText());
        // Twelve squad slots plus the exploration team.
        assertEquals(13, view.table("tableSuggested").getItems().size());
        assertEquals(12, view.table("tableSaved").getItems().size());
        assertEquals(5, view.table("tableTroops").getItems().size());
        // Setting up squads only: hero, gear, skill and joiner views live on the Heroes page.
        TabPane tabs = (TabPane) view.root().lookup(".tab-pane");
        assertEquals(List.of("Suggested squads", "Saved squads (in game)", "Troops"),
                tabs.getTabs().stream().map(t -> t.getText()).toList());

        // Optional visual check: -Dsquads.render.dir=<folder> writes each tab as a PNG.
        String renderDir = System.getProperty("squads.render.dir");
        if (renderDir != null) onFx(() -> render(view, Path.of(renderDir)));
    }

    private record View(Parent root, Map<String, Object> namespace) {
        @SuppressWarnings("unchecked")
        TableView<List<String>> table(String id) {
            return (TableView<List<String>>) namespace.get(id);
        }

        Label status() {
            return (Label) namespace.get("labelStatus");
        }

        Label problems() {
            return (Label) namespace.get("labelProblems");
        }
    }

    private View load() throws Exception {
        Path dir = Files.createDirectories(workspace.resolve("squads"));
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/squads/live-snapshot.json"))) {
            Files.copy(in, dir.resolve("profile-1.json"), StandardCopyOption.REPLACE_EXISTING);
        }
        previousWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, workspace.toString());

        FXMLLoader loader = new FXMLLoader(SquadsLayoutController.class.getResource("/layout/SquadsLayout.fxml"));
        SquadsLayoutController controller = new SquadsLayoutController();
        loader.setController(controller);
        Parent root = loader.load();
        controller.onProfileLoad(new ProfileAux(1L, "Default", "0", true, 50L, "", 0L));
        return new View(root, loader.getNamespace());
    }

    private static Void render(View view, Path dir) throws Exception {
        Files.createDirectories(dir);
        Scene scene = new Scene(view.root(), 1400, 800);
        TabPane tabs = (TabPane) view.root().lookup(".tab-pane");
        for (int i = 0; i < tabs.getTabs().size(); i++) {
            tabs.getSelectionModel().select(i);
            view.root().applyCss();
            view.root().layout();
            File out = dir.resolve("squads-tab-" + (i + 1) + ".png").toFile();
            ImageIO.write(SwingFXUtils.fromFXImage(scene.snapshot(null), null), "png", out);
        }
        return null;
    }

    @FunctionalInterface
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
