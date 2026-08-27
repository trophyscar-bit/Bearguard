package dev.frostguard.api.chat;

/**
 * Puts a right-to-left line back into the order it was written in.
 *
 * <p>The reader returns text in the order it appears across the screen, left to right, because that
 * is the order the boxes sit in. For Arabic that is backwards: the line is written right to left, so
 * what comes back is the sentence reversed, character by character. Left alone it is not merely
 * awkward, it is a different string -- "عليك قول ذلك" arrives as "كلذ لوق كيلع", which no reader and
 * no translator can do anything with. Every Arabic message in a live pass came through that way and
 * was translated into confident nonsense: "I'm sorry", "There's a lot of trouble".
 *
 * <p>Reversing the whole line fixes the Arabic and breaks everything else in it. Names and mentions
 * are written left to right even inside an Arabic sentence, so a blanket reversal turns "@Candy"
 * into "ydnaC@" and "Love" into "evoL". So the line is reversed once, and then each run of
 * left-to-right characters inside it is reversed back -- which is what a bidirectional layout does,
 * arrived at from the other end.
 */
public final class BidiText {

    private BidiText() {
    }

    /**
     * Whether this line is written right to left.
     *
     * <p>Judged by which direction most of the letters belong to rather than by finding one. A
     * mention makes every Arabic message contain Latin, and an Arabic word quoted inside an English
     * sentence should not flip the sentence.
     */
    public static boolean isRightToLeft(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int rtl = 0;
        int ltr = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isRtlLetter(c)) {
                rtl++;
            } else if (Character.isLetter(c)) {
                ltr++;
            }
        }
        return rtl > 0 && rtl >= ltr;
    }

    /** Arabic and Hebrew, the two right-to-left scripts this game is played in. */
    private static boolean isRtlLetter(char c) {
        return (c >= 0x0590 && c <= 0x05FF)
                || (c >= 0x0600 && c <= 0x06FF)
                || (c >= 0x0750 && c <= 0x077F)
                || (c >= 0xFB50 && c <= 0xFDFF)
                || (c >= 0xFE70 && c <= 0xFEFF);
    }

    /**
     * Reads a visually-ordered line back into writing order.
     *
     * <p>A no-op for anything that is not right-to-left, so it is safe to call on every line rather
     * than only on the ones somebody decided were Arabic.
     */
    public static String toLogicalOrder(String text) {
        if (!isRightToLeft(text)) {
            return text;
        }
        String flipped = new StringBuilder(text).reverse().toString();

        // Everything that is not an RTL letter belongs to a left-to-right run: Latin words, digits,
        // mentions, and the punctuation between them. Each such run was reversed along with the
        // rest and has to be put back.
        StringBuilder out = new StringBuilder(flipped.length());
        int i = 0;
        while (i < flipped.length()) {
            char c = flipped.charAt(i);
            if (isRtlLetter(c) || Character.isWhitespace(c)) {
                out.append(c);
                i++;
                continue;
            }
            int start = i;
            while (i < flipped.length()
                    && !isRtlLetter(flipped.charAt(i))
                    && !Character.isWhitespace(flipped.charAt(i))) {
                i++;
            }
            out.append(new StringBuilder(flipped.substring(start, i)).reverse());
        }
        return out.toString();
    }
}
