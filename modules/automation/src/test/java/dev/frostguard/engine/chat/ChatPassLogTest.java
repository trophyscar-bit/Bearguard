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
    void recordsWhetherAReconcileReachedHistoryItAlreadyHad() throws IOException {
        ChatPassLog log = new ChatPassLog(dir);
        log.read("alliance", true, 150, 40, false);

        String written = Files.readString(dir.resolve(ChatPassLog.FILE));
        assertTrue(written.contains("\"bridged\":false"));
        assertTrue(written.contains("\"stored\":40"));
        assertTrue(written.contains("\"mode\":\"reconcile\""));
    }
}
