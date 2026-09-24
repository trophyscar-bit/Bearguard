package dev.frostguard.engine.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.chat.ChatMessage;
import dev.frostguard.engine.chat.ChatTimeEstimator.Candidate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ChatTimeEstimatorTest {

    private static final Instant T0 = Instant.parse("2026-09-24T02:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-24T05:00:00Z");

    private static ChatMessage msg(String body) {
        return new ChatMessage(NOW, "alliance", "Nightjar", "INF", 0, body, "", List.of(),
                ChatMessage.Kind.TEXT, "");
    }

    private static Candidate known(String body, Instant at) {
        return new Candidate(msg(body), at);
    }

    private static Candidate unknown(String body) {
        return new Candidate(msg(body), null);
    }

    @Test
    void spreadsAGapEvenlyBetweenTheStoredMessagesEitherSideOfIt() {
        List<ChatMessage> placed = ChatTimeEstimator.place(List.of(
                known("before", T0),
                unknown("a"), unknown("b"), unknown("c"),
                known("after", T0.plus(Duration.ofMinutes(60)))), NOW);

        assertEquals(List.of("a", "b", "c"), placed.stream().map(ChatMessage::body).toList());
        assertEquals(T0.plus(Duration.ofMinutes(15)), placed.get(0).capturedAt());
        assertEquals(T0.plus(Duration.ofMinutes(30)), placed.get(1).capturedAt());
        assertEquals(T0.plus(Duration.ofMinutes(45)), placed.get(2).capturedAt());
    }

    @Test
    void neverReturnsAMessageTheTranscriptAlreadyHolds() {
        List<ChatMessage> placed = ChatTimeEstimator.place(List.of(
                known("one", T0), known("two", T0.plusSeconds(10)), unknown("new")), NOW);

        assertEquals(1, placed.size());
        assertEquals("new", placed.get(0).body());
    }

    @Test
    void aRunNewerThanEverythingStoredIsBoundedByNow() {
        List<ChatMessage> placed = ChatTimeEstimator.place(List.of(
                known("last stored", T0), unknown("x"), unknown("y"), unknown("z")), NOW);

        // Three hours between the last stored message and now, four slots.
        assertEquals(T0.plus(Duration.ofMinutes(45)), placed.get(0).capturedAt());
        assertEquals(T0.plus(Duration.ofMinutes(90)), placed.get(1).capturedAt());
        assertEquals(T0.plus(Duration.ofMinutes(135)), placed.get(2).capturedAt());
        assertTrue(placed.get(2).capturedAt().isBefore(NOW));
    }

    @Test
    void aRunOlderThanEverythingStoredIsCountedBackFromTheFirstStoredMessage() {
        List<ChatMessage> placed = ChatTimeEstimator.place(List.of(
                unknown("old1"), unknown("old2"), known("first stored", T0)), NOW);

        assertEquals(T0.minus(ChatTimeEstimator.FALLBACK_SPACING.multipliedBy(2)),
                placed.get(0).capturedAt());
        assertEquals(T0.minus(ChatTimeEstimator.FALLBACK_SPACING), placed.get(1).capturedAt());
    }

    @Test
    void withNothingStoredAtAllTheRunIsCountedBackFromNow() {
        List<ChatMessage> placed = ChatTimeEstimator.place(
                List.of(unknown("a"), unknown("b")), NOW);

        assertEquals(NOW.minus(ChatTimeEstimator.FALLBACK_SPACING.multipliedBy(2)),
                placed.get(0).capturedAt());
        assertEquals(NOW.minus(ChatTimeEstimator.FALLBACK_SPACING), placed.get(1).capturedAt());
    }

    @Test
    void aVeryLongOldRunIsNotSpreadBeyondTheReachLimit() {
        List<Candidate> run = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            run.add(unknown("m" + i));
        }
        run.add(known("anchor", T0));

        List<ChatMessage> placed = ChatTimeEstimator.place(run, NOW);

        assertEquals(T0.minus(ChatTimeEstimator.MAX_REACH), placed.get(0).capturedAt());
    }

    @Test
    void storedTimesThatRunBackwardsDoNotPutAMessageBeforeItsNeighbour() {
        List<ChatMessage> placed = ChatTimeEstimator.place(List.of(
                known("late estimate", T0.plus(Duration.ofMinutes(50))),
                unknown("between"),
                known("earlier reading", T0)), NOW);

        assertEquals(1, placed.size());
        assertFalse(placed.get(0).capturedAt().isBefore(T0.plus(Duration.ofMinutes(50))));
    }

    @Test
    void aClockThatIsBehindTheLastStoredMessageDoesNotInventAnEarlierTime() {
        List<ChatMessage> placed = ChatTimeEstimator.place(List.of(
                known("stored", NOW.plusSeconds(600)), unknown("x")), NOW);

        assertEquals(NOW.plusSeconds(600), placed.get(0).capturedAt());
    }
}
