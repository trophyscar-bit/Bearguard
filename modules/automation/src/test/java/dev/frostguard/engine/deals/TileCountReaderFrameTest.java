package dev.frostguard.engine.deals;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import dev.frostguard.vision.deals.TileCountReader;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Every tile with a count checked by eye on the saved deal frames, at the icon position and size the item
 * library matched it at. The reference glyphs were cut from these tiles; read against references from the
 * other frames only, the same tiles scored 78 of 78, which is the independent measure.
 */
class TileCountReaderFrameTest {

    @BeforeAll
    static void loadOpenCv() throws Exception {
        OpenCvPatternLocator.loadNativeLibrary();
    }

    static Stream<Arguments> tiles() {
        return Stream.of(
                Arguments.of("popup-hero-rally-unlock.png", 214, 646, 0.925, "70"),
                Arguments.of("deals-intel-monthly-card-epic.png", 133, 585, 0.7000000000000001, "33"),
                Arguments.of("deals-intel-monthly-card-epic.png", 276, 1141, 1.0, "3"),
                Arguments.of("deals-intel-monthly-card-epic.png", 279, 952, 0.89, "10"),
                Arguments.of("deals-intel-monthly-card-epic.png", 297, 589, 0.7000000000000001, "25"),
                Arguments.of("deals-intel-monthly-card-epic.png", 50, 587, 0.7250000000000001, "2500"),
                Arguments.of("deals-intel-monthly-card-mythic.png", 133, 585, 0.7000000000000001, "83"),
                Arguments.of("deals-intel-monthly-card-mythic.png", 276, 1141, 1.0, "6"),
                Arguments.of("deals-intel-monthly-card-mythic.png", 279, 952, 0.89, "20"),
                Arguments.of("deals-intel-monthly-card-mythic.png", 297, 589, 0.7000000000000001, "40"),
                Arguments.of("deals-intel-monthly-card-mythic.png", 50, 587, 0.7250000000000001, "5000"),
                Arguments.of("gem-shop-chief-gear-packs.png", 150, 1050, 0.8500000000000001, "9"),
                Arguments.of("gem-shop-chief-gear-packs.png", 150, 371, 0.83, "6"),
                Arguments.of("gem-shop-chief-gear-packs.png", 150, 709, 0.83, "1500"),
                Arguments.of("gem-shop-chief-gear-packs.png", 241, 705, 0.9, "25"),
                Arguments.of("gem-shop-chief-gear-packs.png", 242, 368, 0.875, "25"),
                Arguments.of("gem-shop-chief-gear-packs.png", 337, 1042, 0.89, "25"),
                Arguments.of("gem-shop-chief-gear-packs.png", 337, 705, 0.875, "130"),
                Arguments.of("gem-shop-chief-gear-packs.png", 338, 368, 0.875, "1"),
                Arguments.of("gem-shop-chief-gear-packs.png", 49, 1040, 0.89, "20"),
                Arguments.of("gem-shop-chief-gear-packs.png", 55, 713, 0.83, "15"),
                Arguments.of("gem-shop-exclusive-gear-special.png", 213, 964, 1.0, "25"),
                Arguments.of("gem-shop-exclusive-gear-special.png", 328, 964, 1.0, "130"),
                Arguments.of("gem-shop-exclusive-gear-special.png", 443, 964, 1.0, "130"),
                Arguments.of("gem-shop-fire-crystal-value-pack.png", 213, 964, 1.0, "8"),
                Arguments.of("gem-shop-fire-crystal-value-pack.png", 328, 964, 1.0, "96"),
                Arguments.of("gem-shop-fire-crystal-value-pack.png", 443, 964, 1.0, "25"),
                Arguments.of("gem-shop-fire-crystal-value-pack.png", 99, 959, 1.0, "30"),
                Arguments.of("gem-shop-hall-of-chiefs.png", 213, 964, 1.0, "160"),
                Arguments.of("gem-shop-hall-of-chiefs.png", 328, 964, 1.0, "25"),
                Arguments.of("gem-shop-hall-of-chiefs.png", 443, 964, 1.0, "500"),
                Arguments.of("gem-shop-hall-of-chiefs.png", 98, 964, 1.0, "16"),
                Arguments.of("gem-shop-leap-packs-3-4-5.png", 149, 637, 0.8, "2000"),
                Arguments.of("gem-shop-leap-packs-3-4-5.png", 150, 1158, 0.8, "500"),
                Arguments.of("gem-shop-leap-packs-3-4-5.png", 151, 549, 0.775, "100"),
                Arguments.of("gem-shop-leap-packs-3-4-5.png", 153, 948, 0.73, "500"),
                Arguments.of("gem-shop-leap-packs-3-4-5.png", 233, 637, 0.8, "400"),
                Arguments.of("gem-shop-leap-packs-3-4-5.png", 237, 948, 0.73, "100"),
                Arguments.of("gem-shop-leap-packs-3-4-5.png", 317, 1162, 0.8, "4800"),
                Arguments.of("gem-shop-leap-packs-3-4-5.png", 317, 637, 0.8, "100"),
                Arguments.of("gem-shop-leap-packs-3-4-5.png", 318, 553, 0.775, "960"),
                Arguments.of("gem-shop-leap-packs-3-4-5.png", 319, 859, 0.73, "2400"),
                Arguments.of("gem-shop-leap-packs-3-4-5.png", 321, 948, 0.73, "25"),
                Arguments.of("gem-shop-leap-packs-3-4-5.png", 401, 552, 0.8, "60"),
                Arguments.of("gem-shop-leap-packs-3-4-5.png", 66, 1160, 0.8, "50K"),
                Arguments.of("gem-shop-leap-packs-3-4-5.png", 66, 637, 0.8, "2000"),
                Arguments.of("gem-shop-leap-packs-3-4-5.png", 67, 551, 0.775, "10K"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 149, 840, 0.8, "500"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 150, 1145, 0.775, "1000"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 150, 751, 0.8, "25"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 151, 1056, 0.775, "50"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 153, 541, 0.73, "1000"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 233, 840, 0.8, "100"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 234, 1145, 0.775, "200"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 237, 541, 0.73, "200"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 315, 754, 0.83, "240"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 317, 450, 0.8, "4800"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 317, 840, 0.8, "25"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 318, 1060, 0.775, "480"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 318, 1145, 0.775, "50"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 321, 541, 0.73, "50"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 401, 755, 0.8, "25"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 402, 1060, 0.775, "40"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 402, 840, 0.775, "1"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 66, 1058, 0.8, "5000"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 66, 840, 0.8, "500"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 67, 1145, 0.775, "1000"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 67, 449, 0.775, "50K"),
                Arguments.of("gem-shop-leap-packs-purchased-1-2.png", 68, 755, 0.73, "2500"),
                Arguments.of("gem-shop-top-chief-gear-special-swiped.png", 193, 964, 1.0, "130"),
                Arguments.of("gem-shop-top-chief-gear-special-swiped.png", 298, 965, 1.0, "240"),
                Arguments.of("gem-shop-top-chief-gear-special-swiped.png", 538, 964, 1.0, "1"),
                Arguments.of("gem-shop-top-chief-gear-special-swiped.png", 79, 964, 1.0, "130"),
                Arguments.of("gem-shop-top-chief-gear-special.png", 217, 973, 0.9750000000000001, "40"),
                Arguments.of("gem-shop-top-chief-gear-special.png", 331, 968, 0.9750000000000001, "4000"),
                Arguments.of("gem-shop-top-chief-gear-special.png", 443, 964, 1.0, "25"),
                Arguments.of("gem-shop-top-chief-gear-special.png", 97, 961, 1.0250000000000001, "35"),
                Arguments.of("gem-shop-weekly-monthly-cards.png", 139, 441, 0.67, "6"),
                Arguments.of("gem-shop-weekly-monthly-cards.png", 215, 1031, 0.7000000000000001, "20"));
    }

    @ParameterizedTest(name = "{0} tile at {1},{2} reads {4}")
    @MethodSource("tiles")
    void readsTheCountPrintedOnTheTile(String frame, int x, int y, double scale, String count) throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(
                TileCountReaderFrameTest.class.getResourceAsStream("/deals/" + frame), frame));

        assertEquals(Optional.of(count), TileCountReader.read(image, x, y, scale));
    }
}
