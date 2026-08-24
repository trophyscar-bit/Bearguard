package dev.frostguard.tasks.social;

import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.frostguard.api.chat.ChatLineCleaner;
import dev.frostguard.api.chat.ChatMessage;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Runs the message pipeline over rows read by PaddleOCR instead of Tesseract.
 *
 * <p>Everything downstream is the same code the live capture uses -- the same grouping into
 * messages, the same cleanup, the same de-duplication -- so the difference between this bench and
 * {@link ChatFrameBench} is the reader and nothing else. That is the only way to say what a change
 * of reader is worth: measured on the same twenty screens, scored the same way.
 *
 * <p>The rows come from a JSON file the Python side writes, rather than from a service, because the
 * question at this stage is whether the reading is better and not how to deliver it.
 */
public final class PaddleBench {

    /**
     * Rows below this are not text.
     *
     * <p>The reader scores what it finds, and on this feed the split is stark: real writing comes
     * back at 0.94 to 1.00 while the bubble's decoration -- ornaments, emoji, the corner of a frame
     * -- comes back at 0.00 to 0.18. One threshold does what a page of glyph-width and colour rules
     * was doing before it.
     */
    private static final double MIN_CONFIDENCE = 0.60;

    private PaddleBench() {
    }

    public static void main(String[] args) throws Exception {
        String linesJson = args[0];
        String frameDir = args[1];
        JsonNode all = new ObjectMapper().readTree(new File(linesJson));

        java.util.LinkedHashMap<String, ChatMessage> collected = new java.util.LinkedHashMap<>();
        java.util.Set<String> roster = new java.util.LinkedHashSet<>();
        java.io.PrintStream out = new java.io.PrintStream(System.out, true, "UTF-8");

        List<String> names = new ArrayList<>();
        all.fieldNames().forEachRemaining(names::add);
        java.util.Collections.sort(names);

        for (String name : names) {
            BufferedImage img = ImageIO.read(new File(frameDir, name + ".png"));
            List<TextLine> lines = new ArrayList<>();
            for (JsonNode n : all.get(name)) {
                if (n.path("conf").asDouble() < MIN_CONFIDENCE) {
                    continue;
                }
                lines.add(new TextLine(n.path("text").asText(), n.path("left").asInt(),
                        n.path("top").asInt(), n.path("width").asInt(), n.path("height").asInt(),
                        (float) (n.path("conf").asDouble() * 100)));
            }
            for (TextLine l : lines) {
                ChatLineCleaner.Sender s = ChatLineCleaner.parseSender(l.text());
                if (s.trusted() && !s.allianceTag().isEmpty() && !s.name().isBlank()) {
                    roster.add(s.name());
                }
            }
            List<ChatMessage> msgs = ChatFrameReader.read(lines, "alliance", Instant.now(),
                    body -> Optional.empty(),
                    line -> ChatQuoteBar.isQuoteRow(img, line));
            for (ChatMessage m : msgs) {
                ChatCaptureRoutine.keep(collected, m);
            }
        }

        int n = 0;
        for (ChatMessage held : collected.values()) {
            if (held.author().isBlank()) {
                continue;
            }
            ChatMessage m = held.withBody(
                    ChatLineCleaner.repairLeadingMention(held.body(), roster));
            n++;
            com.fasterxml.jackson.databind.node.ObjectNode o =
                    new ObjectMapper().createObjectNode();
            o.put("n", n);
            o.put("author", m.author());
            o.put("tag", m.allianceTag());
            o.put("body", m.body());
            o.put("en", m.translated());
            o.put("quoted", m.quoted());
            o.put("kind", m.kind().name());
            out.println(o.toString());
        }
    }
}
