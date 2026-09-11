package dev.frostguard.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.junit.jupiter.api.Test;

/**
 * Every FXML layout has to be well-formed XML, or the application does not start.
 *
 * <p>On 2026-09-10 two layout comments added with the Upcoming Events calendar contained "--",
 * which XML forbids inside a comment. Nothing compiles FXML, so the build passed and every test
 * passed; the fault only appeared when the launcher loaded the panel while building its sidebar,
 * and Bearguard exited four seconds after starting with "The string "--" is not permitted within
 * comments". Any build of main did the same until it was found.</p>
 *
 * <p>This reads each layout with the JDK's StAX parser -- the same parser JavaFX's FXMLLoader uses,
 * and the one that threw at startup -- so it fails on exactly what the application would fail
 * on, before a build ships instead of after.</p>
 */
class LayoutsAreWellFormedXmlTest {

    @Test
    void everyFxmlLayoutParsesAsXml() throws IOException {
        Path resources = Path.of("src", "main", "resources");
        List<Path> layouts;
        try (Stream<Path> walk = Files.walk(resources)) {
            layouts = walk.filter(p -> p.toString().endsWith(".fxml")).sorted().toList();
        }
        assertFalse(layouts.isEmpty(), "no FXML layouts found under " + resources.toAbsolutePath());

        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        List<String> broken = new ArrayList<>();
        for (Path layout : layouts) {
            try (InputStream in = Files.newInputStream(layout)) {
                XMLStreamReader reader = factory.createXMLStreamReader(in);
                try {
                    while (reader.hasNext()) {
                        reader.next();
                    }
                } finally {
                    reader.close();
                }
            } catch (XMLStreamException e) {
                broken.add(resources.relativize(layout) + " -- "
                        + String.valueOf(e.getMessage()).replace('\n', ' '));
            }
        }

        assertTrue(broken.isEmpty(), "These layouts are not well-formed XML, and the application"
                + " will fail to start when it loads them:\n  " + String.join("\n  ", broken));
    }
}
