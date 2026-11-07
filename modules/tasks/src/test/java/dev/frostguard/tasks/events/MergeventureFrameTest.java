package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * Mergeventure screens from live 720x1280 captures taken on 5 and 6 Nov 2026: the board with
 * seven loose Lv. 2 wheat, the board after the fog row cleared (dirt tiles and buried plants),
 * the board with two crowned pieces, the storage bubble with pieces waiting, the zero-energy
 * "Obtain more" pack popup, the main page, the Caravan, a Caravan Buy dialog, the City rail,
 * the Atlas and the Harvest Rhapsody track.
 */
class MergeventureFrameTest {

    private static final String DIR = "/mergeventure/";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void boardScreenIsRecognisedAndOtherScreensAreNot() throws Exception {
        assertTrue(MergeventureBoard.isBoardScreen(image("f2.png")));
        assertTrue(MergeventureBoard.isBoardScreen(image("stuck.png")));
        assertTrue(MergeventureBoard.isBoardScreen(image("cur.png")));
        assertFalse(MergeventureBoard.isBoardScreen(image("enter.png")));
        assertFalse(MergeventureBoard.isBoardScreen(image("e0tap.png")));
        assertFalse(MergeventureBoard.isBoardScreen(image("city3.png")));
    }

    @Test
    void identicalLooseWheatIsMerged() throws Exception {
        BufferedImage img = image("f2.png");
        Optional<MergeventureBoard.Move> move = MergeventureBoard.nextMove(img, MergeventureBoard.read(img), Set.of());
        assertTrue(move.isPresent());
        assertFalse(move.get().unbury());
    }

    @Test
    void plainDirtIsNotABuriedPieceAndRefusedMovesAreSkipped() throws Exception {
        // 6 Nov: dirt tiles read as buried pieces and one failed drag was repeated 60 times.
        BufferedImage img = image("stuck.png");
        List<MergeventureBoard.Cell> cells = MergeventureBoard.read(img);
        assertTrue(MergeventureBoard.count(cells, MergeventureBoard.Kind.DIRT) > 0);
        Set<MergeventureBoard.Move> refused = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            Optional<MergeventureBoard.Move> move = MergeventureBoard.nextMove(img, cells, refused);
            if (move.isEmpty()) {
                return;
            }
            MergeventureBoard.Move m = move.get();
            MergeventureBoard.Cell target = cells.stream()
                    .filter(c -> c.col() == m.toCol() && c.row() == m.toRow())
                    .findFirst().orElseThrow();
            assertEquals(MergeventureBoard.Kind.PIECE, target.kind(), m.toString());
            assertTrue(refused.add(m), "a refused move came back: " + m);
        }
    }

    @Test
    void crownedPiecesAreFound() throws Exception {
        Set<String> at = new HashSet<>();
        MergeventureBoard.crowned(MergeventureBoard.read(image("cur.png")))
                .forEach(c -> at.add(c.col() + "," + c.row()));
        assertTrue(at.contains("5,1"), at.toString());
        assertTrue(at.contains("5,3"), at.toString());
    }

    @Test
    void theStorageBubbleBadgeIsSeen() throws Exception {
        assertTrue(MergeventureBoard.hasStoredPieces(image("e2.png")));
        assertFalse(MergeventureBoard.hasStoredPieces(image("f2.png")));
    }

    @Test
    void templatesMatchTheirScreens() throws Exception {
        assertTrue(find("city3.png", TemplatesEnum.MERGEVENTURE_CITY_ICON).isFound());
        assertTrue(find("enter.png", TemplatesEnum.MERGEVENTURE_GO_MERGE_BUTTON).isFound());
        assertTrue(find("e0tap.png", TemplatesEnum.MERGEVENTURE_OBTAIN_MORE_TITLE).isFound());
        assertFalse(find("f2.png", TemplatesEnum.MERGEVENTURE_OBTAIN_MORE_TITLE).isFound());
        assertFalse(find("cv0.png", TemplatesEnum.MERGEVENTURE_GO_MERGE_BUTTON).isFound());
    }

    @Test
    void mainPageTimersRead() throws Exception {
        assertEquals(Duration.ofDays(1).plusHours(21).plusMinutes(38).plusSeconds(48),
                timer("enter.png", MergeventureRoutine.EVENT_TIMER_TOP_LEFT, MergeventureRoutine.EVENT_TIMER_BOTTOM_RIGHT, OcrSettingsData.forWhiteTextOnDark()));
        assertEquals(Duration.ofDays(1).plusHours(20).plusMinutes(53).plusSeconds(14),
                timer("main-flags.png", MergeventureRoutine.EVENT_TIMER_TOP_LEFT, MergeventureRoutine.EVENT_TIMER_BOTTOM_RIGHT, OcrSettingsData.forWhiteTextOnDark()));
        assertEquals(Duration.ofHours(5).plusMinutes(38).plusSeconds(48),
                timer("enter.png", MergeventureRoutine.RECOVERY_TOP_LEFT, MergeventureRoutine.RECOVERY_BOTTOM_RIGHT, MergeventureRoutine.RECOVERY_TEXT));
        assertEquals(Duration.ofHours(4).plusMinutes(53).plusSeconds(14),
                timer("main-flags.png", MergeventureRoutine.RECOVERY_TOP_LEFT, MergeventureRoutine.RECOVERY_BOTTOM_RIGHT, MergeventureRoutine.RECOVERY_TEXT));
        // The Caravan stays open a day longer than the board (1d 21:38 on the main page then).
        assertEquals(Duration.ofDays(2).plusHours(21).plusMinutes(5).plusSeconds(19),
                timer("cv0.png", MergeventureRoutine.CARAVAN_TIMER_TOP_LEFT, MergeventureRoutine.CARAVAN_TIMER_BOTTOM_RIGHT, OcrSettingsData.forWhiteTextOnDark()));
    }

    private Duration timer(String name, PointData topLeft, PointData bottomRight, OcrSettingsData settings)
            throws Exception {
        String text = OcrEngine.recognizeText(frame(name), topLeft, bottomRight, settings);
        String fixed = MergeventureRoutine.timerText(text);
        assertTrue(GameTimeUtils.isAcceptedFormat(fixed), name + ": " + text);
        return GameTimeUtils.parseDuration(fixed);
    }

    @Test
    void caravanCoinsAndWaresRead() throws Exception {
        String coins = OcrEngine.recognizeText(frame("cv0.png"), MergeventureRoutine.COINS_TOP_LEFT,
                MergeventureRoutine.COINS_BOTTOM_RIGHT, OcrSettingsData.forWhiteTextOnDark());
        assertEquals(5_400, MergeventureRoutine.parseNumber(coins), coins);
        String live = OcrEngine.recognizeText(frame("caravan.png"), MergeventureRoutine.COINS_TOP_LEFT,
                MergeventureRoutine.COINS_BOTTOM_RIGHT, OcrSettingsData.forWhiteTextOnDark());
        assertEquals(5_400, MergeventureRoutine.parseNumber(live), live);
        String title = OcrEngine.recognizeText(frame("buy1.png"), MergeventureRoutine.BUY_TITLE_TOP_LEFT,
                MergeventureRoutine.BUY_TITLE_BOTTOM_RIGHT, MergeventureRoutine.DARK_TITLE);
        assertEquals("charm guide", MergeventureRoutine.wantedKeyword(title), title);
    }

    @Test
    void caravanWaresAreRankedAndUnwantedOnesIgnored() {
        assertEquals("sojourner", MergeventureRoutine.wantedKeyword("Sojourner Caravan"));
        assertEquals("charm design", MergeventureRoutine.wantedKeyword("Charm Design"));
        assertNull(MergeventureRoutine.wantedKeyword("Basic Lumber"));
        assertEquals(10_000, MergeventureRoutine.parseNumber("10,000"));
        assertNull(MergeventureRoutine.parseNumber("Buy"));
    }

    @Test
    void atlasAndHarvestClaimsAreFound() throws Exception {
        assertTrue(MergeventureRoutine.isAtlas(image("at1.png")));
        assertFalse(MergeventureRoutine.isAtlas(image("enter.png")));
        // Rows 15 and 16 glow on the free track; row 14 is already claimed.
        Optional<Integer> row = MergeventureRoutine.glowingFreeReward(image("h1.png"));
        assertTrue(row.isPresent());
        assertTrue(row.get() > 860 && row.get() < 1150, row.toString());
    }

    @Test
    void missionClaimIsOnlySeenOnAGreenButton() throws Exception {
        assertFalse(MergeventureRoutine.greenButton(image("enter.png"), 582, 355));
        // The zero-energy popup with no drinks owned: a yellow $ pack and a blue Go, nothing free.
        assertTrue(MergeventureRoutine.useButton(image("e0tap.png")).isEmpty());
    }

    @Test
    void redDotsShowWhatIsStillWaiting() throws Exception {
        // 7 Nov: Caravan (coins spendable), the main tab, and Harvest Rhapsody with Lv. 18 ready.
        BufferedImage main = image("main-flags.png");
        assertTrue(MergeventureRoutine.sideButtonFlagged(main, new PointData(638, 460)));
        assertFalse(MergeventureRoutine.sideButtonFlagged(main, new PointData(638, 340)));
        assertFalse(MergeventureRoutine.sideButtonFlagged(main, new PointData(638, 575)));
        assertFalse(MergeventureRoutine.sideButtonFlagged(main, new PointData(638, 695)));
        assertTrue(MergeventureRoutine.tabFlagged(main, new PointData(130, 150)));
        assertTrue(MergeventureRoutine.tabFlagged(main, new PointData(330, 150)));
        assertTrue(MergeventureRoutine.glowingFreeReward(image("harvest-ready.png")).isPresent());
        BufferedImage claimed = image("harvest-claimed.png");
        assertFalse(MergeventureRoutine.tabFlagged(claimed, new PointData(330, 150)));
        assertTrue(MergeventureRoutine.glowingFreeReward(claimed).isEmpty());
    }

    @Test
    void theCaravanTipReminderIsSwitchedOffToClearTheLastDot() throws Exception {
        // 7 Nov: with Tip ticked the Caravan, main tab and City icon stayed red while coins were
        // banked; unticking it left the whole event without a dot.
        assertTrue(MergeventureRoutine.tipTicked(image("caravan-tip-on.png")));
        assertFalse(MergeventureRoutine.tipTicked(image("caravan-tip-off.png")));
        BufferedImage clear = image("main-clear.png");
        for (int y : new int[] {340, 460, 575, 695}) {
            assertFalse(MergeventureRoutine.sideButtonFlagged(clear, new PointData(638, y)), "button at " + y);
        }
        assertFalse(MergeventureRoutine.tabFlagged(clear, new PointData(130, 150)));
        assertFalse(MergeventureRoutine.tabFlagged(clear, new PointData(330, 150)));
    }

    private ImageSearchResultData find(String name, TemplatesEnum template) throws IOException {
        return OpenCvPatternLocator.locatePattern(bytes(name), template.getTemplate(),
                new PointData(0, 0), new PointData(719, 1279), 90);
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
