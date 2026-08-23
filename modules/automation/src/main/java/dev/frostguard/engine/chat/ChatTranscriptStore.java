package dev.frostguard.engine.chat;

import dev.frostguard.api.chat.ChatLineCleaner;
import dev.frostguard.api.chat.ChatMessage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The transcript on disk: one append-only file per day, and nothing else kept.
 *
 * <p>Frames are the reason this exists. A capture pass writes roughly 394KB per screenshot, and
 * the schedule the operator wants -- around thirty screens across three channels every twenty
 * minutes -- lands near 6,500 frames a day. Kept, that is about 2.5GB daily on a stranger's
 * machine. The same content as text is about four megabytes before de-duplication and far less
 * after, because consecutive scroll-backs overlap heavily. So a frame exists only long enough to
 * be read, and {@link #retireFrame} removes it the moment its rows are safely stored.
 *
 * <p>Days are separate files rather than one growing log so that expiry is a file delete instead
 * of a rewrite, and so a corrupt line can never cost more than one day.
 */
public final class ChatTranscriptStore {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String PREFIX = "chat-";
    private static final String SUFFIX = ".jsonl";

    /** How many recent signatures to hold for de-duplication across overlapping scroll-backs. */
    private static final int DEDUPE_WINDOW = 4000;

    private final Path root;
    private final ZoneId zone;
    private final Deque<String> recent = new ArrayDeque<>();
    private final Set<String> recentSet = new HashSet<>();

    public ChatTranscriptStore(Path root, ZoneId zone) {
        this.root = root;
        this.zone = zone;
    }

    /** The file a message captured at this instant belongs in. */
    public Path fileFor(Instant at) {
        return root.resolve(PREFIX + DAY.format(LocalDate.ofInstant(at, zone)) + SUFFIX);
    }

    /**
     * Appends messages that have not been seen before.
     *
     * <p>Every pass deliberately re-reads screens it has already captured, so the great majority
     * of rows arriving here are repeats. De-duplication is by author plus body rather than by
     * frame, because the same message reappears at a different scroll offset in a different frame.
     *
     * @return how many were genuinely new
     */
    public int append(List<ChatMessage> messages) throws IOException {
        List<ChatMessage> fresh = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (!m.isRenderable()) {
                continue;
            }
            String signature = signatureOf(m);
            if (recentSet.contains(signature)) {
                continue;
            }
            remember(signature);
            fresh.add(m);
        }
        if (fresh.isEmpty()) {
            return 0;
        }

        Files.createDirectories(root);
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : fresh) {
            sb.append(ChatTranscriptCodec.toJson(m)).append('\n');
        }
        Files.writeString(fileFor(fresh.get(0).capturedAt()), sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return fresh.size();
    }

    private static String signatureOf(ChatMessage m) {
        return (m.author() + "\0" + m.body()).toLowerCase(Locale.ROOT);
    }

    private void remember(String signature) {
        recent.addLast(signature);
        recentSet.add(signature);
        while (recent.size() > DEDUPE_WINDOW) {
            recentSet.remove(recent.removeFirst());
        }
    }

    /**
     * Seeds the de-duplication window from what is already on disk.
     *
     * <p>Without this a restart re-appends everything the next pass re-reads, because the window
     * only ever held this session's messages.
     */
    public void primeFromDisk() throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> days = dayFiles();
        for (int i = Math.max(0, days.size() - 2); i < days.size(); i++) {
            for (String line : Files.readAllLines(days.get(i), StandardCharsets.UTF_8)) {
                ChatMessage m = ChatTranscriptCodec.fromJson(line);
                if (m != null) {
                    remember(signatureOf(m));
                }
            }
        }
    }

    /** Reads back the most recent messages, newest last, for the live transcript view. */
    public List<ChatMessage> recent(int limit) throws IOException {
        List<ChatMessage> out = new ArrayList<>();
        List<Path> days = dayFiles();
        for (int i = days.size() - 1; i >= 0 && out.size() < limit; i--) {
            List<String> lines = Files.readAllLines(days.get(i), StandardCharsets.UTF_8);
            for (int j = lines.size() - 1; j >= 0 && out.size() < limit; j--) {
                ChatMessage m = ChatTranscriptCodec.fromJson(lines.get(j));
                if (m != null) {
                    out.add(m);
                }
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /**
     * Deletes a frame once its rows are stored.
     *
     * <p>Kept only when reading failed, so there is something to look at when a capture goes
     * wrong -- the standing rule that a visual failure gets its frame dumped rather than guessed
     * at still holds; it just no longer means keeping every successful frame too.
     */
    public static void retireFrame(Path frame, boolean readSucceeded) {
        if (!readSucceeded || frame == null) {
            return;
        }
        try {
            Files.deleteIfExists(frame);
        } catch (IOException e) {
            throw new UncheckedIOException("could not remove a read frame: " + frame, e);
        }
    }

    /** Total bytes the transcript occupies, for the figure shown at startup. */
    public long sizeBytes() throws IOException {
        if (!Files.isDirectory(root)) {
            return 0L;
        }
        long total = 0L;
        for (Path p : dayFiles()) {
            total += Files.size(p);
        }
        return total;
    }

    /** Human-readable form of {@link #sizeBytes}. */
    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * Drops whole days older than the retention window.
     *
     * @return how many day files were removed
     */
    public int purgeOlderThan(int days) throws IOException {
        if (days <= 0 || !Files.isDirectory(root)) {
            return 0;
        }
        LocalDate cutoff = LocalDate.now(zone).minusDays(days);
        int removed = 0;
        for (Path p : dayFiles()) {
            LocalDate day = dayOf(p);
            if (day != null && day.isBefore(cutoff)) {
                Files.deleteIfExists(p);
                removed++;
            }
        }
        return removed;
    }

    private List<Path> dayFiles() throws IOException {
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(p -> p.getFileName().toString().startsWith(PREFIX))
                    .filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .toList();
        }
    }

    private static LocalDate dayOf(Path p) {
        String name = p.getFileName().toString();
        try {
            return LocalDate.parse(name.substring(PREFIX.length(), name.length() - SUFFIX.length()), DAY);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
