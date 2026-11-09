package dev.frostguard.app.panel.misc;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.api.domain.ProfilesData;
import dev.frostguard.api.domain.JobMetrics;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignG;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

public class StatisticsLayoutController extends AbstractProfileController {

    // ========================================================================
    // COUNTER CATEGORY MAPPING
    // ========================================================================

    private static final Map<String, String> COUNTER_CATEGORIES = new LinkedHashMap<>();
    static {
        // Combat
        COUNTER_CATEGORIES.put("Arena Battles Won", "Combat");
        COUNTER_CATEGORIES.put("Arena Battles Lost", "Combat");
        COUNTER_CATEGORIES.put("Arena Gems Spent", "Combat");
        COUNTER_CATEGORIES.put("Arena Refreshes", "Combat");
        COUNTER_CATEGORIES.put("Exploration Fights Won", "Combat");
        COUNTER_CATEGORIES.put("Exploration Fights Lost", "Combat");
        // Intel & Exploration
        COUNTER_CATEGORIES.put("Intel Beast", "Intel & Exploration");
        COUNTER_CATEGORIES.put("Intel Survivor Camps", "Intel & Exploration");
        COUNTER_CATEGORIES.put("Intel Journeys", "Intel & Exploration");
        COUNTER_CATEGORIES.put("Beast Attacks Sent", "Intel & Exploration");
        // Economy
        COUNTER_CATEGORIES.put("Mystery Shop Purchases", "Economy");
        COUNTER_CATEGORIES.put("Mystery Shop Free Claims", "Economy");
        COUNTER_CATEGORIES.put("Daily Refreshes Used", "Economy");
        COUNTER_CATEGORIES.put("Alliance Shop Purchases", "Economy");
        COUNTER_CATEGORIES.put("Gather Marches Deployed", "Economy");
        COUNTER_CATEGORIES.put("Nomadic Merchant Free Resources Claimed", "Economy");
        COUNTER_CATEGORIES.put("Nomadic Merchant VIP Points Purchased", "Economy");
        COUNTER_CATEGORIES.put("Nomadic Merchant Free Refresh Taps Dispatched", "Economy");
        COUNTER_CATEGORIES.put("Nomadic Merchant Daily Refresh Used", "Economy");
        COUNTER_CATEGORIES.put("Storehouse Chests Opened", "Economy");
        COUNTER_CATEGORIES.put("Alliance Gifts Collected", "Economy");
        COUNTER_CATEGORIES.put("Pet Adventure Chests", "Economy");
        COUNTER_CATEGORIES.put("Alliance Triumph Rewards", "Economy");

        // Training & Research
        COUNTER_CATEGORIES.put("Training Batches Started", "Training & Research");
        COUNTER_CATEGORIES.put("Research Started", "Training & Research");
        // Utility
        COUNTER_CATEGORIES.put("Mail Rewards Claimed", "Utility");
        COUNTER_CATEGORIES.put("Daily Missions Claimed", "Utility");
    }

    // Ordered list of categories for display
    private static final List<String> CATEGORY_ORDER = List.of(
            "Combat", "Intel & Exploration", "Economy", "Training & Research", "Utility", "Other");

    // ========================================================================
    // FXML FIELDS
    // ========================================================================

    @FXML private Button btnRefresh;
    @FXML private Button btnReset;
    @FXML private HBox hboxSummaryCards;
    @FXML private TableView<JobMetrics> tableTasks;
    @FXML private TableColumn<JobMetrics, String> colTaskName;
    @FXML private TableColumn<JobMetrics, Number> colRuns;
    @FXML private TableColumn<JobMetrics, String> colAvgTime;
    @FXML private TableColumn<JobMetrics, String> colTotalTime;
    @FXML private TableColumn<JobMetrics, String> colLastRun;
    @FXML private TableColumn<JobMetrics, String> colAvgOcr;
    @FXML private TableColumn<JobMetrics, String> colAvgImg;
    @FXML private VBox vboxCounterSections;
    @FXML private Label lblNoData;

    // "What the bot did for you" section
    @FXML private BorderPane statisticsRoot;
    @FXML private TabPane tabsStatistics;
    @FXML private HBox windowButtons;
    @FXML private TilePane tileSpeedups;
    @FXML private TilePane tilePowerGems;
    @FXML private TilePane tileResources;
    @FXML private TilePane tileActivity;
    @FXML private Label lblActivityHint;
    @FXML private Label lblWindow;
    @FXML private Label lblEarningsEmpty;

    /** Display names + accent colours for the six telemetry metrics. */
    private static final Map<String, String> METRIC_LABELS = new LinkedHashMap<>();
    private static final Map<String, String> METRIC_COLORS = new LinkedHashMap<>();
    static {
        METRIC_LABELS.put("power", "Power");   METRIC_COLORS.put("power", "#4fc3f7");
        METRIC_LABELS.put("gems", "Gems");     METRIC_COLORS.put("gems", "#ba68c8");
        METRIC_LABELS.put("meat", "Meat");     METRIC_COLORS.put("meat", "#ff8a65");
        METRIC_LABELS.put("wood", "Wood");     METRIC_COLORS.put("wood", "#81c784");
        METRIC_LABELS.put("coal", "Coal");     METRIC_COLORS.put("coal", "#90a4ae");
        METRIC_LABELS.put("iron", "Iron");     METRIC_COLORS.put("iron", "#ffb74d");
        METRIC_LABELS.put("steel", "Steel");   METRIC_COLORS.put("steel", "#b0bec5");
        // Speedup durations (minutes) — formatted as "6d 3h" for display.
        METRIC_LABELS.put("sp_general", "Gen. speedup");        METRIC_COLORS.put("sp_general", "#4dd0e1");
        METRIC_LABELS.put("sp_training", "Training spd");       METRIC_COLORS.put("sp_training", "#7986cb");
        METRIC_LABELS.put("sp_construction", "Construction spd"); METRIC_COLORS.put("sp_construction", "#a1887f");
        METRIC_LABELS.put("sp_research", "Research spd");       METRIC_COLORS.put("sp_research", "#4db6ac");
        METRIC_LABELS.put("sp_healing", "Healing spd");         METRIC_COLORS.put("sp_healing", "#e57373");
    }

    /**
     * Icon key per resource metric. Drop <key>.png into <cwd>/stat-icons/ to set any of these.
     */
    private static final Map<String, String> METRIC_ICONS = new LinkedHashMap<>();
    static {
        METRIC_ICONS.put("power", "power");
        METRIC_ICONS.put("gems", "gems");
        METRIC_ICONS.put("meat", "meat");
        METRIC_ICONS.put("wood", "wood");
        METRIC_ICONS.put("coal", "coal");
        METRIC_ICONS.put("iron", "iron");
        METRIC_ICONS.put("steel", "steel");
        METRIC_ICONS.put("sp_general", "sp_general");
        METRIC_ICONS.put("sp_training", "sp_training");
        METRIC_ICONS.put("sp_construction", "sp_construction");
        METRIC_ICONS.put("sp_research", "sp_research");
        METRIC_ICONS.put("sp_healing", "sp_healing");
    }
    /** Icon key per activity tile. Drop <key>.png into <cwd>/stat-icons/ to set any of these. */
    private static final Map<String, String> ACTIVITY_ICONS = new LinkedHashMap<>();
    static {
        ACTIVITY_ICONS.put("Beasts hunted", "beasts");
        ACTIVITY_ICONS.put("Journeys scouted", "journeys");
        ACTIVITY_ICONS.put("Survivor camps", "survivor_camps");
        ACTIVITY_ICONS.put("Gather marches", "gather");
        ACTIVITY_ICONS.put("Daily missions", "daily_missions");
        ACTIVITY_ICONS.put("Growth missions", "growth_missions");
        ACTIVITY_ICONS.put("Mail rewards", "mail");
        ACTIVITY_ICONS.put("Exploration wins", "exploration_win");
        ACTIVITY_ICONS.put("Arena wins", "arena_win");
        ACTIVITY_ICONS.put("Beast attacks", "beast_attacks");
        ACTIVITY_ICONS.put("Storehouse chests", "storehouse");
        ACTIVITY_ICONS.put("Alliance chests", "alliance_chest");
        ACTIVITY_ICONS.put("Pet chests", "pet");
        ACTIVITY_ICONS.put("Triumph rewards", "triumph");
    }

    /** Clean flat glyphs per icon key — the default look. Overridable by a dropped-in PNG. */
    private static final Map<String, Ikon> GLYPHS = new LinkedHashMap<>();
    static {
        GLYPHS.put("power", MaterialDesignA.ARM_FLEX);
        GLYPHS.put("gems", MaterialDesignD.DIAMOND_STONE);
        GLYPHS.put("meat", MaterialDesignF.FOOD_STEAK);
        GLYPHS.put("wood", MaterialDesignP.PINE_TREE);
        GLYPHS.put("coal", MaterialDesignG.GRAIN);
        GLYPHS.put("iron", MaterialDesignA.ANVIL);
        // Steel → steel-mill; speedups → fast-forward / clock / timer family.
        GLYPHS.put("steel", MaterialDesignF.FACTORY);
        GLYPHS.put("sp_general", MaterialDesignF.FAST_FORWARD);
        GLYPHS.put("sp_training", MaterialDesignC.CLOCK_FAST);
        GLYPHS.put("sp_construction", MaterialDesignT.TIMER_SAND);
        GLYPHS.put("sp_research", MaterialDesignF.FLASK);
        GLYPHS.put("sp_healing", MaterialDesignM.MEDICAL_BAG);
        GLYPHS.put("intel", MaterialDesignC.CHART_BAR);
        GLYPHS.put("beasts", MaterialDesignS.SKULL);
        GLYPHS.put("survivor_camps", MaterialDesignT.TENT);
        GLYPHS.put("journeys", MaterialDesignC.COMPASS_OUTLINE);
        GLYPHS.put("exploration_win", MaterialDesignT.TROPHY);
        GLYPHS.put("training", MaterialDesignS.SWORD_CROSS);
        GLYPHS.put("research", MaterialDesignF.FLASK);
        GLYPHS.put("gather", MaterialDesignP.PICKAXE);
        GLYPHS.put("storehouse", MaterialDesignW.WAREHOUSE);
        GLYPHS.put("alliance_chest", MaterialDesignS.SHIELD);
        GLYPHS.put("pet", MaterialDesignP.PAW);
        GLYPHS.put("exploration", MaterialDesignC.COMPASS);
        GLYPHS.put("exploration_chest", MaterialDesignT.TREASURE_CHEST);
        GLYPHS.put("daily_missions", MaterialDesignC.CLIPBOARD_CHECK);
        GLYPHS.put("growth_missions", MaterialDesignT.TRENDING_UP);
        GLYPHS.put("mail", MaterialDesignE.EMAIL);
        GLYPHS.put("life_essence", MaterialDesignS.SPROUT);
        GLYPHS.put("labyrinth", MaterialDesignM.MAP_MARKER_PATH);
        GLYPHS.put("arena_win", MaterialDesignT.TROPHY_VARIANT);
        GLYPHS.put("beast_attacks", MaterialDesignS.SWORD);
        GLYPHS.put("triumph", MaterialDesignT.TROPHY_AWARD);
    }

    /**
     * The tile icon for a key: a user-supplied PNG at {@code <cwd>/stat-icons/<key>.png} if present
     * (drop one in and hit Refresh — no rebuild), otherwise a clean flat glyph. Returns a JavaFX
     * Node either way.
     */
    private static javafx.scene.Node iconNodeFor(String key, String accent) {
        if (key == null) return null;
        try {
            java.io.File ext = new java.io.File(System.getProperty("user.dir"), "stat-icons/" + key + ".png");
            if (ext.isFile()) {
                ImageView iv = new ImageView(new Image(ext.toURI().toString(), TILE_ICON, TILE_ICON, true, true));
                iv.setFitWidth(TILE_ICON); iv.setFitHeight(TILE_ICON); iv.setPreserveRatio(true);
                return iv;
            }
        } catch (Exception ignored) { }
        Ikon glyph = GLYPHS.get(key);
        if (glyph == null) return null;
        // Size via a CSS STYLE CLASS, not setIconSize(): ikonli's CSS pass overrides the
        // programmatic value, so every setIconSize() number rendered at the default. The app's
        // own nav icons size themselves through CSS (.nav-button .ikonli-font-icon) and render
        // real glyphs — so .stat-tile-glyph { -fx-icon-size } is the mechanism that actually takes.
        // Colour still works programmatically (it varies per tile, so it can't be a static class).
        FontIcon fi = new FontIcon(glyph);
        fi.getStyleClass().add("stat-tile-glyph");
        fi.setIconColor(javafx.scene.paint.Color.web(accent == null ? "#9eaab6" : accent));
        return fi;
    }

    /** the configured sleep window for the "last night" report (11pm–8:30am local).
     *  this had drifted to 8:00 while bg_telemetry's own wake-snapshot anchor
     *  documents an observed real capture at 08:30:43 -- restored to match, same fix as #250. */
    private static final LocalTime SLEEP_START = LocalTime.of(23, 0);
    private static final LocalTime WAKE_END = LocalTime.of(8, 30);

    // No profile is selected yet at field-init time, so there's nothing to load -- an ID no real
    // profile will ever have resolves to a not-yet-existing file, which TelemetryReport.load()
    // already treats as "empty" by design. refreshEarnings() replaces this the moment a profile
    // is actually selected.
    private static final long NO_PROFILE_SELECTED_ID = -1L;

    private ProfileAux currentProfile;
    private TelemetryReport telemetry = TelemetryReport.load(NO_PROFILE_SELECTED_ID);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** The "what the bot did" timeframe segments. Dropdown replaces the old
     *  button row -- Past Hour and This Month are new, This Week is the renamed Last 7 Days
     *  (same rolling-7-day math, just a friendlier label). A custom range was explicitly
     *  descoped ("let's just do those for now"). */
    private enum ReportWindow { PAST_HOUR, NIGHT, LAST_24H, THIS_WEEK, THIS_MONTH, TOTAL }

    private static final Map<String, ReportWindow> WINDOW_LABELS = new LinkedHashMap<>();
    static {
        WINDOW_LABELS.put("Past hour", ReportWindow.PAST_HOUR);
        WINDOW_LABELS.put("Last night", ReportWindow.NIGHT);
        WINDOW_LABELS.put("Last 24 hours", ReportWindow.LAST_24H);
        WINDOW_LABELS.put("This week", ReportWindow.THIS_WEEK);
        WINDOW_LABELS.put("This month", ReportWindow.THIS_MONTH);
        WINDOW_LABELS.put("All time", ReportWindow.TOTAL);
    }

    /**
     * Which timeframe is currently shown. Refresh stays on it rather than snapping back to the default.
     */
    private ReportWindow activeWindow = ReportWindow.NIGHT;

    private final ToggleGroup windowGroup = new ToggleGroup();
    private boolean refreshPending;
    private long loadGeneration;

    // ========================================================================
    // INITIALIZATION
    // ========================================================================

    @FXML
    private void initialize() {
        colTaskName.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getTaskName()));
        colRuns.setCellValueFactory(cellData -> new SimpleIntegerProperty(cellData.getValue().getNumberOfRuns()));
        colAvgTime.setCellValueFactory(cellData -> new SimpleStringProperty(String.format("%.2f", cellData.getValue().getAverageExecutionTimeMs() / 1000.0)));
        colTotalTime.setCellValueFactory(cellData -> new SimpleStringProperty(String.format("%.2f", cellData.getValue().getTotalExecutionTimeMs() / 1000.0)));
        colAvgOcr.setCellValueFactory(cellData -> new SimpleStringProperty(String.format("%.2f", cellData.getValue().getAverageOcrFailures())));
        colAvgImg.setCellValueFactory(cellData -> new SimpleStringProperty(String.format("%.2f", cellData.getValue().getAverageTemplateFailures())));
        colLastRun.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getLastRunTime()));

        WINDOW_LABELS.forEach((label, window) -> {
            ToggleButton button = new ToggleButton(label);
            button.setToggleGroup(windowGroup);
            button.setUserData(window);
            button.getStyleClass().add("window-seg-button");
            button.setSelected(window == activeWindow);
            windowButtons.getChildren().add(button);
        });
        // Attached after the initial selection: there is no data to show until a profile loads.
        windowGroup.selectedToggleProperty().addListener((obs, previous, selected) -> {
            if (selected == null) {
                windowGroup.selectToggle(previous);
                return;
            }
            activeWindow = (ReportWindow) selected.getUserData();
            showActiveWindow();
        });
        statisticsRoot.sceneProperty().addListener((obs, previous, scene) -> {
            if (scene != null && refreshPending) {
                refreshStatisticsView();
            }
        });
    }

    public TabPane tabs() {
        return tabsStatistics;
    }

    // ========================================================================
    // PROFILE LIFECYCLE
    // ========================================================================

    @Override
    public void onProfileLoad(ProfileAux profile) {
        super.onProfileLoad(profile);
        this.currentProfile = profile;
        refreshStatisticsView();
    }

    @FXML
    private void handleRefresh(ActionEvent event) {
        if (currentProfile != null) {
            refreshStatisticsView();
        }
    }

    @FXML
    private void handleReset(ActionEvent event) {
        if (currentProfile == null) return;

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Reset Statistics");
        alert.setHeaderText("Reset all statistics for this profile?");
        alert.setContentText("This action cannot be undone.");

        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                if (profileObserver != null) {
                    profileObserver.notifyProfileChange(ConfigurationKeyEnum.STATISTICS_JSON_STRING, "{}");
                }
                Platform.runLater(this::refreshStatisticsView);
            }
        });
    }

    // ========================================================================
    // MAIN REFRESH
    // ========================================================================

    private void refreshStatisticsView() {
        if (currentProfile == null) return;
        // Nothing reads the page while it is off screen, and each refresh loads the whole telemetry store.
        if (statisticsRoot.getScene() == null) {
            refreshPending = true;
            return;
        }
        refreshPending = false;

        refreshEarnings();

        String json = currentProfile.getConfiguration(ConfigurationKeyEnum.STATISTICS_JSON_STRING);
        ProfilesData stats = parseJsonToStats(json);

        hboxSummaryCards.getChildren().clear();
        tableTasks.getItems().clear();
        vboxCounterSections.getChildren().clear();

        if (stats.getTaskStatistics().isEmpty() && stats.getCustomCounters().isEmpty()) {
            lblNoData.setVisible(true);
            lblNoData.setManaged(true);
            tableTasks.setVisible(false);
            tableTasks.setManaged(false);
        } else {
            lblNoData.setVisible(false);
            lblNoData.setManaged(false);
            tableTasks.setVisible(true);
            tableTasks.setManaged(true);

            // Build summary cards
            buildSummaryCards(stats);

            // Populate task table
            ObservableList<JobMetrics> taskData = FXCollections.observableArrayList(stats.getTaskStatistics().values());
            tableTasks.setItems(taskData);

            // Build grouped counter sections
            buildGroupedCounters(stats.getCustomCounters());
        }
    }

    // ========================================================================
    // SUMMARY CARDS
    // ========================================================================

    private void buildSummaryCards(ProfilesData stats) {
        int totalRuns = 0;
        long totalTimeMs = 0;
        long totalOcrFails = 0;
        long totalImgFails = 0;

        for (JobMetrics task : stats.getTaskStatistics().values()) {
            totalRuns += task.getNumberOfRuns();
            totalTimeMs += task.getTotalExecutionTimeMs();
            totalOcrFails += task.getTotalOcrFailures();
            totalImgFails += task.getTotalTemplateSearchFailures();
        }

        String totalTimeStr;
        double totalHours = totalTimeMs / 3_600_000.0;
        if (totalHours >= 1.0) {
            totalTimeStr = String.format("%.1fh", totalHours);
        } else {
            totalTimeStr = String.format("%.0fm", totalTimeMs / 60_000.0);
        }

        String avgOcr = totalRuns > 0 ? String.format("%.2f", (double) totalOcrFails / totalRuns) : "0";
        String avgImg = totalRuns > 0 ? String.format("%.2f", (double) totalImgFails / totalRuns) : "0";
        int counterTotal = stats.getCustomCounters().values().stream().mapToInt(Integer::intValue).sum();

        hboxSummaryCards.getChildren().addAll(
                createSummaryCard("Total Runs", String.valueOf(totalRuns), "#4fc3f7"),
                createSummaryCard("Total Time", totalTimeStr, "#81c784"),
                createSummaryCard("Avg OCR Fail", avgOcr, "#ffb74d"),
                createSummaryCard("Avg Img Fail", avgImg, "#ff8a65"),
                createSummaryCard("Actions", String.valueOf(counterTotal), "#ba68c8")
        );
    }

    private VBox createSummaryCard(String title, String value, String accentColor) {
        VBox card = new VBox(3);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("stat-summary-card");
        HBox.setHgrow(card, Priority.ALWAYS);

        Label lblTitle = new Label(title.toUpperCase());
        lblTitle.getStyleClass().add("stat-title");

        Label lblValue = new Label(value);
        lblValue.getStyleClass().add("stat-value");
        lblValue.setStyle("-fx-text-fill: " + accentColor + ";");

        card.getChildren().addAll(lblTitle, lblValue);

        return card;
    }

    // ========================================================================
    // GROUPED COUNTER SECTIONS
    // ========================================================================

    private void buildGroupedCounters(Map<String, Integer> customCounters) {
        if (customCounters.isEmpty()) return;

        // Group counters by category
        Map<String, Map<String, Integer>> grouped = new LinkedHashMap<>();
        for (String cat : CATEGORY_ORDER) {
            grouped.put(cat, new LinkedHashMap<>());
        }

        for (Map.Entry<String, Integer> entry : customCounters.entrySet()) {
            String category = COUNTER_CATEGORIES.getOrDefault(entry.getKey(), "Other");
            grouped.computeIfAbsent(category, k -> new LinkedHashMap<>()).put(entry.getKey(), entry.getValue());
        }

        // Build TitledPanes per category
        for (String category : CATEGORY_ORDER) {
            Map<String, Integer> counters = grouped.get(category);
            if (counters == null || counters.isEmpty()) continue;

            FlowPane flowPane = new FlowPane();
            flowPane.setHgap(12);
            flowPane.setVgap(12);
            flowPane.setPadding(new Insets(8));

            for (Map.Entry<String, Integer> entry : counters.entrySet()) {
                flowPane.getChildren().add(createCounterCard(entry.getKey(), entry.getValue()));
            }

            TitledPane titledPane = new TitledPane(category + " (" + counters.size() + ")", flowPane);
            titledPane.setExpanded(true);
            titledPane.setCollapsible(true);
            titledPane.getStyleClass().add("stat-titled-pane");

            vboxCounterSections.getChildren().add(titledPane);
        }
    }

    private VBox createCounterCard(String name, Integer value) {
        VBox card = new VBox();
        card.setSpacing(6);
        card.getStyleClass().add("stat-counter-card");
        card.setAlignment(Pos.CENTER);

        Label lblName = new Label(name);
        lblName.getStyleClass().add("counter-name");
        lblName.setWrapText(true);
        lblName.setAlignment(Pos.CENTER);

        Label lblValue = new Label(String.valueOf(value));
        lblValue.getStyleClass().add("counter-value");

        card.getChildren().addAll(lblName, lblValue);

        return card;
    }

    // ========================================================================
    // EARNINGS (telemetry)
    // ========================================================================

    private void refreshEarnings() {
        // Loading by profile NAME let one profile's telemetry satisfy
        // another's (names are mutable/non-unique); TelemetryReport now loads by the profile's
        // stable numeric ID instead, from its own workspace-local file.
        long profileId = currentProfile != null && currentProfile.getId() != null
                ? currentProfile.getId() : NO_PROFILE_SELECTED_ID;
        long generation = ++loadGeneration;
        Thread.ofVirtual().name("statistics-load").start(() -> {
            TelemetryReport loaded = TelemetryReport.load(profileId);
            Platform.runLater(() -> {
                if (generation == loadGeneration) {
                    telemetry = loaded;
                    showActiveWindow();
                }
            });
        });
    }

    /** Renders the currently-selected timeframe. */
    private void showActiveWindow() {
        switch (activeWindow) {
            case PAST_HOUR -> {
                showWindow("Past hour",
                        telemetry.last(1, ChronoUnit.HOURS), telemetry.activityLast(1, ChronoUnit.HOURS),
                        telemetry.coverageForLast(1, ChronoUnit.HOURS),
                        telemetry.silenceForLast(1, ChronoUnit.HOURS));
            }
            case NIGHT -> {
                showWindow("Last night (" + SLEEP_START + "–" + WAKE_END + ")",
                        telemetry.lastNight(ZoneId.systemDefault(), SLEEP_START, WAKE_END),
                        telemetry.activityLastNight(ZoneId.systemDefault(), SLEEP_START, WAKE_END),
                        telemetry.coverageForLastNight(ZoneId.systemDefault(), SLEEP_START, WAKE_END),
                        telemetry.silenceForLastNight(ZoneId.systemDefault(), SLEEP_START, WAKE_END));
            }
            case LAST_24H -> {
                showWindow("Last 24 hours",
                        telemetry.last(24, ChronoUnit.HOURS), telemetry.activityLast(24, ChronoUnit.HOURS),
                        telemetry.coverageForLast(24, ChronoUnit.HOURS),
                        telemetry.silenceForLast(24, ChronoUnit.HOURS));
            }
            case THIS_WEEK -> {
                showWindow("This week (last 7 days)",
                        telemetry.last(7, ChronoUnit.DAYS), telemetry.activityLast(7, ChronoUnit.DAYS),
                        telemetry.coverageForLast(7, ChronoUnit.DAYS),
                        telemetry.silenceForLast(7, ChronoUnit.DAYS));
            }
            case THIS_MONTH -> {
                showWindow("This month (last 30 days)",
                        telemetry.last(30, ChronoUnit.DAYS), telemetry.activityLast(30, ChronoUnit.DAYS),
                        telemetry.coverageForLast(30, ChronoUnit.DAYS),
                        telemetry.silenceForLast(30, ChronoUnit.DAYS));
            }
            case TOTAL -> {
                showWindow("All recorded time", telemetry.total(), telemetry.activityTotal(),
                        telemetry.coverageForTotal(), telemetry.silenceForTotal());
            }
        }
    }

    // "I just don't trust these statistics... put like at the top the timeframe
    // that it was recorded." A window label like "Last night (23:00-08:30)" is the INTENDED
    // window, not proof of what was actually captured — this formats the REAL first/last sample
    // timestamps a window's numbers were built from, so a gapped or stale telemetry run is
    // visible right at the top instead of hidden behind a label that always looks the same.
    private static final DateTimeFormatter COVERAGE_FORMATTER = DateTimeFormatter.ofPattern("M/d h:mm a");

    private String formatCoverage(TelemetryReport.Coverage coverage) {
        if (coverage == null) {
            return null;
        }
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime from = ZonedDateTime.ofInstant(coverage.actualFrom(), zone);
        ZonedDateTime to = ZonedDateTime.ofInstant(coverage.actualTo(), zone);
        return "recorded " + from.format(COVERAGE_FORMATTER) + " → " + to.format(COVERAGE_FORMATTER);
    }

    /**
     * Describes a window the bot recorded nothing in, by naming when it last spoke and when it
     * next did. "Not enough samples yet" is what the page used to say about a night the machine
     * spent crashed, which reads as "still warming up" -- the opposite of the truth. Either
     * endpoint may be absent (no history before the window, or none since).
     */
    private String formatSilence(TelemetryReport.Coverage silence) {
        if (silence == null) {
            return null;
        }
        ZoneId zone = ZoneId.systemDefault();
        String last = silence.actualFrom() == null ? null
                : ZonedDateTime.ofInstant(silence.actualFrom(), zone).format(COVERAGE_FORMATTER);
        String next = silence.actualTo() == null ? null
                : ZonedDateTime.ofInstant(silence.actualTo(), zone).format(COVERAGE_FORMATTER);
        if (last != null && next != null) {
            return "nothing recorded in this window — the bot last reported " + last
                    + " and next reported " + next + ".";
        }
        if (last != null) {
            return "nothing recorded in this window — the bot last reported " + last
                    + " and has not reported since.";
        }
        return "nothing recorded in this window — the bot's first report was " + next + ".";
    }

    /** Rebuilds both the "earned" and "did" sections for the chosen window. */
    private void showWindow(String windowLabel, List<TelemetryReport.Delta> earned,
                            List<TelemetryReport.Activity> did, TelemetryReport.Coverage coverage,
                            TelemetryReport.Coverage silence) {
        boolean nothing = (earned == null || earned.isEmpty()) && (did == null || did.isEmpty());
        String silenceText = formatSilence(silence);

        boolean showEmptyNote = nothing && silenceText == null;
        lblEarningsEmpty.setVisible(showEmptyNote);
        lblEarningsEmpty.setManaged(showEmptyNote);

        String coverageSuffix = formatCoverage(coverage);
        if (silenceText != null) {
            lblWindow.setText(windowLabel + ": " + silenceText);
        } else {
            lblWindow.setText(coverageSuffix != null ? windowLabel + "  (" + coverageSuffix + ")" : windowLabel);
        }

        tileSpeedups.getChildren().clear();
        tilePowerGems.getChildren().clear();
        tileResources.getChildren().clear();
        Map<String, TelemetryReport.Delta> byMetric = new HashMap<>();
        if (earned != null) {
            for (TelemetryReport.Delta d : earned) byMetric.put(d.metric(), d);
        }
        TelemetryReport.Sample latest = telemetry.latest();
        for (String metric : TelemetryReport.METRICS) {
            Long current = latest == null ? null : latest.get(metric);
            if (current == null) continue; // metric never captured
            panelFor(metric).getChildren().add(metricTile(metric, byMetric.get(metric), current));
        }

        tileActivity.getChildren().clear();
        boolean anyActivity = did != null && !did.isEmpty();
        if (anyActivity) {
            for (TelemetryReport.Activity a : did) {
                tileActivity.getChildren().add(createStatTile(ACTIVITY_ICONS.get(a.label()), a.label(),
                        String.valueOf(a.change()), null, "#81c784", true));
            }
        }
        // A silent window gets the outage named, never "filling in", which would be false.
        lblActivityHint.setText(silenceText != null
                ? "No activity recorded: " + silenceText
                : "Filling in. This window needs a couple more sample cycles (the bot snapshots roughly"
                        + " every 2 hours). All time shows totals now.");
        lblActivityHint.setVisible(!anyActivity);
        lblActivityHint.setManaged(!anyActivity);
    }

    private TilePane panelFor(String metric) {
        if (metric.startsWith("sp_")) {
            return tileSpeedups;
        }
        return "power".equals(metric) || "gems".equals(metric) ? tilePowerGems : tileResources;
    }

    /**
     * Three distinct states: a measured change, measured with no change, and no reading at all.
     * Without a reading the headline is never a number, because the current stockpile shown there
     * read as a gain. Spending shows its minus sign in the metric's own colour, since it is not a fault.
     */
    private HBox metricTile(String metric, TelemetryReport.Delta delta, Long current) {
        boolean isSpeedup = metric.startsWith("sp_");
        java.util.function.Function<Long, String> amount = isSpeedup ? StatisticsLayoutController::fmtMinutes : StatisticsLayoutController::fmt;
        boolean measured = delta != null;
        boolean changed = measured && delta.change() != 0;

        String value = changed
                ? (isSpeedup ? fmtMinutesSigned(delta.change()) : fmtSigned(delta.change()))
                : measured ? NO_CHANGE_VALUE : NO_DATA_VALUE;
        String detail = measured
                ? amount.apply(delta.start()) + " to " + amount.apply(delta.end())
                : "No reading in this window. Current: " + amount.apply(current);
        String accent = changed ? METRIC_COLORS.getOrDefault(metric, "#4fc3f7") : NO_DATA_ACCENT;
        return createStatTile(METRIC_ICONS.get(metric), METRIC_LABELS.getOrDefault(metric, metric), value, detail, accent, changed);
    }

    private static final double TILE_ICON = 34;
    /** Headline for a metric that was measured and did not move. */
    private static final String NO_CHANGE_VALUE = "No change";
    /** Headline for a metric with no reading in the selected window. Never a number. */
    private static final String NO_DATA_VALUE = "no data";
    /** Muted colour for the two states that have no change to report. */
    private static final String NO_DATA_ACCENT = "#9eaab6";

    /** Labels keep their preferred width, and TilePane sizes every tile to the widest, so no value is cut off. */
    private HBox createStatTile(String iconKey, String title, String value, String detail, String accent, boolean strong) {
        StackPane iconSlot = new StackPane();
        iconSlot.setMinSize(TILE_ICON, TILE_ICON);
        iconSlot.setPrefSize(TILE_ICON, TILE_ICON);
        javafx.scene.Node iconNode = iconNodeFor(iconKey, accent);
        if (iconNode != null) iconSlot.getChildren().add(iconNode);

        Label lblValue = new Label(value);
        lblValue.getStyleClass().add(strong ? "stat-tile-value" : "stat-tile-value-quiet");
        lblValue.setStyle("-fx-text-fill: " + accent + ";");
        lblValue.setMinWidth(Region.USE_PREF_SIZE);

        Label lblTitle = new Label(title);
        lblTitle.getStyleClass().add("stat-tile-title");
        lblTitle.setMinWidth(Region.USE_PREF_SIZE);

        HBox tile = new HBox(8, iconSlot, new VBox(0, lblValue, lblTitle));
        tile.setAlignment(Pos.CENTER_LEFT);
        tile.getStyleClass().add("stat-tile");
        if (detail != null) {
            Tooltip.install(tile, new Tooltip(detail));
        }
        return tile;
    }

    /** Abbreviates a large number for display: 15685506 → "15.69M". */
    static String fmt(Long value) {
        if (value == null) return "—";
        long v = value;
        long abs = Math.abs(v);
        if (abs >= 1_000_000_000L) return trim(v / 1_000_000_000.0) + "B";
        if (abs >= 1_000_000L) return trim(v / 1_000_000.0) + "M";
        // Below a million, show the real number with commas — no "K". the operator: "million makes
        // sense, the K does not." 76944 → "76,944", not "76.04K".
        return String.format(Locale.US, "%,d", v);
    }

    static String fmtSigned(Long change) {
        if (change == null) return "—";
        String body = fmt(Math.abs(change));
        if (change > 0) return "+" + body;
        if (change < 0) return "-" + body;
        return "0";
    }

    /** Formats a speedup duration in minutes as "6d 3h", "1d 22h 31m", "45m". */
    static String fmtMinutes(Long minutes) {
        if (minutes == null) return "—";
        long m = Math.abs(minutes);
        long days = m / 1440; m %= 1440;
        long hours = m / 60; long mins = m % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append('d');
        if (hours > 0) { if (sb.length() > 0) sb.append(' '); sb.append(hours).append('h'); }
        // Always show minutes when nothing bigger applies, so "0" renders as "0m" not "".
        if (mins > 0 || sb.length() == 0) { if (sb.length() > 0) sb.append(' '); sb.append(mins).append('m'); }
        return sb.toString();
    }

    /** Signed speedup delta: "+3h 12m" gained, "-1d" spent, "0" unchanged. */
    static String fmtMinutesSigned(Long change) {
        if (change == null) return "—";
        if (change == 0) return "0";
        return (change > 0 ? "+" : "-") + fmtMinutes(Math.abs(change));
    }

    private static String trim(double d) {
        return String.format(Locale.US, "%.2f", d);
    }

    // ========================================================================
    // JSON PARSING
    // ========================================================================

    private ProfilesData parseJsonToStats(String json) {
        if (json == null || json.trim().isEmpty() || json.equals("{}")) {
            return new ProfilesData();
        }
        try {
            return objectMapper.readValue(json, ProfilesData.class);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            return new ProfilesData();
        }
    }
}
