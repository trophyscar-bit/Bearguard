package dev.frostguard.app.panel.events;

import dev.frostguard.api.runtime.WorkspacePaths;
import javafx.scene.image.Image;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The game's own event art, for the calendar rows.
 *
 * <p>These are the images the calendar scan already saves: each one is the icon cut out of that
 * event's bar in the in-game chart, so the set grows by itself as new events are seen and no
 * artwork has to be checked into the repository.</p>
 *
 * <p>They are shown whole, on the game's own coloured badge, and the rows round the corners off
 * them. Lifting the art onto transparency was tried first and is not worth it: the badge is part of
 * the icon rather than a background behind it, so a flood fill either left a band of colour behind
 * or ate into the art, and the results were uneven across the set in a way no threshold fixed.</p>
 */
final class EventIconLibrary {

    private static final Map<String, Image> CACHE = new HashMap<>();

    private EventIconLibrary() {
    }

    /**
     * That event's icon, or null when nothing has been learned for it yet and the caller should
     * fall back to a glyph. Cached, because the panel rebuilds every thirty seconds.
     */
    static Image iconFor(String eventLabel) {
        if (eventLabel == null || eventLabel.isBlank()) {
            return null;
        }
        String fileName = eventLabel.replaceAll("[^A-Za-z0-9 ]", "").trim().replace(' ', '_');
        if (fileName.isEmpty()) {
            return null;
        }
        synchronized (CACHE) {
            if (CACHE.containsKey(fileName)) {
                return CACHE.get(fileName);
            }
            Image image = load(fileName);
            CACHE.put(fileName, image);
            return image;
        }
    }

    private static Image load(String fileName) {
        Path file = WorkspacePaths.current().root()
                .resolve("data").resolve("calendar-icons").resolve(fileName + ".png");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Image image = new Image(in);
            return image.isError() ? null : image;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}
