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
 * Reads one segmented row into a message, taking the name and the body as separate reads.
 *
 * <p>This is the point of segmenting first. Reading the whole feed as one block and splitting the
 * result afterwards produced senders like {@code Ww} and bodies carrying three people's lines,
 * because by then the reader had already run them together. Two tight reads per row cannot merge
 * anything: the name strip physically contains one name, and the bubble below it contains one
 * message.
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

    private static final int NAME_TOP_OFFSET = 18;
    private static final int NAME_BOTTOM_OFFSET = 56;
    private static final int BODY_TOP_OFFSET = 58;

    /** Beyond this a bubble is a card or a sticker, and its lower half is art, not text. */
    private static final int MAX_BODY_HEIGHT = 260;

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
        for (ChatRowSegmenter.Row row : rows) {
            int nameTop = row.top() + NAME_TOP_OFFSET;
            int nameBottom = Math.min(row.top() + NAME_BOTTOM_OFFSET, row.bottom());
            int bodyTop = Math.min(row.top() + BODY_TOP_OFFSET, row.bottom());
            int bodyBottom = Math.min(row.bottom(), bodyTop + MAX_BODY_HEIGHT);
            if (nameBottom <= nameTop || bodyBottom <= bodyTop) {
                continue;
            }

            ChatLineCleaner.Sender sender = ChatLineCleaner.parseSender(
                    readText.apply(new PointData(TEXT_X0, nameTop), new PointData(TEXT_X1, nameBottom)));
            String body = ChatLineCleaner.cleanBody(
                    readText.apply(new PointData(TEXT_X0, bodyTop), new PointData(TEXT_X1, bodyBottom)));

            ChatMessage.Kind kind = ChatLineCleaner.classify(body);
            if (kind == ChatMessage.Kind.UNREADABLE) {
                continue;
            }

            // An untrusted name is reported as unknown rather than dropped. The message itself is
            // still real and still belongs in the transcript; it is only the attribution that
            // could not be established, and inventing a participant is the worse failure.
            String author = sender.trusted() ? sender.name() : "";

            String english = kind == ChatMessage.Kind.TEXT
                    ? translate.apply(body).orElse("")
                    : "";

            out.add(new ChatMessage(at, channel, author, sender.allianceTag(), sender.vipLevel(),
                    body, english, ChatLineCleaner.mentions(body), kind));
        }
        return out;
    }
}
