package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import java.io.IOException;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CrystalLaboratoryDailyDealFrameTest {

    private static final AreaData DAILY_DEALS_BADGE_AREA = AreaData.of(25, 775, 210, 1000);
    private static final String[] FRAMES = {
            "/city/crystal-lab-daily-deal-50-percent.png",
            "/city/crystal-lab-daily-deal-50-percent-current.png"
    };

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void findsTheWhiteOutlineDiscountBadgeInsideTheDailyDealsCard() throws IOException {
        for (String frame : FRAMES) {
            ImageSearchResultData badge = OpenCvPatternLocator.locatePatternMono(
                    resource(frame), TemplatesEnum.CRYSTAL_LAB_DAILY_DISCOUNTED_RFC,
                    DAILY_DEALS_BADGE_AREA.topLeft(), DAILY_DEALS_BADGE_AREA.bottomRight(), 90);

            assertTrue(badge.isFound(), () -> "Discount badge should be detected in " + frame + ": " + badge);
            assertTrue(badge.getX() >= DAILY_DEALS_BADGE_AREA.topLeft().getX());
            assertTrue(badge.getX() <= DAILY_DEALS_BADGE_AREA.bottomRight().getX());
            assertTrue(badge.getY() >= DAILY_DEALS_BADGE_AREA.topLeft().getY());
            assertTrue(badge.getY() <= DAILY_DEALS_BADGE_AREA.bottomRight().getY());
        }
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = CrystalLaboratoryDailyDealFrameTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
