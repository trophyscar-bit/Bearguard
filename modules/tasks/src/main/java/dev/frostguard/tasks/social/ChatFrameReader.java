package dev.frostguard.tasks.social;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import dev.frostguard.api.chat.ChatLineCleaner;
import dev.frostguard.api.chat.ChatMessage;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Builds messages from a whole frame that has already been recognised, using where each line sat.
 *
 * <p>This replaces reading the feed a region at a time. That approach had to decide the boundary
 * of every sender line and every bubble before recognising them, and each of those boundaries was
 * an offset from an avatar whose detected top moved with crowns and rank badges. Nine pixels of
 * drift clipped the tops of glyphs, and the reader returned "UNF jMini TyTy" for a line that plainly
 * reads {@code [INF]Mini TyTy}. Worse, when a band landed slightly high the sender line fell into
 * the bubble instead, and the transcript carried "VIP6 [INF]CrisdeuS a Acabo de enviar a farmear"
 * as somebody's message with no author at all.
 *
 * <p>Recognised whole, the same frame reports every line and where it is, and the two kinds of line
 * separate themselves: on a 720-wide capture a sender line begins around x=140 and the bubble text
 * under it around x=170. That is a measurement rather than a guess, it cannot drift, and it costs
 * one recognition per screen instead of one per region.
 */
final class ChatFrameReader {

    /** The message feed, clear of the tab bar above and the input box below. */
    private static final int FEED_TOP = 175;
    private static final int FEED_BOTTOM = 1160;

    /**
     * Left of this a line is decoration rather than text: avatars, rank badges and the crowns that
     * sit outside them. Sender lines and bubbles both begin to the right of it.
     */
    private static final int TEXT_LEFT_EDGE = 120;

    /**
     * A sender line starts in its own column, left of the bubble it introduces.
     *
     * <p>Measured on live frames: sender lines begin at about x=140 and bubble text at about x=170.
     * The boundary sits between them with room either side, because the exact column shifts a
     * little with the width of the alliance tag.
     */
    private static final int SENDER_COLUMN_LIMIT = 162;

    /** Past this gap the next line belongs to a different message, not the one above. */
    private static final int MESSAGE_GAP = 46;

    private ChatFrameReader() {
    }

    /**
     * @param lines     every line recognised on the frame, with positions
     * @param channel   world, alliance or personal
     * @param at        the capture instant to stamp this frame's messages with
     * @param translate resolves a foreign body to English; empty when already English
     */
    static List<ChatMessage> read(List<TextLine> lines, String channel, Instant at,
                                  Function<String, Optional<String>> translate) {
        List<TextLine> feed = new ArrayList<>();
        for (TextLine l : lines) {
            if (l.top() >= FEED_TOP && l.bottom() <= FEED_BOTTOM && l.left() >= TEXT_LEFT_EDGE
                    && !l.text().isBlank()) {
                feed.add(l);
            }
        }
        feed.sort((a, b) -> Integer.compare(a.top(), b.top()));

        List<ChatMessage> out = new ArrayList<>();
        String author = "";
        String tag = "";
        List<TextLine> pending = new ArrayList<>();
        TextLine previous = null;

        for (TextLine line : feed) {
            ChatLineCleaner.Sender sender = senderOn(line);
            if (sender != null) {
                // A sender line closes whatever was being collected and names what follows.
                flush(out, pending, author, tag, channel, at, translate);
                pending.clear();
                author = sender.name();
                tag = sender.allianceTag();
                previous = line;
                continue;
            }
            // A long drop means the bubble above has ended, even without a new sender line: the
            // game prints the name once and omits it on the messages that follow from one person.
            if (previous != null && line.top() - previous.bottom() > MESSAGE_GAP) {
                flush(out, pending, author, tag, channel, at, translate);
                pending.clear();
            }
            pending.add(line);
            previous = line;
        }
        flush(out, pending, author, tag, channel, at, translate);
        return out;
    }

    /** The sender this line names, or null when it is ordinary message text. */
    private static ChatLineCleaner.Sender senderOn(TextLine line) {
        if (line.left() > SENDER_COLUMN_LIMIT) {
            return null;
        }
        ChatLineCleaner.Sender sender = ChatLineCleaner.parseSender(line.text());
        return sender.trusted() && !sender.allianceTag().isEmpty() ? sender : null;
    }

    private static void flush(List<ChatMessage> out, List<TextLine> pending, String author,
                              String tag, String channel, Instant at,
                              Function<String, Optional<String>> translate) {
        if (pending.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (TextLine l : pending) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(l.text());
        }
        String raw = ChatLineCleaner.cleanBody(sb.toString());
        ChatLineCleaner.Body split = ChatLineCleaner.splitQuotedReply(raw);
        String body = split.own();

        ChatMessage.Kind kind = ChatLineCleaner.classify(body);
        if (kind == ChatMessage.Kind.UNREADABLE || ChatLineCleaner.looksLikeSenderLine(body)) {
            return;
        }
        String english = kind == ChatMessage.Kind.TEXT ? translate.apply(body).orElse("") : "";
        if (english.equalsIgnoreCase(body.trim())) {
            english = "";
        }
        out.add(new ChatMessage(at, channel, author, tag, 0, body, english,
                ChatLineCleaner.mentions(body), kind, split.quoted()));
    }
}
