package dev.frostguard.tasks.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import dev.frostguard.api.chat.ChatMessage;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.layout.ChatRowSegmenter;

import org.junit.jupiter.api.Test;

/**
 * Drives the reader with a stub in place of OCR, so the row-to-message rules are exercised without
 * a live screen. Names are invented; the input shapes are the ones live captures produce.
 *
 * <p>Evidence level: automated tests.
 */
class ChatRowReaderTest {

    private static final Instant AT = Instant.parse("2026-08-21T22:15:00Z");

    /**
     * The reader takes the whole row in one call and treats the first line as the sender, so the
     * stub returns both joined the way the reader emits them.
     */
    private static java.util.function.BiFunction<PointData, PointData, String> reader(
            String name, String body) {
        return (tl, br) -> name + (char) 10 + body;
    }

    private static List<ChatRowSegmenter.Row> oneRow() {
        return List.of(new ChatRowSegmenter.Row(0, 200, 0, 112));
    }

    @Test
    void readsNameAndBodyAsSeparateRegionsSoTheyCannotMerge() {
        List<ChatMessage> out = ChatRowReader.read(oneRow(),
                reader("VIP5 [BAE]Nightjar", "rally in five minutes"),
                "world", AT, b -> Optional.empty());

        assertEquals(1, out.size());
        ChatMessage m = out.get(0);
        assertEquals("Nightjar", m.author());
        assertEquals("BAE", m.allianceTag());
        // VIP rank is discarded on the way through; it is not part of the transcript.
        assertEquals(0, m.vipLevel());
        assertEquals("rally in five minutes", m.body());
    }

    @Test
    void messageIsKeptWithAnUnknownAuthorWhenTheNameStripCouldNotBeTrusted() {
        // The message is real even when the name read badly. Dropping it loses chat; inventing a
        // participant from "34 ERE" is worse than admitting the attribution is unknown.
        List<ChatMessage> out = ChatRowReader.read(oneRow(),
                reader("34 ERE", "does anyone have spare meat"),
                "world", AT, b -> Optional.empty());

        assertEquals(1, out.size());
        assertEquals("", out.get(0).author());
        assertEquals("does anyone have spare meat", out.get(0).body());
    }

    @Test
    void rowWithNothingLegibleInTheBubbleIsDroppedEntirely() {
        List<ChatMessage> out = ChatRowReader.read(oneRow(),
                reader("[INF]Marisol", "| = ~"),
                "world", AT, b -> Optional.empty());

        assertTrue(out.isEmpty(), "a bubble that cleans down to nothing is not a message");
    }

    @Test
    void foreignBodyCarriesItsEnglishRenderingAlongsideTheOriginal() {
        List<ChatMessage> out = ChatRowReader.read(oneRow(),
                reader("[THE]Sec", "Teşekkürler"),
                "world", AT, b -> Optional.of("Thank you"));

        ChatMessage m = out.get(0);
        assertEquals("Teşekkürler", m.body(), "the original must survive translation");
        assertEquals("Thank you", m.translated());
        assertEquals("Thank you", m.displayBody());
    }

    @Test
    void aSystemCardIsNotSentForTranslation() {
        // Game-generated cards are events, not speech. Translating them spends a lookup on text no
        // player wrote and that reads the same in every language.
        boolean[] asked = {false};
        List<ChatMessage> out = ChatRowReader.read(oneRow(),
                reader("[INF]Marisol", "Share Coordinates"),
                "world", AT, b -> {
                    asked[0] = true;
                    return Optional.of("nope");
                });

        assertEquals(ChatMessage.Kind.SYSTEM, out.get(0).kind());
        assertTrue(out.get(0).translated().isEmpty());
        assertTrue(!asked[0], "a system card should never reach the translator");
    }

    @Test
    void mentionsAreCarriedSoTheRendererCanHighlightThem() {
        List<ChatMessage> out = ChatRowReader.read(oneRow(),
                reader("[BAE]Nightjar", "@Marisol bring lancers"),
                "world", AT, b -> Optional.empty());

        assertEquals(List.of("Marisol"), out.get(0).mentions());
    }
}
