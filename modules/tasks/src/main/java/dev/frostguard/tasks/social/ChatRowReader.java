package dev.frostguard.tasks.social;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

import dev.frostguard.api.chat.ChatLineCleaner;
import dev.frostguard.api.chat.ChatMessage;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.layout.ChatRowSegmenter;

/**
 * Reads one segmented row into a message.
 *
 * <p>This is the point of segmenting first. Reading the whole feed as one block and splitting the
 * result afterwards produced senders like {@code Ww} and bodies carrying three people's lines,
 * because by then the reader had already run them together. A read bounded to one row cannot merge
 * anything: the region physically contains one name and one message, and the name is its first
 * line.
 *
 * <p>The bands are measured from live 720x1280 captures. The name sits just below the row's top
 * edge, right of the avatar; the bubble begins a little under it and runs to the next avatar.
 */
final class ChatRowReader {

    /** Left edge of the text column, clear of the avatar gutter. */
    private static final int TEXT_X0 = 128;

    /** Right edge, clear of the scrollbar. The per-message translate control sits inside this
     *  band and reads as stray punctuation, which {@link ChatLineCleaner} strips. */
    private static final int TEXT_X1 = 690;

    private static final int BODY_TOP_OFFSET = 58;

    /** Clear of the sender line's descenders before the bubble starts. */
    private static final int BODY_GAP = 4;

    /** Beyond this a bubble is a card or a sticker, and its lower half is art, not text. */
    private static final int MAX_BODY_HEIGHT = 260;

    /** Line feed, by code point, to avoid an escape that source tooling can mangle. */
    private static final char LINE_FEED = (char) 10;

    private ChatRowReader() {
    }

    /**
     * @param rows     the segmented rows, top to bottom
     * @param readText reads a region and returns its text, or null when the read failed
     * @param channel  world, alliance or personal
     * @param at       the capture instant to stamp every message from this frame with
     * @param translate resolves a foreign body to English; empty when already English or unavailable
     */
    static List<ChatMessage> read(List<ChatRowSegmenter.Row> rows,
                                  BiFunction<PointData, PointData, String> readText,
                                  String channel,
                                  Instant at,
                                  java.util.function.Function<String, Optional<String>> translate) {
        List<ChatMessage> out = new ArrayList<>(rows.size());
        String lastAuthor = "";
        String lastTag = "";
        for (ChatRowSegmenter.Row row : rows) {
            // The segmenter measured where the sender line actually is; a fixed offset from the
            // avatar drifted with crowns and rank badges and clipped the glyph tops.
            int nameTop = row.nameTop();
            int nameBottom = Math.min(row.nameBottom(), row.bottom());
            // The body has to start below the sender line, not at a fixed offset from the avatar.
            // Leaving it fixed while the name band moved let the two overlap: the sender line was
            // read a second time as part of the bubble, so the transcript carried
            // "VIP6 [INF]CrisdeuS Reclamen recompensas..." as the message and no author at all.
            int bodyTop = row.hasNameLine()
                    ? Math.min(nameBottom + BODY_GAP, row.bottom())
                    : Math.min(row.top() + BODY_TOP_OFFSET, row.bottom());
            int bodyBottom = Math.min(row.bottom(), bodyTop + MAX_BODY_HEIGHT);
            if (nameBottom <= nameTop || bodyBottom <= bodyTop) {
                continue;
            }

            // One read per row, not two. Reading the name strip and the bubble separately doubled
            // the OCR calls, and a pass is thirty screens across three channels -- the reader is by
            // far the slowest thing here, so halving the calls halves the pass. The name is simply
            // the first line of the region: it sits on its own line above the bubble, which is the
            // same fact the two-region split was relying on.
            String region = readText.apply(new PointData(TEXT_X0, nameTop),
                    new PointData(TEXT_X1, bodyBottom));
            // Line feed by code point: the reader emits \n regardless of platform, so the OS line
            // separator is the wrong thing to look for here.
            int firstBreak = region == null ? -1 : region.indexOf(LINE_FEED);
            String nameLine = firstBreak < 0 ? "" : region.substring(0, firstBreak);
            String bodyLines = firstBreak < 0 ? (region == null ? "" : region) : region.substring(firstBreak + 1);

            ChatLineCleaner.Sender sender = ChatLineCleaner.parseSender(nameLine);
            String raw = ChatLineCleaner.cleanBody(bodyLines);

            // A reply renders the original inside the same bubble, so without splitting it here the
            // transcript credits another player's sentence to whoever quoted it.
            ChatLineCleaner.Body split = ChatLineCleaner.splitQuotedReply(raw);
            String body = split.own();

            ChatMessage.Kind kind = ChatLineCleaner.classify(body);
            if (kind == ChatMessage.Kind.UNREADABLE) {
                continue;
            }
            // When the row's line break is lost the name arrives as the message. Storing it would
            // put a bare "[HOD]TheFlyingDutch" in the transcript as if somebody had said it.
            if (ChatLineCleaner.looksLikeSenderLine(body)) {
                continue;
            }

            // An untrusted name is reported as unknown rather than dropped. The message itself is
            // still real and still belongs in the transcript; it is only the attribution that
            // could not be established, and inventing a participant is the worse failure.
            // The game prints the sender once and omits it on the messages immediately following
            // from the same person, so a row with no name is usually a continuation rather than an
            // anonymous message. Carrying the last known sender forward is what the screen itself
            // means; storing "" put 78 of 182 alliance messages in the transcript with no author.
            // Three different situations, three different answers. A row with a readable sender
            // names its own author. A row with no sender line at all is a continuation -- the game
            // prints the name once and omits it on the messages that follow from the same person --
            // so it inherits. A row that HAD a sender line which could not be read is genuinely
            // unknown, and inheriting there would put the wrong name on someone else's words.
            String author;
            String tag;
            if (sender.trusted()) {
                author = sender.name();
                tag = sender.allianceTag();
                lastAuthor = author;
                lastTag = tag;
            } else if (!row.hasNameLine()) {
                author = lastAuthor;
                tag = lastTag;
            } else {
                author = "";
                tag = "";
            }

            // Only the sender's own words are translated. The quote is another message that was
            // already captured on its own row, so translating it again duplicates the lookup.
            String english = kind == ChatMessage.Kind.TEXT
                    ? translate.apply(body).orElse("")
                    : "";
            // A rendering identical to its source is not a translation; keeping it would show the
            // same sentence twice, once labelled as the original.
            if (english.equalsIgnoreCase(body.trim())) {
                english = "";
            }

            out.add(new ChatMessage(at, channel, author, tag, sender.vipLevel(),
                    body, english, ChatLineCleaner.mentions(body), kind, split.quoted()));
        }
        return out;
    }
}
