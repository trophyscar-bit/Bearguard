package dev.frostguard.engine.deals;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.frostguard.api.deals.DealOffer;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Re-reads a saved scan's frames with the current reader and collects them as the nightly scan does, so a
 * reader change can be checked against a whole run before it ships. Runs only when pointed at a run:
 * {@code -Dreplay.frames=<dir> -Dreplay.map=<frame TAB surface / tab per line> -Dreplay.out=<json>}.
 */
class ScanReplayTest {

    @Test
    void replaysASavedScan() throws Exception {
        String framesDir = System.getProperty("replay.frames");
        Assumptions.assumeTrue(framesDir != null, "no replay requested");
        OpenCvPatternLocator.loadNativeLibrary();
        DealFrameReader reader = new DealFrameReader(DealItemLibrary.load(Path.of(Objects.requireNonNull(
                ScanReplayTest.class.getResource("/deals/item-icons")).toURI())));
        DealCollection collection = new DealCollection();
        Map<String, String> headerByTab = new java.util.HashMap<>();
        for (String line : Files.readAllLines(Path.of(System.getProperty("replay.map")))) {
            String[] cells = line.split("\t", 2);
            String frame = cells[0] + ".png";
            int slash = cells[1].indexOf(" / ");
            String surface = slash < 0 ? cells[1] : cells[1].substring(0, slash);
            String tab = slash < 0 ? null : cells[1].substring(slash + 3);
            if (tab != null) {
                collection.noteTabSpelling(surface, tab);
            }
            BufferedImage image = ImageIO.read(new File(framesDir, frame));
            // A city icon that opened a pop-up without tabs is logged with the surface as its tab.
            boolean tabbed = !surface.equals(tab);
            DealFrameReader.Page page = reader.read(DealFrameReaderTest.rgbaFrame(image), surface, tab, frame, tabbed);
            collection.problems().addAll(page.problems());
            // As the scan does: a pick-your-own page whose header did not read keeps its tab's header read.
            List<DealOffer> read = page.offers();
            if (page.chooseYourOwn() && !read.isEmpty()) {
                String header = read.get(0).tab();
                if (header != null && !header.equals(tab)) {
                    headerByTab.put(tab, header);
                }
                String shop = headerByTab.getOrDefault(tab, header);
                read = read.stream().map(o -> new DealOffer(o.surface(), shop, o.title(), o.priceUsd(), o.priceText(),
                        o.remaining(), o.purchased(), o.items(), o.frame(), o.limitPeriod(), o.limitCount(),
                        o.timeLeft(), o.card())).toList();
            }
            read.forEach(offer -> collection.add(offer, page.chooseYourOwn()));
        }
        collection.settle();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DealOffer offer : collection.offers().values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("surface", offer.surface());
            row.put("tab", offer.tab());
            row.put("where", offer.tab() == null || offer.tab().isBlank() ? offer.surface()
                    : offer.surface() + " > " + offer.tab());
            row.put("title", offer.title());
            row.put("priceUsd", offer.priceUsd());
            row.put("items", offer.items().stream().map(i -> Map.of("key", i.key(), "quantity", i.quantity())).toList());
            row.put("frame", offer.frame());
            rows.add(row);
        }
        Map<String, Object> scan = new LinkedHashMap<>();
        scan.put("offers", rows);
        scan.put("problems", collection.problems());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new File(System.getProperty("replay.out")), scan);
    }
}
