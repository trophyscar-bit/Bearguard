package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.RawImageData;

class HudGemReaderTest {

    @Test
    void acceptsOnlyNumbersGroupedInThousands() {
        assertEquals(OptionalLong.of(59_293L), HudGemReader.parseGrouped("59,293"));
        assertEquals(OptionalLong.of(446_571L), HudGemReader.parseGrouped(" 446,571\n"));
        assertEquals(OptionalLong.of(250L), HudGemReader.parseGrouped("250"));
    }

    @Test
    void treatsMisplacedSeparatorsAndStrayGlyphsAsUnknown() {
        // A dropped comma, a folded digit and a decimal point are not the HUD's format.
        assertTrue(HudGemReader.parseGrouped("46571").isEmpty());
        assertTrue(HudGemReader.parseGrouped("5,9293").isEmpty());
        assertTrue(HudGemReader.parseGrouped("446.571").isEmpty());
        assertTrue(HudGemReader.parseGrouped("59,293,").isEmpty());
        assertTrue(HudGemReader.parseGrouped("").isEmpty());
        assertTrue(HudGemReader.parseGrouped(null).isEmpty());
    }

    @Test
    void aBandWithoutTheDiamondIsUnknown() {
        RawImageData blank = RawImageData.capture(new byte[720 * 1280 * 4], 720, 1280, 32);

        HudGemReader.Reading reading = HudGemReader.read(blank, AreaData.of(520, 22, 710, 58));

        assertTrue(reading.gems().isEmpty());
        assertTrue(reading.evidence().contains("no gem diamond"), reading.evidence());
    }

    @Test
    void aMissingFrameIsUnknown() {
        assertTrue(HudGemReader.read(null, AreaData.of(520, 22, 710, 58)).gems().isEmpty());
    }
}
