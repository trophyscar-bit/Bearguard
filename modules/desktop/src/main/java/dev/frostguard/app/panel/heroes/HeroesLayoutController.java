package dev.frostguard.app.panel.heroes;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.function.Predicate;

import atlantafx.base.controls.ToggleSwitch;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.squads.HeroRecord;
import dev.frostguard.api.squads.SquadSnapshot;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.squads.GearHeroes;
import dev.frostguard.engine.squads.GearManualRun;
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
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.TableColumnHeader;
import javafx.geometry.Insets;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.StringConverter;

/** Troops > Heroes: the nightly gear routine and your gear, Drill Camp level ups, rally joiners and the roster. */
public final class HeroesLayoutController extends AbstractProfileController {

    private static final List<String> SLOTS = List.of("Goggles", "Boots", "Gauntlets", "Belt");
    private static final int ROLLING_COUNT = 10;
    private static final int FEW_UPGRADES = 3;
    private static final int MAX_HERO_LEVEL = 80;
    private static final int ROW_HEIGHT = 40;
    private static final int CONDENSED_ROW_HEIGHT = 32;
    private static final int CONDENSED_FROM_ROWS = 15;
    private static final int HEADER_HEIGHT = 32;
    private static final int HERO_COLUMNS = 3;
    private static final int HALT_REFRESH_SECONDS = 15;
    private static final int HERO_GEAR_TAB = 0;
    private static final double NAME_PADDING = 24;
    private static final double NAME_CAP = 240;
    private static final Font TABLE_FONT = Font.font("Segoe UI", 13);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    static final String ENHANCE_OFF = "Off";
    static final String ENHANCE_PRACTICE = "Practice";
    static final String ENHANCE_REAL = "For real";
    static final String FORGE_PRACTICE = "Practice";
    static final String FORGE_REAL = "For real";

    private static final String NEVER_PRESSED = "Never pressed: Reforge, Ascend, Redeem, or anything that costs gems.";
    private static final Map<String, String> FORGE_ORDER = Map.of(
            GearRanker.FOCUS_RALLY, "Rally leaders first",
            GearRanker.FOCUS_BALANCED, "All leaders evenly");

    private enum Kind { TEXT, NUMBER, PILL }

    @FXML private TabPane tabPane;
    @FXML private TableView<List<String>> tableGear;
    @FXML private TableView<List<String>> tableUpgrades;
    @FXML private TableView<List<String>> tableLevels;
    @FXML private TableView<List<String>> tableJoiners;
    @FXML private TableView<List<String>> tableHeroes;
    @FXML private HBox boxHalt;
    @FXML private VBox boxNightly;
    @FXML private VBox boxHeroesEmpty;
    @FXML private VBox boxGearEmpty;
    @FXML private VBox boxUpgradesEmpty;
    @FXML private VBox boxLevelsEmpty;
    @FXML private VBox boxJoinersEmpty;
    @FXML private VBox boxRosterEmpty;
    @FXML private HBox rowEnhanceLevels;
    @FXML private HBox rowMaxBuys;
    @FXML private HBox rowReserve;
    @FXML private GridPane gridHeroes;
    @FXML private Label labelStatusLine;
    @FXML private Label labelHalt;
    @FXML private Label labelRunReason;
    @FXML private Label labelLastLine;
    @FXML private Label labelLevelsAside;
    @FXML private Label labelRosterAside;
    @FXML private Label labelTonightLine;
    @FXML private Label labelGearHelp;
    @FXML private Button buttonRunNow;
    @FXML private Hyperlink linkGear;
    @FXML private Label labelHeroesInfo;
    @FXML private Label labelGearInfo;
    @FXML private Label labelStones;
    @FXML private Label labelUpgradesMore;
    @FXML private Label labelUpgradesEmptyTitle;
    @FXML private Label labelUpgradesEmptyText;
    @FXML private Label labelLevelsEmptyTitle;
    @FXML private Label labelLevelsEmptyText;
    @FXML private Hyperlink linkUpgrades;
    @FXML private Label labelEnhanceReason;
    @FXML private Label labelForgeReason;
    @FXML private Label labelReserveReason;
    @FXML private Label labelXp;
    @FXML private Label labelJoiners;
    @FXML private Label labelRoster;
    @FXML private Button buttonClearHalt;
    @FXML private ToggleSwitch switchNightly;
    @FXML private ToggleSwitch switchQuickEquip;
    @FXML private ToggleSwitch switchPlan;
    @FXML private ComboBox<String> comboBoxRunAt;
    @FXML private ComboBox<Integer> comboBoxScanDays;
    @FXML private Segmented segmentEnhance;
    @FXML private Segmented segmentForge;
    @FXML private ComboBox<String> comboBoxFocus;
    @FXML private ComboBox<Integer> comboBoxMaxBuys;
    @FXML private ComboBox<Integer> comboBoxReserve;
    @FXML private ComboBox<Integer> comboBoxEnhanceLevels;

    private GearState gear;
    private Inventory inventory;
    private HeroGearStore.NightLedger ledger;
    private ProfileAux currentProfile;
    private Set<String> skipped = new LinkedHashSet<>();
    private boolean revertingForge;
    private boolean showAllUpgrades;
    private boolean showAllPieces;
    private int canBuyTonight;
    private int missingForNext;
    private RunHook runHook = new QueueRunHook();
    private Supplier<Instant> clock = Instant::now;
    private ZoneId zone = ZoneId.systemDefault();
    private int nextForgeCost;
    private final BooleanProperty paused = new SimpleBooleanProperty();
    @FXML private Label labelNightlyCaption;
    private String pausedAt;
    private Predicate<String> spendConfirmer = HeroesLayoutController::askToSpend;
    private final Map<ToggleSwitch, ConfigurationKeyEnum> switches = new LinkedHashMap<>();
    private final Timeline haltRefresh = new Timeline(new KeyFrame(javafx.util.Duration.seconds(15), e -> refreshHalt()));

    @FXML
    private void initialize() {
        haltRefresh.setCycleCount(Animation.INDEFINITE);
        column(tableGear, "Hero", 110, Kind.TEXT);
        column(tableGear, "Piece", 110, Kind.TEXT);
        column(tableGear, "Used for", 110, Kind.TEXT);
        column(tableGear, "Grade", 100, Kind.PILL);
        column(tableGear, "Level", 90, Kind.NUMBER);
        column(tableGear, "Mastery", 100, Kind.NUMBER);
        column(tableUpgrades, "Hero", 110, Kind.TEXT);
        column(tableUpgrades, "Piece", 110, Kind.TEXT);
        column(tableUpgrades, "From", 80, Kind.NUMBER);
        column(tableUpgrades, "To", 80, Kind.NUMBER);
        column(tableUpgrades, "Cost", 110, Kind.NUMBER);
        column(tableUpgrades, "Running total", 140, Kind.NUMBER);
        column(tableUpgrades, "Can I afford it?", 190, Kind.PILL);
        column(tableLevels, "Hero", 110, Kind.TEXT);
        column(tableLevels, "Level", 90, Kind.NUMBER);
        column(tableLevels, "XP to next level", 170, Kind.NUMBER);
        column(tableLevels, "XP short", 140, Kind.NUMBER);
        column(tableJoiners, "Rank", 70, Kind.NUMBER);
        column(tableJoiners, "Hero", 110, Kind.TEXT);
        column(tableJoiners, "Skill", 180, Kind.TEXT);
        column(tableJoiners, "Skill level", 100, Kind.NUMBER);
        column(tableJoiners, "Helps", 110, Kind.TEXT);
        column(tableJoiners, "Only for", 120, Kind.TEXT);
        column(tableJoiners, "Rally score", 110, Kind.NUMBER);
        column(tableHeroes, "Hero", 110, Kind.TEXT);
        column(tableHeroes, "Rarity", 90, Kind.TEXT);
        column(tableHeroes, "Troop type", 120, Kind.TEXT);
        column(tableHeroes, "Level", 80, Kind.NUMBER);
        column(tableHeroes, "Stars", 80, Kind.NUMBER);
        column(tableHeroes, "Power", 120, Kind.NUMBER);

        for (int hour = 0; hour < 24; hour++) {
            for (int minute = 0; minute < 60; minute += 15) comboBoxRunAt.getItems().add(String.format(Locale.ROOT, "%02d:%02d", hour, minute));
        }
        comboBoxScanDays.getItems().addAll(1, 2, 3, 7);
        comboBoxScanDays.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer days) {
                return scanText(days);
            }

            @Override
            public Integer fromString(String text) {
                return comboBoxScanDays.getItems().stream().filter(d -> scanText(d).equals(text)).findFirst().orElse(null);
            }
        });
        segmentEnhance.setItems(List.of(ENHANCE_OFF, ENHANCE_PRACTICE, ENHANCE_REAL), ENHANCE_REAL);
        segmentForge.setItems(List.of(FORGE_PRACTICE, FORGE_REAL), FORGE_REAL);
        segmentEnhance.hint(NEVER_PRESSED);
        segmentForge.hint(NEVER_PRESSED);
        comboBoxFocus.getItems().addAll(GearRanker.FOCUS_RALLY, GearRanker.FOCUS_BALANCED);
        // The stored values stay "Rally squads" and "Balanced"; only the words shown change.
        comboBoxFocus.setConverter(new StringConverter<>() {
            @Override
            public String toString(String value) {
                return value == null ? "" : FORGE_ORDER.getOrDefault(value, value);
            }

            @Override
            public String fromString(String text) {
                return FORGE_ORDER.entrySet().stream().filter(e -> e.getValue().equals(text))
                        .map(Map.Entry::getKey).findFirst().orElse(text);
            }
        });
        comboBoxMaxBuys.getItems().addAll(1, 2, 3, 5, 10);
        comboBoxReserve.getItems().addAll(0, 10, 20, 50, 100);
        comboBoxEnhanceLevels.getItems().addAll(1, 2, 3, 5, 10, 15, 20, 25);

        registerComboBox(comboBoxScanDays, ConfigurationKeyEnum.HERO_GEAR_FORGE_SCAN_DAYS_INT);
        registerComboBox(comboBoxFocus, ConfigurationKeyEnum.HERO_GEAR_FORGE_FOCUS_STRING);
        registerComboBox(comboBoxMaxBuys, ConfigurationKeyEnum.HERO_GEAR_FORGE_MAX_BUYS_INT);
        registerComboBox(comboBoxReserve, ConfigurationKeyEnum.HERO_GEAR_FORGE_RESERVE_INT);
        registerComboBox(comboBoxEnhanceLevels, ConfigurationKeyEnum.HERO_GEAR_ENHANCE_MAX_LEVELS_INT);
        initializeChangeEvents();
        bridge(switchNightly, ConfigurationKeyEnum.HERO_GEAR_FORGE_ENABLED_BOOL);
        bridge(switchQuickEquip, ConfigurationKeyEnum.HERO_QUICK_EQUIP_ENABLED_BOOL);
        bridge(switchPlan, ConfigurationKeyEnum.HERO_UPGRADE_PLAN_ENABLED_BOOL);

        // Each spending feature is one plain choice that writes its two settings.
        segmentEnhance.valueProperty().addListener((obs, was, mode) -> {
            if (mode == null) return;
            showStatusLine();
            publishWhenReady(ConfigurationKeyEnum.HERO_GEAR_ENHANCE_ENABLED_BOOL, !ENHANCE_OFF.equals(mode));
            publishWhenReady(ConfigurationKeyEnum.HERO_GEAR_ENHANCE_LIVE_BOOL, ENHANCE_REAL.equals(mode));
        });
        segmentForge.valueProperty().addListener((obs, was, mode) -> {
            if (mode == null || revertingForge) return;
            if (FORGE_REAL.equals(mode) && !isLoadingProfile && !spendConfirmer.test(spendQuestion())) {
                revertingForge = true;
                segmentForge.setValue(was);
                revertingForge = false;
                return;
            }
            showStatusLine();
            publishWhenReady(ConfigurationKeyEnum.HERO_GEAR_FORGE_LIVE_BOOL, FORGE_REAL.equals(mode));
            refreshRolling();
        });

        boxNightly.visibleProperty().bind(switchNightly.selectedProperty());
        boxNightly.managedProperty().bind(switchNightly.selectedProperty());
        // Only the control greys out; its label and the reason beside it stay at full strength.
        comboBoxEnhanceLevels.disableProperty().bind(Bindings.notEqual(segmentEnhance.valueProperty(), ENHANCE_REAL).or(paused));
        comboBoxMaxBuys.disableProperty().bind(Bindings.notEqual(segmentForge.valueProperty(), FORGE_REAL).or(paused));
        comboBoxReserve.disableProperty().bind(Bindings.notEqual(segmentForge.valueProperty(), FORGE_REAL).or(paused));
        // While a halt stands nothing below the master can be changed, and each reason says so.
        for (Node locked : List.of(comboBoxRunAt, comboBoxScanDays, comboBoxFocus, switchQuickEquip, segmentEnhance, segmentForge, gridHeroes)) {
            locked.disableProperty().bind(paused);
        }
        labelEnhanceReason.textProperty().bind(Bindings.when(paused).then("Locked while paused.")
                .otherwise("Used when Raise gear levels is For real."));
        labelForgeReason.textProperty().bind(Bindings.when(paused).then("Locked while paused.")
                .otherwise("Used when Forge mastery is For real."));
        labelReserveReason.textProperty().bind(Bindings.when(paused).then("Locked while paused.")
                .otherwise("Used when Forge mastery is For real."));
        reasonWhenDisabled(labelEnhanceReason, comboBoxEnhanceLevels);
        reasonWhenDisabled(labelForgeReason, comboBoxMaxBuys);
        reasonWhenDisabled(labelReserveReason, comboBoxReserve);
        for (ToggleSwitch toggle : switches.keySet()) {
            toggle.textProperty().bind(Bindings.when(toggle.selectedProperty()).then("On").otherwise("Off"));
        }
        // While a halt stands the master reads Paused and cannot be flipped, so the page never says On and Paused at once.
        switchNightly.textProperty().unbind();
        paused.addListener((obs, was, now) -> {
            if (now) switchNightly.getStyleClass().add("paused");
            else switchNightly.getStyleClass().remove("paused");
        });
        switchNightly.textProperty().bind(Bindings.when(paused).then("Off (paused)")
                .otherwise(Bindings.when(switchNightly.selectedProperty()).then("On").otherwise("Off")));
        switchNightly.disableProperty().bind(paused);
        labelNightlyCaption.textProperty().bind(Bindings.when(paused).then("Locked while paused. Use Resume upgrades above to change these.")
                .otherwise("Checks your gear, then raises and forges what you allow below."));
        switchPlan.selectedProperty().addListener((obs, was, now) -> fillLevels());
        comboBoxFocus.valueProperty().addListener((obs, was, now) -> refreshRolling());
        comboBoxMaxBuys.valueProperty().addListener((obs, was, now) -> refreshRolling());
        comboBoxReserve.valueProperty().addListener((obs, was, now) -> refreshRolling());
        comboBoxRunAt.valueProperty().addListener((obs, was, now) -> {
            if (now != null && !isLoadingProfile) {
                publishWhenReady(ConfigurationKeyEnum.HERO_GEAR_FORGE_TIME_UTC_STRING, toUtc(now, zone(), now()));
            }
            showStatusLine();
        });
        Tooltip.install(labelGearHelp, new Tooltip("Grades: A is nearly maxed, D has lots of room to grow. "
                + "Each hero's weakest main piece is shown; the rest are under Show all pieces."));
        for (TableView<List<String>> table : List.of(tableGear, tableUpgrades, tableLevels, tableJoiners, tableHeroes)) {
            table.skinProperty().addListener((obs, was, now) -> Platform.runLater(() -> alignHeaders(table)));
            table.widthProperty().addListener((obs, was, now) -> Platform.runLater(() -> alignHeaders(table)));
        }
        switchNightly.selectedProperty().addListener((obs, was, now) -> showStatusLine());
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

    private void bridge(ToggleSwitch toggle, ConfigurationKeyEnum key) {
        switches.put(toggle, key);
        toggle.selectedProperty().addListener((obs, was, now) -> publishWhenReady(key, now));
    }

    /** A greyed row says why, right beside its control. */
    private static void reasonWhenDisabled(Label reason, ComboBox<?> control) {
        reason.visibleProperty().bind(control.disableProperty());
        reason.managedProperty().bind(control.disableProperty());
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

    /** Replaces the question box, so a test can answer it. */
    void spendConfirmer(Predicate<String> confirmer) {
        spendConfirmer = confirmer;
    }

    private String spendQuestion() {
        Integer buys = comboBoxMaxBuys.getValue();
        Integer reserve = comboBoxReserve.getValue();
        return "Forge will spend Essence Stones every night, up to " + (buys != null ? buys : "5") + " forges, always leaving "
                + (reserve != null ? reserve : 0) + " stones unspent. Turn spending on?";
    }

    private static boolean askToSpend(String question) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, question,
                new ButtonType("Yes, spend my stones", ButtonBar.ButtonData.OK_DONE),
                new ButtonType("No, keep my stones", ButtonBar.ButtonData.CANCEL_CLOSE));
        alert.setTitle("Spend Essence Stones?");
        alert.setHeaderText("Spending is currently off");
        return alert.showAndWait().filter(button -> button.getButtonData() == ButtonBar.ButtonData.OK_DONE).isPresent();
    }

    @Override
    public void onProfileLoad(ProfileAux profile) {
        currentProfile = profile;
        if (profile != null) {
            super.onProfileLoad(profile);
            isLoadingProfile = true;
            try {
                switches.forEach((toggle, key) -> toggle.setSelected(Boolean.TRUE.equals(profile.getConfiguration(key))));
                segmentEnhance.setValue(!Boolean.TRUE.equals(profile.getConfiguration(
                        ConfigurationKeyEnum.HERO_GEAR_ENHANCE_ENABLED_BOOL)) ? ENHANCE_OFF
                        : Boolean.TRUE.equals(profile.getConfiguration(ConfigurationKeyEnum.HERO_GEAR_ENHANCE_LIVE_BOOL))
                        ? ENHANCE_REAL : ENHANCE_PRACTICE);
                segmentForge.setValue(Boolean.TRUE.equals(profile.getConfiguration(
                        ConfigurationKeyEnum.HERO_GEAR_FORGE_LIVE_BOOL)) ? FORGE_REAL : FORGE_PRACTICE);
                skipped = new LinkedHashSet<>(GearHeroes.skipped(
                        profile.getConfiguration(ConfigurationKeyEnum.HERO_GEAR_SKIP_HEROES_STRING)));
                setRunAt(profile.getConfiguration(ConfigurationKeyEnum.HERO_GEAR_FORGE_TIME_UTC_STRING));
            } finally {
                isLoadingProfile = false;
            }
        } else {
            skipped = new LinkedHashSet<>();
        }
        reload();
    }

    /** Shows the saved UTC slot as this PC's own clock reads it. */
    private void setRunAt(String utc) {
        String local = localOf(utc);
        if (!comboBoxRunAt.getItems().contains(local)) {
            comboBoxRunAt.getItems().add(local);
            comboBoxRunAt.getItems().sort(String::compareTo);
        }
        comboBoxRunAt.setValue(local);
    }

    /** The UTC slot the shown local time stands for, saved as HH:mm. */
    static String toUtc(String local, ZoneId zone) {
        return toUtc(local, zone, Instant.now());
    }

    static String toUtc(String local, ZoneId zone, Instant at) {
        ZonedDateTime when = LocalDate.ofInstant(at, zone).atTime(LocalTime.parse(local)).atZone(zone);
        return CLOCK.format(when.withZoneSameInstant(ZoneOffset.UTC));
    }

    private String utcRunAt() {
        String local = comboBoxRunAt.getValue();
        return local == null ? null : toUtc(local, zone(), now());
    }

    /** Headers sit left over names and right over numbers; the table's own header label ignores a stylesheet for this. */
    private static void alignHeaders(TableView<List<String>> table) {
        for (Node node : table.lookupAll(".column-header")) {
            if (!(node instanceof TableColumnHeader header) || header.getTableColumn() == null) continue;
            if (!(header.getTableColumn().getUserData() instanceof Kind kind)) continue;
            Label label = (Label) header.lookup(".label");
            if (label == null) continue;
            label.setAlignment(kind == Kind.NUMBER ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            label.setMaxWidth(Double.MAX_VALUE);
            label.setPadding(new Insets(0, 12, 0, 12));
        }
    }

    /** What Run now needs from the bot: is it running, and start the nightly. */
    interface RunHook {
        boolean running(long profileId);

        void run(long profileId);
    }

    private static final class QueueRunHook implements RunHook {
        private static TaskQueue queue(long profileId) {
            try {
                return ScheduleService.obtain().getCoordinator().getQueue(profileId);
            } catch (RuntimeException notStarted) {
                return null;
            }
        }

        @Override
        public boolean running(long profileId) {
            TaskQueue queue = queue(profileId);
            return queue != null && queue.isActive();
        }

        @Override
        public void run(long profileId) {
            queue(profileId).runNow(TpDailyTaskEnum.HERO_GEAR_FORGE, false);
        }
    }

    /** Pins the page's idea of now and of the local zone, so a test can read every time against one moment. */
    void clock(Instant at, ZoneId localZone) {
        clock = () -> at;
        zone = localZone;
    }

    private Instant now() {
        return clock.get();
    }

    private ZoneId zone() {
        return zone;
    }

    /** How old a saved reading may be before the page says so: one scan interval and a grace of six hours. */
    private Duration staleLimit() {
        Integer days = comboBoxScanDays.getValue();
        return Duration.ofDays(days != null ? days : 1).plusHours(6);
    }

    void runHook(RunHook hook) {
        runHook = hook;
        refreshRunNow();
    }

    @FXML
    private void runNow() {
        ProfileAux profile = currentProfile;
        if (profile == null || runBlocker(profile) != null) {
            refreshRunNow();
            return;
        }
        boolean spending = ENHANCE_REAL.equals(segmentEnhance.getValue()) || FORGE_REAL.equals(segmentForge.getValue());
        if (spending && !spendConfirmer.test("Run now uses your For real settings, so it may use XP items and spend Essence Stones right away. Start it?")) {
            return;
        }
        GearManualRun.request(profile.getId());
        runHook.run(profile.getId());
        buttonRunNow.setDisable(true);
        labelRunReason.setText("Started. It runs as soon as the bot is free.");
        show(labelRunReason, true);
    }

    /** Why Run now cannot do anything right now, or null when it can. A paused page has no button at all. */
    private String runBlocker(ProfileAux profile) {
        if (!switchNightly.isSelected()) return "Turn on Run every night first.";
        if (!runHook.running(profile.getId())) return "Start the bot to run now.";
        return null;
    }

    /** Run now shows only when it can matter, and a greyed one says why right under itself. */
    private void refreshRunNow() {
        ProfileAux profile = currentProfile;
        boolean hidden = pausedAt != null;
        show(buttonRunNow, !hidden);
        String reason = profile == null ? "Select a profile first." : hidden ? null : runBlocker(profile);
        buttonRunNow.setDisable(reason != null);
        if (reason != null || hidden || !labelRunReason.getText().startsWith("Started")) {
            labelRunReason.setText(reason == null ? "" : reason);
            show(labelRunReason, reason != null);
        }
    }

    private String localOf(String utc) {
        try {
            return CLOCK.format(nextRun(utc, now(), zone()));
        } catch (DateTimeParseException malformed) {
            return utc + " UTC";
        }
    }

    /** The UTC slot as this PC's clock shows it next, 24-hour. */
    static String localTime(String utc) {
        try {
            return CLOCK.format(nextRun(utc, Instant.now(), ZoneId.systemDefault()));
        } catch (DateTimeParseException malformed) {
            return utc + " UTC";
        }
    }

    /** The next time the UTC slot comes round, in the given zone. */
    static ZonedDateTime nextRun(String utc, Instant now, ZoneId zone) {
        LocalTime slot = LocalTime.parse(utc == null || utc.isBlank()
                ? ConfigurationKeyEnum.HERO_GEAR_FORGE_TIME_UTC_STRING.getDefaultValue() : utc.trim());
        ZonedDateTime next = LocalDate.ofInstant(now, ZoneOffset.UTC).atTime(slot).atOffset(ZoneOffset.UTC).atZoneSameInstant(zone);
        return next.toInstant().isBefore(now) ? next.plusDays(1) : next;
    }

    /** Relative time as the design brief words it: "12 minutes ago", "Today 07:15", "Mon 07:15", "Nov 3". */
    static String friendly(Instant at, Instant now, ZoneId zone) {
        Duration gap = Duration.between(at, now);
        if (!gap.isNegative()) {
            if (gap.toSeconds() < 60) return "just now";
            if (gap.toMinutes() < 60) return gap.toMinutes() + (gap.toMinutes() == 1 ? " minute ago" : " minutes ago");
        } else if (gap.negated().toMinutes() < 60 && gap.negated().toSeconds() >= 60) {
            return "in " + gap.negated().toMinutes() + " min";
        }
        ZonedDateTime when = at.atZone(zone);
        long days = LocalDate.ofInstant(now, zone).toEpochDay() - when.toLocalDate().toEpochDay();
        if (days == 0) return "Today " + CLOCK.format(when);
        if (days == 1) return "Yesterday " + CLOCK.format(when);
        if (days == -1) return "Tomorrow " + CLOCK.format(when);
        if (Math.abs(days) < 7) return WEEKDAY.format(when);
        return when.getYear() == now.atZone(zone).getYear() ? MONTH_DAY.format(when) : MONTH_DAY_YEAR.format(when);
    }

    private String friendly(String iso) {
        try {
            return friendly(Instant.parse(iso), now(), zone());
        } catch (RuntimeException e) {
            return iso;
        }
    }

    static String scanText(Integer days) {
        if (days == null) return "";
        return switch (days) {
            case 1 -> "Every night";
            case 7 -> "Once a week";
            default -> "Every " + days + " days";
        };
    }

    // The nightly routine rewrites these files while the page stays loaded.
    private void reload() {
        ProfileAux profile = currentProfile;
        if (profile == null) {
            showGearProblem("Select a profile.");
            showMessage("Select a profile.");
            boxHalt.setVisible(false);
            boxHalt.setManaged(false);
            return;
        }
        showHalt(profile);
        try {
            HeroGearStore store = new HeroGearStore(WorkspacePaths.current().root());
            gear = store.loadGear(profile.getId()).orElse(null);
            inventory = store.loadInventory(profile.getId()).orElse(null);
            ledger = store.loadNightLedger(profile.getId()).orElse(null);
            fillHeroChoices();
            fillGear();
            fillRolling();
            fillLevels();
            showHalt(profile);
            showStatusLine();
        } catch (IOException | RuntimeException e) {
            showGearProblem("Could not read the saved hero data: " + e);
        }
        loadSnapshot(profile);
    }

    private void showHalt(ProfileAux profile) {
        try {
            Optional<HeroGearStore.GearHalt> halt = new HeroGearStore(WorkspacePaths.current().root()).loadHalt(profile.getId());
            pausedAt = halt.map(HeroGearStore.GearHalt::at).orElse(null);
            paused.set(pausedAt != null);
            halt.ifPresent(h -> labelHalt.setText("It stopped to be safe while "
                    + (HeroGearStore.GearHalt.ENHANCE.equals(h.source()) ? "raising gear levels" : "wearing your best spare gear")
                    + ": " + plainReason(h.reason()) + stillAt(h.reason()) + ". It may stop again if that is still wrong."));
            buttonClearHalt.setDisable(false);
            setBanner(halt.isPresent());
            showStatusLine();
        } catch (IOException | RuntimeException e) {
            pausedAt = now().toString();
            paused.set(true);
            labelHalt.setText("Could not read the pause state, so nothing will be pressed. Resume to clear it and try again.");
            buttonClearHalt.setDisable(false);
            setBanner(true);
        }
    }

    private static final Pattern NO_NEW_LEVEL = Pattern.compile("^(?:Enhance: |Quick Equip: )?(\\S+) (\\S+) showed no new level");

    /** For a "showed no new level" stop, where that piece stood at the last gear check. */
    private String stillAt(String reason) {
        Matcher match = NO_NEW_LEVEL.matcher(reason == null ? "" : reason);
        if (!match.find() || gear == null) return "";
        return gear.pieces().stream().filter(p -> p.hero().equals(match.group(1)) && p.slot().equals(match.group(2)) && p.known())
                .findFirst().map(p -> " (still Lv." + p.enhancement() + ")").orElse("");
    }

    /** The saved reason without the step name that already leads it, e.g. "Enhance: Mia Boots ..." becomes "Mia Boots ...". */
    static String plainReason(String reason) {
        if (reason == null) return "";
        for (String step : List.of("Enhance: ", "Quick Equip: ")) {
            if (reason.startsWith(step)) return reason.substring(step.length());
        }
        return reason;
    }

    private void setBanner(boolean shown) {
        boxHalt.setVisible(shown);
        boxHalt.setManaged(shown);
    }

    @FXML
    private void clearHalt() {
        ProfileAux profile = currentProfile;
        if (profile == null) return;
        try {
            new HeroGearStore(WorkspacePaths.current().root()).clearHalt(profile.getId());
            showHalt(profile);
            showStatusLine();
        } catch (IOException | RuntimeException e) {
            labelHalt.setText("Could not resume: " + e.getMessage());
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
        ledger = null;
        fillHeroChoices();
        fillGear();
        fillRolling();
        fillLevels();
        showStatusLine();
        labelUpgradesEmptyTitle.setText("Could not show the plan");
        labelUpgradesEmptyText.setText(message);
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
                    .ifPresentOrElse(this::show, () -> showMessage("No squad scan yet."));
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
        setRows(tableJoiners, boxJoinersEmpty, List.of());
        setRows(tableHeroes, boxRosterEmpty, List.of());
        labelRosterAside.setText("");
    }

    private void fillSnapshot(SquadSnapshot snapshot) {
        List<List<String>> joinerRows = new ArrayList<>();
        int rank = 1;
        for (SquadPlanner.JoinerScore j : SquadPlanner.plan(snapshot).joiners()) {
            String effect = j.effect().name().toLowerCase(Locale.ROOT);
            joinerRows.add(row(String.valueOf(rank++), j.hero(), j.skill(), text(j.level()),
                    Character.toUpperCase(effect.charAt(0)) + effect.substring(1), j.onlyFor() == null ? "Any troops" : j.onlyFor(),
                    String.format(Locale.ROOT, "%.1f", j.value())));
        }

        List<HeroRecord> heroes = new ArrayList<>(snapshot.heroes());
        heroes.sort(Comparator.comparingInt(HeroesLayoutController::rarityOrder)
                .thenComparing(h -> h.power() == null ? 0 : -h.power()));
        List<List<String>> heroRows = new ArrayList<>();
        for (HeroRecord h : heroes) {
            heroRows.add(row(h.name(), unread(h.rarity()), unread(h.troopClass()), unread(h.level()), unread(h.stars()),
                    h.power() == null ? "Not read" : number(h.power())));
        }

        String scanned = "Last scan " + friendly(snapshot.capturedAt()) + "."
                + (older(snapshot.capturedAt(), Duration.ofDays(7)) ? " Out of date: scan again on the Squads page." : "");
        labelJoiners.setText(scanned);
        labelRoster.setText(scanned + " " + heroRows.size() + " heroes, gold first, then strongest first.");
        setRows(tableJoiners, boxJoinersEmpty, joinerRows);
        setRows(tableHeroes, boxRosterEmpty, heroRows);
        // The roster levels come from this scan, not from the nightly check, so the card says which scan.
        labelRosterAside.setText("Levels from the " + MONTH_DAY.format(Instant.parse(snapshot.capturedAt()).atZone(zone())) + " scan");
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

    private static String number(Long value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%,d", value);
    }

    private static String unread(Object value) {
        return value == null ? "Not read" : Objects.toString(value);
    }

    private static String text(Object value) {
        return value == null ? "-" : Objects.toString(value);
    }

    // ── Header status line ───────────────────────────────────────────

    /** Three short lines: is it on, what happened last night, what the next night will do. */
    private void showStatusLine() {
        ProfileAux profile = currentProfile;
        if (profile == null) {
            lines("Select a profile.", "", "");
            return;
        }
        String runAt = utcRunAt();
        comboBoxRunAt.setTooltip(new Tooltip(runAt == null ? "" : runAt + " UTC"));
        refreshRunNow();
        if (pausedAt != null) {
            lines("Paused", "Stopped " + friendly(pausedAt), "");
        } else if (switchNightly.isSelected()) {
            boolean spending = ENHANCE_REAL.equals(segmentEnhance.getValue()) || FORGE_REAL.equals(segmentForge.getValue());
            String next;
            try {
                next = nextLabel(runAt) + ": " + tonight();
            } catch (DateTimeParseException malformed) {
                next = "Check the run time";
            }
            lines("On \u00b7 " + (spending ? "Spending" : "Practice"), lastLine(), next);
        } else {
            lines("Off", gear != null && gear.scannedAt() != null ? "Gear checked " + friendly(gear.scannedAt()) : "Gear not checked yet",
                    "");
        }
    }

    private void lines(String state, String last, String next) {
        labelStatusLine.setText(state);
        labelLastLine.setText(last);
        labelTonightLine.setText(next);
        show(labelLastLine, !last.isEmpty());
        show(labelTonightLine, !next.isEmpty());
    }

    /** "Tonight 21:30" for this evening's run, else "Next run Tomorrow 21:30". */
    private String nextLabel(String utc) {
        ZonedDateTime next = nextRun(utc, now(), zone());
        long days = LocalDate.ofInstant(now(), zone()).toEpochDay() - next.toLocalDate().toEpochDay();
        return days == 0 && next.getHour() >= 18 ? "Tonight " + CLOCK.format(next)
                : "Next run " + friendly(next.toInstant(), now(), zone());
    }

    /** "Last night 21:31" for an evening or night run of yesterday or today, else "Last run Mon 14:08"; a dry run says so. */
    private String lastWhen(String iso, boolean practice) {
        String tag = practice ? " (practice)" : "";
        try {
            Instant at = Instant.parse(iso);
            ZonedDateTime local = at.atZone(zone());
            long days = LocalDate.ofInstant(now(), zone()).toEpochDay() - local.toLocalDate().toEpochDay();
            if ((days == 0 || days == 1) && (local.getHour() >= 18 || local.getHour() < 6)) return "Last night" + tag + " " + CLOCK.format(local);
            return "Last run" + tag + " " + friendly(at, now(), zone());
        } catch (RuntimeException unreadable) {
            return "Last run" + tag + " " + iso;
        }
    }

    private String lastLine() {
        if (ledger == null) return "Has not run yet";
        if (!ledger.finished()) return lastWhen(ledger.startedAt(), false) + ": did not finish";
        boolean practice = HeroGearStore.NightLedger.PRACTICE.equals(ledger.mode())
                || ledger.mode() == null && !(ENHANCE_REAL.equals(segmentEnhance.getValue()) || FORGE_REAL.equals(segmentForge.getValue()));
        String when = lastWhen(ledger.startedAt(), practice);
        if (!practice) return when + ": raised " + ledger.levels() + " levels, forged " + ledger.buys();
        return ledger.plan().isEmpty() ? when + ": nothing to change"
                : when + ": would " + ledger.plan().stream().map(HeroesLayoutController::planText).collect(Collectors.joining(", would "));
    }

    private String clockOf(String iso) {
        try {
            return CLOCK.format(Instant.parse(iso).atZone(zone()));
        } catch (RuntimeException unreadable) {
            return iso;
        }
    }

    private static final Pattern PLANNED_RAISE = Pattern.compile("^raise (.+) (Goggles|Boots|Gauntlets|Belt) to Lv\\.(\\d+)$");
    private static final Pattern PLANNED_FORGE = Pattern.compile("^forge (.+) (Goggles|Boots|Gauntlets|Belt) (\\d+)% to (\\d+)% \\(\\d+ stones\\)$");

    /** A saved plan line in plain words: "raise Molly's Boots to 70", "forge Bahiti's Boots 42\u219244%". */
    static String planText(String saved) {
        Matcher raise = PLANNED_RAISE.matcher(saved);
        if (raise.matches()) return "raise " + raise.group(1) + "'s " + raise.group(2) + " to " + raise.group(3);
        Matcher forge = PLANNED_FORGE.matcher(saved);
        if (forge.matches()) return "forge " + forge.group(1) + "'s " + forge.group(2) + " " + forge.group(3) + "\u2192" + forge.group(4) + "%";
        return saved;
    }

    /** What the next night will really do, from the settings and the stones counted. */
    private String tonight() {
        boolean enhance = ENHANCE_REAL.equals(segmentEnhance.getValue());
        boolean forge = FORGE_REAL.equals(segmentForge.getValue());
        if (!enhance && !forge) return "spends nothing";
        List<String> plan = new ArrayList<>();
        if (enhance) plan.add("up to " + (comboBoxEnhanceLevels.getValue() != null ? comboBoxEnhanceLevels.getValue() : 5) + " levels (XP items)");
        if (forge) {
            int reserve = comboBoxReserve.getValue() != null ? comboBoxReserve.getValue() : 0;
            if (inventory == null) plan.add("forge up to " + (comboBoxMaxBuys.getValue() != null ? comboBoxMaxBuys.getValue() : 5) + " (stones not counted)");
            else if (canBuyTonight > 0) plan.add("forge up to " + canBuyTonight + " (" + inventory.essenceStones() + " stones)");
            else             plan.add("no forge: needs " + (nextForgeCost + reserve) + " stones, you have " + inventory.essenceStones());
        }
        return String.join(" \u00b7 ", plan);
    }

    /** Data older than {@code limit} is flagged, so a stale number is never mistaken for a live one. */
    boolean older(String iso, Duration limit) {
        try {
            return Duration.between(Instant.parse(iso), now()).compareTo(limit) > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ── Heroes ───────────────────────────────────────────────────────

    /** The heroes the last gear check read, in the order it read them: the strongest by power. */
    private Set<String> scannedHeroes() {
        Set<String> heroes = new LinkedHashSet<>();
        if (gear != null) gear.pieces().forEach(piece -> heroes.add(piece.hero()));
        return heroes;
    }

    private void fillHeroChoices() {
        gridHeroes.getChildren().clear();
        Set<String> heroes = scannedHeroes();
        show(gridHeroes, !heroes.isEmpty());
        show(boxHeroesEmpty, heroes.isEmpty());
        labelHeroesInfo.setText(heroes.isEmpty() ? "" : "Your " + heroes.size() + " strongest, read at each gear check. Untick to leave one out.");
        show(labelHeroesInfo, !heroes.isEmpty());
        int index = 0;
        for (String hero : heroes) {
            CheckBox choice = new CheckBox(hero);
            choice.setSelected(!skipped.contains(hero));
            choice.selectedProperty().addListener((obs, was, now) -> {
                if (now) skipped.remove(hero);
                else skipped.add(hero);
                publishWhenReady(ConfigurationKeyEnum.HERO_GEAR_SKIP_HEROES_STRING, GearHeroes.join(skipped));
                refreshRolling();
            });
            gridHeroes.add(choice, index % HERO_COLUMNS, index / HERO_COLUMNS);
            index++;
        }
    }

    private static void show(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    // ── Your gear ────────────────────────────────────────────────────

    private void fillGear() {
        List<List<String>> rows = new ArrayList<>();
        if (gear != null) {
            Map<String, Map<String, GearPiece>> byHero = new LinkedHashMap<>();
            for (GearPiece piece : gear.pieces()) {
                byHero.computeIfAbsent(piece.hero(), hero -> new LinkedHashMap<>()).put(piece.slot(), piece);
            }
            byHero.forEach((hero, pieces) -> {
                for (String slot : showAllPieces ? SLOTS : List.of(weakestMain(pieces))) {
                    GearPiece p = pieces.get(slot);
                    if (p == null) continue;
                    rows.add(p.known()
                            ? row(hero, slot, p.onRole() ? "Main piece" : "Spare", GearRanker.grade(p),
                                    String.valueOf(p.enhancement()), p.masteryPercent() + "%")
                            : row(hero, slot, p.onRole() ? "Main piece" : "Spare", "?", "Not read", "Not read"));
                }
            });
        }
        boolean stale = gear != null && gear.scannedAt() != null && older(gear.scannedAt(), staleLimit());
        labelGearInfo.setText(gear != null && gear.scannedAt() != null
                ? "Checked " + friendly(gear.scannedAt()) + (stale ? " \u00b7 out of date" : "") : "");
        setRows(tableGear, boxGearEmpty, rows);
        show(labelGearHelp, !rows.isEmpty());
        linkGear.setText(showAllPieces ? "Show fewer" : "Show all " + (gear == null ? 0 : gear.pieces().size()) + " pieces");
        show(linkGear, gear != null && !rows.isEmpty());
    }

    /** The slot Enhance and Forge look at first for a hero: their lowest known main piece, else any piece. */
    private static String weakestMain(Map<String, GearPiece> pieces) {
        String weakest = null;
        double lowest = Double.MAX_VALUE;
        for (String slot : SLOTS) {
            GearPiece p = pieces.get(slot);
            if (p == null || !p.known() || !p.onRole()) continue;
            if (p.bonus() < lowest) {
                lowest = p.bonus();
                weakest = slot;
            }
        }
        if (weakest != null) return weakest;
        return SLOTS.stream().filter(pieces::containsKey).findFirst().orElse(SLOTS.getFirst());
    }

    @FXML
    private void toggleGear() {
        showAllPieces = !showAllPieces;
        fillGear();
    }

    // ── What Forge buys next ─────────────────────────────────────────

    private void fillRolling() {
        labelStones.setText(inventory != null ? "You have " + inventory.essenceStones() + " stones, counted "
                + friendly(inventory.capturedAt()) + (older(inventory.capturedAt(), staleLimit()) ? " \u00b7 may be out of date" : "")
                : "Stones not counted yet");
        labelUpgradesMore.setText("");
        show(labelUpgradesMore, false);
        show(linkUpgrades, false);
        if (gear == null) {
            labelUpgradesEmptyTitle.setText("Nothing planned yet");
            labelUpgradesEmptyText.setText("The plan appears after the first gear check.");
            setRows(tableUpgrades, boxUpgradesEmpty, List.of());
            return;
        }
        Set<String> included = GearHeroes.included(scannedHeroes(), skipped);
        GearState ranked = new GearState(gear.pieces(), GearRanker.weightsFor(gear, comboBoxFocus.getValue(), included));
        int reserve = comboBoxReserve.getValue() != null ? comboBoxReserve.getValue() : 0;
        int maxBuys = comboBoxMaxBuys.getValue() != null ? comboBoxMaxBuys.getValue()
                : Integer.parseInt(ConfigurationKeyEnum.HERO_GEAR_FORGE_MAX_BUYS_INT.getDefaultValue());
        List<Upgrade> upgrades = GearRanker.rolling(ranked, ROLLING_COUNT);
        canBuyTonight = 0;
        missingForNext = 0;
        if (inventory != null && !upgrades.isEmpty()) {
            int spend = 0;
            missingForNext = Math.max(0, upgrades.getFirst().stones() + reserve - inventory.essenceStones());
            nextForgeCost = upgrades.getFirst().stones();
            for (int i = 0; i < upgrades.size() && i < maxBuys; i++) {
                spend += upgrades.get(i).stones();
                if (spend + reserve <= inventory.essenceStones()) canBuyTonight = i + 1;
            }
        }
        List<List<String>> rows = new ArrayList<>();
        int running = 0;
        int number = 0;
        for (Upgrade upgrade : upgrades) {
            running += upgrade.stones();
            number++;
            if (number > (showAllUpgrades ? ROLLING_COUNT : FEW_UPGRADES)) continue;
            rows.add(row(upgrade.hero(), upgrade.slot(), upgrade.fromMastery() + "%", upgrade.toMastery() + "%",
                    upgrade.stones() + " stones", running + " stones", status(number, running, reserve, maxBuys)));
        }
        labelUpgradesEmptyTitle.setText("Nothing to forge");
        labelUpgradesEmptyText.setText("No readable main piece on the heroes ticked above.");
        setRows(tableUpgrades, boxUpgradesEmpty, rows);
        if (rows.size() > 1) {
            labelUpgradesMore.setText("Each row assumes the rows above it were bought.");
            show(labelUpgradesMore, true);
        }
        if (upgrades.size() > FEW_UPGRADES) {
            linkUpgrades.setText(showAllUpgrades ? "Show fewer" : "Show all " + upgrades.size());
            show(linkUpgrades, true);
        }
    }

    @FXML
    private void toggleUpgrades() {
        showAllUpgrades = !showAllUpgrades;
        refreshRolling();
    }

    private String status(int number, int running, int reserve, int maxBuys) {
        if (inventory == null) return "Not counted yet";
        int missing = running + reserve - inventory.essenceStones();
        if (missing > 0) return "Short by " + missing + " stones";
        return number <= maxBuys ? "Can afford" : "Tomorrow night";
    }

    // ── Level ups ────────────────────────────────────────────────────

    private void fillLevels() {
        List<List<String>> rows = new ArrayList<>();
        if (inventory != null) {
            for (Instructor instructor : inventory.instructors()) {
                Long next = instructor.xpToNext();
                rows.add(row(instructor.name(), String.valueOf(instructor.level()),
                        next != null ? String.format(Locale.ROOT, "%,d", next)
                                : instructor.level() >= MAX_HERO_LEVEL ? "Maxed" : "Not read",
                        next == null ? (instructor.level() >= MAX_HERO_LEVEL ? "" : "Not read") : next <= inventory.heroXp() ? "0"
                                : String.format(Locale.ROOT, "%,d", next - inventory.heroXp())));
            }
            labelXp.setText(String.format(Locale.ROOT, "You have %,d hero XP, read %s. Bottles in your Backpack are not counted.",
                    inventory.heroXp(), friendly(inventory.capturedAt())));
        } else {
            labelXp.setText("Hero XP not counted yet");
        }
        labelLevelsAside.setText(inventory != null ? "Levels read " + friendly(inventory.capturedAt()) : "");
        boolean planOn = switchPlan.isSelected();
        labelLevelsEmptyTitle.setText(planOn ? "No Drill Camp data yet" : "The level-up plan is off");
        labelLevelsEmptyText.setText(planOn ? "It appears after the first gear check." : "Turn it on above to see which hero to level first.");
        setRows(tableLevels, boxLevelsEmpty, planOn ? rows : List.of());
    }

    // ── Tables ───────────────────────────────────────────────────────

    /** Rows or, with none, the empty state. Names never truncate: that column is as wide as its longest name. */
    private void setRows(TableView<List<String>> table, VBox empty, List<List<String>> rows) {
        table.setItems(FXCollections.observableArrayList(rows));
        boolean condensed = rows.size() > CONDENSED_FROM_ROWS;
        table.getStyleClass().remove("hero-table-condensed");
        if (condensed) table.getStyleClass().add("hero-table-condensed");
        int height = condensed ? CONDENSED_ROW_HEIGHT : ROW_HEIGHT;
        table.setPrefHeight(HEADER_HEIGHT + height * Math.max(rows.size(), 1) + 2);
        table.setMinHeight(table.getPrefHeight());
        table.setMaxHeight(table.getPrefHeight());
        show(table, !rows.isEmpty());
        show(empty, rows.isEmpty());
        Platform.runLater(() -> alignHeaders(table));
        for (int i = 0; i < table.getColumns().size(); i++) {
            TableColumn<List<String>, ?> column = table.getColumns().get(i);
            if (column.getUserData() != Kind.TEXT) continue;
            double widest = textWidth(column.getId());
            for (List<String> r : rows) widest = Math.max(widest, textWidth(r.get(i)));
            double width = Math.min(NAME_CAP, widest + NAME_PADDING);
            column.setMinWidth(width);
            column.setPrefWidth(Math.max(column.getPrefWidth(), width));
        }
    }

    private static double textWidth(String value) {
        Text text = new Text(value == null ? "" : value);
        text.setFont(TABLE_FONT);
        return text.getLayoutBounds().getWidth();
    }

    private static void column(TableView<List<String>> table, String header, double width, Kind kind) {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        int index = table.getColumns().size();
        TableColumn<List<String>, String> column = new TableColumn<>(header);
        column.setId(header);
        column.setSortable(false);
        column.setUserData(kind);
        column.setPrefWidth(width);
        column.setCellValueFactory(row -> new ReadOnlyStringWrapper(row.getValue().get(index)));
        if (kind == Kind.NUMBER) column.getStyleClass().add("hero-number");
        column.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("hero-number");
                setGraphic(null);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                if (kind == Kind.NUMBER) {
                    getStyleClass().add("hero-number");
                    setAlignment(Pos.CENTER_RIGHT);
                }
                if (kind == Kind.PILL && !item.equals("-")) {
                    Label pill = new Label(item);
                    pill.getStyleClass().addAll("hero-pill", pillStyle(item));
                    setGraphic(pill);
                    setText(null);
                } else {
                    setText(item.equals("-") && kind == Kind.PILL ? "" : item);
                }
            }
        });
        table.getColumns().add(column);
    }

    /** Green for good, amber for short, red for a poor grade, grey for the rest. */
    static String pillStyle(String text) {
        if (text.equals("A") || text.equals("B") || text.startsWith("Can")) return "hero-pill-ok";
        if (text.equals("C") || text.startsWith("Short") || text.startsWith("Need")) return "hero-pill-warn";
        if (text.equals("D")) return "hero-pill-error";
        return "hero-pill-off";
    }
}
