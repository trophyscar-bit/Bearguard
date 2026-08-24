package dev.frostguard.vision.ocr;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads a screen through the local PaddleOCR service, and says so plainly when it cannot.
 *
 * <p>Tesseract does page segmentation -- guess a layout, then read it -- which is the wrong shape
 * for a chat feed. Bubbles sit at arbitrary positions, carry ornaments and emoji that are not text,
 * and mix alphabets on one screen. Measured over twenty live alliance screens it read "VIPS" for
 * "VIP5" and "jdes na druhy ?cet" for "jdes na druhy ucet", made letters out of bubble decoration,
 * and lost one message entirely.
 *
 * <p>PaddleOCR finds the text regions with a model and reads each one after. On the same twenty
 * screens the diacritics came back right, every quoted-reply strip was found, and the decoration it
 * could not read scored 0.00 to 0.18 against 0.94 to 1.00 for real writing -- so the junk sorts
 * itself out on confidence, where the previous reader needed rules about glyph widths and colours
 * to guess at the same thing.
 *
 * <p>It is a separate process because the models take seconds to load and have to be loaded once.
 * That makes it a dependency that can be down, so every call here is allowed to fail: the caller is
 * expected to fall back to Tesseract rather than lose the pass. Nothing leaves the machine -- the
 * service listens on loopback and holds the models locally.
 */
public final class PaddleOcrClient {

    /** Long enough for a full screen on a busy machine, short enough not to stall a pass. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(3);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final String base;

    public PaddleOcrClient(String host, int port) {
        this.base = "http://" + host + ":" + port;
    }

    /** Whether the service is up. Cheap, and the only safe thing to ask before a pass. */
    public boolean isUp() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/health"))
                    .timeout(HEALTH_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("\"ok\":true");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The text on one region of a frame, or an empty list if the service could not answer.
     *
     * <p>Empty rather than an exception, because a reader being unavailable is an ordinary thing
     * that should cost the caller a fallback and not the whole capture.
     *
     * @param minConfidence rows the reader was less sure of than this are dropped. On this feed
     *                      real writing scores 0.94 and up and decoration scores under 0.2, so the
     *                      threshold has a wide gap to sit in.
     */
    public List<TextLine> read(BufferedImage frame, int left, int top, int right, int bottom,
                               String lang, double minConfidence) {
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(frame, "png", png);
            String url = base + "/ocr?lang=" + lang
                    + "&left=" + left + "&top=" + top + "&right=" + right + "&bottom=" + bottom;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(READ_TIMEOUT)
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(png.toByteArray()))
                    .build();
            HttpResponse<byte[]> response = http.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return List.of();
            }
            JsonNode body = MAPPER.readTree(response.body());
            List<TextLine> lines = new ArrayList<>();
            for (JsonNode n : body.path("lines")) {
                double confidence = n.path("conf").asDouble();
                if (confidence < minConfidence) {
                    continue;
                }
                lines.add(new TextLine(n.path("text").asText(),
                        n.path("left").asInt(), n.path("top").asInt(),
                        n.path("width").asInt(), n.path("height").asInt(),
                        (float) (confidence * 100)));
            }
            return lines;
        } catch (Exception e) {
            return List.of();
        }
    }
}
