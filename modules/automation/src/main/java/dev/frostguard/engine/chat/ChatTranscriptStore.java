package dev.frostguard.engine.chat;

import dev.frostguard.api.chat.ChatLineCleaner;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    /**
     * The time each remembered signature was stored under, so a walk that meets a message it
     * already has can also say when -- which is what anchors the estimate for the ones it does not.
     */
    private final Map<String, Instant> atBySignature = new HashMap<>();

    private int dedupeWindow = DEDUPE_WINDOW;

    public ChatTranscriptStore(Path root, ZoneId zone) {
        this.root = root;
        this.zone = zone;
    }

    /** A window of a chosen size, for a test that would otherwise need thousands of messages. */
    ChatTranscriptStore(Path root, ZoneId zone, int window) {
        this(root, zone);
        this.dedupeWindow = window;
    }

    /**
     * Holds at least this many signatures for de-duplication.
     *
     * <p>The ordinary window is a few thousand, which covers what a half-hourly pass can reach. A
     * reconcile pass reaches a day or more back on both channels, and a message older than the
     * window looks new -- it would be appended a second time, a day late.
     */
    public void widenWindow(int size) {
        dedupeWindow = Math.max(dedupeWindow, size);
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
        return append(messages, false);
    }

    /**
     * Appends messages that have not been seen before, each to the file of its own day.
     *
     * <p>Filed per message rather than by the first of them. Messages read in one pass all carry
     * about the same time, so the first one's day was the right file for all of them -- until a
     * reconcile filed messages whose estimated times span days, or an ordinary pass straddled
     * midnight and put the earliest few minutes of a day into the day before.
     *
     * @param estimated whether the times on these messages are estimates, written into the line so
     *                  a reader can tell a recovered message from one read when it was said
     * @return how many were genuinely new
     */
    public int append(List<ChatMessage> messages, boolean estimated) throws IOException {
        Map<Path, StringBuilder> byFile = new LinkedHashMap<>();
        int stored = 0;
        for (ChatMessage m : messages) {
            if (!m.isRenderable()) {
                continue;
            }
            String signature = signatureOf(m);
            if (alreadyHave(signature)) {
                continue;
            }
            remember(signature, m.capturedAt());
            byFile.computeIfAbsent(fileFor(m.capturedAt()), f -> new StringBuilder())
                    .append(ChatTranscriptCodec.toJson(m, estimated)).append('\n');
            stored++;
        }
        if (stored == 0) {
            return 0;
        }

        Files.createDirectories(root);
        for (Map.Entry<Path, StringBuilder> entry : byFile.entrySet()) {
            Files.writeString(entry.getKey(), entry.getValue().toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return stored;
    }

    private static String signatureOf(ChatMessage m) {
    // The author is deliberately not part of this. It used to be, and the author is the half
    // of the pair the reader gets wrong: one alliance message came back on three overlapping
    // frames as "ViIPO LINE PACHChanyu", "ViIPO LINE PACHCnanyu" and "ViIPw LENT PAChechanyu",
    // so every spelling looked new and the transcript kept all three copies. The body is the
    // stable half, so it identifies the message; the channel keeps world and alliance apart.
    // The cost is that two people posting an identical short line inside one window collapse to
    // one entry -- the better failure, since a transcript missing a duplicate "gm" still reads
    // correctly whereas the same sentence three times over does not.
        return m.channel() + " " + ChatLineCleaner.mergeKey(m.body());
    }

    /**
     * Whether this message is one already stored, allowing for the reader having read it
     * differently.
     *
     * <p>Exact matching was not enough, and the failure was quiet. A long pasted message spans
     * several screens, so which part of it lands in view depends where the scroll stopped -- and
     * the reader returns the pieces in a slightly different order and spelling each time. One
     * alliance post was stored fourteen times over a night, as "SkilIs: RIGHT RIGHT LEFT" and
     * "Skills: I RIGHT RIGHT LEFT 2" and twelve more variants, each new to a set that only knew
     * how to compare strings for equality.
     *
     * <p>The cost of that is not merely a duplicated line. The walk stops when it meets two screens
     * with nothing new on them, so a message that always looks new means it never stops: alliance
     * spent every pass of a night reading all seventy-six screens it had photographed and never
     * once caught up, while storing a fifth of what it stored as duplicates.
     *
     * <p>Candidates come from an index rather than a scan. Comparing each arriving message against
     * four thousand remembered ones would be a few hundred thousand comparisons a pass; keys that
     * share no run of characters cannot match, so only those that share one are looked at, which is
     * a handful.
     */
    /**
     * Whether this message is already in the transcript.
     *
     * <p>Public because the walk needs it. Stopping when a screen holds nothing new only means
     * "caught up" if "new" is measured against what has been stored -- measured against the walk
     * alone it means "this scroll has stopped moving", which on a busy channel scrolling into real
     * history is never true, so the walk reads every screen it photographed, every time.
     */
    public boolean alreadyStored(ChatMessage m) {
        return alreadyHave(signatureOf(m));
    }

    /**
     * The time a message is stored under, when the transcript already has it.
     *
     * <p>Matched the way {@link #alreadyStored} matches, so a message the reader spelt differently
     * this time still finds the entry it was stored as.
     */
    public Optional<Instant> storedAt(ChatMessage m) {
        String match = matchOf(signatureOf(m));
        return match == null ? Optional.empty() : Optional.ofNullable(atBySignature.get(match));
    }

    /**
     * Whether these messages, already stored, sit close together in the order they were stored.
     *
     * <p>What separates "I am back in chat I already have" from "I met three lines that people post
     * every hour". The store matches on the body alone, so "join the rally please" matches an
     * old copy of itself; three of those on one screen all match, and each match is far from the
     * others in storage order. A real stretch of the conversation matches messages that were
     * stored one after another.
     *
     * <p>Counted within the channel, because the two channels' passes are stored in turn and the
     * other channel's messages would otherwise make every stretch look long.
     *
     * @param maxSpread the most same-channel messages allowed between the first and last match
     */
    public boolean sameStretch(List<ChatMessage> messages, int maxSpread) {
        if (messages.size() < 2) {
            return true;
        }
        String channel = messages.get(0).channel();
        Map<String, Integer> position = new HashMap<>();
        int index = 0;
        for (String signature : recent) {
            if (channel.equals(channelOf(signature))) {
                position.put(signature, index++);
            }
        }
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        int found = 0;
        for (ChatMessage m : messages) {
            String match = matchOf(signatureOf(m));
            Integer at = match == null ? null : position.get(match);
            if (at != null) {
                lowest = Math.min(lowest, at);
                highest = Math.max(highest, at);
                found++;
            }
        }
        // Matches the window no longer holds cannot be placed, and that is not evidence either way;
        // fewer than two placed ones leave nothing to compare.
        return found < 2 || highest - lowest <= maxSpread;
    }

    /**
     * The newest time the transcript holds for this channel that is strictly before a limit.
     *
     * <p>What bounds a partial walk. When a reconcile does not reach as far back as the hole
     * goes, the messages it did recover are known to be newer than the last thing stored before
     * the hole -- and that is only knowable from the transcript, since the walk never got there.
     */
    public Optional<Instant> newestStoredBefore(String channel, Instant limit) {
        Instant best = null;
        for (Map.Entry<String, Instant> entry : atBySignature.entrySet()) {
            // The store's own reading of a signature, not a guess at its separator.
            if (!channel.equals(channelOf(entry.getKey()))) {
                continue;
            }
            Instant at = entry.getValue();
            if (at.isBefore(limit) && (best == null || at.isAfter(best))) {
                best = at;
            }
        }
        return Optional.ofNullable(best);
    }

    private boolean alreadyHave(String signature) {
        return matchOf(signature) != null;
    }

    /** The remembered signature this one is the same message as, or null when there is none. */
    private String matchOf(String signature) {
        if (recentSet.contains(signature)) {
            return signature;
        }
        String key = keyOf(signature);
        if (key.isEmpty()) {
            return null;
        }
        Set<String> candidates = new HashSet<>();
        for (String run : ChatLineCleaner.mergeRuns(key)) {
            Set<String> sharing = byRun.get(run);
            if (sharing != null) {
                candidates.addAll(sharing);
            }
        }
        String channel = channelOf(signature);
        for (String other : candidates) {
            // Channels are separate conversations and the same sentence in both is two messages.
            if (!channel.equals(channelOf(other))) {
                continue;
            }
            if (ChatLineCleaner.sameMessage(key, keyOf(other))) {
                return other;
            }
        }
        return null;
    }

    /** A signature is the channel, a space, then the message's key. */
    private static String channelOf(String signature) {
        int space = signature.indexOf(' ');
        return space < 0 ? signature : signature.substring(0, space);
    }

    private static String keyOf(String signature) {
        int space = signature.indexOf(' ');
        return space < 0 ? "" : signature.substring(space + 1);
    }

    private void remember(String signature, Instant at) {
        recent.addLast(signature);
        recentSet.add(signature);
        // The earliest time wins: the first place a message was stored is when it entered the
        // transcript, and a later copy only exists because it fell out of the window.
        atBySignature.putIfAbsent(signature, at);
        for (String run : ChatLineCleaner.mergeRuns(keyOf(signature))) {
            byRun.computeIfAbsent(run, r -> new HashSet<>()).add(signature);
        }
        while (recent.size() > dedupeWindow) {
            String dropped = recent.removeFirst();
            recentSet.remove(dropped);
            atBySignature.remove(dropped);
            for (String run : ChatLineCleaner.mergeRuns(keyOf(dropped))) {
                Set<String> sharing = byRun.get(run);
                if (sharing != null && sharing.remove(dropped) && sharing.isEmpty()) {
                    byRun.remove(run);
                }
            }
        }
    }

    /**
     * Which remembered signatures contain each run of characters.
     *
     * <p>Only an index. It holds nothing the window does not already hold, and is emptied in step
     * with it.
     */
    private final java.util.Map<String, Set<String>> byRun = new java.util.HashMap<>();

    /**
     * Seeds the de-duplication window from what is already on disk.
     *
     * <p>Without this a restart re-appends everything the next pass re-reads, because the window
     * only ever held this session's messages.
     */
    public void primeFromDisk() throws IOException {
        primeFromDisk(2);
    }

    /** Seeds the window from the most recent day files, as many as asked for. */
    public void primeFromDisk(int dayCount) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> days = dayFiles();
        for (int i = Math.max(0, days.size() - dayCount); i < days.size(); i++) {
            for (String line : Files.readAllLines(days.get(i), StandardCharsets.UTF_8)) {
                ChatMessage m = ChatTranscriptCodec.fromJson(line);
                if (m != null) {
                    remember(signatureOf(m), m.capturedAt());
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
