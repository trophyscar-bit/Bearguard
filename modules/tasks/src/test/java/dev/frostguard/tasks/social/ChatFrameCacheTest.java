package dev.frostguard.tasks.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Evidence level: automated tests.
 */
class ChatFrameCacheTest {

    @TempDir
    Path base;

    @Test
    void theStampIsTheTimePartOfTheName() {
        assertEquals("20261023-083545-1", ChatFrameCache.stampOf("world-20261023-083545-1.png"));
        assertEquals("20261023-083545-1", ChatFrameCache.stampOf("alliance-20261023-083545-1.png"));
    }

    @Test
    void whenOverBudgetTheOldestFrameGoesWhateverItsChannel() throws IOException {
        Path frames = Files.createDirectories(base.resolve("frames"));
        // 1 MB budget, three ~600 KB files: the oldest two must go, the newest stays.
        byte[] blob = new byte[600 * 1024];
        // The old Alliance frame sorts BEFORE every World name, so a whole-name sort deleted it
        // first even when it was the newest of the lot.
        Files.write(frames.resolve("world-20261023-080000-1.png"), blob);
        Files.write(frames.resolve("alliance-20261023-090000-1.png"), blob);   // newest
        Files.write(frames.resolve("world-20261023-070000-1.png"), blob);      // oldest

        ChatFrameCache cache = new ChatFrameCache(base, 1);
        // keep() writes one more and prunes; use a real image to go through the public path.
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(4, 4,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        cache.keep("alliance", 2, img);

        try (Stream<Path> left = Files.list(frames)) {
            List<String> names = left.map(p -> p.getFileName().toString()).sorted().toList();
            assertTrue(names.stream().anyMatch(n -> n.startsWith("alliance-20261023-090000")),
                    "the newer Alliance frame must survive: " + names);
            assertFalse(names.contains("world-20261023-070000-1.png"),
                    "the oldest frame, a World one, must be the one dropped: " + names);
        }
    }
}
