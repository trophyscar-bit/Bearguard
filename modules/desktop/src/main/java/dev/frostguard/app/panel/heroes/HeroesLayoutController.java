package dev.frostguard.app.panel.heroes;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.api.squads.HeroRecord;
import dev.frostguard.api.squads.SquadSnapshot;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.engine.squads.GearRanker;
import dev.frostguard.engine.squads.GearRanker.GearPiece;
import dev.frostguard.engine.squads.GearRanker.GearState;
import dev.frostguard.engine.squads.GearRanker.Upgrade;
import dev.frostguard.engine.squads.HeroGearStore;
import dev.frostguard.engine.squads.HeroUpgradePlanner.Instructor;
import dev.frostguard.engine.squads.HeroUpgradePlanner.Inventory;
import dev.frostguard.engine.squads.SquadPlanner;
import dev.frostguard.engine.squads.SquadScanRegistry;
import dev.frostguard.engine.squads.SquadSnapshotStore;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.util.Duration;

/** Troops > Heroes: gear grades, the rolling upgrade list and its options, Drill Camp level ups, rally joiners and the roster. */
public final class HeroesLayoutController extends AbstractProfileController {

    private static final List<String> SLOTS = List.of("Goggles", "Boots", "Gauntlets", "Belt");
    private static final int ROLLING_COUNT = 10;
    private static final int MAX_HERO_LEVEL = 80;
    private static final int ROW_HEIGHT = 47;
    private static final int TABLE_CHROME = 40;
    private static final int MIN_VISIBLE_ROWS = 5;
    // JavaFX shows a dead vertical bar beside a horizontal one unless a spare row fits below the last.
    private static final int H_SCROLL_ROOM = ROW_HEIGHT + 10;
    private static final double[] ROSTER_WIDTHS = {130, 90, 110, 65, 85, 115, 115, 115, 105, 125, 115, 105, 105, 105, 105, 290, 190};
    private static final int HALT_REFRESH_SECONDS = 15;
    private static final int HERO_GEAR_TAB = 0;
    private static final String HALTED_STYLE = "-fx-text-fill: #ff6b6b; -fx-font-weight: bold;";
    private static final String NO_SCAN = "No squad scan saved yet. Press Scan now on the Squads page.";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    @FXML private TabPane tabPane;
    @FXML private TableView<List<String>> tableGrades;
    @FXML private TableView<List<String>> tableRolling;
    @FXML private TableView<List<String>> tableLevels;
    @FXML private TableView<List<String>> tableJoiners;
    @FXML private TableView<List<String>> tableHeroes;
    @FXML private Label labelStones;
    @FXML private Label labelXp;
    @FXML private Label labelJoiners;
    @FXML private Label labelRoster;
    @FXML private Label labelHalt;
    @FXML private Button buttonClearHalt;
    @FXML private CheckBox checkBoxForgeEnabled;
    @FXML private CheckBox checkBoxForgeLive;
    @FXML private CheckBox checkBoxQuickEquip;
    @FXML private CheckBox checkBoxEnhance;
    @FXML private CheckBox checkBoxEnhanceLive;
    @FXML private CheckBox checkBoxPlan;
    @FXML private CheckBox checkBoxMia;
    @FXML private CheckBox checkBoxFlint;
    @FXML private CheckBox checkBoxBahiti;
    @FXML private CheckBox checkBoxSergey;
    @FXML private CheckBox checkBoxMolly;
    @FXML private ComboBox<String> comboBoxFocus;
    @FXML private ComboBox<Integer> comboBoxMaxBuys;
    @FXML private ComboBox<Integer> comboBoxReserve;
    @FXML private ComboBox<Integer> comboBoxEnhanceStep;
    @FXML private ComboBox<Integer> comboBoxEnhanceRaises;

    private GearState gear;
    private Inventory inventory;
    private ProfileAux currentProfile;
    private final Timeline haltRefresh = new Timeline(new KeyFrame(Duration.seconds(HALT_REFRESH_SECONDS), e -> refreshHalt()));

    @FXML
    private void initialize() {
        haltRefresh.setCycleCount(Animation.INDEFINITE);
        columns(tableGrades, "Hero", "Role", "Goggles (attack)", "Boots (attack)", "Gauntlets (defense)", "Belt (defense)");
        columns(tableRolling, "#", "Hero", "Piece", "Mastery", "Stones", "Running total", "Bonus", "Status");
        columns(tableLevels, "Hero", "Level", "XP to next level", "Status");
        columns(tableJoiners, "Rank", "Hero", "First expedition skill", "Lv", "Helps", "Value %");
        columns(tableHeroes, "Hero", "Rarity", "Type", "Lv", "Stars", "Power", "Offense %", "Defense %",
                "Attack %", "Lethality %", "Defense %", "Health %", "Hero Atk", "Hero Def", "Hero HP",
                "Gear (+enh / mastery)", "Exclusive");
        // Seventeen columns cannot share the page width, so this table scrolls sideways instead of truncating.
        tableHeroes.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        for (int i = 0; i < ROSTER_WIDTHS.length; i++) tableHeroes.getColumns().get(i).setPrefWidth(ROSTER_WIDTHS[i]);

        comboBoxFocus.getItems().addAll(GearRanker.FOCUS_RALLY, GearRanker.FOCUS_BALANCED);
        comboBoxMaxBuys.getItems().addAll(1, 2, 3, 5, 10);
        comboBoxReserve.getItems().addAll(0, 10, 20, 50, 100);
        comboBoxEnhanceStep.getItems().addAll(1, 2, 5, 10, 20);
        comboBoxEnhanceRaises.getItems().addAll(1, 2, 3, 5, 10);

        registerCheckBox(checkBoxForgeEnabled, ConfigurationKeyEnum.HERO_GEAR_FORGE_ENABLED_BOOL);
        registerCheckBox(checkBoxForgeLive, ConfigurationKeyEnum.HERO_GEAR_FORGE_LIVE_BOOL);
        registerCheckBox(checkBoxQuickEquip, ConfigurationKeyEnum.HERO_QUICK_EQUIP_ENABLED_BOOL);
        registerCheckBox(checkBoxEnhance, ConfigurationKeyEnum.HERO_GEAR_ENHANCE_ENABLED_BOOL);
        registerCheckBox(checkBoxEnhanceLive, ConfigurationKeyEnum.HERO_GEAR_ENHANCE_LIVE_BOOL);
        registerCheckBox(checkBoxPlan, ConfigurationKeyEnum.HERO_UPGRADE_PLAN_ENABLED_BOOL);
        registerCheckBox(checkBoxMia, ConfigurationKeyEnum.HERO_GEAR_FORGE_MIA_BOOL);
        registerCheckBox(checkBoxFlint, ConfigurationKeyEnum.HERO_GEAR_FORGE_FLINT_BOOL);
        registerCheckBox(checkBoxBahiti, ConfigurationKeyEnum.HERO_GEAR_FORGE_BAHITI_BOOL);
        registerCheckBox(checkBoxSergey, ConfigurationKeyEnum.HERO_GEAR_FORGE_SERGEY_BOOL);
        registerCheckBox(checkBoxMolly, ConfigurationKeyEnum.HERO_GEAR_FORGE_MOLLY_BOOL);
        registerComboBox(comboBoxFocus, ConfigurationKeyEnum.HERO_GEAR_FORGE_FOCUS_STRING);
        registerComboBox(comboBoxMaxBuys, ConfigurationKeyEnum.HERO_GEAR_FORGE_MAX_BUYS_INT);
        registerComboBox(comboBoxReserve, ConfigurationKeyEnum.HERO_GEAR_FORGE_RESERVE_INT);
        registerComboBox(comboBoxEnhanceStep, ConfigurationKeyEnum.HERO_GEAR_ENHANCE_STEP_INT);
        registerComboBox(comboBoxEnhanceRaises, ConfigurationKeyEnum.HERO_GEAR_ENHANCE_MAX_RAISES_INT);
        initializeChangeEvents();

        checkBoxForgeLive.disableProperty().bind(checkBoxForgeEnabled.selectedProperty().not());
        checkBoxQuickEquip.disableProperty().bind(checkBoxForgeEnabled.selectedProperty().not());
        checkBoxEnhance.disableProperty().bind(checkBoxForgeEnabled.selectedProperty().not());
        checkBoxEnhanceLive.disableProperty().bind(checkBoxForgeEnabled.selectedProperty().not());
        comboBoxEnhanceStep.disableProperty().bind(checkBoxForgeEnabled.selectedProperty().not());
        comboBoxEnhanceRaises.disableProperty().bind(checkBoxForgeEnabled.selectedProperty().not());
        for (CheckBox hero : heroBoxes().values()) {
            hero.selectedProperty().addListener((obs, was, now) -> refreshRolling());
        }
        comboBoxFocus.valueProperty().addListener((obs, was, now) -> refreshRolling());
        comboBoxMaxBuys.valueProperty().addListener((obs, was, now) -> refreshRolling());
        comboBoxReserve.valueProperty().addListener((obs, was, now) -> refreshRolling());
        SquadScanRegistry.addListener(this::onScanEvent);
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> {
            reload();
            updateHaltRefresh();
        });
        tabPane.sceneProperty().addListener((obs, was, now) -> {
            if (now != null) reload();
            updateHaltRefresh();
        });
    }

    // The halt can be written at any time by the nightly routine while the page sits open.
    private void updateHaltRefresh() {
        if (tabPane.getScene() != null && tabPane.getSelectionModel().getSelectedIndex() == HERO_GEAR_TAB) haltRefresh.play();
        else haltRefresh.stop();
    }

    private void refreshHalt() {
        if (currentProfile != null) showHalt(currentProfile);
    }

    Timeline haltRefresh() {
        return haltRefresh;
    }

    @Override
    public void onProfileLoad(ProfileAux profile) {
        currentProfile = profile;
        if (profile != null) super.onProfileLoad(profile);
        reload();
    }

    // The nightly routine rewrites these files while the page stays loaded.
    private void reload() {
        ProfileAux profile = currentProfile;
        if (profile == null) {
            showGearProblem("Select a profile.");
            showMessage("Select a profile.");
            labelHalt.setText("Select a profile.");
            labelHalt.setStyle("");
            buttonClearHalt.setDisable(true);
            return;
        }
        showHalt(profile);
        try {
            HeroGearStore store = new HeroGearStore(WorkspacePaths.current().root());
            gear = store.loadGear(profile.getId()).orElse(null);
            inventory = store.loadInventory(profile.getId()).orElse(null);
            fillGrades();
            fillRolling();
            fillLevels();
        } catch (IOException | RuntimeException e) {
            showGearProblem("Could not read the saved hero data: " + e);
        }
        loadSnapshot(profile);
    }

    private void showHalt(ProfileAux profile) {
        try {
            Optional<HeroGearStore.GearHalt> halt = new HeroGearStore(WorkspacePaths.current().root()).loadHalt(profile.getId());
            labelHalt.setText(halt.map(h -> "HALTED since " + WHEN.format(Instant.parse(h.at())) + ". " + h.reason()
                    + ". Quick Equip, Enhance and Forge press nothing until you clear this.")
                    .orElse("Not halted. Quick Equip, Enhance and Forge run as ticked below."));
            labelHalt.setStyle(halt.isPresent() ? HALTED_STYLE : "");
            buttonClearHalt.setDisable(halt.isEmpty());
        } catch (IOException | RuntimeException e) {
            labelHalt.setText("Could not read the halt, so nothing will be pressed: " + e);
            labelHalt.setStyle(HALTED_STYLE);
            buttonClearHalt.setDisable(false);
        }
    }

    @FXML
    private void clearHalt() {
        ProfileAux profile = currentProfile;
        if (profile == null) return;
        try {
            new HeroGearStore(WorkspacePaths.current().root()).clearHalt(profile.getId());
            showHalt(profile);
        } catch (IOException | RuntimeException e) {
            labelHalt.setText("Could not clear the halt: " + e);
        }
    }

    private void refreshRolling() {
        try {
            fillRolling();
        } catch (RuntimeException e) {
            showGearProblem("Could not read the saved hero data: " + e);
        }
    }

    private void showGearProblem(String message) {
        gear = null;
        inventory = null;
        fillGrades();
        fillRolling();
        fillLevels();
        labelStones.setText(message);
        labelXp.setText(message);
    }

    private void onScanEvent(SquadScanRegistry.Event event) {
        if (event.state() != SquadScanRegistry.State.SUCCEEDED) return;
        Platform.runLater(() -> {
            ProfileAux selected = currentProfile;
            if (selected != null && selected.getId().equals(event.profileId())) show(event.snapshot());
        });
    }

    private void loadSnapshot(ProfileAux profile) {
        try {
            new SquadSnapshotStore(WorkspacePaths.current().root()).load(profile.getId())
                    .ifPresentOrElse(this::show, () -> showMessage(NO_SCAN));
        } catch (IOException | RuntimeException e) {
            showMessage("Could not load the last scan: " + e.getMessage());
        }
    }

    private void show(SquadSnapshot snapshot) {
        try {
            fillSnapshot(snapshot);
        } catch (RuntimeException e) {
            showMessage("Could not display the last scan: " + e);
        }
    }

    private void showMessage(String message) {
        labelJoiners.setText(message);
        labelRoster.setText(message);
        setRows(tableJoiners, List.of(), 0);
        setRows(tableHeroes, List.of(), H_SCROLL_ROOM);
    }

    private void fillSnapshot(SquadSnapshot snapshot) {
        List<List<String>> joinerRows = new ArrayList<>();
        int rank = 1;
        for (SquadPlanner.JoinerScore j : SquadPlanner.plan(snapshot).joiners()) {
            joinerRows.add(row(String.valueOf(rank++), j.hero(), j.skill(), text(j.level()),
                    j.effect().name().toLowerCase(Locale.ROOT) + (j.onlyFor() == null ? "" : ", " + j.onlyFor() + " only"),
                    String.format(Locale.ROOT, "%.1f", j.value())));
        }

        List<HeroRecord> heroes = new ArrayList<>(snapshot.heroes());
        heroes.sort(Comparator.comparingInt(HeroesLayoutController::rarityOrder)
                .thenComparing(h -> h.power() == null ? 0 : -h.power()));
        List<List<String>> heroRows = new ArrayList<>();
        for (HeroRecord h : heroes) {
            Map<String, Long> ex = h.exploration() == null ? Map.of() : h.exploration();
            Double atk = h.expeditionStat("Attack"), leth = h.expeditionStat("Lethality");
            Double def = h.expeditionStat("Defense"), hp = h.expeditionStat("Health");
            heroRows.add(row(h.name(), text(h.rarity()), text(h.troopClass()), text(h.level()),
                    text(h.stars()), number(h.power()), sum(atk, leth), sum(def, hp), pct(atk), pct(leth), pct(def), pct(hp),
                    number(ex.get("Hero Attack")), number(ex.get("Hero Defense")), number(ex.get("Hero Health")),
                    gear(h.gear()), h.exclusiveGear() == null ? "-"
                            : h.exclusiveGear() + (h.exclusiveGearLevel() == null ? "" : " +" + h.exclusiveGearLevel())));
        }

        String scanned = "Last scan " + when(snapshot.capturedAt()) + ".";
        labelJoiners.setText(scanned + " Highest value first.");
        labelRoster.setText(scanned + " " + heroRows.size() + " heroes, gold first, then by power.");
        setRows(tableJoiners, joinerRows, 0);
        setRows(tableHeroes, heroRows, H_SCROLL_ROOM);
    }

    // The page scrolls, not the table, and the row count depends on the scan.
    private static void setRows(TableView<List<String>> table, List<List<String>> rows, int extraHeight) {
        table.setItems(FXCollections.observableArrayList(rows));
        table.setPrefHeight(TABLE_CHROME + extraHeight + ROW_HEIGHT * Math.max(rows.size(), MIN_VISIBLE_ROWS));
    }

    private static int rarityOrder(HeroRecord hero) {
        if ("SSR".equals(hero.rarity())) return 0;
        if ("SR".equals(hero.rarity())) return 1;
        return 2;
    }

    private static List<String> row(String... cells) {
        List<String> row = new ArrayList<>(cells.length);
        for (String cell : cells) row.add(cell == null ? "-" : cell);
        return row;
    }

    private static String gear(List<HeroGear> gear) {
        if (gear == null || gear.isEmpty()) return "-";
        return gear.stream().map(g -> g.enhancement() == null ? "-"
                : "+" + g.enhancement() + (g.mastery() == null ? "" : "/" + g.mastery()))
                .collect(Collectors.joining("  "));
    }

    private static String sum(Double a, Double b) {
        return a == null || b == null ? "-" : String.format(Locale.ROOT, "%.1f", a + b);
    }

    private static String pct(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.2f", value);
    }

    private static String number(Long value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%,d", value);
    }

    private static String text(Object value) {
        return value == null ? "-" : Objects.toString(value);
    }

    private static String when(String iso) {
        try {
            return WHEN.format(Instant.parse(iso));
        } catch (RuntimeException e) {
            return iso;
        }
    }

    private Map<String, CheckBox> heroBoxes() {
        Map<String, CheckBox> boxes = new LinkedHashMap<>();
        boxes.put("Mia", checkBoxMia);
        boxes.put("Flint", checkBoxFlint);
        boxes.put("Bahiti", checkBoxBahiti);
        boxes.put("Sergey", checkBoxSergey);
        boxes.put("Molly", checkBoxMolly);
        return boxes;
    }

    private void fillGrades() {
        List<List<String>> rows = new ArrayList<>();
        if (gear != null) {
            Map<String, Map<String, GearPiece>> byHero = new LinkedHashMap<>();
            for (GearPiece piece : gear.pieces()) {
                byHero.computeIfAbsent(piece.hero(), hero -> new LinkedHashMap<>()).put(piece.slot(), piece);
            }
            byHero.forEach((hero, pieces) -> {
                List<String> row = new ArrayList<>(List.of(hero, role(pieces)));
                for (String slot : SLOTS) row.add(cell(pieces.get(slot)));
                rows.add(row);
            });
        }
        tableGrades.setItems(FXCollections.observableArrayList(rows));
    }

    private static String role(Map<String, GearPiece> pieces) {
        GearPiece goggles = pieces.get("Goggles");
        if (goggles == null) return "-";
        return goggles.onRole() ? "Attacker" : "Defender";
    }

    static String cell(GearPiece piece) {
        if (piece == null) return "-";
        if (!piece.known()) return "?" + (piece.onRole() ? " *" : "") + "  unread";
        return String.format(Locale.ROOT, "%s%s  +%d  %d%%  %.1f", GearRanker.grade(piece), piece.onRole() ? " *" : "",
                piece.enhancement(), piece.masteryPercent(), piece.bonus());
    }

    private void fillRolling() {
        List<List<String>> rows = new ArrayList<>();
        if (gear != null) {
            Set<String> included = new LinkedHashSet<>();
            heroBoxes().forEach((hero, box) -> {
                if (box.isSelected()) included.add(hero);
            });
            GearState ranked = new GearState(gear.pieces(),
                    GearRanker.weightsFor(gear, comboBoxFocus.getValue(), included));
            int reserve = comboBoxReserve.getValue() != null ? comboBoxReserve.getValue() : 0;
            int maxBuys = comboBoxMaxBuys.getValue() != null ? comboBoxMaxBuys.getValue()
                    : Integer.parseInt(ConfigurationKeyEnum.HERO_GEAR_FORGE_MAX_BUYS_INT.getDefaultValue());
            int running = 0;
            int number = 0;
            for (Upgrade upgrade : GearRanker.rolling(ranked, ROLLING_COUNT)) {
                running += upgrade.stones();
                number++;
                rows.add(List.of(String.valueOf(number), upgrade.hero(), upgrade.slot(),
                        upgrade.fromMastery() + "% to " + upgrade.toMastery() + "%",
                        String.valueOf(upgrade.stones()), String.valueOf(running),
                        String.format(Locale.ROOT, "%.2f to %.2f", upgrade.bonusBefore(), upgrade.bonusAfter()),
                        status(number, running, reserve, maxBuys)));
            }
            labelStones.setText(inventory != null
                    ? "Essence Stones on hand: " + inventory.essenceStones() + ". Read " + inventory.capturedAt() + "."
                    : "Stone balance not read yet.");
        } else {
            labelStones.setText("No gear data saved yet.");
        }
        tableRolling.setItems(FXCollections.observableArrayList(rows));
    }

    private String status(int number, int running, int reserve, int maxBuys) {
        if (inventory == null) return "Balance not read";
        int missing = running + reserve - inventory.essenceStones();
        if (missing > 0) return "Saving, " + missing + " more stones";
        return number <= maxBuys ? "Can buy now" : "Next night";
    }

    private void fillLevels() {
        List<List<String>> rows = new ArrayList<>();
        if (inventory != null) {
            for (Instructor instructor : inventory.instructors()) {
                Long next = instructor.xpToNext();
                rows.add(List.of(instructor.name(), String.valueOf(instructor.level()),
                        next != null ? String.format(Locale.ROOT, "%,d", next)
                                : instructor.level() >= MAX_HERO_LEVEL ? "Maxed" : "Not read",
                        next == null ? "-" : next <= inventory.heroXp() ? "Can level now"
                                : String.format(Locale.ROOT, "Short by %,d", next - inventory.heroXp())));
            }
            labelXp.setText(String.format(Locale.ROOT, "Hero XP on hand: %,d. Unopened Backpack bottles are not counted.",
                    inventory.heroXp()));
        } else {
            labelXp.setText("No hero XP data saved yet.");
        }
        tableLevels.setItems(FXCollections.observableArrayList(rows));
    }

    private static void columns(TableView<List<String>> table, String... headers) {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        for (int i = 0; i < headers.length; i++) {
            int index = i;
            TableColumn<List<String>, String> column = new TableColumn<>(headers[i]);
            column.setSortable(false);
            column.setCellValueFactory(row -> new ReadOnlyStringWrapper(row.getValue().get(index)));
            table.getColumns().add(column);
        }
    }
}
