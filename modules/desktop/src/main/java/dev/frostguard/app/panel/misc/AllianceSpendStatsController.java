package dev.frostguard.app.panel.misc;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.alliance.AllianceSpendReport;
import dev.frostguard.engine.alliance.AllianceSpendReport.BuyerTotal;
import dev.frostguard.engine.alliance.AllianceSpendReport.PriceSource;
import dev.frostguard.engine.alliance.AllianceSpendReport.Purchase;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.util.Callback;

/**
 * The Alliance Spend tab under Statistics: what the alliance bought through gift packs, read from
 * the Alliance Gifts ledger and priced against the deal scans. Kept apart from the resource
 * statistics because it reads other players' purchases, not what the bot earned.
 */
public class AllianceSpendStatsController {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private static final Map<String, Integer> WINDOWS = new LinkedHashMap<>();
    static {
        WINDOWS.put("Last 24 hours", 1);
        WINDOWS.put("Last 7 days", 7);
        WINDOWS.put("Last 30 days", 30);
        WINDOWS.put("All time", 0);
    }

    @FXML private ComboBox<String> comboWindow;
    @FXML private Label lblHeadline;
    @FXML private Label lblPeriods;
    @FXML private Label lblNote;
    @FXML private Label lblUnpriced;
    @FXML private TableView<BuyerTotal> tableBuyers;
    @FXML private TableColumn<BuyerTotal, String> colBuyer;
    @FXML private TableColumn<BuyerTotal, Number> colBuyerCount;
    @FXML private TableColumn<BuyerTotal, Number> colBuyerUsd;
    @FXML private TableColumn<BuyerTotal, Number> colBuyerKnown;
    @FXML private TableColumn<BuyerTotal, Number> colBuyerEst;
    @FXML private TableView<Purchase> tablePurchases;
    @FXML private TableColumn<Purchase, Purchase> colAt;
    @FXML private TableColumn<Purchase, String> colWho;
    @FXML private TableColumn<Purchase, String> colPack;
    @FXML private TableColumn<Purchase, Double> colUsd;
    @FXML private TableColumn<Purchase, String> colSource;

    private AllianceSpendReport report;

    @FXML
    private void initialize() {
        colBuyer.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().buyer()));
        colBuyerCount.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().purchases()));
        colBuyerUsd.setCellValueFactory(c -> new SimpleDoubleProperty(c.getValue().usd()));
        colBuyerUsd.setCellFactory(shownAs(usd -> money(usd.doubleValue())));
        colBuyerKnown.setCellValueFactory(c -> new SimpleDoubleProperty(c.getValue().knownUsd()));
        colBuyerKnown.setCellFactory(shownAs(usd -> money(usd.doubleValue())));
        colBuyerEst.setCellValueFactory(c -> new SimpleDoubleProperty(c.getValue().estimatedUsd()));
        colBuyerEst.setCellFactory(shownAs(usd -> money(usd.doubleValue())));
        colAt.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue()));
        colAt.setComparator(Comparator.comparing(Purchase::at));
        colAt.setCellFactory(shownAs(AllianceSpendStatsController::logged));
        colWho.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().anonymous() || c.getValue().buyer() == null ? "(anonymous)" : c.getValue().buyer()));
        colPack.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().pack()));
        colUsd.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().usd()));
        colUsd.setCellFactory(shownAs(usd -> usd == null ? "?" : money(usd)));
        colSource.setCellValueFactory(c -> new SimpleStringProperty(sourceLabel(c.getValue().source())
                + (c.getValue().priceNote() == null ? "" : " (" + c.getValue().priceNote() + ")")));

        comboWindow.getItems().setAll(WINDOWS.keySet());
        comboWindow.getSelectionModel().select("Last 7 days");
        comboWindow.valueProperty().addListener((obs, was, now) -> show());
        reload();
    }

    @FXML
    private void handleRefresh(ActionEvent event) {
        reload();
    }

    /** Reads the ledger off the UI thread; it is a file the chest task appends to. */
    private void reload() {
        Thread loader = new Thread(() -> {
            AllianceSpendReport loaded;
            try {
                loaded = AllianceSpendReport.load(WorkspacePaths.current().root());
            } catch (Exception e) {
                Platform.runLater(() -> {
                    lblHeadline.setText("Could not read the Alliance Spend log");
                    lblNote.setText(e.getMessage());
                });
                return;
            }
            Platform.runLater(() -> {
                report = loaded;
                show();
            });
        }, "alliance-spend-stats");
        loader.setDaemon(true);
        loader.start();
    }

    private void show() {
        if (report == null) {
            return;
        }
        Integer days = WINDOWS.getOrDefault(comboWindow.getValue(), 7);
        LocalDateTime now = LocalDateTime.now();
        List<Purchase> shown = report.since(days == 0 ? null : now.minusDays(days));

        double known = AllianceSpendReport.usd(shown, false);
        double estimated = AllianceSpendReport.usd(shown, true) - known;
        lblHeadline.setText(shown.size() + " purchases · " + money(known) + " exact + ~" + money(estimated) + " estimated");
        lblPeriods.setText(periodLine("24h", report.since(now.minusDays(1)))
                + "     " + periodLine("7 days", report.since(now.minusDays(7)))
                + "     " + periodLine("30 days", report.since(now.minusDays(30))));

        long backlog = shown.stream().filter(Purchase::backlog).count();
        long unpriceable = AllianceSpendReport.unpriceable(shown);
        List<String> notes = new ArrayList<>(List.of(
                "Total = Exact + Estimated.",
                "Exact: the pack's price is in the price file or the deal scan.",
                "Estimated: no exact price, so it is guessed from the deal scan or from the size of the gift it sent."));
        if (unpriceable > 0) {
            notes.add(unpriceable + " purchase(s) have no gift size and no scanned price and are not in the totals.");
        }
        if (report.unreadableLines() > 0) {
            notes.add(report.unreadableLines() + " unreadable line(s) in gifts.jsonl were skipped.");
        }
        if (backlog > 0) {
            notes.add("* " + backlog + " were already in the list when logging began, so their date is when they were first seen.");
        }
        lblNote.setText(notes.stream().map(n -> "• " + n).collect(Collectors.joining("\n")));

        tableBuyers.setItems(FXCollections.observableArrayList(AllianceSpendReport.byBuyer(shown)));
        tablePurchases.setItems(FXCollections.observableArrayList(shown));

        Map<String, Integer> unpriced = AllianceSpendReport.unpricedPacks(shown);
        lblUnpriced.setText(unpriced.isEmpty() ? "None."
                : unpriced.entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + ")")
                        .collect(Collectors.joining(", ")));
    }

    /** The day a purchase was logged, starred when it was already in the list as logging began. */
    static String logged(Purchase purchase) {
        return purchase.at().format(WHEN) + (purchase.backlog() ? " *" : "");
    }

    /** Shows a value as text while the column keeps sorting on the value itself, not on that text. */
    private static <S, T> Callback<TableColumn<S, T>, TableCell<S, T>> shownAs(Function<T, String> text) {
        return column -> new TableCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : text.apply(item));
            }
        };
    }

    private static String periodLine(String label, List<Purchase> list) {
        return label + ": " + list.size() + " · ~" + money(AllianceSpendReport.usd(list, true));
    }

    private static String money(double usd) {
        return String.format("$%,.2f", usd);
    }

    private static String sourceLabel(PriceSource source) {
        return switch (source) {
            case OPERATOR -> "price file";
            case DEAL_SCAN -> "deal scan";
            case SCAN_RANGE -> "estimate: scan range";
            case TIER_ESTIMATE -> "estimate: gift size";
            case NONE -> "-";
        };
    }
}
