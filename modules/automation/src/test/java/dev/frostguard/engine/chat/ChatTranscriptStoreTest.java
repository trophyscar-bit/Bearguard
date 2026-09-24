package dev.frostguard.engine.chat;

import dev.frostguard.api.chat.ChatMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Evidence level: automated tests.
 */
class ChatTranscriptStoreTest {

    @TempDir
    Path dir;

    private static ChatMessage msg(Instant at, String author, String body) {
        return new ChatMessage(at, "world", author, "INF", 0, body, "", List.of(),
                ChatMessage.Kind.TEXT, "");
    }

    private ChatTranscriptStore store() {
        return new ChatTranscriptStore(dir, ZoneOffset.UTC);
    }

    @Test
    void writesMessagesIntoTheDayFileTheyBelongTo() throws IOException {
        Instant at = Instant.parse("2026-08-21T22:15:00Z");
        ChatTranscriptStore s = store();

        assertEquals(1, s.append(List.of(msg(at, "Nightjar", "rally in five"))));

        Path day = dir.resolve("chat-2026-08-21.jsonl");
        assertTrue(Files.exists(day), "expected a file named for the capture's day");
        assertTrue(Files.readString(day, StandardCharsets.UTF_8).contains("rally in five"));
    }

    @Test
    void aMessageSeenAgainOnAnOverlappingScrollBackIsNotStoredTwice() throws IOException {
        // Each pass deliberately re-reads screens it already captured, so most arriving rows are
        // repeats. Without this the transcript would grow by the whole scroll-back every cycle.
        Instant at = Instant.parse("2026-08-21T22:15:00Z");
        ChatTranscriptStore s = store();

        assertEquals(1, s.append(List.of(msg(at, "Nightjar", "rally in five"))));
        assertEquals(0, s.append(List.of(msg(at.plusSeconds(90), "Nightjar", "rally in five"))));
    }

    @Test
    void unreadableRowsNeverReachTheTranscript() throws IOException {
        ChatMessage junk = new ChatMessage(Instant.parse("2026-08-21T22:15:00Z"), "world", "",
                "", 0, "", "", List.of(), ChatMessage.Kind.UNREADABLE, "");

        assertEquals(0, store().append(List.of(junk)));
    }

    @Test
    void restartDoesNotReAppendWhatIsAlreadyOnDisk() throws IOException {
        // The de-duplication window only ever holds this session's messages, so a fresh store has
        // to learn what the previous run already wrote before the next overlapping pass arrives.
        Instant at = Instant.parse("2026-08-21T22:15:00Z");
        store().append(List.of(msg(at, "Nightjar", "rally in five")));

        ChatTranscriptStore restarted = store();
        restarted.primeFromDisk();

        assertEquals(0, restarted.append(List.of(msg(at, "Nightjar", "rally in five"))));
    }

    @Test
    void readsBackRecentMessagesOldestFirst() throws IOException {
        Instant at = Instant.parse("2026-08-21T22:15:00Z");
        ChatTranscriptStore s = store();
        s.append(List.of(msg(at, "Nightjar", "first"), msg(at.plusSeconds(60), "Marisol", "second")));

        List<ChatMessage> back = s.recent(10);

        assertEquals(2, back.size());
        assertEquals("first", back.get(0).body());
        assertEquals("second", back.get(1).body());
    }

    @Test
    void aTruncatedFinalLineCostsOneMessageRatherThanTheDay() throws IOException {
        Instant at = Instant.parse("2026-08-21T22:15:00Z");
        ChatTranscriptStore s = store();
        s.append(List.of(msg(at, "Nightjar", "intact")));
        // Append-only writing across a restart or power cut can leave a half-written last line.
        Files.writeString(dir.resolve("chat-2026-08-21.jsonl"), "{\"at\":\"2026-08-2",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        List<ChatMessage> back = s.recent(10);

        assertEquals(1, back.size());
        assertEquals("intact", back.get(0).body());
    }

    @Test
    void framesAreDeletedOnceTheirRowsAreStored() throws IOException {
        Path frame = Files.writeString(dir.resolve("world-1.png"), "x");

        ChatTranscriptStore.retireFrame(frame, true);

        assertFalse(Files.exists(frame), "a frame that was read has no reason to stay on disk");
    }

    @Test
    void aFrameIsKeptWhenReadingItFailedSoThereIsSomethingToLookAt() throws IOException {
        Path frame = Files.writeString(dir.resolve("world-2.png"), "x");

        ChatTranscriptStore.retireFrame(frame, false);

        assertTrue(Files.exists(frame), "a failed capture must leave its frame for diagnosis");
    }

    @Test
    void purgeRemovesWholeDaysPastTheRetentionWindow() throws IOException {
        ChatTranscriptStore s = store();
        Instant old = Instant.now().minus(40, ChronoUnit.DAYS);
        Instant fresh = Instant.now();
        s.append(List.of(msg(old, "Nightjar", "ancient")));
        s.append(List.of(msg(fresh, "Marisol", "today")));

        assertEquals(1, s.purgeOlderThan(30));
        assertFalse(Files.exists(s.fileFor(old)));
        assertTrue(Files.exists(s.fileFor(fresh)));
    }

    @Test
    void messagesEitherSideOfMidnightGoToTheirOwnDaysNotTheFirstOnesDay() throws IOException {
        // One pass used to file everything under the day of its first message, so a reconcile whose
        // estimated times span days -- or an ordinary pass straddling midnight -- put the later
        // messages into the wrong day's file and out of that day's digest.
        Instant late = Instant.parse("2026-08-21T23:59:00Z");
        Instant early = Instant.parse("2026-08-22T00:01:00Z");
        ChatTranscriptStore s = store();

        assertEquals(2, s.append(List.of(msg(late, "Nightjar", "rally in five"),
                msg(early, "Marisol", "bear trap tonight at nine"))));

        assertTrue(Files.readString(s.fileFor(late)).contains("rally in five"));
        assertFalse(Files.readString(s.fileFor(late)).contains("bear trap"));
        assertTrue(Files.readString(s.fileFor(early)).contains("bear trap tonight at nine"));
    }

    @Test
    void saysWhatTimeAStoredMessageWasFiledUnderIncludingAfterARestart() throws IOException {
        Instant at = Instant.parse("2026-08-21T22:15:00Z");
        ChatTranscriptStore s = store();
        s.append(List.of(msg(at, "Nightjar", "rally in five")));

        // Read the way the walk reads it: a different spelling of the same message still finds it.
        assertEquals(java.util.Optional.of(at),
                s.storedAt(msg(at.plusSeconds(600), "Nightjar", "rally in five")));
        assertEquals(java.util.Optional.empty(),
                s.storedAt(msg(at, "Marisol", "who has spare speedups")));

        ChatTranscriptStore restarted = store();
        restarted.primeFromDisk();
        assertEquals(java.util.Optional.of(at),
                restarted.storedAt(msg(at, "Nightjar", "rally in five")));
    }

    @Test
    void anEstimatedMessageIsMarkedInItsLineAndAnOrdinaryOneIsNot() throws IOException {
        Instant at = Instant.parse("2026-08-21T22:15:00Z");
        ChatTranscriptStore s = store();

        s.append(List.of(msg(at, "Nightjar", "rally in five")), true);
        s.append(List.of(msg(at, "Marisol", "bear trap tonight at nine")), false);

        List<String> lines = Files.readAllLines(s.fileFor(at));
        assertTrue(lines.get(0).contains("\"est\":true"));
        assertFalse(lines.get(1).contains("\"est\""));
    }

    @Test
    void aWiderWindowKeepsMessagesFromLookingNewOnceTheyAreOlderThanTheOrdinaryOne() throws IOException {
        Instant at = Instant.parse("2026-08-21T22:15:00Z");
        ChatTranscriptStore narrow = new ChatTranscriptStore(dir.resolve("narrow"), ZoneOffset.UTC, 2);
        ChatTranscriptStore wide = new ChatTranscriptStore(dir.resolve("wide"), ZoneOffset.UTC, 2);
        wide.widenWindow(10);
        List<ChatMessage> three = List.of(msg(at, "A", "rally in five"),
                msg(at, "B", "bear trap tonight at nine"),
                msg(at, "C", "who has spare speedups"));
        narrow.append(three);
        wide.append(three);

        // Two slots hold the last two; the first has fallen out and reads as new again.
        assertEquals(1, narrow.append(List.of(msg(at, "A", "rally in five"))));
        assertEquals(0, wide.append(List.of(msg(at, "A", "rally in five"))));
    }

    @Test
    void reportsItsOwnSizeForTheStartupFigure() throws IOException {
        ChatTranscriptStore s = store();
        s.append(List.of(msg(Instant.parse("2026-08-21T22:15:00Z"), "Nightjar", "rally in five")));

        assertTrue(s.sizeBytes() > 0);
        assertEquals("2.0 KB", ChatTranscriptStore.humanSize(2048));
        assertEquals("512 B", ChatTranscriptStore.humanSize(512));
    }
}
