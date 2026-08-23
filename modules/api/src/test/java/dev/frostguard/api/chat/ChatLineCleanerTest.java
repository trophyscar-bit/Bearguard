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
    void splitsTheFullestSenderFormAndDiscardsTheVipBadge() {
        ChatLineCleaner.Sender s = ChatLineCleaner.parseSender("VIP7 [THE]Nightjar");

        assertEquals("Nightjar", s.name());
        assertEquals("THE", s.allianceTag());
        assertTrue(s.trusted());
        // VIP rank is not kept. It is shown on some senders and not others, carries nothing worth
        // recording, and reading it is what let a mangled badge reach the name.
        assertEquals(0, s.vipLevel());
    }

    @Test
    void aMangledVipBadgeCannotReachTheName() {
        // The exact shapes seen in the live transcript, where a misread badge became the author.
        for (String line : new String[] {
            "ViIPO LINE [INF]Chanyu", "SS} VirP/ [BAE]Mejbreach", "& VIP6 [INF]CrisdeuS"}) {
            ChatLineCleaner.Sender s = ChatLineCleaner.parseSender(line);
            assertTrue(s.trusted(), "should trust the name in " + line);
            assertEquals(0, s.vipLevel());
            assertFalse(s.name().toUpperCase(java.util.Locale.ROOT).contains("VIP"),
                    "badge leaked into the name: " + s.name());
        }
    }

    @Test
    void theNameIsWhateverFollowsTheAllianceTag() {
        assertEquals("CrisdeuS", ChatLineCleaner.parseSender("& VIP6 [INF]CrisdeuS").name());
        assertEquals("Chanyu", ChatLineCleaner.parseSender("ViIPO LINE [INF]Chanyu").name());
    }

    @Test
    void theClosingBracketIsAcceptedInEveryShapeTheReaderProduces() {
        // Measured across 232 rows from 50 live alliance frames: the closing bracket comes back as
        // a literal ] in only a handful and as j, J or | in most of the rest. Insisting on ]
        // recognised 2 sender lines out of 213; accepting the real shapes recognises 181.
        for (String line : new String[] {
            "VIP6 [INF]AthenaRyu", "VIPG [INF jAthenaRyu", "VIP6 [INF|Nicko .",
            "VIPG [INFJAthenaRyu", "VIP6 (INF]Nicko"}) {
            ChatLineCleaner.Sender s = ChatLineCleaner.parseSender(line);
            assertEquals("INF", s.allianceTag(), "tag not read from: " + line);
            assertTrue(s.trusted(), "should trust: " + line);
        }
    }

    @Test
    void trailingDecorationIsTrimmedOffTheName() {
        // "Nicko ." and "AthenaRyu 7" are the same two players with a speck of the avatar frame
        // caught on the end of the line.
        assertEquals("Nicko", ChatLineCleaner.parseSender("VIP6 [INF|Nicko .").name());
        assertEquals("AthenaRyu", ChatLineCleaner.parseSender("VIP6 [INF]AthenaRyu 7").name());
        assertEquals("AthenaRyu", ChatLineCleaner.parseSender("VIP6 [INF]AthenaRyu -").name());
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
    void aNameFullOfInventedPunctuationIsNotTrusted() {
        // Seen rendering as real participants in the live transcript: the reader turning a VIP badge
        // and an alliance tag into braces and slashes.
        for (String junk : new String[] {"S} ViP/ TRejbreach", "fe MPA) /UIBGeULClICr", "SS} VirP/ TMejbreach"}) {
            assertFalse(ChatLineCleaner.parseSender(junk).trusted(), "should not trust " + junk);
        }
    }

    @Test
    void aSenderLineWithTheMessageRunIntoItIsNotTrusted() {
        // Seen live: the reader joins a sender line to the words that follow it, and the whole
        // sentence was stored as the author. No player name is a sentence long.
        assertFalse(ChatLineCleaner.parseSender(
                "[INF]Mojorisinfans se Tengo a Natalia y no la he podido").trusted());
    }

    @Test
    void ordinaryNamesSurviveTheTighterCheck() {
        for (String ok : new String[] {"TheFlyingDutch", "Kim Jong Um", "Mrs_Lasanha", "Snoopy!R"}) {
            assertTrue(ChatLineCleaner.parseSender(ok).trusted(), "should trust " + ok);
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
    void accentedLatinIsForeignEvenWhenTheMessageIsTooShortToScoreWords() {
        // Turkish appears in live world chat. It is Latin script, so the script test alone passes
        // it, and at two words it is below the threshold the word test needs -- the diacritics are
        // what catch it.
        assertFalse(ChatLineCleaner.looksEnglish("Teşekkürler işçi"));
    }

    @Test
    void unaccentedForeignTextIsCaughtByItsLackOfEnglishFunctionWords() {
        // The real miss: Turkish and Spanish written in plain ASCII scored as confidently English
        // under the old ratio test, so every foreign message on a live pass went untranslated.
        assertFalse(ChatLineCleaner.looksEnglish("bana nazik bir sekilde maymun dedi"));
        assertFalse(ChatLineCleaner.looksEnglish("Y de onde eres vos amigo"));
    }

    @Test
    void ordinaryEnglishStillReadsAsEnglishAndIsNeverSent() {
        assertTrue(ChatLineCleaner.looksEnglish("who uses outlook?"));
        assertTrue(ChatLineCleaner.looksEnglish("i pray for you"));
        assertTrue(ChatLineCleaner.looksEnglish("A quick google search told me Latin America"));
    }

    @Test
    void quotedReplyIsSeparatedFromTheSendersOwnWords() {
        // A reply renders the original inside the same bubble. Measured on a live pass, 84 of 295
        // messages carried someone else's sentence appended to their own.
        ChatLineCleaner.Body b = ChatLineCleaner.splitQuotedReply(
                String.join("\n", "@Rhaegar it still does",
                        "Rhaegar: JK i know outlook doesnt say that"));

        assertEquals("@Rhaegar it still does", b.own());
        assertTrue(b.quoted().startsWith("Rhaegar:"));
    }

    @Test
    void aNameMentionedMidSentenceDoesNotSplitTheMessage() {
        // Taking the FIRST name-colon match splits on a mention rather than the quote, which is why
        // the boundary is the last one.
        ChatLineCleaner.Body b = ChatLineCleaner.splitQuotedReply("TheFlyingDutch same");

        assertEquals("TheFlyingDutch same", b.own());
        assertTrue(b.quoted().isEmpty());
    }

    @Test
    void theAlliancePollCardIsNotMistakenForConversation() {
        // Pinned above the feed with a clipboard icon the segmenter reads as an avatar, so it
        // arrives as a message on every pass. These are its rows exactly as the reader returns them.
        for (String card : new String[] {
            "selection] Which Bear Trap do you...",
            "_ Participants: 49/98 (Have Not Participate",
            "Initiator: AthenaRyu",
            "Vote in: 11:29:34",
            "Vote"}) {
            assertEquals(ChatMessage.Kind.SYSTEM, ChatLineCleaner.classify(card), "should be furniture: " + card);
        }
    }

    @Test
    void talkingAboutTheVoteIsStillConversation() {
        assertEquals(ChatMessage.Kind.TEXT, ChatLineCleaner.classify("did everyone vote yet"));
    }

    @Test
    void gameChatterIsRecognisedSoItCanBeHidden() {
        assertTrue(ChatLineCleaner.isNonSpeech("[BAE]Qwert recalled a message"));
        assertTrue(ChatLineCleaner.isNonSpeech("Share layout"));
        assertFalse(ChatLineCleaner.isNonSpeech("who uses outlook?"));
    }

    @Test
    void cacheKeyIgnoresCasingAndSpacingSoARepeatedPhraseIsFetchedOnce() {
        assertEquals(ChatLineCleaner.cacheKey("Join   the RALLY"),
                ChatLineCleaner.cacheKey("join the rally"));
    }
}
