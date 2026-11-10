package dev.frostguard.tasks.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * The header strip of a real Chat screen, kept from the frame saved on 9 Nov. Everything below the
 * tabs is painted out because it is other players' names and messages.
 */
class ChatScreenClassifierTest {

    private static final String CHAT_FIXTURE = "chat-screen-header-20261109.png";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void findsTheCloseControlOfTheSavedChatScreen() throws Exception {
        Optional<ImageSearchResultData> close =
                ChatScreenClassifier.locateCloseControl(raw(ImageIO.read(startupDir().resolve(CHAT_FIXTURE).toFile())));

        assertTrue(close.isPresent());
        assertTrue(Math.abs(close.get().getPoint().getX() - 682) <= 8, close.get()::toString);
        assertTrue(Math.abs(close.get().getPoint().getY() - 40) <= 8, close.get()::toString);
    }

    @Test
    void aFrameWithoutTheChatTitleOffersNothingToTap() throws Exception {
        BufferedImage frame = ImageIO.read(startupDir().resolve(CHAT_FIXTURE).toFile());
        var graphics = frame.createGraphics();
        graphics.setColor(java.awt.Color.BLACK);
        graphics.fillRect(70, 8, 131, 65);
        graphics.dispose();

        assertTrue(ChatScreenClassifier.locateCloseControl(raw(frame)).isEmpty());
    }

    @Test
    void noOtherSavedStartupOrHomeScreenIsTakenForChat() throws Exception {
        Path resources = startupDir().getParent();
        List<Path> frames;
        try (Stream<Path> startup = Files.list(resources.resolve("startup"));
                Stream<Path> city = Files.list(resources.resolve("city"));
                Stream<Path> live = Files.list(resources.resolve("live-regressions-20260818"))) {
            frames = Stream.of(startup, city, live)
                    .flatMap(stream -> stream)
                    .filter(path -> path.getFileName().toString().endsWith(".png"))
                    .filter(path -> !path.getFileName().toString().equals(CHAT_FIXTURE))
                    .toList();
        }

        assertFalse(frames.isEmpty());
        for (Path path : frames) {
            BufferedImage frame = ImageIO.read(path.toFile());
            if (frame.getWidth() != 720 || frame.getHeight() != 1280) {
                continue;
            }
            assertEquals(Optional.empty(), ChatScreenClassifier.locateCloseControl(raw(frame)), path.toString());
        }
    }

    private static Path startupDir() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                ChatScreenClassifierTest.class.getResource("/startup/" + CHAT_FIXTURE)).toURI()).getParent();
    }

    private static RawImageData raw(BufferedImage frame) {
        byte[] pixels = new byte[frame.getWidth() * frame.getHeight() * 4];
        for (int y = 0, offset = 0; y < frame.getHeight(); y++) {
            for (int x = 0; x < frame.getWidth(); x++, offset += 4) {
                int argb = frame.getRGB(x, y);
                pixels[offset] = (byte) (argb >> 16);
                pixels[offset + 1] = (byte) (argb >> 8);
                pixels[offset + 2] = (byte) argb;
                pixels[offset + 3] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(pixels, frame.getWidth(), frame.getHeight(), 32);
    }
}
