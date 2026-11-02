package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/** The shop before and after one Redeem, live on 2 November, and the counter that leads to it. */
class FlameAndFangsShopFrameTest {

    private static final String SHOP = "/intel/flame-fangs-shop-20261102.png";
    private static final String BULK = "/intel/flame-fangs-shop-bulk-offered-20261102.png";
    private static final String MAP = "/intel/intel-firebeast-gold-pin-20261101.png";
    private static final String NO_EVENT = "/intel/intel-agnes-portrait-rescan-20261031.png";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void theCounterIsOnTheEventMap() throws IOException {
        assertTrue(locate(MAP, TemplatesEnum.INTEL_CORE_COUNTER).isFound());
    }

    @Test
    void noCounterWithoutTheEvent() throws IOException {
        assertFalse(locate(NO_EVENT, TemplatesEnum.INTEL_CORE_COUNTER).isFound());
    }

    @Test
    void fireCrystalsAreTheTopRow() throws IOException {
        assertTrue(locate(SHOP, TemplatesEnum.FLAME_FANGS_FIRE_CRYSTAL_ROW).isFound());
    }

    @Test
    void anythingElseInTheTopRowIsRefused() throws IOException {
        assertFalse(locate("/intel/flame-fangs-shop-other-rows-20261101.png",
                TemplatesEnum.FLAME_FANGS_FIRE_CRYSTAL_ROW).isFound());
    }

    @Test
    void redeemIsLitAndNoPillBeforeTheFirstTap() throws IOException {
        BufferedImage shop = image(SHOP);
        assertTrue(FlameAndFangsShop.redeemLit(shop));
        assertFalse(FlameAndFangsShop.bulkOffered(shop));
    }

    @Test
    void thePillOpensAfterTheFirstTap() throws IOException {
        assertTrue(FlameAndFangsShop.bulkOffered(image(BULK)));
    }

    @Test
    void soldOutSpendsNothingMoreAndLeavesTheOtherRowsAlone() throws IOException {
        // The icon stays in the top row at its limit, so only the unlit button stops another tap.
        BufferedImage soldOut = image("/intel/flame-fangs-shop-sold-out-20261102.png");
        assertTrue(locate("/intel/flame-fangs-shop-sold-out-20261102.png",
                TemplatesEnum.FLAME_FANGS_FIRE_CRYSTAL_ROW).isFound());
        assertFalse(FlameAndFangsShop.redeemLit(soldOut));
        assertFalse(FlameAndFangsShop.bulkOffered(soldOut));
    }

    private static ImageSearchResultData locate(String frame, TemplatesEnum template) throws IOException {
        boolean row = template == TemplatesEnum.FLAME_FANGS_FIRE_CRYSTAL_ROW;
        return OpenCvPatternLocator.locatePattern(resource(frame), template,
                row ? IntelligenceRoutine.FLAME_FANGS_TOP_ROW_ICON.topLeft() : new PointData(0, 0),
                row ? IntelligenceRoutine.FLAME_FANGS_TOP_ROW_ICON.bottomRight() : new PointData(720, 1280), 90);
    }

    private static BufferedImage image(String path) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(resource(path)));
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = FlameAndFangsShopFrameTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
