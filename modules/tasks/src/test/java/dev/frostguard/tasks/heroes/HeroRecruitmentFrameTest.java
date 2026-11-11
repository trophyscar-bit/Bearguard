package dev.frostguard.tasks.heroes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.OcrException;

/**
 * Real Hero Recruitment frames with both free recruits on cooldown, plus frames of
 * other screens. They pin the timer regions and the screen check that
 * HeroRecruitmentRoutine relies on after a claim tap.
 */
class HeroRecruitmentFrameTest {

    private static final String SHORT_TIMERS = "/herorecruitment/timers-running-20261110.png";
    private static final String DAY_TIMER = "/herorecruitment/timers-running-epic-days-20260820.png";

    @Test
    void readsAdvancedCountdown() throws Exception {
        assertTimer(SHORT_TIMERS, CommonGameAreas.HERO_RECRUIT_ADVANCED_TIMER_OCR_AREA,
                Duration.ofMinutes(26).plusSeconds(18));
    }

    @Test
    void readsEpicCountdown() throws Exception {
        assertTimer(SHORT_TIMERS, CommonGameAreas.HERO_RECRUIT_EPIC_TIMER_OCR_AREA,
                Duration.ofHours(4).plusMinutes(49).plusSeconds(21));
    }

    @Test
    void readsAdvancedCountdownNearAFullDay() throws Exception {
        assertTimer(DAY_TIMER, CommonGameAreas.HERO_RECRUIT_ADVANCED_TIMER_OCR_AREA,
                Duration.ofHours(20).plusMinutes(20).plusSeconds(41));
    }

    @Test
    void readsEpicCountdownWithADayPrefix() throws Exception {
        assertTimer(DAY_TIMER, CommonGameAreas.HERO_RECRUIT_EPIC_TIMER_OCR_AREA,
                Duration.ofDays(1).plusHours(16).plusMinutes(24).plusSeconds(14));
    }

    @ParameterizedTest
    @ValueSource(strings = {SHORT_TIMERS, DAY_TIMER})
    void confirmsTheRecruitmentScreen(String resource) throws Exception {
        assertTrue(screenShown(resource));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/herorecruitment/other-heroes-grid-20261110.png",
            "/herorecruitment/other-hero-detail-20261110.png",
            "/herorecruitment/other-gear-details-popup-20261110.png"})
    void rejectsOtherScreens(String resource) throws Exception {
        assertFalse(screenShown(resource));
    }

    private boolean screenShown(String resource) throws Exception {
        RawImageData frame = load(resource);
        return HeroRecruitmentRoutine.showsRecruitmentScreen(area -> {
            try {
                return OcrEngine.recognizeText(frame, area.topLeft(), area.bottomRight(),
                        CommonOCRSettings.HERO_RECRUIT_SCREEN_LABEL_SETTINGS);
            } catch (OcrException unreadable) {
                return null;
            }
        });
    }

    private void assertTimer(String resource, AreaData area, Duration expected) throws Exception {
        String text = OcrEngine.recognizeText(load(resource), area.topLeft(), area.bottomRight(),
                CommonOCRSettings.HERO_RECRUIT_TIMER_SETTINGS);

        assertTrue(GameTimeUtils.isAcceptedFormat(text), () -> "rejected OCR text '" + text + "'");
        assertEquals(expected, GameTimeUtils.parseDuration(text), () -> "OCR text '" + text + "'");
    }

    private RawImageData load(String resource) throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(resource)));
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                rgba[offset++] = (byte) ((rgb >> 16) & 0xFF);
                rgba[offset++] = (byte) ((rgb >> 8) & 0xFF);
                rgba[offset++] = (byte) (rgb & 0xFF);
                rgba[offset++] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
    }
}
