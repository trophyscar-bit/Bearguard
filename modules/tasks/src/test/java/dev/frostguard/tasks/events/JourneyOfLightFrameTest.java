package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.tasks.events.JourneyofLightRoutine.GiftAction;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * Journey of Light, checked against live 720x1280 captures: three teams idle,
 * the same three in the air, My Treasures with and without enough to assemble, the Common assemble
 * dialog, the milestone list, and the Quick Adventure Pack with the free watch already taken.
 */
class JourneyOfLightFrameTest {

    private static final String DIR = "/events/journeyoflight/";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    // ---- expeditions ------------------------------------------------------------------------

    @Test
    void readsTheLandingTimeOfEveryTeamInTheAir() throws Exception {
        RawImageData frame = frame("jol-teams-in-flight-20261001.png");
        for (int slot = 0; slot < 3; slot++) {
            String clock = read(frame, JourneyofLightRoutine.SLOT_TIMERS[slot], JourneyofLightRoutine.TIMER_SETTINGS);
            assertEquals(Duration.ofHours(7).plusMinutes(56).plusSeconds(9), GameTimeUtils.parseDuration(clock),
                    "team " + (slot + 1) + " read '" + clock + "'");
        }
    }

    @Test
    void theInheritedOffCentreCropsClipTheLeadingZero() throws Exception {
        // Teams 2 and 3 through the crops this replaced, 8px right of centre: the clipped "0"
        // comes back as a 9, a time the format check rejects, so a flying team read as no timer.
        RawImageData frame = frame("jol-teams-in-flight-20261001.png");
        for (AreaData old : new AreaData[] { AreaData.of(234, 1036, 338, 1058), AreaData.of(397, 1036, 501, 1058) }) {
            String clock = read(frame, old, JourneyofLightRoutine.TIMER_SETTINGS);
            assertFalse(GameTimeUtils.isAcceptedFormat(clock), () -> "old crop now reads '" + clock + "'");
        }
    }

    @Test
    void anIdleTeamShowsNoTimerAndSaysExpedition() throws Exception {
        RawImageData idle = frame("jol-teams-idle-20261001.png");
        assertEquals("", read(idle, JourneyofLightRoutine.SLOT_TIMERS[0], JourneyofLightRoutine.TIMER_SETTINGS));
        assertTrue(read(idle, JourneyofLightRoutine.SLOT_LABELS[0], JourneyofLightRoutine.LINE_SETTINGS)
                .toLowerCase().contains("expedition"));

        // The same slot in flight says "Finish", so a flying team is never mistaken for idle.
        RawImageData flying = frame("jol-teams-in-flight-20261001.png");
        assertFalse(read(flying, JourneyofLightRoutine.SLOT_LABELS[0], JourneyofLightRoutine.LINE_SETTINGS)
                .toLowerCase().contains("expedition"));
    }

    @Test
    void theLockedFourthSlotIsNeitherATimerNorAnIdleTeam() throws Exception {
        for (String name : new String[] { "jol-teams-idle-20261001.png", "jol-teams-in-flight-20261001.png" }) {
            RawImageData frame = frame(name);
            assertEquals("", read(frame, JourneyofLightRoutine.SLOT_TIMERS[3], JourneyofLightRoutine.TIMER_SETTINGS), name);
            assertFalse(read(frame, JourneyofLightRoutine.SLOT_LABELS[3], JourneyofLightRoutine.LINE_SETTINGS)
                    .toLowerCase().contains("expedition"), name);
        }
    }

    // ---- milestones -------------------------------------------------------------------------

    @Test
    void readsCompletedExpeditionsFromTheMainScreenCounter() throws Exception {
        String counter = read(frame("jol-teams-idle-20261001.png"), JourneyofLightRoutine.FLIGHT_COUNTER,
                JourneyofLightRoutine.FRACTION_SETTINGS);
        assertEquals(8, JourneyofLightRoutine.completedFlights(counter), counter);
    }

    @Test
    void theRowProgressCannotBeUsedBecauseItsRedDigitsDoNotRead() throws Exception {
        // Why the count comes from the main screen: inside the list the unreached count is drawn
        // red and comes back as "/10". If this ever reads "8/10" the row could be used directly.
        AreaData progress = AreaData.of(515, 215, 660, 249);
        String text = read(frame("jol-milestones-none-reached-20261001.png"), progress,
                JourneyofLightRoutine.FRACTION_SETTINGS);
        assertEquals(-1, JourneyofLightRoutine.completedFlights(text), text);
    }

    @Test
    void readsEveryMilestoneTargetOnTheFirstPage() throws Exception {
        RawImageData frame = frame("jol-milestones-none-reached-20261001.png");
        int[] expected = { 10, 20, 30, 50, 70 };
        for (int row = 0; row < expected.length; row++) {
            String header = read(frame, JourneyofLightRoutine.milestoneHeader(row), JourneyofLightRoutine.LINE_SETTINGS);
            assertEquals(expected[row], JourneyofLightRoutine.milestoneTarget(header), "row " + row + " '" + header + "'");
        }
    }

    @Test
    void theClaimedTickIsSeenOnAClaimedRowAndNowhereOnUnclaimedOnes() throws Exception {
        ImageSearchResultData tick = OpenCvPatternLocator.locatePattern(bytes("jol-milestones-claimed-row-20261001.png"),
                TemplatesEnum.JOURNEY_OF_LIGHT_MILESTONE_CLAIMED.getTemplate(),
                new PointData(500, 1040), new PointData(650, 1115), 90);
        assertTrue(tick.isFound());

        for (int row = 0; row < JourneyofLightRoutine.MILESTONE_ROWS; row++) {
            AreaData button = JourneyofLightRoutine.milestoneButton(row);
            ImageSearchResultData none = OpenCvPatternLocator.locatePattern(
                    bytes("jol-milestones-none-reached-20261001.png"),
                    TemplatesEnum.JOURNEY_OF_LIGHT_MILESTONE_CLAIMED.getTemplate(),
                    button.topLeft(), button.bottomRight(), 90);
            assertFalse(none.isFound(), "row " + row);
        }
    }

    // ---- free watch -------------------------------------------------------------------------

    @Test
    void recognisesTheFreeWatchAsAlreadyClaimed() throws Exception {
        RawImageData frame = frame("jol-quick-adventure-gift-claimed-20261001.png");
        assertTrue(read(frame, JourneyofLightRoutine.PACK_HEADER, JourneyofLightRoutine.LINE_SETTINGS)
                .toLowerCase().contains("adventure"));
        String title = read(frame, JourneyofLightRoutine.GIFT_TITLE, JourneyofLightRoutine.LINE_SETTINGS);
        String button = read(frame, JourneyofLightRoutine.GIFT_BUTTON, JourneyofLightRoutine.LINE_SETTINGS);
        assertEquals(GiftAction.ALREADY_CLAIMED, JourneyofLightRoutine.giftAction(title, button));
    }

    @Test
    void onlyPressesTheGiftButtonWhenItsOwnLabelSaysItIsFree() {
        assertEquals(GiftAction.CLAIM, JourneyofLightRoutine.giftAction("Expedition Gift", "Claim"));
        assertEquals(GiftAction.CLAIM, JourneyofLightRoutine.giftAction("Expedition Gift", "Free"));
        assertEquals(GiftAction.ALREADY_CLAIMED, JourneyofLightRoutine.giftAction("Expedition Gift", "Claimed"));
        // Every other card on that screen is a paid pack.
        assertEquals(GiftAction.UNRECOGNISED, JourneyofLightRoutine.giftAction("Expedition Gift", "$4.99"));
        assertEquals(GiftAction.UNRECOGNISED, JourneyofLightRoutine.giftAction("Common Quick Adventure Pack", "Claim"));
        assertEquals(GiftAction.UNRECOGNISED, JourneyofLightRoutine.giftAction(null, null));
    }

    // ---- treasures --------------------------------------------------------------------------

    @Test
    void tellsAnEnabledAssembleButtonFromAGreyOne() throws Exception {
        BufferedImage ready = image("jol-treasures-common-ready-20261001.png");   // 5 Common, 1 Premium
        assertTrue(JourneyofLightRoutine.assembleReady(ready, JourneyofLightRoutine.COMMON_ASSEMBLE_BODY));
        assertFalse(JourneyofLightRoutine.assembleReady(ready, JourneyofLightRoutine.PREMIUM_ASSEMBLE_BODY));

        BufferedImage none = image("jol-treasures-none-ready-20261001.png");      // 2 Common, 2 Premium
        assertFalse(JourneyofLightRoutine.assembleReady(none, JourneyofLightRoutine.COMMON_ASSEMBLE_BODY));
        assertFalse(JourneyofLightRoutine.assembleReady(none, JourneyofLightRoutine.PREMIUM_ASSEMBLE_BODY));
    }

    @Test
    void findsTheAssembleConfirmOnlyInsideTheDialog() throws Exception {
        AreaData dialog = JourneyofLightRoutine.ASSEMBLE_DIALOG;
        ImageSearchResultData confirm = OpenCvPatternLocator.locatePattern(bytes("jol-assemble-dialog-common-20261001.png"),
                TemplatesEnum.JOURNEY_OF_LIGHT_ASSEMBLE_CONFIRM.getTemplate(), dialog.topLeft(), dialog.bottomRight(), 90);
        assertTrue(confirm.isFound());

        // Without the dialog the same area holds the lower treasure cards' Enable buttons.
        ImageSearchResultData absent = OpenCvPatternLocator.locatePattern(bytes("jol-treasures-common-ready-20261001.png"),
                TemplatesEnum.JOURNEY_OF_LIGHT_ASSEMBLE_CONFIRM.getTemplate(), dialog.topLeft(), dialog.bottomRight(), 90);
        assertFalse(absent.isFound());
    }

    // ---- navigation -------------------------------------------------------------------------

    @Test
    void findsTheEventTabWhetherOrNotDealsOpenedOnIt() throws Exception {
        assertTrue(fullScreen("jol-deals-other-tab-selected-20261001.png", TemplatesEnum.JOURNEY_OF_LIGHT_UNSELECTED_TAB).isFound());
        assertTrue(fullScreen("jol-teams-idle-20261001.png", TemplatesEnum.JOURNEY_OF_LIGHT_TAB).isFound());
    }

    // ---- scheduling -------------------------------------------------------------------------

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 9, 6);
    private static final LocalDateTime RESET = LocalDateTime.of(2026, 10, 1, 20, 0);

    @Test
    void wakesJustAfterTheFirstTeamLands() {
        LocalDateTime lands = NOW.plusHours(8);
        assertEquals(lands.plusMinutes(1), JourneyofLightRoutine.nextRun(NOW, false, Optional.of(lands), RESET));
    }

    @Test
    void wakesAfterResetIfThatComesFirstSoTheFreeWatchIsNotLeftADay() {
        LocalDateTime lands = RESET.plusHours(2);
        assertEquals(RESET.plusMinutes(5), JourneyofLightRoutine.nextRun(NOW, false, Optional.of(lands), RESET));
    }

    @Test
    void aRunThatReadsNoTimerRetriesSoonInsteadOfSleepingForWeeks() {
        // The routine this replaces started from now + 1000 hours and kept it when nothing read,
        // which parks the task for six weeks -- past the end of any Journey of Light.
        assertEquals(NOW.plusMinutes(30), JourneyofLightRoutine.nextRun(NOW, false, Optional.empty(), RESET));
    }

    @Test
    void afterTheEventEndsItKeepsCheckingThroughTheCollectionDay() {
        assertEquals(NOW.plusHours(6), JourneyofLightRoutine.nextRun(NOW, true, Optional.empty(), RESET));
    }

    // ---- helpers ----------------------------------------------------------------------------

    private ImageSearchResultData fullScreen(String name, TemplatesEnum template) throws IOException {
        return OpenCvPatternLocator.locatePattern(bytes(name), template.getTemplate(),
                new PointData(0, 0), new PointData(719, 1279), 90);
    }

    private String read(RawImageData frame, AreaData area, OcrSettingsData settings) throws Exception {
        String text = OcrEngine.recognizeText(frame, area.topLeft(), area.bottomRight(), settings);
        return text == null ? "" : text;
    }

    private byte[] bytes(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(DIR + name), name)) {
            return in.readAllBytes();
        }
    }

    private BufferedImage image(String name) throws IOException {
        return ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(DIR + name), name));
    }

    private RawImageData frame(String name) throws IOException {
        BufferedImage image = image(name);
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        int o = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                rgba[o++] = (byte) ((rgb >> 16) & 0xFF);
                rgba[o++] = (byte) ((rgb >> 8) & 0xFF);
                rgba[o++] = (byte) (rgb & 0xFF);
                rgba[o++] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
    }
}
