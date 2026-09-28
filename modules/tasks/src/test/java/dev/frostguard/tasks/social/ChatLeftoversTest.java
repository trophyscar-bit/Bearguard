package dev.frostguard.tasks.social;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Evidence level: automated tests.
 */
class ChatLeftoversTest {

    private static final Instant SESSION = Instant.parse("2026-09-28T14:14:00Z");

    @TempDir
    Path pending;

    private Path folder(String name, Instant modified, boolean withFrame) throws IOException {
        Path dir = Files.createDirectory(pending.resolve(name));
        if (withFrame) {
            Files.writeString(dir.resolve("0000.png"), "x");
        }
        Files.setLastModifiedTime(dir, FileTime.from(modified));
        return dir;
    }

    @Test
    void offersFoldersFromBeforeThisSessionOldestFirst() throws IOException {
        Path later = folder("alliance-20260928-100923", SESSION.minusSeconds(200), true);
        Path earlier = folder("world-20260928-100649", SESSION.minusSeconds(400), true);

        List<ChatLeftovers.Leftover> found = ChatLeftovers.find(pending, SESSION, Set.of());

        assertEquals(List.of(new ChatLeftovers.Leftover("world", earlier),
                new ChatLeftovers.Leftover("alliance", later)), found);
    }

    @Test
    void neverOffersAFolderThisSessionIsStillPhotographingOrAlreadyHolds() throws IOException {
        folder("world-20260928-101611", SESSION.plusSeconds(60), true);
        Path queued = folder("alliance-20260928-100923", SESSION.minusSeconds(200), true);

        assertEquals(List.of(), ChatLeftovers.find(pending, SESSION, Set.of(queued)));
    }

    @Test
    void ignoresEmptyFoldersAndAnythingThatIsNotAFeed() throws IOException {
        folder("world-20260928-100000", SESSION.minusSeconds(300), false);
        folder("personal-20260928-100000", SESSION.minusSeconds(300), true);
        folder("notes", SESSION.minusSeconds(300), true);

        assertEquals(List.of(), ChatLeftovers.find(pending, SESSION, Set.of()));
    }

    @Test
    void aMissingPendingFolderIsNotAnError() {
        assertEquals(List.of(), ChatLeftovers.find(pending.resolve("nope"), SESSION, Set.of()));
    }
}
