package dev.frostguard.app.panel.misc;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.alliance.AllianceSpendReport;
import dev.frostguard.engine.alliance.AllianceSpendReport.BuyerTotal;
import dev.frostguard.engine.alliance.AllianceSpendReport.PriceSource;
import dev.frostguard.engine.alliance.AllianceSpendReport.Purchase;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

/**
 * The Alliance Spend tab under Statistics: what the alliance bought through gift packs, read from
 * the Alliance Gifts ledger and priced against the deal scans. Kept apart from the resource
 * statistics because it reads other players' purchases, not what the bot earned.
 */
public class AllianceSpendStatsController {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d HH:mm");

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
    @FXML private TableColumn<BuyerTotal, String> colBuyerCount;
    @FXML private TableColumn<BuyerTotal, String> colBuyerUsd;
    @FXML private TableColumn<BuyerTotal, String> colBuyerEst;
    @FXML private TableView<Purchase> tablePurchases;
    @FXML private TableColumn<Purchase, String> colAt;
    @FXML private TableColumn<Purchase, String> colWho;
    @FXML private TableColumn<Purchase, String> colPack;
    @FXML private TableColumn<Purchase, String> colTier;
    @FXML private TableColumn<Purchase, String> colUsd;
    @FXML private TableColumn<Purchase, String> colSource;

    private AllianceSpendReport report;

    @FXML
    private void initialize() {
        colBuyer.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().buyer()));
        colBuyerCount.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().purchases())));
        colBuyerUsd.setCellValueFactory(c -> new SimpleStringProperty(money(c.getValue().usd())));
        colBuyerEst.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().estimated() == 0 ? "-" : c.getValue().estimated() + " of " + c.getValue().purchases()));
        colAt.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().at().format(WHEN) + (c.getValue().backlog() ? " *" : "")));
        colWho.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().anonymous() || c.getValue().buyer() == null ? "(anonymous)" : c.getValue().buyer()));
        colPack.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().pack()));
        colTier.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().tier() == null ? "?" : c.getValue().tier().replace("Alliance Gift ", "Tier ")));
        colUsd.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().usd() == null ? "?" : money(c.getValue().usd())));
        colSource.setCellValueFactory(c -> new SimpleStringProperty(sourceLabel(c.getValue().source())));

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

        lblHeadline.setText(shown.size() + " purchases · " + money(AllianceSpendReport.usd(shown, true)));
        lblPeriods.setText(periodLine("24h", report.since(now.minusDays(1)))
                + "     " + periodLine("7 days", report.since(now.minusDays(7)))
                + "     " + periodLine("30 days", report.since(now.minusDays(30))));

        long estimated = shown.stream().filter(p -> p.source() == PriceSource.TIER_ESTIMATE).count();
        long backlog = shown.stream().filter(Purchase::backlog).count();
        StringBuilder note = new StringBuilder();
        note.append(money(AllianceSpendReport.usd(shown, false))).append(" of that is from known pack prices; ")
                .append(estimated).append(" of ").append(shown.size())
                .append(" purchases are priced by gift tier (Tier I = $4.99 measured; II-IV assumed).");
        if (backlog > 0) {
            note.append(" * ").append(backlog).append(" were already in the list when logging began, so their time is when they were first seen.");
        }
        lblNote.setText(note.toString());

        tableBuyers.setItems(FXCollections.observableArrayList(AllianceSpendReport.byBuyer(shown)));
        tablePurchases.setItems(FXCollections.observableArrayList(shown));

        Map<String, Integer> unpriced = AllianceSpendReport.unpricedPacks(shown);
        lblUnpriced.setText(unpriced.isEmpty() ? "None."
                : unpriced.entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + ")")
                        .collect(Collectors.joining(", ")));
    }

    private static String periodLine(String label, List<Purchase> list) {
        return label + ": " + list.size() + " · " + money(AllianceSpendReport.usd(list, true));
    }

    private static String money(double usd) {
        return String.format("$%,.2f", usd);
    }

    private static String sourceLabel(PriceSource source) {
        return switch (source) {
            case OPERATOR -> "price file";
            case DEAL_SCAN -> "deal scan";
            case TIER_ESTIMATE -> "tier estimate";
            case NONE -> "-";
        };
    }
}
