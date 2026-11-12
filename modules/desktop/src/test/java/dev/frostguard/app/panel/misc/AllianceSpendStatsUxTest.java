package dev.frostguard.app.panel.misc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.app.shared.JavaFxToolkit;
import dev.frostguard.engine.alliance.AllianceSpendReport.BuyerTotal;
import dev.frostguard.engine.alliance.AllianceSpendReport.Purchase;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

/** The Alliance Spend tables must sort amounts and dates by value, not by their text. */
class AllianceSpendStatsUxTest {

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
    void priceColumnsSortByAmountNotByText() throws Exception {
        Map<String, Object> view = open();
        TableView<Purchase> purchases = table(view, "tablePurchases");
        TableView<BuyerTotal> buyers = table(view, "tableBuyers");

        sortAscending(purchases, view.get("colUsd"));
        assertEquals(List.of(4.99, 9.99, 15.99, 100.0), onFx(() -> purchases.getItems().stream().map(Purchase::usd).toList()));

        sortAscending(buyers, view.get("colBuyerUsd"));
        assertEquals(List.of("Alder", "Brook", "Cedar"), onFx(() -> buyers.getItems().stream().map(BuyerTotal::buyer).toList()));
    }

    @Test
    void loggedShowsOnlyTheDayAndSortsByDateAcrossYears() throws Exception {
        Map<String, Object> view = open();
        TableView<Purchase> purchases = table(view, "tablePurchases");

        sortAscending(purchases, view.get("colAt"));

        assertEquals(List.of("12/30/2025", "01/02/2026 *", "02/04/2026", "11/01/2026"), onFx(() -> purchases.getItems()
                .stream().map(AllianceSpendStatsController::logged).toList()));
    }

    @Test
    void columnsSayWhatTheyHoldAndTheGiftTierIsGone() throws Exception {
        Map<String, Object> view = open();

        assertEquals(List.of("Logged", "Buyer", "Pack", "USD", "Price from"), headers(table(view, "tablePurchases")));
        assertEquals(List.of("Buyer", "Purchases", "Total", "Exact", "Estimated"), headers(table(view, "tableBuyers")));
        String note = ((javafx.scene.control.Label) view.get("lblNote")).getText();
        assertTrue(note.startsWith("• Total = Exact + Estimated.\n"), note);
    }

    private Map<String, Object> open() throws Exception {
        seed();
        previousWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, workspace.toString());
        Map<String, Object> view = onFx(() -> {
            FXMLLoader loader = new FXMLLoader(AllianceSpendStatsController.class.getResource("/layout/AllianceSpendStatsLayout.fxml"));
            loader.load();
            @SuppressWarnings("unchecked")
            ComboBox<String> window = (ComboBox<String>) loader.getNamespace().get("comboWindow");
            window.setValue("All time");
            return loader.getNamespace();
        });
        TableView<Purchase> purchases = table(view, "tablePurchases");
        long deadline = System.currentTimeMillis() + 10_000;
        while (onFx(() -> purchases.getItems().size()) < 4 && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(4, onFx(() -> purchases.getItems().size()), "ledger loaded");
        return view;
    }

    private void seed() throws Exception {
        Path spend = Files.createDirectories(workspace.resolve("data/alliance-spend"));
        Files.writeString(spend.resolve("pack-prices.json"), """
                {"Arcane Pack": 9.99, "Frost Bundle": 15.99, "Lucky Chest": 4.99, "Hero Rally Pack": 100.00}""");
        Files.writeString(spend.resolve("gifts.jsonl"), String.join("\n",
                "{\"capturedAt\": \"2026-11-01T09:00\", \"buyer\": \"Cedar\", \"pack\": \"Hero Rally Pack\"}",
                "{\"capturedAt\": \"2026-02-04T12:00\", \"buyer\": \"Cedar\", \"pack\": \"Lucky Chest\"}",
                "{\"capturedAt\": \"2026-01-02T08:00\", \"buyer\": \"Brook\", \"pack\": \"Frost Bundle\", \"backlog\": true}",
                "{\"capturedAt\": \"2025-12-30T10:00\", \"buyer\": \"Alder\", \"pack\": \"Arcane Pack\"}"));
    }

    private static <S> void sortAscending(TableView<S> table, Object column) throws Exception {
        @SuppressWarnings("unchecked")
        TableColumn<S, ?> col = (TableColumn<S, ?>) column;
        onFx(() -> {
            col.setSortType(TableColumn.SortType.ASCENDING);
            table.getSortOrder().setAll(List.of(col));
            table.sort();
            return null;
        });
    }

    private static List<String> headers(TableView<?> table) throws Exception {
        return onFx(() -> table.getColumns().stream().map(TableColumn::getText).toList());
    }

    @SuppressWarnings("unchecked")
    private static <S> TableView<S> table(Map<String, Object> view, String id) {
        return (TableView<S>) view.get(id);
    }

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
