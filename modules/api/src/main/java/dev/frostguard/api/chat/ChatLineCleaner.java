package dev.frostguard.api.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a row's raw OCR into an author and a body worth rendering.
 *
 * <p>Every rule here was measured against live captures rather than imagined. Across 1,929 real
 * messages the reader emits {@code |} 1,516 times, {@code =} 1,219 times and a scatter of
 * {@code ~ ® * "} -- none of which any player typed. They are the game's own chrome: bubble
 * borders, the per-message translate control, VIP crowns and emoji the OCR could not resolve.
 * Left in place they read as text, get counted as content, and get sent to a translator.
 *
 * <p>Sender strings are similarly shaped. Of the same sample, 164 begin with digits and 52 are
 * punctuation runs -- {@code 34 ERE}, {@code - in}, {@code ae} -- which are frame furniture caught
 * by the name strip, not people. A name that cannot be trusted is better reported as unknown than
 * rendered as a participant.
 */
public final class ChatLineCleaner {

    /** {@code VIP7 [THE]Phantom} -- the fullest sender form the game renders. */
    private static final Pattern SENDER = Pattern.compile(
            // A mood badge is drawn left of the VIP tag on some senders and reads as stray
            // punctuation. Anchoring on \s alone pushed the whole line into the name group, so
            // "& VIP6 [INF]CrisdeuS" was stored verbatim as the author.
            "^[^\\p{L}\\p{N}\\[\\]()]*(?:VIP\\s*(?<vip>\\d{1,2})\\s*)?"
                    + "(?:[\\[(](?<tag>[A-Za-z0-9]{2,4})[\\])]\\s*)?"
                    + "(?<name>.+?)\\s*$");

    /**
     * The sender line reduced to its one dependable landmark: the bracketed alliance tag.
     *
     * <p>Anything left of the tag is badge and decoration, whatever it happened to read as. The
     * last tag on the line wins, so a tag mentioned inside a name cannot end the match early.
     */
    private static final Pattern TAG_ANCHORED = Pattern.compile(
            "^.*\\[(?<tag>[A-Za-z0-9]{2,4})\\]\\s*(?<name>\\p{L}[^\\s]{0,23})");

    /**
     * Rewrites the alliance tag into a canonical {@code [TAG]} before anything else reads the line.
     *
     * <p>Measured over 232 rows from 50 live alliance frames: the closing bracket comes back as a
     * literal {@code ]} in only a handful of rows, and as {@code j}, {@code J} or {@code |} in most
     * of the rest -- it is a thin tall glyph sitting hard against the first letter of the name.
     * Worse, {@code |} was being deleted as an invented character before the tag was ever parsed,
     * so the closing bracket vanished entirely. Normalising first means the tag patterns and the
     * artifact strip see the same shape. Insisting on a literal {@code ]} recognised 2 sender lines
     * out of 213.
     */
    private static final Pattern TAG_ANY =
            Pattern.compile("[\\[({]\\s*([A-Za-z0-9]{2,4})\\s*[\\])}jJ|Il1]");

    /** {@code @Name} and the spaced {@code @ [TAG]Name} the reader also produces. */
    private static final Pattern MENTION = Pattern.compile(
            "@\\s*(?:[\\[(][A-Za-z0-9]{2,4}[\\])])?\\s*([A-Za-z0-9_][A-Za-z0-9_ ]{1,20}?)(?=[\\s,.:!?]|$)");

    /** Characters the reader invents from bubble borders, crowns and unresolved emoji. */
    private static final Pattern ARTIFACTS = Pattern.compile("[|=~®©*“”„¦¬`^]+");

    /** Game-generated cards, which are events rather than things a player said. */
    private static final Pattern SYSTEM_CARD = Pattern.compile(
            "(?i)\\b(share (coordinates|layout)|lucky pouch|new message\\(s\\)|tap to enter"
                    + "|help (request|needed)|has joined the alliance|alliance bomb"
                    // The alliance poll is pinned above the feed and carries a clipboard icon the
                    // segmenter reads as an avatar, so it arrives as a message on every pass. Its
                    // own wording is what identifies it: "Initiator:", "Participants: 49/98",
                    // "Vote in: 11:29:34".
                    + "|initiator|participants|vote in|selection"
                    + "|have not participated|alliance notice)\\b");

    /**
     * A bubble that is nothing but the word "Vote".
     *
     * <p>The poll card's button, caught as a row of its own. Matched whole rather than as a word,
     * because "vote" inside a sentence is somebody actually talking about the vote.
     */
    private static final Pattern VOTE_BUTTON = Pattern.compile("(?i)^\s*vote\s*$");

    private static final Pattern EMOJI_ONLY = Pattern.compile("^[\\p{So}\\p{Cn}\\s]+$");

    /**
     * A player name as the game renders it at the head of a quoted reply.
     *
     * <p>Replying puts the original message inside the new bubble as {@code Name: text}. Read as
     * one region that lands in the body, so a third of all captured messages carried somebody
     * else's words appended to their own -- measured at 84 of 295 on a live pass.
     */
    private static final String QUOTED_NAME =
            "(?:\\[[A-Za-z0-9]{2,4}\\])?[A-Za-z][A-Za-z0-9_'!.-]{0,14}(?:\\s[A-Za-z0-9_'!.-]{1,12}){0,2}";

    /** The quote begins on its own line, so a newline boundary is the reliable one. */
    private static final Pattern QUOTE_AT_LINE = Pattern.compile("\\n\\s*(" + QUOTED_NAME + "):\\s");

    /** Fallback for when the reader loses the line break. */
    private static final Pattern QUOTE_INLINE = Pattern.compile("\\s(" + QUOTED_NAME + "):\\s");

    /**
     * Game-generated chatter rather than anything a player typed: recalled messages, shared layouts
     * and coordinates, rally and formation cards. Kept separable so the reader can hide it and be
     * left with the conversation.
     */
    private static final Pattern NON_SPEECH = Pattern.compile(
            "(?i)\\b(recalled a message|share (layout|coordinates)|state\\s*#\\s*\\d+"
                    + "|has joined the alliance|left your alliance|new message\\(s\\)"
                    + "|hold(ing)? a rally|join(ed)? the rally|rally (started|is starting)"
                    + "|defeat the beast|gather together|formation shared)\\b");

    /**
     * The most common English function words. Enough of them in a message means it is English;
     * their absence means it is not, whatever alphabet it happens to use.
     */
    private static final java.util.Set<String> COMMON_ENGLISH = java.util.Set.of(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "i", "it", "for", "not", "on",
            "with", "he", "as", "you", "do", "at", "this", "but", "his", "by", "from", "they", "we",
            "say", "her", "she", "or", "an", "will", "my", "one", "all", "would", "there", "their",
            "what", "so", "up", "out", "if", "about", "who", "get", "which", "go", "me", "when",
            "make", "can", "like", "time", "no", "just", "him", "know", "take", "into", "year",
            "your", "good", "some", "could", "them", "see", "other", "than", "then", "now", "look",
            "only", "come", "its", "over", "think", "back", "after", "use", "two", "how", "our",
            "work", "first", "well", "way", "even", "new", "want", "because", "any", "these", "give",
            "day", "most", "us", "is", "are", "was", "were", "am", "been", "has", "had", "did",
            "does", "dont", "cant", "im", "ive", "youre", "yes", "yeah", "ok", "okay", "thanks",
            "thank", "please", "hi", "hello", "hey", "lol", "sorry", "rally", "join", "help", "need",
            "more", "bro", "man", "still", "here", "why", "much", "very", "really");

    private static final Pattern WORDS = Pattern.compile("[A-Za-z']+");

    /** Below this share of recognisable English words a message is treated as foreign. */
    private static final double ENGLISH_WORD_RATIO = 0.25;

    /**
     * Below this the function-word test has no power and is left alone.
     *
     * <p>Three was too low. "proton password manager" carries no function words at all, scored zero,
     * and was shipped to the translator to come back unchanged -- a wasted lookup on plain English.
     * Short messages now rely on the diacritic and script checks, which need no word evidence.
     */
    private static final int MIN_WORDS_TO_JUDGE = 5;
    private static final Pattern REPEATED_SPACE = Pattern.compile("\\s{2,}");

    private ChatLineCleaner() {
    }

    /** Sender name split into its parts, with {@code trusted} false when it is frame furniture. */
    public record Sender(String name, String allianceTag, int vipLevel, boolean trusted) {
    }

    /**
     * Splits a sender strip into VIP level, alliance tag and name.
     *
     * <p>A name is only trusted when it starts with a letter and carries at least two more
     * characters. That single rule rejects the whole observed garbage population -- digit-led runs
     * and punctuation fragments -- without a blocklist that would need maintaining per patch.
     */
    public static Sender parseSender(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Sender("", "", 0, false);
        }
        // Canonicalise the tag first. The artifact strip below deletes "|", which is one of the
        // shapes the closing bracket reads as, so leaving this until afterwards threw the bracket
        // away before anything could parse it.
        String normalised = TAG_ANY.matcher(raw).replaceAll("[$1]");
        String cleaned = collapse(ARTIFACTS.matcher(normalised).replaceAll(" "));

        // The alliance tag is the one anchor on this line that survives a bad read, so when it is
        // present everything before it is discarded outright. The VIP badge is why: it is drawn on
        // some senders and not others, and when the reader mangles "VIP6" into "ViIPO" the exact
        // VIP group stops matching and the wreckage falls through into the NAME -- which is where
        // authors like "ViIPO LINE PACHChanyu" and "SS} VirP/ TMejbreach" came from. Anchoring on
        // the tag means no spelling of the badge can reach the name. VIP rank is not worth keeping
        // in its own right.
        Matcher anchored = TAG_ANCHORED.matcher(cleaned);
        if (anchored.matches()) {
            String tagged = trimNameNoise(collapse(anchored.group("name")));
            return new Sender(tagged, anchored.group("tag"), 0, isPlausibleName(tagged));
        }

        Matcher m = SENDER.matcher(cleaned);
        if (!m.matches()) {
            return new Sender(cleaned, "", 0, false);
        }
        String name = trimNameNoise(collapse(m.group("name")));
        String tag = m.group("tag") == null ? "" : m.group("tag");
        int vip = m.group("vip") == null ? 0 : Integer.parseInt(m.group("vip"));
        return new Sender(name, tag, vip, isPlausibleName(name));
    }

    /** Characters that occur in real player names: letters, digits, and the usual name punctuation. */
    private static final Pattern NAME_ODDITY = Pattern.compile("[^\\p{L}\\p{N} _.'-]");

    /** More than one stray symbol is the reader inventing glyphs, not a player being creative. */
    private static final int MAX_ODD_CHARS_IN_A_NAME = 1;

    /**
     * Whether a name strip read cleanly enough to attribute a message to.
     *
     * <p>"Starts with a letter and is three characters long" was too permissive. It let through
     * "S} ViP/ TRejbreach" and "fe MPA) /UIBGeULClICr", which rendered in the transcript as
     * participants -- the reader mangling a VIP badge and an alliance tag into punctuation. Real
     * names carry at most an odd character or two; a scattering of braces and slashes is noise.
     */
    private static boolean isPlausibleName(String name) {
        if (name.length() < 3 || !Character.isLetter(name.charAt(0))) {
            return false;
        }
        // A bracket surviving into the name means the alliance tag was not parsed off cleanly, so
        // what is left is the wreckage of the whole line rather than a player -- this is what
        // "VIPG [INF jAthenaRyu" looks like by the time it gets here.
        if (UNPARSED_TAG.matcher(name).find()) {
            return false;
        }
        // Likewise a VIP badge still sitting in the name: it is drawn on some senders and not
        // others, so any spelling of it here means the badge was misread rather than skipped, and
        // the rest of the line cannot be trusted either.
        if (STRAY_VIP.matcher(name).find()) {
            return false;
        }
        return NAME_ODDITY.matcher(name).results().count() <= MAX_ODD_CHARS_IN_A_NAME;
    }

    /** Drops the decorations the reader picks up past the end of the name, e.g. "CrisdeuS 7". */
    private static String trimNameNoise(String name) {
        return NAME_TRAILING_NOISE.matcher(name).replaceAll("");
    }

    /** A bracket left in the name means the tag was never separated from it. */
    private static final Pattern UNPARSED_TAG = Pattern.compile("[\\[\\]()]");

    /** The badge as the reader spells it when it fails: VIP, ViIPO, VIPG, VirP, VIPw. */
    private static final Pattern STRAY_VIP = Pattern.compile("(?i)\\bV[il1|]{0,2}[PR][0-9A-Za-z]?\\b");

    /** Trailing digits and stray letters the reader picks up from decorations beside the name. */
    private static final Pattern NAME_TRAILING_NOISE =
            Pattern.compile("[\\s\\p{N}\\p{Punct}]+$");

    /** Strips the reader's invented characters and collapses the whitespace they leave behind. */
    public static String cleanBody(String raw) {
        if (raw == null) {
            return "";
        }
        String body = collapse(ARTIFACTS.matcher(raw).replaceAll(" "));
        return trimOrphanGlyphs(body);
    }

    /**
     * Strips the single stray glyph the bubble's own edges leave at each end of a message.
     *
     * <p>The bubble border on the left and the per-message translate control on the right both fall
     * inside the read, and both survive as one orphan character: measured over 310 stored messages,
     * 123 of them carried one. It is what makes an otherwise readable line look like wreckage --
     * "Pero ahora somos pocos conectados" arrives as "ç Pero ahora somos pocos conectados", and
     * "jaaj" as "jaaj E".
     *
     * <p>Only shapes no message begins or ends with are removed. A leading letter is deliberately
     * left alone even though it is often junk, because "Y" and "y" open Spanish sentences and
     * "e" opens Portuguese ones, and deleting a real word to tidy a stray one is the worse trade.
     */
    private static String trimOrphanGlyphs(String body) {
        String out = body;
        for (int i = 0; i < 2; i++) {
            out = LEADING_ORPHAN.matcher(out).replaceAll("");
        }
        out = TRAILING_ORPHAN.matcher(out).replaceAll("");
        out = TRAILING_LOOSE_CAPS.matcher(out).replaceAll("");
        return out.strip();
    }

    /** One or two symbols before the first word: the bubble's left edge, never punctuation a
     *  player opens with, so @ # [ and ( are excluded. */
    private static final Pattern LEADING_ORPHAN =
            Pattern.compile("^[^\\w\\s@#\\[(]{1,2}\\s+");

    /** Symbols hanging off the end, where the translate control sits. */
    private static final Pattern TRAILING_ORPHAN =
            Pattern.compile("\\s+[^\\w\\s.!?)\\]]{1,3}\\s*$");

    /** The control also reads as one or two loose capitals, most often "E". */
    private static final Pattern TRAILING_LOOSE_CAPS =
            Pattern.compile("\\s+[A-Z]{1,2}\\s*$");

    /** Names this message addressed, in order, without duplicates. */
    public static List<String> mentions(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher m = MENTION.matcher(body);
        while (m.find()) {
            String name = m.group(1).trim();
            if (isPlausibleName(name)) {
                found.add(name);
            }
        }
        return new ArrayList<>(found);
    }

    /** Decides what the bubble actually held, so the renderer can show it honestly. */
    public static ChatMessage.Kind classify(String cleanedBody) {
        if (cleanedBody == null || cleanedBody.isBlank()) {
            return ChatMessage.Kind.UNREADABLE;
        }
        if (SYSTEM_CARD.matcher(cleanedBody).find() || VOTE_BUTTON.matcher(cleanedBody).matches()) {
            return ChatMessage.Kind.SYSTEM;
        }
        if (EMOJI_ONLY.matcher(cleanedBody).matches()) {
            return ChatMessage.Kind.EMOJI;
        }
        // Under three letters there is nothing a reader or a translator can use, whatever the
        // reader emitted. Treating it as text puts noise in the transcript and burns a lookup.
        long letters = cleanedBody.chars().filter(Character::isLetter).count();
        return letters >= 3 ? ChatMessage.Kind.TEXT : ChatMessage.Kind.UNREADABLE;
    }

    /**
     * Whether the body already reads as English, decided locally so the common case never leaves
     * the machine.
     *
     * <p>The first version compared how much of the text was ASCII, which is not a language test at
     * all. Turkish and Spanish are written in plain ASCII, so "bana nazik bir sekilde maymun dedi"
     * scored as confidently English and was never translated. On a live pass that rule called every
     * one of the foreign messages English -- the translation feature had a 100% miss rate while
     * appearing to work.
     *
     * <p>What actually separates English from other Latin-script languages is its function words.
     * A message carrying none of them is not English regardless of its alphabet, and one carrying
     * several is, so the decision keys on those instead. Any non-Latin script still settles it
     * outright.
     *
     * <p>A short message is left alone: two words are not enough evidence either way, and sending
     * them all for translation would spend lookups on "one day" and "thanks bro".
     */
    public static boolean looksEnglish(String body) {
        if (body == null || body.isBlank()) {
            return true;
        }
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (!Character.isLetter(c) || c < 128) {
                continue;
            }
            // Any non-Latin script settles it outright. So does an accented Latin letter: English
            // effectively does not use them, so a single one is stronger evidence than a word count
            // -- and it catches short messages the word test is too small to judge.
            return false;
        }

        java.util.List<String> words = new ArrayList<>();
        Matcher m = WORDS.matcher(body.toLowerCase(Locale.ROOT));
        while (m.find()) {
            words.add(m.group());
        }
        if (words.size() < MIN_WORDS_TO_JUDGE) {
            return true;
        }
        long known = words.stream().filter(COMMON_ENGLISH::contains).count();
        return (known / (double) words.size()) >= ENGLISH_WORD_RATIO;
    }

    /** What a bubble held once the quoted reply is taken off it. */
    public record Body(String own, String quoted) {
    }

    /**
     * Separates the sender's own words from the message they were replying to.
     *
     * <p>Both are read as one region, so without this the transcript attributes another player's
     * sentence to whoever quoted it. The quote is rendered last, so the final boundary is the
     * right one -- taking the first match splits on a name mentioned mid-sentence instead.
     */
    public static Body splitQuotedReply(String body) {
        if (body == null || body.isBlank()) {
            return new Body("", "");
        }
        int start = quoteStart(body);
        if (start < 0) {
            return new Body(collapse(body), "");
        }
        return new Body(collapse(body.substring(0, start)), collapse(body.substring(start)));
    }

    /**
     * Where the quoted original begins, or -1 when the bubble is not a reply.
     *
     * <p>Found by locating the colon and then stepping back over the name, rather than by matching
     * a name pattern forwards. Matching forwards is what broke it: the name could run to three
     * words, so in "...trade them in for resource pouches Nicko: have so many..." the earliest
     * position that could satisfy the pattern was "resource", and the split handed "resource
     * pouches" to the quote. The sender lost the end of their own sentence, on 116 of 279 replies.
     *
     * <p>Stepping back from the colon cannot do that. A name is one word, or two for the handful of
     * players who have a space in theirs, so at most two are taken and everything before them stays
     * with the message.
     */
    private static int quoteStart(String body) {
        int found = -1;
        for (int colon = body.indexOf(':'); colon >= 0; colon = body.indexOf(':', colon + 1)) {
            // The game writes "Name: text", so a colon with no space after it is punctuation
            // inside a sentence, or a timestamp.
            if (colon + 1 >= body.length() || !Character.isWhitespace(body.charAt(colon + 1))) {
                continue;
            }
            int nameStart = walkBackOverName(body, colon);
            if (nameStart < 0) {
                continue;
            }
            // A quote with nothing before it is the whole bubble, not a reply.
            if (!body.substring(0, nameStart).isBlank()) {
                found = nameStart;
            }
        }
        return found;
    }

    /** Start of the one or two words immediately before {@code colon}, or -1 if they are not a name. */
    private static int walkBackOverName(String body, int colon) {
        int end = colon;
        int start = -1;
        for (int words = 0; words < MAX_WORDS_IN_A_QUOTED_NAME; words++) {
            int wordEnd = end;
            while (wordEnd > 0 && Character.isWhitespace(body.charAt(wordEnd - 1))) {
                wordEnd--;
            }
            int wordStart = wordEnd;
            while (wordStart > 0 && !Character.isWhitespace(body.charAt(wordStart - 1))) {
                wordStart--;
            }
            if (wordStart == wordEnd) {
                break;
            }
            String word = body.substring(wordStart, wordEnd);
            if (!isNameWord(word)) {
                break;
            }
            start = wordStart;
            end = wordStart;
            // One word is the normal case; only keep going for names that really do carry a space.
            if (words == 0 && !startsLikeContinuedName(body, wordStart)) {
                break;
            }
        }
        return start;
    }

    /** Player names are one word, or two for the few that carry a space. */
    private static final int MAX_WORDS_IN_A_QUOTED_NAME = 2;

    /** A word that could be part of a player name rather than ordinary sentence text. */
    private static boolean isNameWord(String word) {
        String bare = word.startsWith("[") && word.contains("]")
                ? word.substring(word.indexOf(']') + 1) : word;
        if (bare.isEmpty() || bare.length() > 18 || !Character.isLetter(bare.charAt(0))) {
            return false;
        }
        return bare.chars().allMatch(c -> Character.isLetterOrDigit(c)
                || c == '_' || c == '\'' || c == '.' || c == '-' || c == '!');
    }

    /**
     * Whether the word before this one also looks like part of the same name.
     *
     * <p>Only capitalised, so "Mini TyTy" is taken as one name while "for resource" is not -- the
     * second word of a two-word player name is written as a name, not as sentence text.
     */
    private static boolean startsLikeContinuedName(String body, int wordStart) {
        int end = wordStart;
        while (end > 0 && Character.isWhitespace(body.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && !Character.isWhitespace(body.charAt(start - 1))) {
            start--;
        }
        if (start == end) {
            return false;
        }
        String previous = body.substring(start, end);
        return isNameWord(previous) && Character.isUpperCase(previous.charAt(0));
    }

    /**
     * True when a body is actually a sender line the row split failed to separate.
     *
     * <p>The name sits on the first line of the row, so a missing line break sends it downstream as
     * the message. It shows up as a bubble containing nothing but a tag and a name -- "64
     * [HOD]TheFlyingDutch" -- which is not something anyone said.
     */
    public static boolean looksLikeSenderLine(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        Sender s = parseSender(body);
        // The giveaway is the decoration, not the name: a stray sender line still carries its
        // alliance tag or VIP badge, and no message is only that. Keying on "does this parse as a
        // name" instead would match almost any short sentence, since the parser will happily read
        // one as a name.
        if (s.allianceTag().isEmpty() && s.vipLevel() == 0) {
            return false;
        }
        long words = WORDS.matcher(s.name()).results().count();
        return words <= MAX_WORDS_IN_A_NAME;
    }

    /** A player name runs to a few words; a sentence does not stop there. */
    private static final int MAX_WORDS_IN_A_NAME = 3;

    /** True when the line is a game event rather than something a player said. */
    public static boolean isNonSpeech(String body) {
        return body != null && NON_SPEECH.matcher(body).find();
    }

    /** Lowercased key for caching a translation, so the same phrase is only ever fetched once. */
    public static String cacheKey(String body) {
        return collapse(body == null ? "" : body).toLowerCase(Locale.ROOT);
    }

    private static String collapse(String s) {
        return REPEATED_SPACE.matcher(s.trim()).replaceAll(" ").trim();
    }
}
