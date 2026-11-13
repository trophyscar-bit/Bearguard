package dev.frostguard.tasks.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.SidebarFrameClassifier;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.tasks.lifecycle.DailyPanelReader.Card;
import dev.frostguard.tasks.lifecycle.DailyPanelReader.Reading;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Pins where the timer sweep's "scrolled-panel" rows live now, against saved sidebar frames.
 *
 * <p>The sweep swiped the City tab and read Alliance Contribution, Hero Recruit, Pet Adventure and
 * Tree of Life from fixed boxes below the fold. On 2026-11-13 it logged "Neither scrolled-panel
 * anchor was readable" on every pass; the frame it saved at 15:02 shows why: after the swipe the
 * City tab ends at Tech Research and Expert, and none of those rows is on it at all. They are on
 * the Daily tab.</p>
 *
 * <p>Fixtures are cropped to the sidebar panel (x 0-490, y 240-880 of the 720x1280 screen) and are
 * placed back at that offset on a blank screen, so every coordinate is the live one. No avatar,
 * power, gems, VIP, alliance or player names, or chat are included.</p>
 *
 * <p>Evidence level: saved real-frame verification (City tab from the failing 11-13 pass, Daily tab
 * from 11-13 and 08-21).</p>
 */
class DailyPanelReaderFrameTest {

    private static final int CROP_X = 0;
    private static final int CROP_Y = 240;

    private static final String CITY_AFTER_SWIPE = "/timersweep/city-tab-after-swipe-20261113.png";
    private static final String DAILY_TOP = "/timersweep/daily-tab-top-20261113.png";
    private static final String DAILY_RECRUIT = "/timersweep/daily-tab-recruit-contribution-20260821.png";

    @Test
    void theCityTabTheSweepSwipedNoLongerCarriesAnyOfItsRows() throws Exception {
        RawImageData frame = frame(CITY_AFTER_SWIPE);

        assertEquals(Optional.of(SidebarSection.CITY), section(frame));
        String panel = panelText(frame);
        assertTrue(panel.contains("covert knowledge") || panel.contains("lancer"),
                "fixture should be the City tab's Training/Expert rows, read: " + panel);
        for (String gone : List.of("contribut", "tree of life", "recruit", "pet adventure", "epic")) {
            assertFalse(panel.contains(gone), "'" + gone + "' is not on the City tab any more: " + panel);
        }
    }

    @Test
    void aFrameThatIsNotTheDailyTabYieldsNothingToSchedule() throws Exception {
        assertEquals(Optional.empty(), DailyPanelReader.read(frame(CITY_AFTER_SWIPE)),
                "a City-tab frame must never be read as Daily rows");
    }

    @Test
    void readsRecruitTimersAndTheContributionCounterByTheirOwnTitles() throws Exception {
        // This older capture was masked right of x=448, which hides the panel's close handle the
        // tab classifier keys on, so the cards are read directly; the tab check itself is pinned
        // on the 11-13 frames.
        List<Reading> readings = DailyPanelReader.readCards(frame(DAILY_RECRUIT));
        Map<Card, Reading> byCard = DailyPanelReader.merge(List.of(readings));

        Reading epic = byCard.get(Card.HERO_RECRUIT_EPIC);
        assertEquals(Duration.ofHours(21).plusMinutes(5).plusSeconds(43), epic.timer());

        Reading advanced = byCard.get(Card.HERO_RECRUIT_ADVANCED);
        assertNull(advanced.timer(), "Advanced shows Free here, so it carries no countdown");

        Reading contribution = byCard.get(Card.ALLIANCE_CONTRIBUTION);
        int[] counts = DailyPanelReader.contribution(contribution.status()).orElseThrow(
                () -> new AssertionError("counter unread: " + contribution.status()));
        assertEquals(25, counts[0]);
        assertEquals(25, counts[1]);

        assertEquals(1, readings.stream().filter(r -> r.card() == Card.ALLIANCE_CONTRIBUTION).count(),
                "the section header of the same name must not count as a second card");
    }

    @Test
    void readsTheCurrentDailyTabAndAttributesNoForeignTimer() throws Exception {
        RawImageData frame = frame(DAILY_TOP);
        assertEquals(Optional.of(SidebarSection.DAILY), section(frame));

        List<Reading> readings = DailyPanelReader.read(frame).orElseThrow();

        // Pet Adventure shows "Completed" and the Trek Supplies card two rows up carries a
        // countdown: that countdown belongs to no tracked card and must not be attributed.
        assertEquals(List.of(Card.PET_ADVENTURE),
                readings.stream().map(Reading::card).collect(Collectors.toList()));
        assertNull(readings.get(0).timer());
    }

    @Test
    void sectionHeadersClippedAtTheColumnEdgeAreNotCardTitles() {
        List<TextLine> words = List.of(
                word("Contribution", 75, 362),          // header, clipped by the crop at x=75
                word("Alliance", 126, 401),
                word("Contribution", 210, 401),
                word("Epic", 204, 522),
                word("21:05:43", 188, 549));
        List<Reading> readings = DailyPanelReader.cards(words, y -> "Can contribute 4/25");

        assertEquals(List.of(Card.ALLIANCE_CONTRIBUTION, Card.HERO_RECRUIT_EPIC),
                readings.stream().map(Reading::card).collect(Collectors.toList()));
        assertEquals(Duration.ofHours(21).plusMinutes(5).plusSeconds(43), readings.get(1).timer());
    }

    @Test
    void aCountdownIsOnlyTakenFromTheLineDirectlyUnderItsTitle() {
        List<TextLine> words = List.of(
                word("Epic", 204, 400),
                word("Lighthouse", 160, 470),           // next card's title, 70 px down
                word("10:09:26", 188, 497));
        List<Reading> readings = DailyPanelReader.cards(words, y -> null);

        assertEquals(1, readings.size());
        assertNull(readings.get(0).timer(), "a countdown two lines down belongs to another card");
    }

    @Test
    void mergeKeepsTheViewportThatActuallyShowedTheValue() {
        Reading clipped = new Reading(Card.HERO_RECRUIT_EPIC, 860, null, null);
        Reading full = new Reading(Card.HERO_RECRUIT_EPIC, 520, Duration.ofHours(6), null);

        Map<Card, Reading> merged = DailyPanelReader.merge(List.of(List.of(clipped), List.of(full)));

        assertEquals(Duration.ofHours(6), merged.get(Card.HERO_RECRUIT_EPIC).timer());
    }

    @Test
    void contributionCounterSurvivesTheGlyphsALiveReadPutBeforeIt() {
        assertEquals(15, DailyPanelReader.contribution("Can contribute 15/25").orElseThrow()[0]);
        assertEquals(25, DailyPanelReader.contribution("Gan contribute)25/25").orElseThrow()[0]);
        assertTrue(DailyPanelReader.contribution("Alliance Contribution").isEmpty());
    }

    private static TextLine word(String text, int left, int top) {
        return new TextLine(text, left, top, text.length() * 9, 15, 95f);
    }

    private static Optional<SidebarSection> section(RawImageData frame) throws Exception {
        return SidebarFrameClassifier.selectedSection(screen(frame));
    }

    private static String panelText(RawImageData frame) throws Exception {
        OcrSettingsData settings = OcrSettingsData.assembler()
                .textLayout(TextLayout.SPARSE)
                .stripBackground(false)
                .language("eng")
                .build();
        return OcrEngine.recognizeWords(frame, DailyPanelReader.TEXT_TOP_LEFT,
                        DailyPanelReader.TEXT_BOTTOM_RIGHT, settings).stream()
                .map(TextLine::text)
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
    }

    private static BufferedImage screen(RawImageData frame) {
        return dev.frostguard.vision.convert.ImageConverter.toBufferedImage(frame);
    }

    private static RawImageData frame(String resource) throws Exception {
        BufferedImage crop;
        try (InputStream in = DailyPanelReaderFrameTest.class.getResourceAsStream(resource)) {
            crop = ImageIO.read(Objects.requireNonNull(in, "missing fixture " + resource));
        }
        BufferedImage screen = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = screen.createGraphics();
        g.drawImage(crop, CROP_X, CROP_Y, null);
        g.dispose();

        byte[] rgba = new byte[screen.getWidth() * screen.getHeight() * 4];
        int offset = 0;
        for (int y = 0; y < screen.getHeight(); y++) {
            for (int x = 0; x < screen.getWidth(); x++) {
                int rgb = screen.getRGB(x, y);
                rgba[offset++] = (byte) ((rgb >> 16) & 0xFF);
                rgba[offset++] = (byte) ((rgb >> 8) & 0xFF);
                rgba[offset++] = (byte) (rgb & 0xFF);
                rgba[offset++] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(rgba, screen.getWidth(), screen.getHeight(), 32);
    }
}
