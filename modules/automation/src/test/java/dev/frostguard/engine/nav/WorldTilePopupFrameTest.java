package dev.frostguard.engine.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * The World tile popup has to be told apart from the bare map before anything presses back for it:
 * back on a bare World map asks to quit the game.
 *
 * <p>Positives are crops of the live "Sidebar did not open" frames from 2026-11-13, cut to the
 * popup, with tile coordinates, player name, avatar, power and alliance painted out. The template
 * is the white band of the card header through its flag, star and share icons, which every one of
 * those cards carries whatever its body shows.
 */
class WorldTilePopupFrameTest {

    private static final String DIR = "/navigation/";
    private static final int THRESHOLD = 90;

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded it in this JVM.
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "tile-popup-20261113/beast-attack-popup.png",
            "tile-popup-20261113/own-gather-recall-popup.png",
            "tile-popup-20261113/player-city-popup.png",
            "recall-20261111/world-tile-popup-with-recall-button.png"})
    void findsTheTilePopupOverTheMap(String frame) throws IOException {
        ImageSearchResultData hit = locate(frame);

        assertTrue(hit.isFound(), () -> "Expected the tile popup in " + frame + ": " + hit);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Bare World map from the same session: tiles, level badges, territory lines.
            "tile-popup-20261113/world-map-no-popup.png",
            // World with the Marching panel and no popup.
            "march-recall-20261111/world-marching-list.png",
            // An open sidebar: back here would close the panel the caller just opened.
            "recall-20261111/daily-sidebar-open.png",
            // The Recall confirmation belongs to its own guard and must not be read as a popup.
            "recall-20261111/recall-confirm.png"})
    void doesNotFireWithoutATilePopup(String frame) throws IOException {
        ImageSearchResultData hit = locate(frame);

        assertFalse(hit.isFound(), () -> "No tile popup in " + frame + ": " + hit);
    }

    private ImageSearchResultData locate(String frame) throws IOException {
        byte[] bytes = resource(frame);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        return OpenCvPatternLocator.locatePattern(bytes, TemplatesEnum.WORLD_TILE_POPUP_HEADER,
                new PointData(0, 0), new PointData(image.getWidth() - 1, image.getHeight() - 1),
                THRESHOLD);
    }

    private byte[] resource(String name) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(DIR + name)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + DIR + name).readAllBytes();
        }
    }
}
