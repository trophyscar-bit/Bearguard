package dev.frostguard.app.panel.deals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import dev.frostguard.api.deals.DealItem;
import dev.frostguard.api.deals.DealPriceTracker;
import dev.frostguard.api.deals.DealScan;
import dev.frostguard.api.deals.DealScoring;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.deals.DealsStore;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Deal Tracker page: the daily {@code bg_deals_telemetry} scans, as pack verdicts and a per-item
 * price tracker.
 *
 * <p>Polls the scan files like the Upcoming Events page polls its cache: they change once a day,
 * reading them is cheap, and a push listener across the custom-task boundary buys nothing.</p>
 */
public class DealTrackerLayoutController {

    private static final int REFRESH_SECONDS = 60;
    private static final int HISTORY_DAYS = 400;
    private static final int WEEK_DAYS = 7;
    private static final int WEEK_ROWS = 8;
    private static final int PICKER_ICON_SIZE = 28;
    private static final int MOVING_AVERAGE_DAYS = 7;
    private static final DateTimeFormatter CHART_DAY = DateTimeFormatter.ofPattern("MMM d");

    @FXML
    private Label labelScanStatus;
    @FXML
    private Label labelLatestHeader;
    @FXML
    private Label labelValuesHelp;
    @FXML
    private VBox verdictContainer;
    @FXML
    private VBox latestContainer;
    @FXML
    private VBox weekContainer;
    @FXML
    private VBox itemsContainer;
    @FXML
    private VBox problemsCard;
    @FXML
    private VBox problemsContainer;
    @FXML
    private Button buttonRefresh;
    @FXML
    private FlowPane itemPicker;
    @FXML
    private ComboBox<String> comboItem;
    @FXML
    private VBox trackerStats;
    @FXML
    private LineChart<String, Number> priceChart;

    private final DealsStore store = DealsStore.forWorkspace(WorkspacePaths.current().root());
    private Timeline refreshTimer;
    private List<DealPriceTracker.Observation> observations = List.of();
    private String selectedItem;
    private boolean updatingPicker;

    @FXML
    private void initialize() {
        comboItem.valueProperty().addListener((obs, was, now) -> {
            if (!updatingPicker && now != null) {
                selectItem(now);
            }
        });
        refresh();
        refreshTimer = new Timeline(new KeyFrame(javafx.util.Duration.seconds(REFRESH_SECONDS), e -> refresh()));
        refreshTimer.setCycleCount(Animation.INDEFINITE);
        refreshTimer.play();
    }

    @FXML
    private void handleRefresh() {
        refresh();
    }

    public void stopAutoRefresh() {
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
    }

    private void refresh() {
        verdictContainer.getChildren().clear();
        latestContainer.getChildren().clear();
        weekContainer.getChildren().clear();
        itemsContainer.getChildren().clear();
        problemsContainer.getChildren().clear();

        DealsStore.Loaded loaded = store.readSince(LocalDate.now().minusDays(HISTORY_DAYS));
        List<String> pageProblems = new ArrayList<>(loaded.problems());
        Map<String, Double> values;
        try {
            values = store.readItemValues();
        } catch (IOException unreadable) {
            values = Map.of();
            pageProblems.add("item-values.json is not valid JSON and was ignored: " + unreadable.getMessage());
        }
        labelValuesHelp.setText("Fitted from every pack seen, and refitted each refresh as prices come in. To pin an "
                + "item's price yourself, put dollars per unit in " + store.itemValuesFile()
                + ", e.g. {\"1h Speedup\": 0.30}. Item icons live in " + store.itemIconsDir()
                + "; add a PNG there to start tracking a new item.");

        if (loaded.scans().isEmpty()) {
            labelScanStatus.setText("No scans yet. The bg_deals_telemetry custom task runs daily at about 20:30 and "
                    + "writes to " + store.scansDir() + ".");
            observations = List.of();
            rebuildPicker();
            showProblems(pageProblems);
            return;
        }

        LocalDate latestDay = new TreeMap<>(loaded.scans()).lastKey();
        DealScan latest = loaded.scans().get(latestDay);
        labelScanStatus.setText("Last scan " + latest.scannedAt().replace('T', ' ').replaceAll("\\.\\d+$", "")
                + ": " + latest.offers().size() + " offers, " + latest.problems().size() + " unread item(s). "
                + loaded.scans().size() + " day(s) of history.");
        labelLatestHeader.setText("Latest scan (" + latestDay + ")");

        List<DealScoring.ScoredOffer> scored = DealScoring.score(loaded.scans(), values);
        List<DealScoring.ScoredOffer> latestOffers = scored.stream()
                .filter(o -> o.day().equals(latestDay))
                .sorted(byScoreThenPrice())
                .toList();
        List<DealScoring.ScoredOffer> week = scored.stream()
                .filter(o -> o.score() != null && !o.day().isBefore(latestDay.minusDays(WEEK_DAYS - 1L)))
                .sorted(byScoreThenPrice())
                .toList();

        verdictContainer.getChildren().add(summaryLine("Best in the latest scan",
                latestOffers.stream().filter(o -> o.score() != null).findFirst().orElse(null)));
        verdictContainer.getChildren().add(summaryLine("Best this week", week.isEmpty() ? null : week.get(0)));
        latestOffers.stream().filter(o -> o.score() != null).reduce((first, second) -> second)
                .ifPresent(worst -> verdictContainer.getChildren().add(summaryLine("Worst in the latest scan", worst)));

        latestOffers.forEach(o -> latestContainer.getChildren().add(offerRow(o)));
        week.stream().limit(WEEK_ROWS).forEach(o -> weekContainer.getChildren().add(offerRow(o)));
        if (week.isEmpty()) {
            weekContainer.getChildren().add(description("No scored offers yet: scores need readable prices and items."));
        }

        DealScoring.itemRates(loaded.scans(), values).forEach(rate -> itemsContainer.getChildren().add(description(
                rate.key() + ": " + usd(rate.usdPerUnit()) + " per unit, fitted over " + rate.observations()
                        + " pack(s)" + (rate.operatorUsdPerUnit() != null ? " (your value)" : ""))));

        observations = DealPriceTracker.observations(loaded.scans(), values);
        rebuildPicker();
        showProblems(withScanProblems(pageProblems, latest.problems()));
    }

    // ── price tracker ───────────────────────────────────────────────

    private void rebuildPicker() {
        List<String> items = observations.stream().map(DealPriceTracker.Observation::item).distinct().sorted().toList();
        updatingPicker = true;
        comboItem.getItems().setAll(items);
        itemPicker.getChildren().clear();
        for (String item : items) {
            Button button = new Button(item);
            iconFor(item).ifPresent(icon -> button.setGraphic(icon));
            button.setTooltip(new Tooltip("Show the price history of " + item));
            button.setOnAction(e -> selectItem(item));
            itemPicker.getChildren().add(button);
        }
        if (selectedItem == null || !items.contains(selectedItem)) {
            selectedItem = items.isEmpty() ? null : items.get(0);
        }
        comboItem.setValue(selectedItem);
        updatingPicker = false;
        renderTracker();
    }

    private void selectItem(String item) {
        selectedItem = item;
        updatingPicker = true;
        comboItem.setValue(item);
        updatingPicker = false;
        renderTracker();
    }

    private void renderTracker() {
        trackerStats.getChildren().clear();
        priceChart.getData().clear();
        DealPriceTracker.Summary summary = selectedItem == null ? null
                : DealPriceTracker.summarize(selectedItem, observations, LocalDate.now());
        boolean hasData = summary != null;
        priceChart.setVisible(hasData);
        priceChart.setManaged(hasData);
        if (!hasData) {
            trackerStats.getChildren().add(description("No item prices yet: an item is tracked once a priced pack "
                    + "containing it has been read."));
            return;
        }

        trackerStats.getChildren().add(stat("Latest", priceLine(summary.latest())));
        trackerStats.getChildren().add(stat("Cheapest seen", priceLine(summary.cheapest())));
        trackerStats.getChildren().add(stat("Most expensive seen", priceLine(summary.priciest())));
        trackerStats.getChildren().add(stat("Average", usd(summary.averageUsd()) + " per unit"
                + (summary.averageGems() == null ? "" : " (" + gems(summary.averageGems()) + ")")
                + " over " + summary.observations() + " sighting(s)"));
        trackerStats.getChildren().add(stat("Last 7 days", summary.last7AverageUsd() == null ? "not seen"
                : usd(summary.last7AverageUsd()) + " average"));
        trackerStats.getChildren().add(stat("Last 30 days", summary.last30AverageUsd() == null ? "not seen"
                : usd(summary.last30AverageUsd()) + " average"));
        Double change = summary.monthOverMonthChange();
        trackerStats.getChildren().add(stat("This month vs last", change == null ? "needs two months of scans"
                : String.format(Locale.US, "%+.1f%%", change * 100)
                + (change < 0 ? " (getting cheaper)" : change > 0 ? " (getting pricier)" : "")));
        String months = summary.monthlyAverageUsd().entrySet().stream()
                .map(e -> e.getKey() + " " + usd(e.getValue()))
                .collect(Collectors.joining(" · "));
        trackerStats.getChildren().add(stat("Monthly averages", months));

        XYChart.Series<String, Number> cheapest = new XYChart.Series<>();
        cheapest.setName("Cheapest that day");
        XYChart.Series<String, Number> moving = new XYChart.Series<>();
        moving.setName(MOVING_AVERAGE_DAYS + "-day average");
        List<Map.Entry<LocalDate, Double>> days = new ArrayList<>(summary.dailyCheapestUsd().entrySet());
        for (int i = 0; i < days.size(); i++) {
            LocalDate day = days.get(i).getKey();
            String label = CHART_DAY.format(day);
            cheapest.getData().add(new XYChart.Data<>(label, days.get(i).getValue()));
            LocalDate from = day.minusDays(MOVING_AVERAGE_DAYS - 1L);
            double average = days.stream().filter(d -> !d.getKey().isBefore(from) && !d.getKey().isAfter(day))
                    .mapToDouble(Map.Entry::getValue).average().orElse(days.get(i).getValue());
            moving.getData().add(new XYChart.Data<>(label, average));
        }
        priceChart.getData().add(cheapest);
        priceChart.getData().add(moving);
    }

    private String priceLine(DealPriceTracker.Observation observation) {
        return usd(observation.unitUsd()) + " per unit"
                + (observation.unitGems() == null ? "" : " (" + gems(observation.unitGems()) + ")")
                + " in " + observation.pack() + " at " + usd(observation.packPriceUsd()) + " for "
                + String.format(Locale.US, "%,d", observation.quantity()) + ", " + observation.day();
    }

    private java.util.Optional<ImageView> iconFor(String item) {
        for (String name : List.of(item + ".png", item.replace(' ', '_') + ".png")) {
            Path file = store.itemIconsDir().resolve(name);
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    ImageView view = new ImageView(new Image(in));
                    view.setFitHeight(PICKER_ICON_SIZE);
                    view.setPreserveRatio(true);
                    return java.util.Optional.of(view);
                } catch (IOException unreadable) {
                    return java.util.Optional.empty();
                }
            }
        }
        return java.util.Optional.empty();
    }

    // ── offers ──────────────────────────────────────────────────────

    private static Comparator<DealScoring.ScoredOffer> byScoreThenPrice() {
        return Comparator.<DealScoring.ScoredOffer, Double>comparing(o -> o.score() == null ? -1 : o.score())
                .reversed()
                .thenComparing(o -> o.offer().priceUsd() == null ? Double.MAX_VALUE : o.offer().priceUsd());
    }

    private HBox summaryLine(String heading, DealScoring.ScoredOffer offer) {
        return stat(heading, offer == null ? "nothing scored yet"
                : offer.offer().title() + " " + price(offer) + " -- score " + format(offer.score()) + " ("
                + offer.verdict() + ", " + offer.day() + ")");
    }

    private VBox offerRow(DealScoring.ScoredOffer scored) {
        Label verdict = new Label(scored.verdict());
        verdict.setMinWidth(140);
        verdict.setStyle("-fx-font-weight: bold; -fx-text-fill: " + verdictColour(scored.verdict()) + ";");
        Label title = new Label(scored.offer().title() + "  " + price(scored)
                + (scored.score() == null ? "" : "   score " + format(scored.score())));
        title.setStyle("-fx-font-weight: bold;");
        title.setWrapText(true);
        HBox top = new HBox(10, verdict, title);
        HBox.setHgrow(title, Priority.ALWAYS);

        StringBuilder detail = new StringBuilder(scored.offer().surface());
        if (scored.offer().tab() != null && !scored.offer().tab().isBlank()
                && !scored.offer().tab().equals(scored.offer().title())) {
            detail.append(" > ").append(scored.offer().tab());
        }
        if (scored.offer().remaining() != null) {
            detail.append(" · ").append(scored.offer().remaining()).append(" left");
        }
        if (scored.basis() != null) {
            detail.append(" · ").append(scored.basis());
        }
        detail.append(" · seen ").append(scored.daysSeen()).append(scored.daysSeen() == 1 ? " day" : " days");
        if (scored.lowestEarlierPrice() != null && scored.offer().priceUsd() != null) {
            double before = scored.lowestEarlierPrice();
            detail.append(scored.offer().priceUsd() < before ? " · cheaper than ever (" + usd(before) + " before)"
                    : " · lowest before " + usd(before));
        }
        String items = scored.offer().items().stream()
                .map(DealTrackerLayoutController::itemText)
                .collect(Collectors.joining(", "));
        VBox row = new VBox(2, top, description(detail.toString()),
                description(items.isEmpty() ? "items not read" : items));
        row.setStyle("-fx-padding: 4 0 6 0;");
        return row;
    }

    private static String itemText(DealItem item) {
        return item.key() + " x" + String.format(Locale.US, "%,d", item.quantity());
    }

    private static String price(DealScoring.ScoredOffer scored) {
        if (scored.offer().priceUsd() != null) {
            return usd(scored.offer().priceUsd());
        }
        return scored.offer().priceText() == null || scored.offer().priceText().isBlank()
                ? "(price unread)" : "(" + scored.offer().priceText() + ")";
    }

    private static String verdictColour(String verdict) {
        if (verdict.startsWith("Best")) return "#4caf50";
        if (verdict.endsWith("Great")) return "#8bc34a";
        if (verdict.endsWith("Fair")) return "#ffc107";
        if (verdict.endsWith("Bad deal")) return "#f44336";
        return "#9e9e9e";
    }

    private static HBox stat(String heading, String body) {
        Label head = new Label(heading + ":");
        head.setStyle("-fx-font-weight: bold;");
        head.setMinWidth(150);
        Label text = new Label(body);
        text.setWrapText(true);
        HBox line = new HBox(8, head, text);
        HBox.setHgrow(text, Priority.ALWAYS);
        return line;
    }

    private static Label description(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("task-card-description");
        return label;
    }

    /** Unit prices of bulk items run to fractions of a cent, so small values keep more digits. */
    private static String usd(double value) {
        return value >= 0.1 ? String.format(Locale.US, "$%.2f", value) : String.format(Locale.US, "$%.5f", value);
    }

    private static String gems(double value) {
        return value >= 10 ? String.format(Locale.US, "%,.0f gems", value) : String.format(Locale.US, "%.2f gems", value);
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static List<String> withScanProblems(List<String> pageProblems, List<String> scanProblems) {
        List<String> all = new ArrayList<>(scanProblems);
        all.addAll(pageProblems);
        return all;
    }

    private void showProblems(List<String> problems) {
        problems.forEach(p -> problemsContainer.getChildren().add(description(p)));
        problemsCard.setVisible(!problems.isEmpty());
        problemsCard.setManaged(!problems.isEmpty());
    }
}
