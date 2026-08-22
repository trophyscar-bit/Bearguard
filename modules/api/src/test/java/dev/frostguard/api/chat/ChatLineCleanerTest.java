package dev.frostguard.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Cases are the shapes measured across 1,929 live chat messages, rewritten with invented names so
 * no real player, alliance or account appears in the repository.
 *
 * <p>Evidence level: automated tests, with every pattern taken from live capture data.
 */
class ChatLineCleanerTest {

    @Test
    void splitsTheFullestSenderFormIntoVipTagAndName() {
        ChatLineCleaner.Sender s = ChatLineCleaner.parseSender("VIP7 [THE]Nightjar");

        assertEquals("Nightjar", s.name());
        assertEquals("THE", s.allianceTag());
        assertEquals(7, s.vipLevel());
        assertTrue(s.trusted());
    }

    @Test
    void readsATaggedSenderWhenTheReaderMistakesTheBracketForAParenthesis() {
        // Live captures carry both forms for the same tag; the leading bracket is thin enough that
        // the reader flips it, and a bracket-only pattern silently drops those senders.
        ChatLineCleaner.Sender s = ChatLineCleaner.parseSender("(INF]Marisol");

        assertEquals("Marisol", s.name());
        assertEquals("INF", s.allianceTag());
        assertTrue(s.trusted());
    }

    @Test
    void frameFurnitureCaughtByTheNameStripIsNotTrustedAsAPlayer() {
        // These are the observed garbage shapes: digit-led runs and punctuation fragments picked
        // up from borders and timers. Rendering them would invent participants.
        for (String junk : new String[] {"34 ERE", "- in", "11", "ae", "..."}) {
            assertFalse(ChatLineCleaner.parseSender(junk).trusted(),
                    "should not trust " + junk + " as a sender");
        }
    }

    @Test
    void stripsTheReadersInventedCharactersFromTheBody() {
        // | and = are the two most common characters in the whole corpus and no player types them
        // here -- they are bubble borders and the per-message translate control.
        assertEquals("Thanks for the rally",
                ChatLineCleaner.cleanBody("| = Thanks for the rally ~ ®"));
    }

    @Test
    void collectsMentionsIncludingTheSpacedTaggedFormTheReaderProduces() {
        List<String> mentions = ChatLineCleaner.mentions("@Nightjar and @ [INF]Marisol please rally");

        assertEquals(List.of("Nightjar", "Marisol"), mentions);
    }

    @Test
    void repeatedMentionOfTheSamePlayerIsListedOnce() {
        assertEquals(List.of("Nightjar"), ChatLineCleaner.mentions("@Nightjar hey @Nightjar"));
    }

    @Test
    void classifiesGameCardsAsSystemRatherThanPlayerSpeech() {
        assertEquals(ChatMessage.Kind.SYSTEM, ChatLineCleaner.classify("Lucky Pouch"));
        assertEquals(ChatMessage.Kind.SYSTEM, ChatLineCleaner.classify("Share Coordinates"));
    }

    @Test
    void bodyWithTooFewLettersIsUnreadableRatherThanText() {
        // A bubble that reduced to "&" or "." carries nothing to read or translate; calling it
        // text puts noise in the transcript and spends a lookup on it.
        assertEquals(ChatMessage.Kind.UNREADABLE, ChatLineCleaner.classify("&"));
        assertEquals(ChatMessage.Kind.UNREADABLE, ChatLineCleaner.classify("."));
    }

    @Test
    void englishBodyIsRecognisedLocallySoItNeverLeavesTheMachine() {
        assertTrue(ChatLineCleaner.looksEnglish("good morning everyone"));
        assertTrue(ChatLineCleaner.looksEnglish("Thank you"));
    }

    @Test
    void nonLatinScriptIsDecisiveOnItsOwn() {
        assertFalse(ChatLineCleaner.looksEnglish("윈드님 축하해요 좋다"));
        assertFalse(ChatLineCleaner.looksEnglish("二十四小时"));
        assertFalse(ChatLineCleaner.looksEnglish("مرحبا بالجميع"));
    }

    @Test
    void accentedLatinStillCountsAsForeignWhenItIsNotMostlyAscii() {
        // Turkish appears in live world chat. It is Latin script, so the script test alone passes
        // it; the ASCII ratio is what catches it and sends it for translation.
        assertFalse(ChatLineCleaner.looksEnglish("Teşekkürler işçi"));
    }

    @Test
    void cacheKeyIgnoresCasingAndSpacingSoARepeatedPhraseIsFetchedOnce() {
        assertEquals(ChatLineCleaner.cacheKey("Join   the RALLY"),
                ChatLineCleaner.cacheKey("join the rally"));
    }
}
