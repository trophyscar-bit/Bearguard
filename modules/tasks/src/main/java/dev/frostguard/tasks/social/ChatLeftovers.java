package dev.frostguard.tasks.social;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Frame folders a previous session photographed and never read.
 *
 * <p>A pass photographs first and reads later on its own thread, so stopping the application while
 * a read is under way leaves its folder behind. Nothing picks it up on the next launch: the frames
 * sit in {@code pending} for good, and their messages never reach the transcript. That is what
 * happened to two passes when the bot was stopped to fix a build, and it is why this exists.
 *
 * <p>A folder counts as left over only if it is older than this session and is not one the reader
 * already holds. Without the first test a pass still being photographed would be read while it is
 * half written; without the second a folder queued behind a long read would be read twice.
 */
final class ChatLeftovers {

    private ChatLeftovers() {
    }

    /** A folder of frames and the channel its name says they came from. */
    record Leftover(String channel, Path dir) {
    }

    /**
     * @param pending  the folder frame folders are made in
     * @param before   the start of this session; anything modified since belongs to it
     * @param since    the oldest a folder may be. Reading frames from days ago is worse than not
     *                 reading them: a message carries the time it was read, so six-day-old chat
     *                 would be filed as new today, and it is outside the window that would
     *                 recognise it as already stored.
     * @param inFlight folders already handed to the reader
     * @return the leftovers, oldest first, so the transcript fills in the order it was said
     */
    static List<Leftover> find(Path pending, Instant since, Instant before, Set<Path> inFlight) {
        List<Leftover> found = new ArrayList<>();
        if (!Files.isDirectory(pending)) {
            return found;
        }
        try (Stream<Path> dirs = Files.list(pending)) {
            dirs.filter(Files::isDirectory)
                    .sorted(java.util.Comparator.comparing((Path dir) -> stampOf(dir.getFileName().toString()))
                            .thenComparing(dir -> dir.getFileName().toString()))
                    .forEach(dir -> {
                String channel = channelOf(dir.getFileName().toString());
                if (channel == null || inFlight.contains(dir) || !modifiedBetween(dir, since, before)
                        || !hasFrames(dir)) {
                    return;
                }
                found.add(new Leftover(channel, dir));
            });
        } catch (IOException e) {
            // Unreadable means nothing is offered, which leaves the frames where they are for next time.
            return new ArrayList<>();
        }
        return found;
    }

    /**
     * The time a folder was made, from its name: {@code world-20260928-100649} is 20260928-100649.
     * Sorting on the whole name would put every Alliance folder ahead of every World one whatever
     * the time, and a leftover has to be read in the order it was said.
     */
    private static String stampOf(String folderName) {
        int dash = folderName.indexOf('-');
        return dash < 0 ? folderName : folderName.substring(dash + 1);
    }

    /** Only the two feeds. Personal is a stack of conversations and was never photographed this way. */
    private static String channelOf(String folderName) {
        if (folderName.startsWith("alliance-")) {
            return "alliance";
        }
        if (folderName.startsWith("world-")) {
            return "world";
        }
        return null;
    }

    private static boolean modifiedBetween(Path dir, Instant since, Instant before) {
        try {
            Instant at = Files.getLastModifiedTime(dir).toInstant();
            return at.isBefore(before) && !at.isBefore(since);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasFrames(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".png"));
        } catch (IOException e) {
            return false;
        }
    }
}
