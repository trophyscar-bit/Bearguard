package dev.frostguard.engine.deals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class DealCarouselTest {

    /** Frames of the Dawn Market tab: tier 1 with option 3 selected, tiers 2 and 5, then options 1, 2 and 4. */
    private static final Map<String, Integer> SELECTED_OPTION_CENTRE = Map.of(
            "d00.png", 438, "r01.png", 438, "r04.png", 438, "o1.png", 149, "o2.png", 293, "o4.png", 581);
    private static final int TOLERANCE = 8;

    @BeforeAll
    static void loadLibrary() throws Exception {
        OpenCvPatternLocator.loadNativeLibrary();
    }

    @Test
    void findsBothArrowsOnEveryDawnMarketTierAndOption() throws Exception {
        for (String name : SELECTED_OPTION_CENTRE.keySet()) {
            DealCarousel.Layout layout = DealCarousel.locate(frame(name)).orElseThrow(() -> new AssertionError(name));

            assertEquals(65, layout.leftArrow().centreX(), TOLERANCE, name);
            assertEquals(655, layout.rightArrow().centreX(), TOLERANCE, name);
            assertEquals(561, layout.banner().centreY(), TOLERANCE, name);
        }
    }

    @Test
    void ignoresWhiteArtworkThatOnlyResemblesAnArrow() throws Exception {
        assertTrue(DealCarousel.locate(frame("weekly-cards-chevrons.png")).isEmpty());
    }

    @Test
    void findsTheFramedOptionWhicheverOneIsSelected() throws Exception {
        for (Map.Entry<String, Integer> expected : SELECTED_OPTION_CENTRE.entrySet()) {
            RawImageData frame = frame(expected.getKey());
            DealCarousel.Layout layout = DealCarousel.locate(frame).orElseThrow();

            DealCarousel.Box selected = DealCarousel.selectedOption(frame, layout)
                    .orElseThrow(() -> new AssertionError(expected.getKey()));

            assertEquals(expected.getValue(), selected.centreX(), TOLERANCE, expected.getKey());
        }
    }

    @Test
    void neighboursHiddenByTheSelectionGlowStandClearOnceTheSelectionMoves() throws Exception {
        TreeSet<Integer> options = new TreeSet<>();
        for (String name : List.of("d00.png", "o1.png")) {
            RawImageData frame = frame(name);
            DealCarousel.Layout layout = DealCarousel.locate(frame).orElseThrow();
            DealCarousel.Box selected = DealCarousel.selectedOption(frame, layout).orElseThrow();
            addDistinct(options, selected.centreX());
            DealCarousel.otherOptionCentres(frame, layout, selected).forEach(centre -> addDistinct(options, centre));
        }

        assertEquals(4, options.size(), options.toString());
        int[] expected = {150, 294, 438, 582};
        int index = 0;
        for (int centre : options) {
            assertEquals(expected[index++], centre, 20, options.toString());
        }
    }

    @Test
    void readsThePackNameOnTheBanner() throws Exception {
        Map<String, String> names = Map.of("d00.png", "Gleam of Hope Pack", "r01.png", "Prayers of Dawn Pack",
                "r04.png", "Protection of Sol Pack");
        for (Map.Entry<String, String> expected : names.entrySet()) {
            RawImageData frame = frame(expected.getKey());

            assertEquals(expected.getValue(),
                    DealCarousel.bannerTitle(frame, DealCarousel.locate(frame).orElseThrow()), expected.getKey());
        }
    }

    /** Two centres within half an option width are the same option. */
    private static void addDistinct(TreeSet<Integer> options, int centre) {
        if (options.stream().noneMatch(known -> Math.abs(known - centre) < 60)) {
            options.add(centre);
        }
    }

    private static RawImageData frame(String name) throws Exception {
        return DealFrameReaderTest.rgbaFrame(ImageIO.read(Objects.requireNonNull(
                DealCarouselTest.class.getResourceAsStream("/deals/carousel/" + name), name)));
    }
}
