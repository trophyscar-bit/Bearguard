package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.tasks.dailies.ChiefOrderRoutine.ChiefOrderType;
import dev.frostguard.tasks.dailies.ChiefOrderRoutine.OrderSlot;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * The Chief Order shelf after the Fire Crystal Age, from a live 720x1280 capture: Crunch Time,
 * Rush Job (on cooldown), Comprehensive Care, Double Time, Advanced Training (on cooldown) and
 * Dawn Alliance Commendation (on cooldown). Urgent Mobilization and Productivity Day are gone,
 * and the tasks looking for them retried every ten minutes for weeks.
 */
class ChiefOrderFireCrystalShelfFrameTest {

    private static final String DIR = "/chieforder/";
    private static final String SHELF = "fc-shelf-20261022.png";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void everyFireCrystalOrderIsFoundInItsSlotByLabel() throws Exception {
        RawImageData shelf = frame(SHELF);
        List<ChiefOrderType> expected = List.of(ChiefOrderType.CRUNCH_TIME, ChiefOrderType.RUSH_JOB,
                ChiefOrderType.COMPREHENSIVE_CARE, ChiefOrderType.DOUBLE_TIME,
                ChiefOrderType.ADVANCED_TRAINING, ChiefOrderType.DAWN_ALLIANCE_COMMENDATION);
        for (int i = 0; i < expected.size(); i++) {
            ChiefOrderType want = expected.get(i);
            String label = label(shelf, ChiefOrderRoutine.SHELF_SLOTS.get(i)).toLowerCase();
            assertTrue(want.labelMatches(label),
                    () -> "slot label '" + label + "' should name " + want.getDescription());
        }
    }

    @Test
    void retiredOrdersAreAbsentFromAShelfThatIsPlainlyOpen() throws Exception {
        RawImageData shelf = frame(SHELF);
        int legible = 0;
        for (OrderSlot slot : ChiefOrderRoutine.SHELF_SLOTS) {
            String label = label(shelf, slot).toLowerCase();
            if (!label.isBlank()) {
                legible++;
            }
            for (ChiefOrderType retired : List.of(ChiefOrderType.URGENT_MOBILIZATION, ChiefOrderType.PRODUCTIVITY_DAY)) {
                assertFalse(retired.labelMatches(label), label);
            }
        }
        // Enough labels read that "not found" means absent, not "the menu did not open".
        assertTrue(legible >= ChiefOrderRoutine.SHELF_CONFIRMED_LABELS, "legible labels: " + legible);
    }

    @Test
    void coversTellCooldownFromAvailable() throws Exception {
        RawImageData shelf = frame(SHELF);
        String rushJob = cover(shelf, ChiefOrderRoutine.SHELF_SLOTS.get(1)).toLowerCase();
        assertTrue(rushJob.contains("cooldown") && rushJob.contains("22:15:33"), rushJob);

        String crunchTime = cover(shelf, ChiefOrderRoutine.SHELF_SLOTS.get(0)).toLowerCase();
        assertFalse(crunchTime.contains("cooldown") || crunchTime.contains("active"), crunchTime);
    }

    @Test
    void everyOrderHasANeedleAndNoNeedleNamesAnotherOrder() {
        for (ChiefOrderType type : ChiefOrderType.values()) {
            assertFalse(type.labelNeedles().isEmpty(), type + " has no identifying word");
            for (ChiefOrderType other : ChiefOrderType.values()) {
                if (other != type) {
                    assertFalse(type.labelMatches(other.getDescription()),
                            type + " would claim the slot of " + other.getDescription());
                }
            }
        }
    }

    @Test
    void anEnabledEnactButtonIsBlueAndAGreyedOneIsNot() throws Exception {
        assertTrue(enactEnabledOn("fc-crunch-time-enactable-20261022.png"));
        assertTrue(enactEnabledOn("fc-double-time-enactable-20261022.png"));
        // Comprehensive Care with nobody sick: the label still matches at about 88, so without the
        // colour check the routine would tap it and book a 4h cooldown for nothing.
        assertFalse(enactEnabledOn("fc-comprehensive-care-greyed-20261022.png"));
    }

    private boolean enactEnabledOn(String name) throws IOException {
        ImageSearchResultData match = OpenCvPatternLocator.locatePattern(bytes(name),
                TemplatesEnum.CHIEF_ORDER_ENACT_BUTTON.getTemplate(),
                new PointData(0, 0), new PointData(719, 1279), 80);
        assertTrue(match.isFound(), name + " enact label not found");
        AreaData area = match.getMatchedArea();
        return ChiefOrderRoutine.enactEnabled(image(name), area);
    }

    private String label(RawImageData frame, OrderSlot slot) throws Exception {
        String text = OcrEngine.recognizeText(frame, slot.labelTopLeft(), slot.labelBottomRight(),
                OcrSettingsData.forTextBlock());
        return text == null ? "" : text;
    }

    private String cover(RawImageData frame, OrderSlot slot) throws Exception {
        String text = OcrEngine.recognizeText(frame, slot.coverTopLeft(), slot.coverBottomRight(),
                OcrSettingsData.forTextBlock());
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
