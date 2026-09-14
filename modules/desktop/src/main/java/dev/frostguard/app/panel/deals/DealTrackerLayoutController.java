package dev.frostguard.app.panel.deals;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import dev.frostguard.api.deals.DealItem;
import dev.frostguard.api.deals.DealScan;
import dev.frostguard.api.deals.DealScoring;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.deals.DealsStore;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Deal Tracker page: the daily {@code bg_deals} scans, scored by {@link DealScoring}.
 *
 * <p>Polls the scan files like the Upcoming Events page polls its cache: they change once a day,
 * reading them is cheap, and a push listener across the custom-task boundary buys nothing.</p>
 */
public class DealTrackerLayoutController {

    private static final int REFRESH_SECONDS = 60;
    private static final int HISTORY_DAYS = 30;
    private static final int WEEK_DAYS = 7;
    private static final int WEEK_ROWS = 8;

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

    private final DealsStore store = DealsStore.forWorkspace(WorkspacePaths.current().root());
    private Timeline refreshTimer;

    @FXML
    private void initialize() {
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
        Map<String, Double> values;
        String valuesProblem = null;
        try {
            values = store.readItemValues();
        } catch (IOException unreadable) {
            values = Map.of();
            valuesProblem = "item-values.json is not valid JSON and was ignored: " + unreadable.getMessage();
        }
        labelValuesHelp.setText("Learned from every pack seen: how many of each item a dollar usually buys. "
                + "To use your own prices instead, put dollars per unit in " + store.itemValuesFile()
                + ", e.g. {\"1h Speedup\": 0.30}. Item icons live in " + store.itemIconsDir() + ".");

        if (loaded.scans().isEmpty()) {
            labelScanStatus.setText("No scans yet. The bg_deals custom task runs daily at about 20:30 and writes to "
                    + store.scansDir() + ".");
            showProblems(withValuesProblem(loaded.problems(), valuesProblem));
            return;
        }

        List<DealScoring.ScoredOffer> scored = DealScoring.score(loaded.scans(), values);
        LocalDate latestDay = ((java.util.TreeMap<LocalDate, DealScan>) new java.util.TreeMap<>(loaded.scans()))
                .lastKey();
        DealScan latest = loaded.scans().get(latestDay);
        labelScanStatus.setText("Last scan " + latest.scannedAt().replace('T', ' ').replaceAll("\\.\\d+$", "")
                + ": " + latest.offers().size() + " offers, " + latest.problems().size() + " unread item(s). "
                + loaded.scans().size() + " day(s) of history.");
        labelLatestHeader.setText("Latest scan (" + latestDay + ")");

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

        Map<String, Double> manual = values;
        DealScoring.itemRates(loaded.scans(), values).forEach(rate -> itemsContainer.getChildren().add(description(
                rate.key() + ": " + format(rate.medianPerDollar()) + " per $ (median of " + rate.observations()
                        + ")" + (manual.containsKey(rate.key()) ? ", your value $" + format(manual.get(rate.key()))
                        + " each" : ""))));

        showProblems(withValuesProblem(latest.problems(), valuesProblem));
    }

    private static Comparator<DealScoring.ScoredOffer> byScoreThenPrice() {
        return Comparator.<DealScoring.ScoredOffer, Double>comparing(o -> o.score() == null ? -1 : o.score())
                .reversed()
                .thenComparing(o -> o.offer().priceUsd() == null ? Double.MAX_VALUE : o.offer().priceUsd());
    }

    private HBox summaryLine(String heading, DealScoring.ScoredOffer offer) {
        Label head = new Label(heading + ":");
        head.setStyle("-fx-font-weight: bold;");
        Label body = new Label(offer == null ? "nothing scored yet"
                : offer.offer().title() + " " + price(offer) + " -- " + format(offer.score()) + " (" + offer.verdict()
                + ", " + offer.day() + ")");
        body.setWrapText(true);
        HBox line = new HBox(8, head, body);
        HBox.setHgrow(body, Priority.ALWAYS);
        return line;
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
            detail.append(scored.offer().priceUsd() < before ? " · cheaper than ever ($" + format(before) + " before)"
                    : " · lowest before $" + format(before));
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
            return "$" + format(scored.offer().priceUsd());
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

    private static Label description(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("task-card-description");
        return label;
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static List<String> withValuesProblem(List<String> problems, String valuesProblem) {
        if (valuesProblem == null) {
            return problems;
        }
        List<String> all = new java.util.ArrayList<>(problems);
        all.add(valuesProblem);
        return all;
    }

    private void showProblems(List<String> problems) {
        problems.forEach(p -> problemsContainer.getChildren().add(description(p)));
        problemsCard.setVisible(!problems.isEmpty());
        problemsCard.setManaged(!problems.isEmpty());
    }
}
