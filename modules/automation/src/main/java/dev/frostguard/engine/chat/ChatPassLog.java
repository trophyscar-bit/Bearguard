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
        photographed(channel, reconcile, screens, true);
    }

    /**
     * @param covered whether the walk stopped because it reached the chat it was sent to reach, and
     *                not because it ran out of time. Only a reconcile that covered its target counts
     *                as the one the next reconcile builds on; see {@link #lastCoveredReconcile}.
     */
    public void photographed(String channel, boolean reconcile, int screens, boolean covered) {
        photographed(channel, reconcile, screens, covered, null);
    }

    /**
     * @param walkStart when the walk began, the same figure the read line of that walk carries, so
     *                  the two can be paired exactly. Reads queue and can finish hours later, and a
     *                  walk whose read was lost to a restart would otherwise shift every pairing
     *                  after it by one. Null leaves it out.
     */
    public void photographed(String channel, boolean reconcile, int screens, boolean covered,
                             Instant walkStart) {
        ObjectNode n = line(channel, reconcile, "photographed");
        n.put("screens", screens);
        n.put("covered", covered);
        if (walkStart != null) {
            n.put("walkStart", walkStart.toString());
        }
        write(n);
    }

    /**
     * When the most recent reconcile of this channel that reached its target finished walking, or
     * empty when none did.
     *
     * <p>Not the same as the last one that ran. A reconcile that ran out of time before reaching
     * what it was sent for has left a hole behind it, and a later reconcile measured from it would
     * start where the short one began and never look at that hole. Lines written before this field
     * existed count as covered, since that is what they were assumed to be.
     */
    public Optional<Instant> lastCoveredReconcile(String channel) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Instant latest = null;
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                try {
                    JsonNode n = MAPPER.readTree(raw);
                    if (n == null || !"reconcile".equals(n.path("mode").asText())
                            || !channel.equals(n.path("channel").asText())
                            || !"photographed".equals(n.path("phase").asText())
                            || !n.path("covered").asBoolean(true)) {
                        continue;
                    }
                    Instant at = Instant.parse(n.path("at").asText());
                    if (latest == null || at.isAfter(latest)) {
                        latest = at;
                    }
                } catch (Exception malformed) {
                    // A half-written last line is normal for an append-only file.
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.ofNullable(latest);
    }

    /**
     * The photographs have been read and their messages stored.
     *
     * @param bridged whether the walk ended on messages the transcript already held. False means
     *                it ran out of screens still finding new ones, so whatever is older than the
     *                oldest screen read was not looked at.
     */
    public void read(String channel, boolean reconcile, int screens, int stored, boolean bridged) {
        read(channel, reconcile, screens, stored, bridged, null);
    }

    /** @param walkStart the walk these frames came from; see {@link #photographed}. Null leaves it out. */
    public void read(String channel, boolean reconcile, int screens, int stored, boolean bridged,
                     Instant walkStart) {
        ObjectNode n = line(channel, reconcile, "read");
        n.put("screens", screens);
        n.put("stored", stored);
        n.put("bridged", bridged);
        if (walkStart != null) {
            n.put("walkStart", walkStart.toString());
        }
        write(n);
    }

    /**
     * When this channel was last photographed in any mode, or empty when none is on record.
     *
     * <p>A long gap since it means the bot was not running, and the next pass has more to catch up
     * on than an ordinary one is sized for.
     */
    public Optional<Instant> lastPhotographed(String channel) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Instant latest = null;
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                try {
                    JsonNode n = MAPPER.readTree(raw);
                    if (n == null || !channel.equals(n.path("channel").asText())
                            || !"photographed".equals(n.path("phase").asText())) {
                        continue;
                    }
                    Instant at = Instant.parse(n.path("at").asText());
                    if (latest == null || at.isAfter(latest)) {
                        latest = at;
                    }
                } catch (Exception malformed) {
                    // A half-written last line is normal for an append-only file.
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.ofNullable(latest);
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

    /**
     * Whether an ordinary pass of this channel left a hole that no reconcile has looked at since.
     *
     * <p>An ordinary pass that photographs its whole scroll and still finds messages it does not
     * have has not reached the end of what was said: whatever lies between its oldest screen and
     * the previous pass's newest message is missing, and the next ordinary pass starts from the
     * newest again, so nothing else will ever go back for it. Five of these in ten days, all in
     * Alliance, each waiting for the 1 AM catch-up or for nothing.
     *
     * <p>A pass whose frames were dropped unread counts the same: nothing of it was stored.
     *
     * @param within how recent the unbridged pass must be. Past this a five minute reconcile could
     *               not reach back to it anyway, and re-walking for a hole that old only holds the
     *               emulator for nothing.
     */
    public boolean unbridgedSinceLastReconcile(String channel, java.time.Duration within, Instant now) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        Instant unbridgedAt = null;
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                JsonNode n;
                try {
                    n = MAPPER.readTree(raw);
                } catch (Exception malformed) {
                    continue;
                }
                if (n == null || !channel.equals(n.path("channel").asText())) {
                    continue;
                }
                String mode = n.path("mode").asText();
                String phase = n.path("phase").asText();
                Instant at;
                try {
                    at = Instant.parse(n.path("at").asText());
                } catch (Exception badTime) {
                    continue;
                }
                if ("reconcile".equals(mode) && "photographed".equals(phase)) {
                    unbridgedAt = null;
                } else if ("normal".equals(mode) && "read".equals(phase)
                        && !n.path("bridged").asBoolean(true)) {
                    unbridgedAt = at;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return unbridgedAt != null && !unbridgedAt.isBefore(now.minus(within));
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
