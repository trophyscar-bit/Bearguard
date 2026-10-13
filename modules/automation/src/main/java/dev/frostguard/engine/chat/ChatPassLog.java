package dev.frostguard.engine.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A line for every channel every pass, written next to the transcript.
 *
 * <p>The transcript says what was said and nothing about when the bot was not listening. A quiet
 * night and a paused bot look identical in it, and a digest built from the transcript reports both
 * as a calm alliance. This is the other half: which passes ran, how far each got, and whether a
 * reconcile reached history it already had or ran out of time first.
 *
 * <p>It is also how the routine knows whether tonight's reconcile has already happened, so that a
 * restart neither repeats it nor forgets it.
 *
 * <p>Two lines per channel per pass, {@code photographed} when the device is released and
 * {@code read} when the text is stored. They are separate because reading runs later on its own
 * thread, and a reconcile that was photographed but never read -- the app closed in between -- is
 * a fact worth being able to see.
 */
public final class ChatPassLog {

    public static final String FILE = "passes.jsonl";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    public ChatPassLog(Path root) {
        this.file = root.resolve(FILE);
    }

    /** The device has been released; {@code screens} were photographed. */
    public void photographed(String channel, boolean reconcile, int screens) {
        ObjectNode n = line(channel, reconcile, "photographed");
        n.put("screens", screens);
        write(n);
    }

    /**
     * The photographs have been read and their messages stored.
     *
     * @param bridged whether the walk ended on messages the transcript already held. False means
     *                it ran out of screens still finding new ones, so whatever is older than the
     *                oldest screen read was not looked at.
     */
    public void read(String channel, boolean reconcile, int screens, int stored, boolean bridged) {
        ObjectNode n = line(channel, reconcile, "read");
        n.put("screens", screens);
        n.put("stored", stored);
        n.put("bridged", bridged);
        write(n);
    }

    /**
     * When the most recent reconcile of this channel began, or empty when none is on record.
     *
     * <p>Per channel, not per pass: if the World tab would not open one night, the Alliance
     * reconcile that did run must not make World look done.
     */
    public Optional<Instant> lastReconcileStart(String channel) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Instant latest = null;
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Instant at = reconcileAt(raw, channel);
                if (at != null && (latest == null || at.isAfter(latest))) {
                    latest = at;
                }
            }
        } catch (IOException e) {
            // Unknown is not "never happened". Returning empty makes the routine reconcile again,
            // which costs ten minutes; returning a recent time would skip a night silently.
            return Optional.empty();
        }
        return Optional.ofNullable(latest);
    }

    private static Instant reconcileAt(String raw, String channel) {
        try {
            JsonNode n = MAPPER.readTree(raw);
            if (n == null || !"reconcile".equals(n.path("mode").asText())
                    || !channel.equals(n.path("channel").asText())
                    || !"photographed".equals(n.path("phase").asText())) {
                return null;
            }
            return Instant.parse(n.path("at").asText());
        } catch (Exception malformed) {
            // A half-written last line is normal for an append-only file that a crash can cut off.
            return null;
        }
    }

    private static ObjectNode line(String channel, boolean reconcile, String phase) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("at", Instant.now().toString());
        n.put("channel", channel);
        n.put("mode", reconcile ? "reconcile" : "normal");
        n.put("phase", phase);
        return n;
    }

    private void write(ObjectNode n) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, n + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Thrown, not swallowed: whether a lost log line is worth stopping for is the caller's
            // call, and the routine's is that it is not -- it warns and carries on with the pass.
            throw new java.io.UncheckedIOException("could not write the pass log: " + file, e);
        }
    }
}
