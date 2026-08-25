import dev.frostguard.api.chat.ChatLineCleaner;
import dev.frostguard.api.chat.ChatMessage;
import dev.frostguard.engine.chat.ChatTranscriptCodec;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes messages a transcript holds more than once.
 *
 * <p>Written because the store used to recognise a repeat only by comparing keys for equality, and
 * a long pasted message does not read the same twice -- one post was kept fourteen times. The store
 * no longer does that, but the copies it already wrote are still on disk, and nothing else will
 * remove them.
 *
 * <p>Only exact repeats. The capture's own matcher is deliberately tolerant, because during a pass
 * it compares messages from a single two-and-a-half-minute window where a wrong match costs one
 * merged line. Run across a transcript spanning days it is far too eager: it linked "Manana no
 * podre hacer" to "Depende la hora, pero manana antes de las 19 UTC no podria estar", and because
 * a match to one message makes it a candidate for the next, whole conversations collapsed into a
 * single entry -- two fifths of the file. Removing something that was never a duplicate is worse
 * than leaving a duplicate, so this only removes what is provably the same text.
 *
 * <p>The winner is written where the first copy sat, which keeps the transcript in the order the
 * conversation happened and each day's messages in that day's file.
 */
public final class TranscriptDedupe {

    public static void main(String[] args) throws IOException {
        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        Path root = Path.of(args[0]);
        boolean apply = args.length > 1 && args[1].equals("apply");

        List<Path> days = new ArrayList<>();
        try (var listing = Files.list(root)) {
            listing.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .sorted().forEach(days::add);
        }

        // Every message, in the order the transcript holds them, remembering which file each came
        // from so the rewrite can put them back where they belong.
        List<Entry> all = new ArrayList<>();
        for (Path day : days) {
            List<String> lines = Files.readAllLines(day, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                ChatMessage m = ChatTranscriptCodec.fromJson(line);
                if (m == null) {
                    // Unparseable lines are kept exactly as they are. This tool is here to remove
                    // duplicates, not to quietly discard anything it does not understand.
                    all.add(new Entry(day, line, null, null));
                    continue;
                }
                all.add(new Entry(day, line, m, ChatLineCleaner.mergeKey(m.body())));
            }
        }

        // Index by shared runs so each message is compared against the few that could match it
        // rather than against every message already seen.
        Map<String, Set<Integer>> byRun = new HashMap<>();
        int[] winnerOf = new int[all.size()];
        java.util.Arrays.fill(winnerOf, -1);
        int duplicates = 0;
        List<String[]> samples = new ArrayList<>();

        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            if (e.message == null || e.key == null || e.key.isEmpty()) {
                continue;
            }
            Set<Integer> candidates = new HashSet<>();
            for (String run : ChatLineCleaner.mergeRuns(e.key)) {
                Set<Integer> sharing = byRun.get(run);
                if (sharing != null) {
                    candidates.addAll(sharing);
                }
            }
            int match = -1;
            for (int other : candidates) {
                Entry o = all.get(other);
                if (!o.message.channel().equals(e.message.channel())) {
                    continue;
                }
                if (o.key.equals(e.key)) {
                    match = other;
                    break;
                }
            }
            if (match >= 0) {
                duplicates++;
                if (samples.size() < SAMPLES) {
                    samples.add(new String[] {all.get(match).message.body(), e.message.body()});
                }
                // The better copy wins, but keeps the earlier one's place in the transcript.
                if (better(e.message, all.get(match).message)) {
                    all.get(match).message = e.message;
                    all.get(match).line = e.line;
                }
                winnerOf[i] = match;
                continue;
            }
            for (String run : ChatLineCleaner.mergeRuns(e.key)) {
                byRun.computeIfAbsent(run, r -> new HashSet<>()).add(i);
            }
        }

        out.printf("%s%n", root);
        out.printf("  messages   : %d%n", all.size());
        out.printf("  duplicates : %d (%.0f%%)%n", duplicates,
                100.0 * duplicates / Math.max(1, all.size()));
        out.printf("  would keep : %d%n", all.size() - duplicates);

        if (!apply) {
            out.println("  (dry run -- pass 'apply' to rewrite)");
            out.println();
            out.println("  a sample of what it will remove:");
            for (String[] pair : samples) {
                out.printf("    kept : %s%n", trim(pair[0]));
                out.printf("    drop : %s%n%n", trim(pair[1]));
            }
            return;
        }

        Map<Path, List<String>> rewritten = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            if (winnerOf[i] >= 0) {
                continue;
            }
            rewritten.computeIfAbsent(all.get(i).day, d -> new ArrayList<>()).add(all.get(i).line);
        }
        for (Path day : days) {
            List<String> kept = rewritten.getOrDefault(day, List.of());
            Files.writeString(day, kept.isEmpty() ? "" : String.join("\n", kept) + "\n",
                    StandardCharsets.UTF_8);
            out.printf("  rewrote %-28s %d line(s)%n", day.getFileName(), kept.size());
        }
    }

    private static final int SAMPLES = 12;

    private static String trim(String body) {
        String one = body.replace('\n', ' ');
        return one.length() > 96 ? one.substring(0, 96) + "..." : one;
    }

    /** The same rule the capture uses: a named message beats an unnamed one, then the longer. */
    private static boolean better(ChatMessage candidate, ChatMessage held) {
        if (held.author().isEmpty() != candidate.author().isEmpty()) {
            return held.author().isEmpty();
        }
        return candidate.body().length() > held.body().length();
    }

    private static final class Entry {
        final Path day;
        String line;
        ChatMessage message;
        final String key;

        Entry(Path day, String line, ChatMessage message, String key) {
            this.day = day;
            this.line = line;
            this.message = message;
            this.key = key;
        }
    }
}
