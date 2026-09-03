package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import dev.frostguard.vision.convert.RegexNumberParser;

/**
 * Pins the two Alliance Trade number parses in {@link MonumentRoutine} to the exact OCR strings
 * the live run produced.
 *
 * <p>Written after a live failure that cost every Monument pass for at least ten days: the My
 * Requests counter was being OCR'd perfectly and then thrown away by the parse. Logged verbatim
 * 2026-08-30 22:20:06 --
 * {@code "Not filing a new request: counter parsed as null from raw text 'Requests Left Today (3/3)'"}.
 *
 * <p>Root cause: {@link RegexNumberParser#extractByPattern} anchors with {@link
 * java.util.regex.Matcher#matches()}, which requires the pattern to describe the WHOLE string.
 * Both Monument call sites passed a pattern describing only the fragment they wanted, so neither
 * could ever match. Every other extractByPattern caller in the codebase anchors with
 * {@code ".*?(\\d+).*"}; these two were the only ones that did not.
 *
 * <p>Evidence level: real logged OCR strings, copied verbatim from account_Default_1.log.
 */
class MonumentTradeCounterParseTest {

    /** Verbatim from the 2026-08-30 22:20 log line. */
    private static final String LIVE_COUNTER_TEXT = "Requests Left Today (3/3)";

    /** Copy of the production pattern at MonumentRoutine#processAllianceTradeSends. */
    private static final Pattern OWNED_PATTERN = Pattern.compile(".*?(\\d+).*");

    // ---- the fix ----

    @Test
    void parsesTheLiveRequestsLeftCounter() {
        assertEquals(3, RegexNumberParser.numerator(LIVE_COUNTER_TEXT),
                "the exact string the live run read and then discarded");
    }

    @Test
    void readsAnExhaustedCounterAsZeroRatherThanNull() {
        // Must be 0, not null: MonumentRoutine treats null as "could not read" and dumps a
        // diagnostic frame, while 0 is the genuine "no requests left today" answer.
        assertEquals(0, RegexNumberParser.numerator("Requests Left Today (0/3)"));
    }

    @Test
    void toleratesTheOcrWhitespaceSeenAroundTheSlash() {
        assertEquals(2, RegexNumberParser.numerator("Requests Left Today ( 2 / 3 )"));
    }

    @Test
    void parsesOwnedCountsThatCarryTheirLabel() {
        assertEquals(1, RegexNumberParser.extractByPattern("Owned: 1", OWNED_PATTERN));
        assertEquals(2, RegexNumberParser.extractByPattern("Owned: 2", OWNED_PATTERN));
        assertEquals(2, RegexNumberParser.extractByPattern("2", OWNED_PATTERN));
    }

    // ---- regression pins: the old patterns, proving they could never have worked ----

    @Test
    void oldRequestsPatternCouldNeverMatchTheLiveString() {
        assertNull(RegexNumberParser.extractByPattern(
                        LIVE_COUNTER_TEXT, Pattern.compile("\\((\\d+)\\s*/")),
                "if this ever starts returning a value, extractByPattern stopped anchoring "
                        + "and the rest of its ten callers need re-checking");
    }

    @Test
    void oldOwnedPatternCouldNeverMatchALabelledRow() {
        assertNull(RegexNumberParser.extractByPattern(
                "Owned: 1", Pattern.compile("(\\d+)")));
    }
}
