package dev.frostguard.vision.ocr;

import dev.frostguard.api.domain.OcrSettingsData;
import net.sourceforge.tess4j.Tesseract;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-3 review of the multilingual OCR support:
 * - item 4: an explicit, unsupported language request (e.g. "ara", "rus") must fail loudly, not
 *   silently downgrade to "eng" and return plausible-but-wrong glyph guesses for non-Latin text.
 * - item 5: real coverage beyond the settings contract -- the resolved language actually reaching
 *   the Tesseract engine, the packaged chi_sim model genuinely being usable, preserveLineBreaks'
 *   real effect on recognition output, and LF/CRLF normalization.
 */
class TesseractOcrProviderTest {

    // ------------------------------------------------------------------
    // resolveSupportedLanguage
    // ------------------------------------------------------------------

    @Test
    void noExplicitRequestFallsBackToEnglish() throws OcrException {
        // null/blank means the caller never asked for a language at all -- every pre-existing
        // call site before multilingual support existed. Must stay unaffected.
        assertEquals("eng", TesseractOcrProvider.resolveSupportedLanguage(null));
        assertEquals("eng", TesseractOcrProvider.resolveSupportedLanguage(""));
        assertEquals("eng", TesseractOcrProvider.resolveSupportedLanguage("   "));
    }

    @Test
    void packagedLanguagesPassThroughUnchanged() throws OcrException {
        assertEquals("eng", TesseractOcrProvider.resolveSupportedLanguage("eng"));
        assertEquals("chi_sim", TesseractOcrProvider.resolveSupportedLanguage("chi_sim"));
        assertEquals("eng+chi_sim", TesseractOcrProvider.resolveSupportedLanguage("eng+chi_sim"));
    }

    @Test
    void explicitlyRequestingAnUnpackagedLanguageFailsLoudlyInsteadOfSilentlyUsingEnglish() {
        // Arabic/Russian/Portuguese/Czech/French were selectable in config despite no trained-data
        // model ever being packaged for them. Silently substituting "eng" here used to return
        // confident-looking garbage for genuinely non-Latin text instead of an honest failure.
        UnsupportedOcrLanguageException ara = assertThrows(UnsupportedOcrLanguageException.class,
                () -> TesseractOcrProvider.resolveSupportedLanguage("ara"));
        assertEquals(List.of("ara"), ara.getRequestedUnsupported());

        UnsupportedOcrLanguageException rus = assertThrows(UnsupportedOcrLanguageException.class,
                () -> TesseractOcrProvider.resolveSupportedLanguage("rus"));
        assertEquals(List.of("rus"), rus.getRequestedUnsupported());
    }

    @Test
    void mixedRequestFailsOnTheUnsupportedComponentRatherThanSilentlyDroppingIt() {
        // A caller combining "eng+ara" explicitly wants Arabic read too -- silently returning
        // eng-only results without ever saying so would look like success while quietly failing
        // half the request.
        UnsupportedOcrLanguageException ex = assertThrows(UnsupportedOcrLanguageException.class,
                () -> TesseractOcrProvider.resolveSupportedLanguage("eng+ara"));
        assertEquals(List.of("ara"), ex.getRequestedUnsupported());
    }

    @Test
    void requestWithOnlyUnsupportedLanguagesFailsListingAllOfThem() {
        UnsupportedOcrLanguageException ex = assertThrows(UnsupportedOcrLanguageException.class,
                () -> TesseractOcrProvider.resolveSupportedLanguage("ara+rus"));
        assertEquals(List.of("ara", "rus"), ex.getRequestedUnsupported());
    }

    // ------------------------------------------------------------------
    // configureTesseract -- proves the resolved language actually reaches the engine, not just
    // that resolveSupportedLanguage's return value looks right in isolation.
    // ------------------------------------------------------------------

    @Test
    void resolvedLanguageIsActuallyAppliedToTheRealTesseractEngine() throws Exception {
        OcrSettingsData cfg = OcrSettingsData.configurator().language("chi_sim").build();

        Tesseract engine = TesseractOcrProvider.configureTesseract(cfg);

        // Tesseract (tess4j) exposes no public getter for the language it was configured with --
        // reflection on its private field is the only way to prove the setter was actually called
        // with OUR resolved value, not just that resolveSupportedLanguage() returns the right
        // string when called on its own.
        Field languageField = Tesseract.class.getDeclaredField("language");
        languageField.setAccessible(true);
        assertEquals("chi_sim", languageField.get(engine));
    }

    @Test
    void configureTesseractPropagatesTheUnsupportedLanguageFailure() {
        OcrSettingsData cfg = OcrSettingsData.configurator().language("ara").build();

        assertThrows(UnsupportedOcrLanguageException.class,
                () -> TesseractOcrProvider.configureTesseract(cfg));
    }

    // ------------------------------------------------------------------
    // The packaged chi_sim model is genuinely usable, not just present on disk.
    //
    // This proves the full real pipeline -- tessdata lookup, native library load, chi_sim.traineddata
    // load, and an actual Tesseract inference pass -- completes successfully with chi_sim selected.
    // It does NOT assert accurate recognition of real Chinese glyphs: rendering genuine CJK text
    // needs a CJK-capable font, which isn't guaranteed on a bare Linux CI runner (unlike the Latin
    // fonts every JDK/CI image ships), so asserting exact character output here would risk a
    // flaky/false-negative test on infrastructure this repo doesn't control. That gap -- proving
    // actual multilingual chat text reads correctly -- needs a real captured chat frame from a
    // multilingual account once the chat-capture feature itself exists to produce one.
    // ------------------------------------------------------------------

    @Test
    void chiSimTrainedDataModelLoadsAndRunsARealRecognitionPassWithoutError() {
        OcrSettingsData cfg = OcrSettingsData.configurator().language("chi_sim").build();
        BufferedImage image = renderText(new String[]{"12345"}, 300, 80);

        assertDoesNotThrow(() -> new TesseractOcrProvider().recognizeText(image, cfg));
    }

    // ------------------------------------------------------------------
    // preserveLineBreaks -- real effect on real recognition output, not just a flag being read.
    // ------------------------------------------------------------------

    @Test
    void preserveLineBreaksKeepsMultipleLinesSeparateInRealRecognitionOutput() throws OcrException {
        BufferedImage image = renderText(new String[]{"first line", "second line"}, 400, 140);
        OcrSettingsData cfg = OcrSettingsData.configurator()
                .textLayout(OcrSettingsData.TextLayout.TEXT_BLOCK)
                .preserveLineBreaks(true)
                .build();

        String recognized = new TesseractOcrProvider().recognizeText(image, cfg);

        assertTrue(recognized.contains("\n"),
                "Two visually separate lines should still be two lines in the output: '" + recognized + "'");
    }

    @Test
    void defaultBehaviorFlattensMultipleLinesIntoOne() throws OcrException {
        BufferedImage image = renderText(new String[]{"first line", "second line"}, 400, 140);
        OcrSettingsData cfg = OcrSettingsData.configurator()
                .textLayout(OcrSettingsData.TextLayout.TEXT_BLOCK)
                .preserveLineBreaks(false)
                .build();

        String recognized = new TesseractOcrProvider().recognizeText(image, cfg);

        assertFalse(recognized.contains("\n"),
                "Without preserveLineBreaks the result should be flattened to one line: '" + recognized + "'");
    }

    // ------------------------------------------------------------------
    // LF/CRLF normalization -- pure string transforms, no engine/image needed.
    // ------------------------------------------------------------------

    @Test
    void normalizeSingleLineDropsEveryLineEndingStyle() {
        assertEquals("ab", TesseractOcrProvider.normalizeSingleLine("a\nb"));
        assertEquals("ab", TesseractOcrProvider.normalizeSingleLine("a\r\nb"));
        assertEquals("ab", TesseractOcrProvider.normalizeSingleLine("a\rb"));
        assertEquals("hello", TesseractOcrProvider.normalizeSingleLine("  hello  \n"));
    }

    @Test
    void normalizeMultilineStripsCrSoWindowsLineEndingsCollapseToPlainLf() {
        // Tesseract emits plain \n on this platform; \r\n only shows up if something upstream
        // (or the OS pipe) reintroduces it. Stripping \r turns \r\n into a clean \n rather than
        // leaving a stray \r riding along on every line.
        assertEquals("a\nb", TesseractOcrProvider.normalizeMultiline("a\r\nb"));
        assertEquals("a\nb", TesseractOcrProvider.normalizeMultiline("a\nb"));
        assertEquals("ab", TesseractOcrProvider.normalizeMultiline("a\rb")); // bare \r alone isn't a line break, just noise -- dropped
        assertEquals("a\nb", TesseractOcrProvider.normalizeMultiline("  a\nb  "));
    }

    // ------------------------------------------------------------------

    /** Renders plain black text on a white background -- real Latin glyphs, guaranteed available
     *  on every JDK/CI image (unlike CJK fonts), for genuine end-to-end recognition tests. */
    private static BufferedImage renderText(String[] lines, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.PLAIN, 28));
        int y = 40;
        for (String line : lines) {
            g.drawString(line, 15, y);
            y += 45;
        }
        g.dispose();
        return image;
    }
}
