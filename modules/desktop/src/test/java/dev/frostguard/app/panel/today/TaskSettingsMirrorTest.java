package dev.frostguard.app.panel.today;

import com.sun.javafx.stage.WindowHelper;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.app.shared.JavaFxToolkit;
import dev.frostguard.app.shared.SceneGraphWalk;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static dev.frostguard.api.configs.ConfigurationKeyEnum.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSettingsMirrorTest {

    /** A settings page reduced to its key table, so controls map to keys the way the real pages do. */
    private static final class FakePage extends AbstractProfileController {

        private final VBox root = new VBox();

        TextField text(ConfigurationKeyEnum key, String value) {
            TextField field = new TextField(value);
            textFieldMappings.put(field, key);
            root.getChildren().add(field);
            return field;
        }

        CheckBox check(ConfigurationKeyEnum key) {
            CheckBox box = new CheckBox(key.name());
            checkBoxMappings.put(box, key);
            root.getChildren().add(box);
            return box;
        }
    }

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        JavaFxToolkit.start();
    }

    private static void onFx(ThrowingRunnable body) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                body.run();
                done.complete(null);
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        });
        done.get(10, TimeUnit.SECONDS);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static TaskSettingsIndex.Source sourceOf(Node real) {
        return new TaskSettingsIndex.Source("Page", Map.of(), Map.of(), Map.of(real, "Caption"), null, List.of(real));
    }

    private static <T> T first(Node root, Class<T> type) {
        return SceneGraphWalk.descendants(root).stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }

    private static TaskSettingsIndex indexOf(String title, FakePage page) {
        Map<String, Parent> roots = Map.of(title, page.root);
        return new TaskSettingsIndex(Map.of(title, page), roots, null, null);
    }

    @Test
    void enterSavesThroughTheRealFieldsOwnHandler() throws Exception {
        onFx(() -> {
            TextField real = new TextField("120");
            List<String> saved = new ArrayList<>();
            real.setOnAction(event -> saved.add(real.getText()));
            TaskSettingsMirror.Result result = TaskSettingsMirror.build(sourceOf(real));
            TextField mirror = first(result.rows().get(0), TextField.class);

            mirror.setText("150");
            assertEquals("120", real.getText());
            assertTrue(result.dirty().getAsBoolean());

            mirror.fireEvent(new javafx.event.ActionEvent());

            assertEquals("150", real.getText());
            assertEquals(List.of("150"), saved);
            assertFalse(result.dirty().getAsBoolean());
        });
    }

    @Test
    void aChangeOnTheRealPageShowsInTheMirrorWithoutCountingAsAnEdit() throws Exception {
        onFx(() -> {
            TextField real = new TextField("120");
            TaskSettingsMirror.Result result = TaskSettingsMirror.build(sourceOf(real));
            TextField mirror = first(result.rows().get(0), TextField.class);

            real.setText("200");

            assertEquals("200", mirror.getText());
            assertFalse(result.dirty().getAsBoolean());
        });
    }

    @Test
    void aCheckBoxWritesThroughToTheRealOne() throws Exception {
        onFx(() -> {
            CheckBox real = new CheckBox("Option");
            TaskSettingsMirror.Result result = TaskSettingsMirror.build(sourceOf(real));

            first(result.rows().get(0), CheckBox.class).setSelected(true);

            assertTrue(real.isSelected());
        });
    }

    @Test
    void optionsResolveToTheControlsSavingTheirOwnKeys() throws Exception {
        onFx(() -> {
            FakePage page = new FakePage();
            CheckBox raidSwitch = page.check(LABYRINTH_RAID_ENABLED_BOOL);
            TextField research = page.text(LABYRINTH_RESEARCH_INFANTRY_INT, "1");
            TextField gearForge = page.text(LABYRINTH_GEARFORGE_MARKSMAN_INT, "2");
            page.text(GATHER_SPEED_BOOST_TYPE_STRING, "3");

            TaskSettingsIndex.Source source = indexOf("Labyrinth", page).find(TpDailyTaskEnum.LABYRINTH_RAID).orElseThrow();

            assertSame(raidSwitch, source.activation());
            assertEquals(List.of(research, gearForge), source.controls());
            assertEquals(LABYRINTH_RESEARCH_INFANTRY_INT, source.keys().get(research));
            assertEquals(LABYRINTH_GEARFORGE_MARKSMAN_INT, source.keys().get(gearForge));
        });
    }

    @Test
    void aTaskWhoseSwitchHasNoEnumKeyStillFindsItsSwitch() throws Exception {
        onFx(() -> {
            FakePage page = new FakePage();
            CheckBox shopSwitch = page.check(ALLIANCE_SHOP_ENABLED_BOOL);
            page.text(GATHER_SPEED_BOOST_TYPE_STRING, "x");

            TaskSettingsIndex.Source source = indexOf("Alliance Shop", page).find(TpDailyTaskEnum.ALLIANCE_SHOP).orElseThrow();

            assertSame(shopSwitch, source.activation());
        });
    }

    private static TaskInspectorPanel.Detail detail(String key, TpDailyTaskEnum type) {
        return new TaskInspectorPanel.Detail(key, key, type, null, "Queued", "Scheduled", "12:00", "Never", List.of(),
                RunNowPolicy.decide(true, false, false, true), true);
    }

    private static void withPanel(Consumer<TaskInspectorPanel> body, List<Integer> editEnded, TaskSettingsIndex index) {
        TaskInspectorPanel panel = new TaskInspectorPanel(key -> { }, () -> { }, () -> { }, () -> editEnded.add(1));
        panel.attachSettings(index);
        body.accept(panel);
    }

    /** A focus-holding window that is never shown, with skins built so the panel's rows are in the scene. */
    private static void inFocusedWindow(Parent content) {
        Stage neverShown = new Stage();
        neverShown.setScene(new Scene(content));
        WindowHelper.setFocused(neverShown, true);
        content.applyCss();
    }

    private static TextField panelField(TaskInspectorPanel panel) {
        Node body = ((ScrollPane) panel.root()).getContent();
        return first(body, TextField.class);
    }

    @Test
    void typingInThePanelIsAnEditUntilItIsSaved() throws Exception {
        onFx(() -> {
            FakePage page = new FakePage();
            page.check(LABYRINTH_RAID_ENABLED_BOOL);
            page.text(LABYRINTH_RESEARCH_INFANTRY_INT, "120");
            withPanel(panel -> {
                panel.show(detail("raid", TpDailyTaskEnum.LABYRINTH_RAID));
                TextField field = panelField(panel);
                assertFalse(panel.editInProgress());

                field.setText("12");
                assertTrue(panel.editInProgress());

                field.setText("120");
                assertFalse(panel.editInProgress());
            }, new ArrayList<>(), indexOf("Labyrinth", page));
        });
    }

    @Test
    void aFocusedTextBoxIsAnEditAndLeavingItSavesAndReportsTheEnd() throws Exception {
        onFx(() -> {
            FakePage page = new FakePage();
            page.check(LABYRINTH_RAID_ENABLED_BOOL);
            TextField real = page.text(LABYRINTH_RESEARCH_INFANTRY_INT, "120");
            List<Integer> editEnded = new ArrayList<>();
            withPanel(panel -> {
                panel.show(detail("raid", TpDailyTaskEnum.LABYRINTH_RAID));
                Button elsewhere = new Button("Elsewhere");
                inFocusedWindow(new VBox(panel.root(), elsewhere));
                TextField field = panelField(panel);

                field.requestFocus();
                assertTrue(field.isFocused());
                assertTrue(panel.editInProgress());
                assertEquals(List.of(), editEnded);

                field.setText("12");
                elsewhere.requestFocus();

                assertEquals("12", real.getText());
                assertFalse(panel.editInProgress());
                assertEquals(List.of(1), editEnded);
            }, editEnded, indexOf("Labyrinth", page));
        });
    }

    @Test
    void aFocusedCheckBoxDoesNotHoldUpFollowing() throws Exception {
        onFx(() -> {
            FakePage page = new FakePage();
            page.check(TRAIN_BOOL);
            withPanel(panel -> {
                panel.show(detail("train", TpDailyTaskEnum.TRAINING_INFANTRY));
                inFocusedWindow(new VBox(panel.root()));
                CheckBox option = first(((ScrollPane) panel.root()).getContent(), CheckBox.class);

                option.requestFocus();

                assertTrue(option.isFocused());
                assertFalse(panel.editInProgress());
            }, new ArrayList<>(), indexOf("Training", page));
        });
    }
}
