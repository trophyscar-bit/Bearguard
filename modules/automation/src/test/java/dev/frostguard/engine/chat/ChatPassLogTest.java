package dev.frostguard.engine.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Evidence level: automated tests.
 */
class ChatPassLogTest {

    @TempDir
    Path dir;

    @Test
    void hasNoReconcileOnRecordUntilOneIsPhotographed() {
        ChatPassLog log = new ChatPassLog(dir);

        assertEquals(Optional.empty(), log.lastReconcileStart("alliance"));

        log.photographed("alliance", false, 75);
        log.read("alliance", false, 75, 12, true);
        assertEquals(Optional.empty(), log.lastReconcileStart("alliance"),
                "ordinary passes are not reconciles");
    }

    @Test
    void anAlreadyRunReconcileIsRememberedAcrossARestart() {
        new ChatPassLog(dir).photographed("world", true, 150);

        Optional<Instant> found = new ChatPassLog(dir).lastReconcileStart("world");

        assertTrue(found.isPresent());
        assertTrue(Math.abs(found.get().until(Instant.now(), java.time.temporal.ChronoUnit.SECONDS)) < 30);
    }

    @Test
    void eachChannelKeepsItsOwnRecordSoOneNightOfAFailedTabIsNotHiddenByTheOther() {
        ChatPassLog log = new ChatPassLog(dir);
        log.photographed("alliance", true, 150);

        assertTrue(log.lastReconcileStart("alliance").isPresent());
        assertEquals(Optional.empty(), log.lastReconcileStart("world"));
    }

    @Test
    void aReconcileThatWasOnlyReadNotPhotographedDoesNotCountAsStarted() {
        // The photographed line is the start marker. A stray read line -- from a log cut off and
        // resumed -- must not make tonight look done when the device was never used.
        ChatPassLog log = new ChatPassLog(dir);
        log.read("alliance", true, 150, 40, true);

        assertEquals(Optional.empty(), log.lastReconcileStart("alliance"));
    }

    @Test
    void aHalfWrittenLastLineIsIgnoredNotFatal() throws IOException {
        ChatPassLog log = new ChatPassLog(dir);
        log.photographed("alliance", true, 150);
        Files.writeString(dir.resolve(ChatPassLog.FILE), "{\"at\":\"2026-09-2",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertTrue(log.lastReconcileStart("alliance").isPresent());
    }

    @Test
    void aRecentUnbridgedOrdinaryPassIsAHoleUntilAReconcileStarts() {
        ChatPassLog log = new ChatPassLog(dir);
        Instant now = Instant.now();
        java.time.Duration reach = java.time.Duration.ofHours(12);

        assertEquals(false, log.unbridgedSinceLastReconcile("alliance", reach, now));

        log.read("alliance", false, 74, 30, true);
        assertEquals(false, log.unbridgedSinceLastReconcile("alliance", reach, now),
                "a pass that reached known history left no hole");

        log.read("alliance", false, 74, 30, false);
        assertTrue(log.unbridgedSinceLastReconcile("alliance", reach, now));
        assertEquals(false, log.unbridgedSinceLastReconcile("world", reach, now),
                "the other channel is unaffected");

        log.photographed("alliance", true, 150);
        assertEquals(false, log.unbridgedSinceLastReconcile("alliance", reach, now),
                "a reconcile has looked at it since");
    }

    @Test
    void anUnbridgedReconcileIsNotAHoleToReconcileAgain() {
        // Otherwise a gap too old to reach would re-trigger a reconcile on every pass for ever.
        ChatPassLog log = new ChatPassLog(dir);
        log.read("alliance", true, 150, 40, false);

        assertEquals(false, log.unbridgedSinceLastReconcile("alliance",
                java.time.Duration.ofHours(12), Instant.now()));
    }

    @Test
    void aHoleTooOldForAShortReconcileToReachIsIgnored() {
        ChatPassLog log = new ChatPassLog(dir);
        log.read("alliance", false, 74, 30, false);

        assertEquals(false, log.unbridgedSinceLastReconcile("alliance",
                java.time.Duration.ofHours(12), Instant.now().plus(java.time.Duration.ofHours(13))));
    }

    @Test
    void recordsWhetherAReconcileReachedHistoryItAlreadyHad() throws IOException {
        ChatPassLog log = new ChatPassLog(dir);
        log.read("alliance", true, 150, 40, false);

        String written = Files.readString(dir.resolve(ChatPassLog.FILE));
        assertTrue(written.contains("\"bridged\":false"));
        assertTrue(written.contains("\"stored\":40"));
        assertTrue(written.contains("\"mode\":\"reconcile\""));
    }

    @Test
    void aReconcileThatRanOutOfTimeIsNotTheOneTheNextBuildsOn() {
        ChatPassLog log = new ChatPassLog(dir);
        assertEquals(Optional.empty(), log.lastCoveredReconcile("alliance"));

        log.photographed("alliance", true, 900, false);
        assertEquals(Optional.empty(), log.lastCoveredReconcile("alliance"),
                "one that hit its ceiling short of the target does not count");
        assertTrue(log.lastReconcileStart("alliance").isPresent(),
                "but it did run, so the night is not owed again");

        log.photographed("alliance", true, 300, true);
        assertTrue(log.lastCoveredReconcile("alliance").isPresent());
    }

    @Test
    void lineWrittenBeforeTheCoveredFieldExistedCountAsCovered() throws IOException {
        Files.writeString(dir.resolve(ChatPassLog.FILE),
                "{\"at\":\"2026-10-20T05:00:00Z\",\"channel\":\"world\",\"mode\":\"reconcile\","
                        + "\"phase\":\"photographed\",\"screens\":150}\n", StandardCharsets.UTF_8);
        assertEquals(Optional.of(Instant.parse("2026-10-20T05:00:00Z")),
                new ChatPassLog(dir).lastCoveredReconcile("world"));
    }

    @Test
    void remembersWhenAChannelWasLastPhotographedInAnyMode() {
        ChatPassLog log = new ChatPassLog(dir);
        assertEquals(Optional.empty(), log.lastPhotographed("alliance"));

        log.photographed("alliance", false, 40);
        log.read("alliance", false, 40, 5, true);
        log.photographed("world", true, 150);

        assertTrue(log.lastPhotographed("alliance").isPresent(), "a read line is not a photograph");
        assertTrue(log.lastPhotographed("world").isPresent(), "a reconcile counts too");
        assertEquals(Optional.empty(), log.lastPhotographed("personal"));
    }
}
