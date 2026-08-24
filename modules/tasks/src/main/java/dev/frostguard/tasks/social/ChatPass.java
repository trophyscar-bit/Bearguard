package dev.frostguard.tasks.social;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import dev.frostguard.api.chat.ChatGarble;
import dev.frostguard.api.chat.ChatLineCleaner;
import dev.frostguard.api.chat.ChatMessage;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.TextLine;

/**
 * One walk back through a chat channel: everything that happens to a screen after it is read, and
 * everything that happens to the pass once every screen has been.
 *
 * <p>This exists because the bench and the live task had drifted apart. Both walked a set of
 * screens and turned them into messages, but each had its own copy of the walk, and the copies
 * stopped matching: the bench did not run the repair rules the routine ran, so a change measured at
 * 90% clean on a fixed corpus scored 72% the first time it met production. The measurement was not
 * wrong about the code it ran -- it was running different code. A number from a bench that does not
 * exercise the shipping path is not evidence, and twice in one evening it was reported as though it
 * were.
 *
 * <p>So the walk lives here once. The live task supplies screens from an emulator and the bench
 * supplies them from PNG files, and past that point there is nothing left to diverge: the same
 * grouping, the same repairs, the same de-duplication, the same decision about what is worth
 * storing. Anything a bench measures is now something production does.
 */
final class ChatPass {

    /**
     * Two screens in a row contributing nothing means this walk has reached history it has already
     * read, and going further only spends captures re-reading it.
     */
    private static final int BARREN_SCREENS_BEFORE_STOP = 2;

    private final String channel;
    private final Function<String, Optional<String>> translate;
    private final OcrSettingsData latin;
    private final OcrSettingsData cjk;
    private final OcrSettingsData cyrillic;
    private final int textColumnRight;

    private final LinkedHashMap<String, ChatMessage> collected = new LinkedHashMap<>();
    private final Set<String> roster = new LinkedHashSet<>();
    private int knownBefore;
    private int barrenScreens;

    ChatPass(String channel, Function<String, Optional<String>> translate,
             OcrSettingsData latin, OcrSettingsData cjk, OcrSettingsData cyrillic,
             int textColumnRight) {
        this.channel = channel;
        this.translate = translate;
        this.latin = latin;
        this.cjk = cjk;
        this.cyrillic = cyrillic;
        this.textColumnRight = textColumnRight;
    }

    /** What one screen contributed, for the caller to log. */
    record Screen(int lines, int readable, int fresh) {
    }

    /**
     * Folds one screen into the pass.
     *
     * @param fromService whether the rows came from the OCR service rather than the built-in
     *                    reader. The repair rules below exist to patch up the built-in reader and
     *                    must not be run over the other one's rows: the word boxes they measure
     *                    against come from the built-in reader, so matching them to rows the
     *                    service produced compares two different readings of the same screen and
     *                    acts on the difference. Live, that interleaved words into each other and
     *                    put CJK into a Spanish name. The service reports its own confidence, which
     *                    is what those rules were reconstructing by inference, and it reads other
     *                    scripts without being asked twice.
     */
    Screen addScreen(RawImageData frame, BufferedImage image, List<TextLine> rows,
                     boolean fromService) {
        List<TextLine> lines = rows;
        if (!fromService) {
            List<TextLine> words = readWords(frame);
            lines = ChatScriptRecovery.reread(frame, lines, words, textColumnRight, cjk, cyrillic);
            lines = ChatOrnamentFilter.clean(lines, words, image);
        }

        // Every sender line names a member, whether or not their message survived reading, so the
        // roster is built from the rows rather than from the messages they became.
        for (TextLine line : lines) {
            ChatLineCleaner.Sender sender = ChatLineCleaner.parseSender(line.text());
            if (sender.trusted() && !sender.allianceTag().isEmpty() && !sender.name().isBlank()) {
                roster.add(sender.name());
            }
        }

        List<ChatMessage> read = ChatFrameReader.read(lines, channel, Instant.now(), translate,
                line -> ChatQuoteBar.isQuoteRow(image, line));
        for (ChatMessage m : read) {
            keep(m);
        }

        int fresh = collected.size() - knownBefore;
        knownBefore = collected.size();
        barrenScreens = fresh == 0 ? barrenScreens + 1 : 0;
        return new Screen(lines.size(), read.size(), fresh);
    }

    /** Whether this walk has reached history it has already read. */
    boolean reachedKnownHistory() {
        return barrenScreens >= BARREN_SCREENS_BEFORE_STOP;
    }

    /** Everything worth storing from this walk, in the order it was read. */
    List<ChatMessage> messages() {
        List<ChatMessage> out = new ArrayList<>(collected.size());
        for (ChatMessage m : collected.values()) {
            if (!m.author().isBlank()) {
                roster.add(m.author());
            }
            roster.addAll(m.mentions());
        }
        for (ChatMessage m : collected.values()) {
            // A message the walk never attributed to anybody was never seen with a name on it,
            // because overlapping scroll steps mean a real message is seen two or three times and
            // the attributed copy wins. Those are game announcements or a strip of a message whose
            // readable half was elsewhere; neither is anybody's words.
            if (m.author().isBlank()) {
                continue;
            }
            String repaired = ChatLineCleaner.repairLeadingMention(m.body(), roster);
            // What the reader could not actually read is worse than nothing: a fragment is
            // indistinguishable from a player who types strangely, so it costs the reader trust in
            // the lines either side of it as well as its own.
            String legible = ChatGarble.repair(repaired);
            if (legible.isBlank()) {
                continue;
            }
            ChatMessage kept = legible.equals(m.body()) ? m : m.withBody(legible);
            if (!legible.equals(m.body())) {
                // The English was made from the body as it read before the repair, so it carries
                // the same wreckage. Read again from the repaired text rather than patched.
                kept = kept.withTranslated(translate.apply(legible).orElse(""));
            }
            out.add(englishQuote(kept));
        }
        return out;
    }

    /**
     * The message being replied to, in English.
     *
     * <p>A reply and the thing it answers are one exchange, and a reader who needs the reply
     * translated needs the quote translated too -- otherwise the half of the conversation that
     * explains the other half is the half they cannot read. The game writes a quote as
     * "Name: what they said", and only the second part is language.
     */
    private ChatMessage englishQuote(ChatMessage m) {
        if (!m.hasQuote()) {
            return m;
        }
        // Folded before the name is looked for, not after: the reader writes the game's CJK colon
        // for the one between a quoted name and its text, and searching the raw string for ":"
        // finds nothing, so the whole strip is read as the thing that was said.
        String quote = ChatGarble.normalise(m.quoted()).strip();
        int colon = quote.indexOf(':');
        String who = colon > 0 && colon < MAX_QUOTED_NAME ? quote.substring(0, colon + 1) : "";
        // The name is judged separately from what was said, because it is not evidence that
        // anything was said. A strip that read as nothing but "CrisdeuS:" used to survive on the
        // strength of the name alone, and showed the reader a reply to an empty message.
        String said = ChatGarble.repair(quote.substring(who.length()));
        if (said.isBlank()) {
            return m.withQuoted("");
        }
        return m.withQuoted(translate.apply(said).map(en -> (who + " " + en).strip())
                .orElse((who + " " + said).strip()));
    }

    /** Longer than this before a colon and it is a sentence, not the name being answered. */
    private static final int MAX_QUOTED_NAME = 24;

    private List<TextLine> readWords(RawImageData frame) {
        try {
            return OcrEngine.recognizeWords(frame, new PointData(0, 0),
                    new PointData(frame.getWidth(), frame.getHeight()), latin);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Files one message against what the walk has already read, treating a re-read as the same
     * message rather than a new one.
     *
     * <p>Where two copies disagree the fuller one wins -- a message clipped by the bottom of the
     * screen is still a correct reading of its first half -- except that an attributed copy always
     * beats an unattributed one. A mention is carried across either way: the copy with the fuller
     * body is not always the copy that kept its mention.
     */
    private void keep(ChatMessage m) {
        String key = ChatLineCleaner.mergeKey(m.body());
        String match = collected.containsKey(key) ? key : null;
        if (match == null) {
            for (String seen : collected.keySet()) {
                if (ChatLineCleaner.sameMessage(seen, key)) {
                    match = seen;
                    break;
                }
            }
        }
        if (match == null) {
            collected.put(key, m);
            return;
        }
        ChatMessage held = collected.get(match);
        ChatMessage winner = better(m, held) ? m : held;
        ChatMessage other = winner == m ? held : m;
        if (!winner.body().startsWith("@") && other.body().startsWith("@")) {
            int end = other.body().indexOf(' ');
            String mention = end < 0 ? other.body() : other.body().substring(0, end);
            winner = winner.withBody(mention + " " + winner.body());
        }
        collected.remove(match);
        collected.put(ChatLineCleaner.mergeKey(winner.body()), winner);
    }

    private static boolean better(ChatMessage candidate, ChatMessage held) {
        if (held.author().isEmpty() != candidate.author().isEmpty()) {
            return held.author().isEmpty();
        }
        return candidate.body().length() > held.body().length();
    }
}
