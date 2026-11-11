package dev.frostguard.app.panel.heroes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.app.shared.JavaFxToolkit;
import dev.frostguard.engine.squads.HeroGearStore;
import javafx.animation.Animation;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Orientation;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;

/** The Heroes page must fill from the saved files, and its options must change the list. */
class HeroesLayoutUxTest {

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
    void fillsGradesRollingListAndLevels() throws Exception {
        View view = onFx(this::load);

        assertEquals(5, view.table("tableGrades").getItems().size());
        assertEquals(10, view.table("tableRolling").getItems().size());
        assertEquals(5, view.table("tableLevels").getItems().size());
        List<String> top = view.table("tableRolling").getItems().getFirst();
        assertEquals("Bahiti", top.get(1));
        assertEquals("Boots", top.get(2));
        assertTrue(top.get(7).startsWith("Saving"), top.toString());

        List<List<String>> joiners = view.table("tableJoiners").getItems();
        assertFalse(joiners.isEmpty(), "joiners ranked");
        assertEquals("1", joiners.getFirst().get(0));
        assertEquals(6, joiners.getFirst().size());
        for (int i = 1; i < joiners.size(); i++) {
            assertTrue(Double.parseDouble(joiners.get(i - 1).get(5)) >= Double.parseDouble(joiners.get(i).get(5)),
                    joiners.toString());
        }
        assertTrue(view.label("labelJoiners").getText().startsWith("Last scan"), view.label("labelJoiners").getText());

        List<List<String>> roster = view.table("tableHeroes").getItems();
        assertEquals(21, roster.size());
        assertEquals(17, roster.getFirst().size());
        assertEquals("SSR", roster.getFirst().get(1));
        assertTrue(view.label("labelRoster").getText().contains("21 heroes"), view.label("labelRoster").getText());

        assertEquals(List.of("Hero Gear", "Hero Level Ups", "Rally Joiners", "Roster"), view.tabTitles());

        String renderDir = System.getProperty("heroes.render.dir");
        if (renderDir != null) onFx(() -> render(view, Path.of(renderDir)));
    }

    @Test
    void newTablesShowEveryRowWithoutTheirOwnVerticalScroll() throws Exception {
        View view = onFx(this::load);

        onFx(() -> {
            Scene scene = new Scene(view.root(), 1100, 1420);
            scene.getStylesheets().add(Objects.requireNonNull(
                    HeroesLayoutController.class.getResource("/styles/style.css")).toExternalForm());
            TabPane tabs = (TabPane) view.root().lookup(".tab-pane");
            for (int tab = 2; tab <= 3; tab++) {
                tabs.getSelectionModel().select(tab);
                view.root().applyCss();
                view.root().layout();
                scene.snapshot(null);
            }
            for (String id : List.of("tableJoiners", "tableHeroes")) {
                assertTrue(view.table(id).lookupAll(".scroll-bar").stream().map(ScrollBar.class::cast)
                        .filter(bar -> bar.getOrientation() == Orientation.VERTICAL).noneMatch(ScrollBar::isVisible), id);
            }
            return null;
        });
    }

    @Test
    void saysSoWhenThereIsNoScanYet() throws Exception {
        View view = onFx(() -> load(false));

        assertTrue(view.table("tableJoiners").getItems().isEmpty());
        assertTrue(view.table("tableHeroes").getItems().isEmpty());
        assertTrue(view.label("labelJoiners").getText().startsWith("No squad scan saved yet"), view.label("labelJoiners").getText());
        assertTrue(view.label("labelRoster").getText().startsWith("No squad scan saved yet"), view.label("labelRoster").getText());
    }

    @Test
    void onlyTheNightsBuysSayCanBuyNow() throws Exception {
        seed(true);
        editInventory("\"essenceStones\": 3", "\"essenceStones\": 25");
        View view = onFx(this::open);

        onFx(() -> {
            view.maxBuys().setValue(1);
            return null;
        });
        assertEquals(List.of("Can buy now", "Next night", "Saving, 5 more stones"), view.statuses(3));

        onFx(() -> {
            view.maxBuys().setValue(2);
            return null;
        });
        assertEquals(List.of("Can buy now", "Can buy now", "Saving, 5 more stones"), view.statuses(3));
    }

    @Test
    void aMissingInventoryIsNotZeroStones() throws Exception {
        seed(true);
        Files.delete(squads().resolve("hero-inventory-1.json"));

        View view = onFx(this::open);

        assertEquals(10, view.table("tableRolling").getItems().size());
        assertTrue(view.table("tableRolling").getItems().stream().allMatch(r -> r.get(7).equals("Balance not read")));
        assertEquals("Stone balance not read yet.", view.label("labelStones").getText());
    }

    @Test
    void aFileMissingHeroWeightsShowsAMessageAndKeepsTheOtherTabs() throws Exception {
        seed(true);
        Files.writeString(squads().resolve("hero-gear-1.json"),
                "{\"pieces\":[{\"hero\":\"Mia\",\"slot\":\"Goggles\",\"enhancement\":100,\"masteryPercent\":60,\"onRole\":true}]}");

        View view = onFx(this::open);

        assertTrue(view.label("labelStones").getText().startsWith("Could not read the saved hero data"),
                view.label("labelStones").getText());
        assertTrue(view.table("tableGrades").getItems().isEmpty());
        assertTrue(view.table("tableRolling").getItems().isEmpty());
        assertEquals(21, view.table("tableHeroes").getItems().size());
    }

    @Test
    void aMalformedFileShowsAMessage() throws Exception {
        seed(true);
        Files.writeString(squads().resolve("hero-inventory-1.json"), "{not json");

        View view = onFx(this::open);

        assertTrue(view.label("labelXp").getText().startsWith("Could not read the saved hero data"),
                view.label("labelXp").getText());
    }

    @Test
    void aNullProfileClearsThePage() throws Exception {
        View view = onFx(this::load);

        onFx(() -> {
            view.controller().onProfileLoad(null);
            return null;
        });

        assertTrue(view.table("tableRolling").getItems().isEmpty());
        assertEquals("Select a profile.", view.label("labelRoster").getText());
    }

    @Test
    void anUnreadCostIsOnlyMaxedAtTheLevelCap() throws Exception {
        seed(true);
        editInventory("\"level\": 79, \"xpToNext\": 2400000", "\"level\": 79, \"xpToNext\": null");

        View view = onFx(this::open);

        Map<String, String> costs = view.table("tableLevels").getItems().stream()
                .collect(Collectors.toMap(r -> r.get(0), r -> r.get(2)));
        assertEquals("Not read", costs.get("Mia"));
        assertEquals("Maxed", costs.get("Molly"));
    }

    @Test
    void reloadsWhenATabIsShownAndWhenThePageIsShown() throws Exception {
        View view = onFx(this::load);

        editInventory("\"essenceStones\": 3", "\"essenceStones\": 25");
        onFx(() -> {
            ((TabPane) view.root().lookup(".tab-pane")).getSelectionModel().select(1);
            return null;
        });
        assertTrue(view.label("labelStones").getText().contains("on hand: 25"), view.label("labelStones").getText());

        editInventory("\"essenceStones\": 25", "\"essenceStones\": 40");
        onFx(() -> new Scene(view.root(), 1100, 1420));
        assertTrue(view.label("labelStones").getText().contains("on hand: 40"), view.label("labelStones").getText());
    }

    @Test
    void untickingAHeroTakesThemOffTheList() throws Exception {
        View view = onFx(this::load);

        onFx(() -> {
            ((CheckBox) view.namespace().get("checkBoxBahiti")).setSelected(false);
            return null;
        });

        assertTrue(view.table("tableRolling").getItems().stream().noneMatch(r -> r.get(1).equals("Bahiti")));
    }

    @Test
    void quickEquipIsOnTheGearTabAndStartsUnticked() throws Exception {
        View view = onFx(this::load);

        CheckBox box = (CheckBox) view.namespace().get("checkBoxQuickEquip");
        assertEquals("Quick Equip every hero (all 21), so better spare gear gets worn instead of eaten", box.getText());
        assertFalse(box.isSelected());
    }

    @Test
    @SuppressWarnings("unchecked")
    void enhanceOptionsLoadTheirDefaultsAndStartUnticked() throws Exception {
        View view = onFx(this::load);

        CheckBox enhance = (CheckBox) view.namespace().get("checkBoxEnhance");
        CheckBox live = (CheckBox) view.namespace().get("checkBoxEnhanceLive");
        assertEquals("Level up the lowest gear on the 5 heroes", enhance.getText());
        assertEquals("Actually use XP items (off: only log what it would do)", live.getText());
        assertFalse(enhance.isSelected());
        assertFalse(live.isSelected());
        assertEquals(5, ((ComboBox<Integer>) view.namespace().get("comboBoxEnhanceLevels")).getValue());
        assertFalse(view.namespace().containsKey("comboBoxEnhanceStep"), "the step is the planner's, not the page's");
        assertFalse(view.namespace().containsKey("comboBoxEnhanceRaises"));
    }

    @Test
    void theMasterSaysWhenItRunsAndTheScanLineSaysHowOften() throws Exception {
        View view = onFx(this::load);

        String master = ((CheckBox) view.namespace().get("checkBoxForgeEnabled")).getText();
        assertTrue(master.matches("Run the hero gear check every night at \\d{1,2}:\\d{2} (AM|PM)"), master);
        assertEquals(HeroesLayoutController.localTime("02:30"), master.substring(master.lastIndexOf(" at ") + 4));
        assertTrue(view.label("labelScanInfo").getText().contains("every 7 days"), view.label("labelScanInfo").getText());
        assertEquals("nonsense UTC", HeroesLayoutController.localTime("nonsense"));
    }

    @Test
    void theHeroesSitAtTheTopInTheirOrder() throws Exception {
        View view = onFx(this::load);

        javafx.scene.layout.HBox row = (javafx.scene.layout.HBox) ((CheckBox) view.namespace().get("checkBoxMia")).getParent();
        assertEquals(List.of("Mia", "Flint", "Molly", "Bahiti", "Sergey"), row.getChildren().stream()
                .filter(CheckBox.class::isInstance).map(n -> ((CheckBox) n).getText()).toList());
        assertTrue(row.getChildren().stream().anyMatch(n -> n instanceof Label l && l.getText().equals("(your top 5)")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void forgeOrderShowsPlainWordsOverTheStoredValues() throws Exception {
        View view = onFx(this::load);
        ComboBox<String> order = (ComboBox<String>) view.namespace().get("comboBoxFocus");

        assertEquals(List.of("Rally squads", "Balanced"), order.getItems());
        assertEquals("Rally squads", order.getValue(), "an existing setting still loads");
        assertEquals("Biggest rally impact first (Bahiti leads, he carries most rally troops)",
                order.getConverter().toString("Rally squads"));
        assertTrue(order.getConverter().toString("Balanced").startsWith("Even across"));
        assertEquals("Balanced", order.getConverter().fromString(order.getConverter().toString("Balanced")));
        assertEquals("Order follows Forge order above. Bahiti tops it because he carries most of your rally troops.",
                view.label("labelRollingOrder").getText());

        onFx(() -> {
            order.setValue("Balanced");
            return null;
        });

        assertEquals("Order follows Forge order above.", view.label("labelRollingOrder").getText());
    }

    @Test
    void anUnreadPieceShowsAsUnreadInTheGearTable() throws Exception {
        seed(false);
        assertEquals("? *  unread", HeroesLayoutController.cell(
                new dev.frostguard.engine.squads.GearRanker.GearPiece("Mia", "Boots", null, 40, true)));
    }

    @Test
    void everythingBelowTheMasterIsGreyedWhileItIsOff() throws Exception {
        View view = onFx(this::load);

        onFx(() -> view.tick(false, false, false, false, false));
        assertEquals(List.of(), view.enabled(BELOW_MASTER));
        assertFalse(view.disabled("checkBoxForgeEnabled"));

        onFx(() -> view.tick(true, false, false, false, false));
        assertEquals(List.of("checkBoxMia", "checkBoxFlint", "checkBoxMolly", "checkBoxBahiti", "checkBoxSergey",
                "checkBoxQuickEquip", "checkBoxEnhance", "checkBoxForgeLive", "comboBoxFocus"), view.enabled(BELOW_MASTER));
    }

    @Test
    void spendingOnlySettingsAreGreyedUntilTheirSpendBoxIsTicked() throws Exception {
        View view = onFx(this::load);

        onFx(() -> view.tick(true, true, true, false, false));
        assertTrue(view.disabled("comboBoxMaxBuys"));
        assertTrue(view.disabled("comboBoxReserve"));
        assertFalse(view.disabled("checkBoxEnhanceLive"));
        assertTrue(view.disabled("comboBoxEnhanceLevels"), "levels matter only when XP items are really used");

        onFx(() -> view.tick(true, true, false, true, false));
        assertTrue(view.disabled("checkBoxEnhanceLive"), "nothing to use XP items on with Enhance off");
        assertTrue(view.disabled("comboBoxEnhanceLevels"));

        onFx(() -> view.tick(true, true, true, true, true));
        assertEquals(BELOW_MASTER, view.enabled(BELOW_MASTER));
    }

    @Test
    void rendersTheFourOptionStates() throws Exception {
        renderState("1-all-off.png", false, false, false, false, false, false);
        renderState("2-master-on-spending-off.png", false, true, true, true, false, false);
        renderState("3-everything-on.png", false, true, true, true, true, true);
        View halted = renderState("4-halted.png", true, true, true, true, false, false);

        assertTrue(halted.label("labelHalt").getText().contains("HALTED since"), halted.label("labelHalt").getText());
        assertFalse(halted.disabled("buttonClearHalt"));
        assertTrue(halted.disabled("comboBoxMaxBuys"));
    }

    private View renderState(String name, boolean halt, boolean nightly, boolean quickEquip, boolean enhance,
                             boolean enhanceLive, boolean forgeLive) throws Exception {
        seed(true);
        HeroGearStore store = new HeroGearStore(workspace);
        if (halt) {
            store.saveHalt(1, new HeroGearStore.GearHalt("Enhance: Mia Boots showed no new level",
                    "2026-11-10T07:45:00Z", HeroGearStore.GearHalt.ENHANCE));
        } else {
            store.clearHalt(1);
        }
        View view = onFx(this::open);
        onFx(() -> view.tick(nightly, quickEquip, enhance, enhanceLive, forgeLive));
        String renderDir = System.getProperty("heroes.render.dir");
        onFx(() -> {
            Scene scene = new Scene(view.root(), 1100, 2000);
            scene.getStylesheets().add(Objects.requireNonNull(
                    HeroesLayoutController.class.getResource("/styles/style.css")).toExternalForm());
            view.root().applyCss();
            view.root().layout();
            javafx.scene.image.WritableImage image = scene.snapshot(null);
            if (renderDir != null) {
                Path dir = Files.createDirectories(Path.of(renderDir));
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", dir.resolve(name).toFile());
            }
            return null;
        });
        return view;
    }

    private static final List<String> BELOW_MASTER = List.of("checkBoxMia", "checkBoxFlint", "checkBoxMolly", "checkBoxBahiti",
            "checkBoxSergey", "checkBoxQuickEquip", "checkBoxEnhance", "checkBoxEnhanceLive", "comboBoxEnhanceLevels",
            "checkBoxForgeLive", "comboBoxMaxBuys", "comboBoxReserve", "comboBoxFocus");

    @Test
    void withNoHaltTheStatusSaysSoAndClearIsOff() throws Exception {
        View view = onFx(this::load);

        assertTrue(view.label("labelHalt").getText().startsWith("Not halted"), view.label("labelHalt").getText());
        assertTrue(((Button) view.namespace().get("buttonClearHalt")).isDisabled());
    }

    @Test
    void aSavedHaltIsShownAndClearLiftsIt() throws Exception {
        seed(true);
        HeroGearStore store = new HeroGearStore(workspace);
        store.saveHalt(1, new HeroGearStore.GearHalt("Quick Equip: Mia slot 1 went down", "2026-11-10T07:45:00Z",
                HeroGearStore.GearHalt.QUICK_EQUIP));
        store.saveLeadBaseline(1, Map.of("Mia", List.of()));

        View view = onFx(this::open);
        Button clear = (Button) view.namespace().get("buttonClearHalt");

        assertTrue(view.label("labelHalt").getText().contains("HALTED since"), view.label("labelHalt").getText());
        assertTrue(view.label("labelHalt").getText().contains("Mia slot 1 went down"), view.label("labelHalt").getText());
        assertFalse(clear.isDisabled());

        String renderDir = System.getProperty("heroes.render.dir");
        if (renderDir != null) onFx(() -> render(view, Path.of(renderDir, "halted")));

        onFx(() -> {
            clear.fire();
            return null;
        });

        assertTrue(store.loadHalt(1).isEmpty());
        assertTrue(store.loadLeadBaseline(1).isEmpty());
        assertTrue(view.label("labelHalt").getText().startsWith("Not halted"), view.label("labelHalt").getText());
        assertTrue(clear.isDisabled());
    }

    @Test
    void theHaltRefreshesWhileTheHeroGearTabIsShowingAndStopsOtherwise() throws Exception {
        seed(true);
        HeroGearStore store = new HeroGearStore(workspace);
        View view = onFx(this::open);
        Timeline refresh = view.controller().haltRefresh();
        assertEquals(Animation.Status.STOPPED, refresh.getStatus(), "not on screen yet");

        Scene scene = onFx(() -> new Scene(view.root(), 1100, 800));
        assertEquals(Animation.Status.RUNNING, onFx(refresh::getStatus));
        assertTrue(view.label("labelHalt").getText().startsWith("Not halted"), view.label("labelHalt").getText());

        store.saveHalt(1, new HeroGearStore.GearHalt("Enhance: no read", "2026-11-10T07:45:00Z", HeroGearStore.GearHalt.ENHANCE));
        onFx(() -> {
            refresh.getKeyFrames().getFirst().getOnFinished().handle(null);
            return null;
        });
        assertTrue(view.label("labelHalt").getText().contains("HALTED since"), view.label("labelHalt").getText());

        TabPane tabs = (TabPane) view.root().lookup(".tab-pane");
        onFx(() -> {
            tabs.getSelectionModel().select(1);
            return null;
        });
        assertEquals(Animation.Status.STOPPED, onFx(refresh::getStatus));
        onFx(() -> {
            tabs.getSelectionModel().select(0);
            return null;
        });
        assertEquals(Animation.Status.RUNNING, onFx(refresh::getStatus));
        onFx(() -> {
            scene.setRoot(new javafx.scene.layout.Pane());
            return null;
        });
        assertEquals(Animation.Status.STOPPED, onFx(refresh::getStatus));
    }

    @Test
    void anUnreadableHaltStillOffersClear() throws Exception {
        seed(true);
        Files.writeString(squads().resolve("hero-gear-halt-1.json"), "{not json");

        View view = onFx(this::open);

        assertTrue(view.label("labelHalt").getText().startsWith("Could not read the halt"), view.label("labelHalt").getText());
        assertFalse(((Button) view.namespace().get("buttonClearHalt")).isDisabled());
    }

    private record View(Parent root, Map<String, Object> namespace, HeroesLayoutController controller) {
        @SuppressWarnings("unchecked")
        TableView<List<String>> table(String id) {
            return (TableView<List<String>>) namespace.get(id);
        }

        Label label(String id) {
            return (Label) namespace.get(id);
        }

        @SuppressWarnings("unchecked")
        ComboBox<Integer> maxBuys() {
            return (ComboBox<Integer>) namespace.get("comboBoxMaxBuys");
        }

        List<String> statuses(int count) {
            return table("tableRolling").getItems().stream().limit(count).map(r -> r.get(7)).toList();
        }

        List<String> tabTitles() {
            return ((TabPane) root.lookup(".tab-pane")).getTabs().stream().map(Tab::getText).toList();
        }

        boolean disabled(String id) {
            return ((javafx.scene.Node) namespace.get(id)).isDisabled();
        }

        List<String> enabled(List<String> ids) {
            return ids.stream().filter(id -> !disabled(id)).toList();
        }

        Void tick(boolean nightly, boolean quickEquip, boolean enhance, boolean enhanceLive, boolean forgeLive) {
            ((CheckBox) namespace.get("checkBoxForgeEnabled")).setSelected(nightly);
            ((CheckBox) namespace.get("checkBoxQuickEquip")).setSelected(quickEquip);
            ((CheckBox) namespace.get("checkBoxEnhance")).setSelected(enhance);
            ((CheckBox) namespace.get("checkBoxEnhanceLive")).setSelected(enhanceLive);
            ((CheckBox) namespace.get("checkBoxForgeLive")).setSelected(forgeLive);
            return null;
        }
    }

    private View load() throws Exception {
        return load(true);
    }

    private View load(boolean withScan) throws Exception {
        seed(withScan);
        return open();
    }

    private Path squads() {
        return workspace.resolve("squads");
    }

    private void editInventory(String from, String to) throws Exception {
        Path file = squads().resolve("hero-inventory-1.json");
        String json = Files.readString(file);
        assertTrue(json.contains(from), from);
        Files.writeString(file, json.replace(from, to));
    }

    private void seed(boolean withScan) throws Exception {
        Path dir = Files.createDirectories(squads());
        for (String name : List.of("hero-gear", "hero-inventory")) {
            try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/squads/" + name + ".json"))) {
                Files.copy(in, dir.resolve(name + "-1.json"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (withScan) {
            try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/squads/live-snapshot.json"))) {
                Files.copy(in, dir.resolve("profile-1.json"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        previousWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, workspace.toString());
    }

    private View open() throws Exception {
        FXMLLoader loader = new FXMLLoader(HeroesLayoutController.class.getResource("/layout/HeroesLayout.fxml"));
        HeroesLayoutController controller = new HeroesLayoutController();
        loader.setController(controller);
        Parent root = loader.load();
        controller.onProfileLoad(new ProfileAux(1L, "Default", "0", true, 50L, "", 0L));
        return new View(root, loader.getNamespace(), controller);
    }

    private static Void render(View view, Path dir) throws Exception {
        Files.createDirectories(dir);
        Scene scene = new Scene(view.root(), 1100, 2000);
        scene.getStylesheets().add(Objects.requireNonNull(
                HeroesLayoutController.class.getResource("/styles/style.css")).toExternalForm());
        TabPane tabs = (TabPane) view.root().lookup(".tab-pane");
        for (int i = 0; i < tabs.getTabs().size(); i++) {
            tabs.getSelectionModel().select(i);
            view.root().applyCss();
            view.root().layout();
            File out = dir.resolve("heroes-tab-" + (i + 1) + ".png").toFile();
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
