package dev.frostguard.app.panel.today;

import com.sun.javafx.stage.WindowHelper;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.app.shared.JavaFxToolkit;
import dev.frostguard.app.shared.SceneGraphWalk;
import dev.frostguard.app.panel.combat.BearTrapLayoutController;
import dev.frostguard.app.panel.dailies.LabyrinthLayoutController;
import dev.frostguard.app.panel.profile.ConfigAux;
import dev.frostguard.app.panel.profile.ProfileAux;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.controlsfx.control.CheckComboBox;
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

        void startListening() {
            initializeChangeEvents();
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
            assertEquals(List.of(research, gearForge, source.controls().get(2)), source.controls());
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

    @Test
    void aTaskListsItsOwnOptionsFirstThenTheRestOfThePage() throws Exception {
        onFx(() -> {
            FakePage page = new FakePage();
            CheckBox food = page.check(PET_SKILL_FOOD_BOOL);
            TextField resource = page.text(PET_SKILL_GATHERING_RESOURCE_STRING, "Wood");
            CheckBox stamina = page.check(PET_SKILL_STAMINA_BOOL);

            TaskSettingsIndex.Source source = indexOf("Pets", page).find(TpDailyTaskEnum.PET_SKILL_STAMINA).orElseThrow();

            assertSame(stamina, source.activation());
            assertEquals(List.of(food, resource), source.controls());
        });
    }

    @Test
    void aSharedPageKeepsATaskToItsOwnSection() throws Exception {
        onFx(() -> {
            FakePage page = new FakePage();
            CheckBox chests = page.check(ALLIANCE_CHESTS_BOOL);
            page.check(ALLIANCE_TECH_BOOL);

            TaskSettingsIndex.Source source = indexOf("Alliance", page).find(TpDailyTaskEnum.ALLIANCE_CHESTS).orElseThrow();

            assertSame(chests, source.activation());
            assertTrue(source.controls().stream().noneMatch(CheckBox.class::isInstance));
        });
    }

    @Test
    void repeatedCaptionsTakeThePageHeadingsAndNeverAnExampleNumber() throws Exception {
        onFx(() -> {
            FXMLLoader loader = new FXMLLoader(TaskSettingsMirrorTest.class.getResource("/layout/LabyrinthLayout.fxml"));
            LabyrinthLayoutController controller = new LabyrinthLayoutController();
            loader.setController(controller);
            Parent page = loader.load();

            TaskSettingsIndex.Source source = new TaskSettingsIndex(Map.of("Labyrinth", controller), Map.of("Labyrinth", page), null, null)
                    .find(TpDailyTaskEnum.LABYRINTH).orElseThrow();
            TaskSettingsMirror.Result result = TaskSettingsMirror.build(source);

            List<String> captions = result.rows().stream()
                    .map(row -> row instanceof VBox box ? ((Label) box.getChildren().get(0)).getText() : ((Labeled) row).getText())
                    .toList();
            assertTrue(captions.contains("Land of Heroes, Squad 1: Infantry %"), captions.toString());
            assertTrue(captions.stream().noneMatch(caption -> caption.matches("[\\d.,% ]*")), captions.toString());
            assertEquals(captions.size(), captions.stream().distinct().count(), captions.toString());
        });
    }

    /** Showing the Bear Trap page's options, swapping away and back, and loading profiles must save nothing. */
    @Test
    void showingAndSwappingPanelsWritesNothing() throws Exception {
        List<String> writes = new ArrayList<>();
        onFx(() -> {
            FXMLLoader loader = new FXMLLoader(TaskSettingsMirrorTest.class.getResource("/layout/BearTrapLayout.fxml"));
            BearTrapLayoutController bear = new BearTrapLayoutController();
            loader.setController(bear);
            Parent bearRoot = loader.load();
            bear.attachProfileListener((key, value) -> writes.add("bear " + key + "=" + value));
            FakePage pets = new FakePage();
            pets.attachProfileListener((key, value) -> writes.add("pets " + key + "=" + value));
            pets.check(PET_SKILL_FOOD_BOOL);
            pets.check(PET_SKILL_STAMINA_BOOL);
            pets.startListening();
            ProfileAux profile = new ProfileAux(1L, "One", "1", true, 50L, "", 0L);
            profile.setConfigs(List.of(
                    new ConfigAux(BEAR_TRAP_EVENT_BOOL.name(), "true"),
                    new ConfigAux(BEAR_TRAP_JOIN_RALLY_BOOL.name(), "true"),
                    new ConfigAux(BEAR_TRAP_JOIN_FLAG_INT.name(), "1,2,3"),
                    new ConfigAux(BEAR_TRAP_JOIN_TROOP_FLAG_STRING.name(), "4,5,6"),
                    new ConfigAux(BEAR_TRAP_PREPARATION_TIME_INT.name(), "10"),
                    new ConfigAux(BEAR_TRAP_SCHEDULE_DATETIME_STRING.name(), "10-11-2026 19:00")));
            bear.onProfileLoad(profile);
            pets.onProfileLoad(profile);

            Map<String, Parent> roots = Map.of("Bear Trap", bearRoot, "Pets", pets.root);
            TaskSettingsIndex index = new TaskSettingsIndex(Map.of("Bear Trap", bear, "Pets", pets), roots, null, null);
            withPanel(panel -> {
                inFocusedWindow(panel.root());
                panel.show(detail("bear", TpDailyTaskEnum.BEAR_TRAP));
                panel.show(detail("pet", TpDailyTaskEnum.PET_SKILL_STAMINA));
                panel.show(detail("bear", TpDailyTaskEnum.BEAR_TRAP));
                panel.root().applyCss();
                bear.onProfileLoad(profile);
                panel.show(detail("pet", TpDailyTaskEnum.PET_SKILL_STAMINA));
            }, new ArrayList<>(), index);
        });
        onFx(() -> { });

        assertEquals(List.of(), writes);
    }

    /** Bear Trap is edited only on its own page; the task panel shows a summary and a way there. */
    @Test
    void bearTrapShowsAReadOnlySummaryAndAJumpToItsPage() throws Exception {
        onFx(() -> {
            FXMLLoader loader = new FXMLLoader(TaskSettingsMirrorTest.class.getResource("/layout/BearTrapLayout.fxml"));
            BearTrapLayoutController bear = new BearTrapLayoutController();
            loader.setController(bear);
            Parent bearRoot = loader.load();
            List<String> writes = new ArrayList<>();
            bear.attachProfileListener((key, value) -> writes.add(key + "=" + value));
            ProfileAux profile = new ProfileAux(1L, "One", "1", true, 50L, "", 0L);
            profile.setConfigs(List.of(
                    new ConfigAux(BEAR_TRAP_EVENT_BOOL.name(), "true"),
                    new ConfigAux(BEAR_TRAP_CALL_RALLY_BOOL.name(), "true"),
                    new ConfigAux(BEAR_TRAP_NUMBER_INT.name(), "2"),
                    new ConfigAux(BEAR_TRAP_SCHEDULE_DATETIME_STRING.name(), "10-11-2026 19:00")));
            bear.onProfileLoad(profile);

            TaskSettingsIndex index = new TaskSettingsIndex(Map.of("Bear Trap", bear), Map.of("Bear Trap", bearRoot), null, null);
            List<String> opened = new ArrayList<>();
            TaskInspectorPanel panel = new TaskInspectorPanel(opened::add, () -> { }, () -> { }, () -> { });
            panel.attachSettings(index);
            panel.show(detail("bear", TpDailyTaskEnum.BEAR_TRAP));
            Node body = ((ScrollPane) panel.root()).getContent();

            List<Node> inputs = SceneGraphWalk.descendants(body).stream()
                    .filter(node -> node instanceof CheckBox || node instanceof ToggleButton || node instanceof ComboBoxBase<?>
                            || node instanceof TextInputControl || node instanceof CheckComboBox<?>
                            || node instanceof Spinner<?>)
                    .toList();
            assertEquals(List.of(), inputs, "no Bear Trap setting may be editable in the task panel");

            String summary = bear.settingsSummary().get();
            assertTrue(summary.startsWith("Leader · Trap 2 · "), summary);
            assertTrue(summary.endsWith(" · Auto bear on"), summary);
            assertTrue(SceneGraphWalk.descendants(body).stream()
                    .anyMatch(node -> node instanceof Label label && summary.equals(label.getText())));

            Hyperlink edit = first(body, Hyperlink.class);
            assertEquals("Edit in Events > Bear Trap", edit.getText());
            edit.fire();
            assertEquals(List.of("Bear Trap"), opened);

            ((RadioButton) loader.getNamespace().get("radioJoiner")).fire();
            assertTrue(SceneGraphWalk.descendants(body).stream()
                    .anyMatch(node -> node instanceof Label label && label.getText().startsWith("Joiner · Trap 2 · ")),
                    "the summary follows the page");
            assertEquals(List.of(BEAR_TRAP_CALL_RALLY_BOOL + "=false"), writes);
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
