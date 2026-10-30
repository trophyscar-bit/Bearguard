package dev.frostguard.engine.deals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.RawImageData;

class GemStoreReaderTest {

    @Test
    void readsAllSixGemBundlesWithPriceAndFirstPurchaseBonus() throws Exception {
        List<String> problems = new ArrayList<>();
        Optional<List<GemStoreReader.GemBundle>> read = GemStoreReader.read(frame("gem-shop-get-gems.png"), problems);

        assertTrue(read.isPresent(), "the Get Gems page must be recognised; problems: " + problems);
        List<GemStoreReader.GemBundle> bundles = read.get();
        assertEquals(List.of(), problems);
        assertEquals(6, bundles.size(), bundles.toString());
        assertBundle(bundles.get(0), 500, 0.99, 500L);
        assertBundle(bundles.get(1), 2_500, 4.99, 2_500L);
        assertBundle(bundles.get(2), 5_000, 9.99, 5_000L);
        assertBundle(bundles.get(3), 10_000, 19.99, 10_000L);
        assertBundle(bundles.get(4), 25_000, 49.99, 25_000L);
        assertBundle(bundles.get(5), 50_000, 99.99, 50_000L);
    }

    @Test
    void locatesTheSixCardsAsARegularTwoByThreeGrid() throws Exception {
        List<GemStoreReader.GemBundle> bundles = GemStoreReader.read(frame("gem-shop-get-gems.png")).orElseThrow();

        List<GemStoreReader.Box> expected = List.of(
                new GemStoreReader.Box(35, 474, 315, 233), new GemStoreReader.Box(370, 474, 315, 233),
                new GemStoreReader.Box(35, 739, 315, 233), new GemStoreReader.Box(370, 739, 315, 233),
                new GemStoreReader.Box(35, 1005, 315, 233), new GemStoreReader.Box(370, 1005, 315, 233));
        assertEquals(expected, bundles.stream().map(GemStoreReader.GemBundle::card).toList());
    }

    @Test
    void returnsNothingForPagesThatAreNotTheGemStore() throws Exception {
        for (String name : List.of("gem-shop-leap-packs-3-4-5.png", "gem-shop-weekly-monthly-cards.png",
                "gem-shop-fire-crystal-value-pack.png")) {
            List<String> problems = new ArrayList<>();

            Optional<List<GemStoreReader.GemBundle>> read = GemStoreReader.read(frame(name), problems);

            assertTrue(read.isEmpty(), name + " -> " + read);
            assertEquals(List.of(), problems, name);
        }
    }

    @Test
    void takesTheBonusAfterTheLastPlusSoTheGemIconIsNotADigit() {
        assertEquals(5_000L, GemStoreReader.parseBonus("++5,000"));
        assertEquals(2_500L, GemStoreReader.parseBonus("+2,500"));
        assertNull(GemStoreReader.parseBonus("42,500"), "without its plus sign the number is not trusted");
    }

    @Test
    void rejectsQuantitiesWithMisplacedSeparatorsInsteadOfRepairingThem() {
        assertEquals(50_000L, GemStoreReader.parseGrouped("50,000"));
        assertEquals(500L, GemStoreReader.parseGrouped("500"));
        assertNull(GemStoreReader.parseGrouped("50,00"));
        assertNull(GemStoreReader.parseGrouped(""));
    }

    private static void assertBundle(GemStoreReader.GemBundle bundle, long gems, double priceUsd, Long bonus) {
        assertEquals(gems, bundle.gems(), bundle.toString());
        assertEquals(priceUsd, bundle.priceUsd(), 1e-9, bundle.toString());
        assertEquals(bonus, bundle.bonusGems(), bundle.toString());
    }

    private static RawImageData frame(String name) throws Exception {
        BufferedImage image = ImageIO.read(GemStoreReaderTest.class.getResource("/deals/" + name));
        return DealFrameReaderTest.rgbaFrame(image);
    }
}
