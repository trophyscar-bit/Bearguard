package dev.frostguard.tasks.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DailyDealsFreeChestRoutineTest {

    private static final String DIR = "/shop/daily-deals/";

    @BeforeAll
    static void loadOpenCv() {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError | IOException ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    private static ImageSearchResultData find(String crop, TemplatesEnum template) throws IOException {
        byte[] png;
        try (var in = DailyDealsFreeChestRoutineTest.class.getResourceAsStream(DIR + crop)) {
            png = Objects.requireNonNull(in, crop).readAllBytes();
        }
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        return OpenCvPatternLocator.locatePattern(png, template, new PointData(0, 0),
                new PointData(image.getWidth() - 1, image.getHeight() - 1), 90);
    }

    @Test
    void cartIconMatchesOnTheWorldMapAndTheCityViewWithoutItsRedDot() throws IOException {
        assertTrue(find("cart-world-map.png", TemplatesEnum.HOME_SHOP_CART_BUTTON).isFound());
        assertTrue(find("cart-city-view.png", TemplatesEnum.HOME_SHOP_CART_BUTTON).isFound());
    }

    @Test
    void dailyDealsTabMatchesWhenSelected() throws IOException {
        assertTrue(find("tab-selected-chest-free.png", TemplatesEnum.SHOP_TAB_DAILY_DEALS).isFound());
    }

    @Test
    void freeChestMatchesOnlyWhileItIsStillFree() throws IOException {
        assertTrue(find("free-chest-available.png", TemplatesEnum.SHOP_DAILY_DEALS_FREE_CHEST).isFound());
        assertFalse(find("free-chest-claimed-timer.png", TemplatesEnum.SHOP_DAILY_DEALS_FREE_CHEST).isFound());
    }

    private static ZonedDateTime utc(int h, int m) {
        return ZonedDateTime.of(2026, 11, 12, h, m, 0, 0, ZoneOffset.UTC);
    }

    private static ZonedDateTime local(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC);
    }

    @Test
    void successSchedulesTheNextDayAt0110Utc() {
        assertEquals(ZonedDateTime.of(2026, 11, 13, 1, 10, 0, 0, ZoneOffset.UTC),
                local(ShopChestSchedule.nextRunAfterSuccess(utc(13, 40), 70)));
        assertEquals(ZonedDateTime.of(2026, 11, 13, 1, 10, 0, 0, ZoneOffset.UTC),
                local(ShopChestSchedule.nextRunAfterSuccess(utc(0, 30), 70)));
    }

    @Test
    void customArmamentSlotIs0117Utc() {
        assertEquals(ZonedDateTime.of(2026, 11, 13, 1, 17, 0, 0, ZoneOffset.UTC),
                local(ShopChestSchedule.nextRunAfterSuccess(utc(13, 40), 77)));
    }

    @Test
    void failureRetriesAfterTheDelayOutsideTheResetWindow() {
        assertEquals(utc(14, 10), local(ShopChestSchedule.nextRetryAfterFailure(utc(13, 40), 30, 70)));
    }

    @Test
    void failureNeverRetriesInTheTwentyMinutesBeforeReset() {
        ZonedDateTime postReset = ZonedDateTime.of(2026, 11, 13, 1, 10, 0, 0, ZoneOffset.UTC);
        assertEquals(postReset, local(ShopChestSchedule.nextRetryAfterFailure(utc(23, 20), 30, 70)));
        assertEquals(postReset, local(ShopChestSchedule.nextRetryAfterFailure(utc(23, 0), 45, 70)));
        assertEquals(utc(23, 39), local(ShopChestSchedule.nextRetryAfterFailure(utc(23, 9), 30, 70)));
    }
}
