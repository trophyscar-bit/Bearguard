package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;

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
 * Dawn Academy screens from live 720x1280 captures taken on 27 Oct 2026: Cyrille's skill card
 * before and after an upgrade, the learning dialog, the City sidebar Expert row, the Trek
 * landing, both trek routes, the free supply panel and Endless Wayfarer. The gem supply buy
 * frames (buyable panel, Purchase dialog, panel after the buy) are from 28 Oct 2026.
 */
class DawnAcademyFrameTest {

    private static final String DIR = "/dawnacademy/";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void theShortestLearningTimeThatFillsTheBarIsPicked() {
        // 4,200/7,200 needs 3,000 XP: 10m gives 600, 2h gives 7,200.
        assertEquals(Duration.ofHours(2), DawnAcademyRoutine.pickLearningOption(4_200, 7_200).duration());
        assertEquals(Duration.ofMinutes(10), DawnAcademyRoutine.pickLearningOption(7_000, 7_200).duration());
        assertEquals(Duration.ofHours(10), DawnAcademyRoutine.pickLearningOption(0, 30_000).duration());
        assertEquals(Duration.ofHours(23), DawnAcademyRoutine.pickLearningOption(0, 50_000).duration());
        // More than a 23h session can give: still 23h, the longest unlocked option.
        assertEquals(Duration.ofHours(23), DawnAcademyRoutine.pickLearningOption(0, 200_000).duration());
    }

    @Test
    void aFullBarBanksTwoHoursSoTheQueueIsNeverIdle() {
        // 29 Oct: CYRILLE 36,600/14,400 and 91,200/55,200 waiting on Books left the queue idle.
        assertEquals(Duration.ofHours(2), DawnAcademyRoutine.BANK_OPTION.duration());
    }

    @Test
    void fractionsAndCountsParse() {
        assertArrayEquals(new long[]{4_200, 7_200}, DawnAcademyRoutine.parseFraction("4,200/7,200"));
        assertArrayEquals(new long[]{4_200, 7_200}, DawnAcademyRoutine.parseFraction("4,200+7,200/7,200"));
        assertNull(DawnAcademyRoutine.parseFraction("Learning XP"));
        assertEquals(9, DawnAcademyRoutine.parseCount("9/100"));
        assertEquals(10, DawnAcademyRoutine.parseCount("10"));
        assertNull(DawnAcademyRoutine.parseCount("/"));
    }

    @Test
    void theSkillCardShowsLearningXpBesideTheLearnButton() throws Exception {
        // With the Upgrade Skill button the card sits 40px higher; the XP read follows the button.
        assertArrayEquals(new long[]{7_800, 3_600}, xpBesideLearn("skill-card-upgradable.png"));
        assertArrayEquals(new long[]{4_200, 7_200}, xpBesideLearn("skill-card-lv2.png"));
        assertTrue(find("skill-card-upgradable.png", TemplatesEnum.DAWN_ACADEMY_UPGRADE_SKILL_BUTTON).isFound());
        assertFalse(find("skill-card-lv2.png", TemplatesEnum.DAWN_ACADEMY_UPGRADE_SKILL_BUTTON).isFound());
    }

    @Test
    void aLearningSkillShowsItsTimerUnderTheSpeedupIcon() throws Exception {
        ImageSearchResultData speedup = find("skill-card-learning.png", TemplatesEnum.EXPERT_TRAINING_SPEEDUP_ICON);
        assertTrue(speedup.isFound());
        int top = speedup.getMatchedArea().topLeft().getY();
        Duration left = plainDuration("skill-card-learning.png", new PointData(535, top + 64), new PointData(660, top + 94));
        assertTrue(left != null && left.toMinutes() >= 119 && left.toMinutes() <= 120, "timer " + left);
        assertFalse(find("skill-card-learning.png", TemplatesEnum.EXPERT_TRAINING_LEARN_BUTTON).isFound());
    }

    @Test
    void aLockedSkillShowsUnlockInsteadOfLearn() throws Exception {
        assertTrue(find("skill-locked.png", TemplatesEnum.DAWN_ACADEMY_SKILL_UNLOCK_BUTTON).isFound());
        assertFalse(find("skill-locked.png", TemplatesEnum.EXPERT_TRAINING_LEARN_BUTTON).isFound());
    }

    @Test
    void theLearnDialogShowsThePickedDurationOnItsButton() throws Exception {
        assertTrue(find("learn-dialog-2h.png", TemplatesEnum.DAWN_ACADEMY_LEARN_SKILLS_TITLE).isFound());
        assertTrue(find("learn-dialog-2h.png", TemplatesEnum.DAWN_ACADEMY_LEARN_CONFIRM_BUTTON).isFound());
        assertEquals(Duration.ofHours(2),
                duration("learn-dialog-2h.png", new PointData(440, 928), new PointData(640, 962)));
    }

    @Test
    void theSidebarExpertRowCarriesTheLearningTimer() throws Exception {
        ImageSearchResultData row = find("sidebar-expert-row.png", TemplatesEnum.LEFT_MENU_EXPERT_TRAINING_BUTTON);
        assertTrue(row.isFound());
        int top = row.getMatchedArea().topLeft().getY();
        Duration left = plainDuration("sidebar-expert-row.png", new PointData(120, top + 10), new PointData(330, top + 42));
        assertTrue(left != null && left.toMinutes() == 107, "timer " + left);
    }

    @Test
    void theTrekLandingShowsBothRoutesAndBothCounters() throws Exception {
        assertTrue(find("trek-landing.png", TemplatesEnum.DAWN_ACADEMY_TUNDRA_TREK_SIGN).isFound());
        assertTrue(find("trek-landing.png", TemplatesEnum.DAWN_ACADEMY_FRONTIER_TREK_SIGN).isFound());
        assertEquals(0, count("trek-landing.png", new PointData(512, 22), new PointData(615, 60)));
        // The Frontier counter's lone 0 reads as 8 with the white-text preset, so the plain read is used.
        assertEquals(0, plainCount("trek-landing.png", new PointData(350, 22), new PointData(420, 60)));
        assertTrue(find("academy.png", TemplatesEnum.DAWN_ACADEMY_TREK_BUTTON).isFound());
    }

    @Test
    void theTrekRoutesShowTheirSuppliesAndAutoButton() throws Exception {
        assertEquals(10, count("tundra-route.png", new PointData(512, 22), new PointData(615, 60)));
        assertEquals(10, count("frontier-route.png", new PointData(512, 22), new PointData(615, 60)));
        assertTrue(find("tundra-route.png", TemplatesEnum.TUNDRA_TREK_AUTO_BUTTON).isFound());
        assertTrue(find("tundra-route.png", TemplatesEnum.DAWN_ACADEMY_NEXT_STOP_BUTTON).isFound());
    }

    @Test
    void theAutoTrekDialogShowsTheAutoManageTick() throws Exception {
        assertTrue(find("auto-trek-unticked.png", TemplatesEnum.DAWN_ACADEMY_AUTO_TREK_TITLE).isFound());
        assertTrue(find("auto-trek-unticked.png", TemplatesEnum.TUNDRA_TREK_CHECK_INACTIVE).isFound());
        assertTrue(find("auto-trek-ticked.png", TemplatesEnum.TUNDRA_TREK_CHECK_ACTIVE).isFound());
    }

    @Test
    void theSupplyPanelHasNoClaimWhileItsTimerRuns() throws Exception {
        assertTrue(find("supply-panel.png", TemplatesEnum.DAWN_ACADEMY_CLAIM_FOR_FREE_TITLE).isFound());
        Duration next = duration("supply-panel.png", new PointData(505, 585), new PointData(650, 625));
        assertTrue(next != null && next.toMinutes() == 402, "next supply " + next);
    }

    @Test
    void theDailyLimitCountsGemBuysLeftAndThePurchaseDialogShowsThePrice() throws Exception {
        // Live 28 Oct: 1/4 with a 1,000-gem button; after one buy the panel stayed open at 0/4.
        assertEquals(1, buysLeft("supply-panel-buyable.png"));
        assertEquals(0, buysLeft("supply-panel-bought.png"));
        assertEquals(0, buysLeft("supply-panel.png"));
        assertTrue(find("supply-panel-bought.png", TemplatesEnum.DAWN_ACADEMY_CLAIM_FOR_FREE_TITLE).isFound());
        assertTrue(find("supply-purchase-dialog.png", TemplatesEnum.GATHER_SPEED_PURCHASE_TITLE).isFound());
        assertFalse(find("supply-panel-buyable.png", TemplatesEnum.GATHER_SPEED_PURCHASE_TITLE).isFound());
        assertEquals(1_000, DawnAcademyRoutine.parsePrice(OcrEngine.recognizeText(frame("supply-purchase-dialog.png"),
                DawnAcademyRoutine.PURCHASE_PRICE_TOP_LEFT, DawnAcademyRoutine.PURCHASE_PRICE_BOTTOM_RIGHT, "eng")));
    }

    @Test
    void buysLeftAndPricesParse() {
        assertEquals(1, DawnAcademyRoutine.parseBuysLeft("Daily Limit: 1/4"));
        assertEquals(0, DawnAcademyRoutine.parseBuysLeft("Daily Limit: 0/4"));
        assertNull(DawnAcademyRoutine.parseBuysLeft("Daily Limit:"));
        assertNull(DawnAcademyRoutine.parseBuysLeft("Daily Limit: 7/4"));
        assertEquals(1_000, DawnAcademyRoutine.parsePrice("Confirm to spend 1,000 Gems?"));
        assertEquals(1_500, DawnAcademyRoutine.parsePrice("Confirm to spend 1.500 Gems?"));
        assertNull(DawnAcademyRoutine.parsePrice("Confirm to spend Gems?"));
    }

    @Test
    void theGiftScreenOpensFromStatsAndShowsTheChosenCount() throws Exception {
        assertTrue(find("expert-stats.png", TemplatesEnum.DAWN_ACADEMY_GIFT_OPEN_BUTTON).isFound());
        assertTrue(find("gift-screen.png", TemplatesEnum.DAWN_ACADEMY_SELECT_GIFT_TITLE).isFound());
        assertEquals(0, count("gift-screen.png", new PointData(470, 1068), new PointData(590, 1108)));
    }

    @Test
    void theGiftScreenShowsAffinitySoAnExpertCloseToALevelCanBeToppedOff() throws Exception {
        String bar = OcrEngine.recognizeText(frame("gift-screen.png"), DawnAcademyRoutine.GIFT_AFFINITY_TOP_LEFT,
                DawnAcademyRoutine.GIFT_AFFINITY_BOTTOM_RIGHT, DawnAcademyRoutine.AFFINITY_DIGITS);
        assertArrayEquals(new long[]{100, 460}, DawnAcademyRoutine.parseAffinity(bar), "affinity " + bar);
        // A nearly full bar is white-on-yellow: the white-text preset read 570/580 as 970/580.
        // Frame: matt's phone screenshot of Cyrille's Stats tab, 29 Oct, scaled to 720 wide.
        String full = OcrEngine.recognizeText(frame("cyrille-stats-570-scaled.png"), new PointData(135, 902),
                new PointData(585, 942), DawnAcademyRoutine.AFFINITY_DIGITS);
        assertArrayEquals(new long[]{570, 580}, DawnAcademyRoutine.parseAffinity(full), "affinity " + full);
        assertNull(DawnAcademyRoutine.parseAffinity("970/580"));
        // 29 Oct: Cyrille at 570/580 needs one Compass (+10 each).
        assertEquals(1, DawnAcademyRoutine.topOffCompasses(570, 580));
        assertEquals(2, DawnAcademyRoutine.topOffCompasses(565, 580));
        assertEquals(20, DawnAcademyRoutine.topOffCompasses(260, 460));
        assertEquals(0, DawnAcademyRoutine.topOffCompasses(100, 460));
        assertEquals(0, DawnAcademyRoutine.topOffCompasses(0, 410));
        assertEquals(0, DawnAcademyRoutine.topOffCompasses(580, 580));
    }

    @Test
    void anIdleQueueLeadsToTheGuideHandAndItsFingertipIsTheExpert() throws Exception {
        // Live 27 Oct: the idle Expert row opened the Academy with this hand on Cyrille, and a
        // tap at (395,850) opened her screen.
        ImageSearchResultData hand = OpenCvPatternLocator.locatePattern(bytes("academy-idle-guide.png"),
                TemplatesEnum.DAWN_ACADEMY_GUIDE_HAND.getTemplate(),
                DawnAcademyRoutine.GUIDE_HAND_AREA.topLeft(), DawnAcademyRoutine.GUIDE_HAND_AREA.bottomRight(), 90);
        assertTrue(hand.isFound());
        PointData tip = DawnAcademyRoutine.fingertip(hand.getMatchedArea());
        assertTrue(Math.abs(tip.getX() - 395) <= 10 && Math.abs(tip.getY() - 850) <= 10, tip.getX() + "," + tip.getY());
        assertFalse(OpenCvPatternLocator.locatePattern(bytes("academy.png"),
                TemplatesEnum.DAWN_ACADEMY_GUIDE_HAND.getTemplate(),
                DawnAcademyRoutine.GUIDE_HAND_AREA.topLeft(), DawnAcademyRoutine.GUIDE_HAND_AREA.bottomRight(), 90)
                .isFound());
    }

    @Test
    void aPendingRelationshipAdvancementReplacesGiftUntilItIsDone() throws Exception {
        // Live 29 Oct: the flame bubble on the Academy led to Agnes, whose Stats tab showed
        // Relationship Advancement instead of Gift. Advance (5 of 18 shards) made her
        // Acquaintance 1 and Gift came back.
        assertTrue(find("expert-advance-ready.png", TemplatesEnum.DAWN_ACADEMY_RELATIONSHIP_ADVANCE_BUTTON).isFound());
        assertFalse(find("expert-advance-ready.png", TemplatesEnum.DAWN_ACADEMY_GIFT_OPEN_BUTTON).isFound());
        assertTrue(find("expert-after-advance.png", TemplatesEnum.DAWN_ACADEMY_GIFT_OPEN_BUTTON).isFound());
        assertFalse(find("expert-after-advance.png", TemplatesEnum.DAWN_ACADEMY_RELATIONSHIP_ADVANCE_BUTTON).isFound());
        assertFalse(find("expert-stats.png", TemplatesEnum.DAWN_ACADEMY_RELATIONSHIP_ADVANCE_BUTTON).isFound());

        assertTrue(find("relationship-advance.png", TemplatesEnum.DAWN_ACADEMY_ADVANCE_BUTTON).isFound());
        String shards = OcrEngine.recognizeText(frame("relationship-advance.png"),
                DawnAcademyRoutine.ADVANCE_SHARDS_TOP_LEFT, DawnAcademyRoutine.ADVANCE_SHARDS_BOTTOM_RIGHT,
                OcrSettingsData.forWhiteTextOnDark());
        assertArrayEquals(new long[]{18, 5}, DawnAcademyRoutine.parseFraction(shards), "shards " + shards);
        assertTrue(find("relationship-advanced.png", TemplatesEnum.DAWN_ACADEMY_ADVANCED_TITLE).isFound());
        assertFalse(find("relationship-advance.png", TemplatesEnum.DAWN_ACADEMY_ADVANCED_TITLE).isFound());
    }

    @Test
    void wayfarerShowsClaimAll() throws Exception {
        assertTrue(find("wayfarer.png", TemplatesEnum.DAWN_ACADEMY_WAYFARER_CLAIM_ALL).isFound());
    }

    private long[] xpBesideLearn(String name) throws Exception {
        ImageSearchResultData learn = find(name, TemplatesEnum.EXPERT_TRAINING_LEARN_BUTTON);
        assertTrue(learn.isFound(), name + " learn button");
        int top = learn.getMatchedArea().topLeft().getY();
        String text = OcrEngine.recognizeText(frame(name), new PointData(115, top + 8), new PointData(535, top + 44),
                OcrSettingsData.forWhiteTextOnDark());
        return DawnAcademyRoutine.parseFraction(text);
    }

    private Duration duration(String name, PointData topLeft, PointData bottomRight) throws Exception {
        String text = OcrEngine.recognizeText(frame(name), topLeft, bottomRight, OcrSettingsData.forWhiteTextOnDark());
        return text != null && GameTimeUtils.isAcceptedFormat(text) ? GameTimeUtils.parseDuration(text) : null;
    }

    private Duration plainDuration(String name, PointData topLeft, PointData bottomRight) throws Exception {
        String text = OcrEngine.recognizeText(frame(name), topLeft, bottomRight, "eng");
        return text != null && GameTimeUtils.isAcceptedFormat(text) ? GameTimeUtils.parseDuration(text) : null;
    }

    private Integer count(String name, PointData topLeft, PointData bottomRight) throws Exception {
        return DawnAcademyRoutine.parseCount(OcrEngine.recognizeText(frame(name), topLeft, bottomRight,
                OcrSettingsData.forWhiteTextOnDark()));
    }

    private Integer buysLeft(String name) throws Exception {
        return DawnAcademyRoutine.parseBuysLeft(OcrEngine.recognizeText(frame(name),
                DawnAcademyRoutine.BUYS_LEFT_TOP_LEFT, DawnAcademyRoutine.BUYS_LEFT_BOTTOM_RIGHT, "eng"));
    }

    private Integer plainCount(String name, PointData topLeft, PointData bottomRight) throws Exception {
        return DawnAcademyRoutine.parseCount(OcrEngine.recognizeText(frame(name), topLeft, bottomRight, "eng"));
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

    private RawImageData frame(String name) throws IOException {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(DIR + name), name));
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
