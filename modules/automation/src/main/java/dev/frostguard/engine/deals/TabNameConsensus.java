package dev.frostguard.engine.deals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One name from many misreads of it, letter by letter.
 *
 * <p>The reads are cleaned to letters, spaces and the marks names use, and the most typical read (the one
 * closest to all others by edit distance) is the frame. Every read is aligned to it; each of its letters
 * keeps whatever most reads have there, including nothing when most reads lack it. Letters a read adds
 * beyond the frame are not voted on, so the result is never longer than the typical read.</p>
 */
final class TabNameConsensus {

    private static final char DROPPED = '\0';

    private TabNameConsensus() {
    }

    static String of(List<String> reads) {
        List<String> cleaned = reads.stream().map(TabNameConsensus::clean).filter(r -> !r.isBlank()).toList();
        if (cleaned.isEmpty()) {
            return reads.isEmpty() ? "" : reads.get(0).trim();
        }
        String frame = cleaned.get(0);
        long best = Long.MAX_VALUE;
        for (String candidate : cleaned) {
            long total = 0;
            for (String other : cleaned) {
                total += DealCollection.editDistance(candidate, other);
            }
            if (total < best) {
                best = total;
                frame = candidate;
            }
        }
        List<Map<Character, Integer>> votes = new ArrayList<>();
        for (int i = 0; i < frame.length(); i++) {
            votes.add(new HashMap<>());
        }
        for (String read : cleaned) {
            char[] aligned = align(frame, read);
            for (int i = 0; i < aligned.length; i++) {
                votes.get(i).merge(aligned[i], 1, Integer::sum);
            }
        }
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < frame.length(); i++) {
            char frameChar = frame.charAt(i);
            char chosen = votes.get(i).entrySet().stream()
                    .max(Map.Entry.<Character, Integer>comparingByValue()
                            .thenComparing(e -> e.getKey() == frameChar ? 1 : 0))
                    .map(Map.Entry::getKey).orElse(frameChar);
            if (chosen != DROPPED) {
                name.append(chosen);
            }
        }
        return name.toString().replaceAll("\\s+", " ").trim();
    }

    /** The character of {@code read} aligned with each character of {@code frame}, or {@link #DROPPED}. */
    private static char[] align(String frame, String read) {
        int n = frame.length();
        int m = read.length();
        int[][] cost = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            cost[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            cost[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int substitute = cost[i - 1][j - 1] + (frame.charAt(i - 1) == read.charAt(j - 1) ? 0 : 1);
                cost[i][j] = Math.min(substitute, Math.min(cost[i - 1][j], cost[i][j - 1]) + 1);
            }
        }
        char[] aligned = new char[n];
        int i = n;
        int j = m;
        while (i > 0) {
            if (j > 0 && cost[i][j] == cost[i - 1][j - 1] + (frame.charAt(i - 1) == read.charAt(j - 1) ? 0 : 1)) {
                aligned[i - 1] = read.charAt(j - 1);
                i--;
                j--;
            } else if (cost[i][j] == cost[i - 1][j] + 1) {
                aligned[i - 1] = DROPPED;
                i--;
            } else {
                j--;
            }
        }
        return aligned;
    }

    /** Letters, spaces and the marks a name can hold; a quote read between two words is the space it replaced. */
    private static String clean(String read) {
        return read.replaceAll("(?<=\\p{L})['\u2019`](?=\\p{Lu})", " ")
                .replaceAll("[^A-Za-z &/'-]", "")
                .replaceAll("\\s+", " ")
                .replaceAll("^[^A-Za-z]+|[^A-Za-z]+$", "")
                .trim();
    }
}
