package dev.frostguard.engine.deals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.deals.DealItem;
import dev.frostguard.api.deals.DealOffer;
import dev.frostguard.api.deals.DealScan;

class DealsStoreTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 13);

    @Test
    void roundTripsAScanAndReportsACorruptDayInsteadOfDroppingIt(@TempDir Path workspace) throws Exception {
        DealsStore store = DealsStore.forWorkspace(workspace);
        DealOffer offer = new DealOffer("Gem Shop", "Hall of Chiefs", "Hall of Chiefs Pack", 4.99, "$4.99", 1,
                false, List.of(new DealItem("1h Speedup", 16, "icon")), "001.png");
        store.write(DAY, new DealScan(DAY + "T20:30", List.of(offer), List.of("Deals: tab strip unreadable")));
        Files.writeString(store.scansDir().resolve(DAY.plusDays(1) + ".json"), "{not json");

        DealsStore.Loaded loaded = store.readSince(DAY);

        DealScan scan = loaded.scans().get(DAY);
        assertEquals(offer, scan.offers().get(0));
        assertEquals(List.of("Deals: tab strip unreadable"), scan.problems());
        assertEquals(1, loaded.problems().size());
        assertTrue(loaded.problems().get(0).contains(DAY.plusDays(1).toString()));
    }

    @Test
    void readsOperatorItemValues(@TempDir Path workspace) throws Exception {
        DealsStore store = DealsStore.forWorkspace(workspace);
        Files.createDirectories(store.root());
        Files.writeString(store.itemValuesFile(), "{\"1h Speedup\": 0.35}");

        assertEquals(0.35, store.readItemValues().get("1h Speedup"), 1e-9);
    }
}
