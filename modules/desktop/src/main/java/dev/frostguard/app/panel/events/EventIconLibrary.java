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
 * <p>Preference goes to the cleaned copies under {@code calendar-icons/clean}, where the art has
 * been lifted off the bar fill by {@code tools/clean-calendar-icons.py}. Colour keying was tried
 * first and abandoned: each crop carries two flat colours (the bar and the chart behind it), some
 * bars are graded, and the art itself reuses the bar's yellow, so no threshold cut the whole set.
 * The script segments the foreground instead, which does.</p>
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
        Path icons = WorkspacePaths.current().root().resolve("data").resolve("calendar-icons");
        // The cleaned copy first: tools/clean-calendar-icons.py lifts the art off the bar fill, and
        // an event that has not been through it yet still shows, just on its bar colour.
        Path file = icons.resolve("clean").resolve(fileName + ".png");
        if (!Files.isRegularFile(file)) {
            file = icons.resolve(fileName + ".png");
        }
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
