package dev.frostguard.tasks.social;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * What the reader is allowed to call a message in another script.
 *
 * <p>Every string here came off a live pass. The accepted ones are messages players actually sent;
 * the rejected ones are what the reader returned when it was handed a bubble ornament or a piece of
 * a Latin sentence and asked to read it as Chinese.
 */
class ChatScriptRecoveryTest {

    @Test
    void acceptsAMessageWrittenInAnotherScript() {
        assertTrue(ChatScriptRecovery.isMostlyAnotherScript("내아내사랑해가안잊혀져"));
        assertTrue(ChatScriptRecovery.isMostlyAnotherScript("恭喜"));
    }

    @Test
    void rejectsGlyphsScatteredThroughLatinText() {
        // Stored and translated as written before this test existed.
        assertFalse(ChatScriptRecovery.isMostlyAnotherScript("sr 名 sr 名 大 missed you."));
        assertFalse(ChatScriptRecovery.isMostlyAnotherScript("Buen trabajo chicos ウラ I NFIXXX!"));
        assertFalse(ChatScriptRecovery.isMostlyAnotherScript("We are grateful to have you 付"));
    }

    @Test
    void rejectsASingleStrayGlyph() {
        assertFalse(ChatScriptRecovery.isMostlyAnotherScript("付"));
        assertFalse(ChatScriptRecovery.isMostlyAnotherScript("baz (た"));
    }

    @Test
    void stillSeesAWordThatNeedsNoSecondReading() {
        assertTrue(ChatScriptRecovery.hasReadableWord("congrats"));
        assertFalse(ChatScriptRecovery.hasReadableWord("g2 - E o"));
    }
}
