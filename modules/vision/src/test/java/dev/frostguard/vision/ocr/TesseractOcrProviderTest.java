package dev.frostguard.vision.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TesseractOcrProviderTest {

    @Test
    void nullOrBlankRequestFallsBackToEnglish() {
        assertEquals("eng", TesseractOcrProvider.resolveSupportedLanguage(null));
        assertEquals("eng", TesseractOcrProvider.resolveSupportedLanguage(""));
        assertEquals("eng", TesseractOcrProvider.resolveSupportedLanguage("   "));
    }

    @Test
    void packagedLanguagesPassThroughUnchanged() {
        assertEquals("eng", TesseractOcrProvider.resolveSupportedLanguage("eng"));
        assertEquals("chi_sim", TesseractOcrProvider.resolveSupportedLanguage("chi_sim"));
        assertEquals("eng+chi_sim", TesseractOcrProvider.resolveSupportedLanguage("eng+chi_sim"));
    }

    @Test
    void unpackagedLanguageFallsBackToEnglish() {
        // Dave's #253 review: Arabic/Russian/Portuguese/Czech/French were selectable in config
        // despite no trained-data model ever being packaged for them.
        assertEquals("eng", TesseractOcrProvider.resolveSupportedLanguage("ara"));
        assertEquals("eng", TesseractOcrProvider.resolveSupportedLanguage("rus"));
    }

    @Test
    void mixedRequestDropsOnlyTheUnsupportedComponent() {
        assertEquals("eng", TesseractOcrProvider.resolveSupportedLanguage("eng+ara"));
        assertEquals("chi_sim", TesseractOcrProvider.resolveSupportedLanguage("ara+chi_sim"));
    }

    @Test
    void requestWithOnlyUnsupportedLanguagesFallsBackToEnglish() {
        assertEquals("eng", TesseractOcrProvider.resolveSupportedLanguage("ara+rus"));
    }
}
