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
            "^\\s*(?:VIP\\s*(?<vip>\\d{1,2})\\s*)?"
                    + "(?:[\\[(](?<tag>[A-Za-z0-9]{2,4})[\\])]\\s*)?"
                    + "(?<name>.+?)\\s*$");

    /** {@code @Name} and the spaced {@code @ [TAG]Name} the reader also produces. */
    private static final Pattern MENTION = Pattern.compile(
            "@\\s*(?:[\\[(][A-Za-z0-9]{2,4}[\\])])?\\s*([A-Za-z0-9_][A-Za-z0-9_ ]{1,20}?)(?=[\\s,.:!?]|$)");

    /** Characters the reader invents from bubble borders, crowns and unresolved emoji. */
    private static final Pattern ARTIFACTS = Pattern.compile("[|=~®©*“”„¦¬`^]+");

    /** Game-generated cards, which are events rather than things a player said. */
    private static final Pattern SYSTEM_CARD = Pattern.compile(
            "(?i)\\b(share (coordinates|layout)|lucky pouch|new message\\(s\\)|tap to enter"
                    + "|help (request|needed)|has joined the alliance|alliance bomb)\\b");

    private static final Pattern EMOJI_ONLY = Pattern.compile("^[\\p{So}\\p{Cn}\\s]+$");
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
        String cleaned = collapse(ARTIFACTS.matcher(raw).replaceAll(" "));
        Matcher m = SENDER.matcher(cleaned);
        if (!m.matches()) {
            return new Sender(cleaned, "", 0, false);
        }
        String name = collapse(m.group("name"));
        String tag = m.group("tag") == null ? "" : m.group("tag");
        int vip = m.group("vip") == null ? 0 : Integer.parseInt(m.group("vip"));
        return new Sender(name, tag, vip, isPlausibleName(name));
    }

    private static boolean isPlausibleName(String name) {
        return name.length() >= 3 && Character.isLetter(name.charAt(0));
    }

    /** Strips the reader's invented characters and collapses the whitespace they leave behind. */
    public static String cleanBody(String raw) {
        if (raw == null) {
            return "";
        }
        return collapse(ARTIFACTS.matcher(raw).replaceAll(" "));
    }

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
        if (SYSTEM_CARD.matcher(cleanedBody).find()) {
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
     * the machine. Any non-Latin script is decisive on its own; otherwise a body has to be mostly
     * ASCII letters before it is treated as English.
     */
    public static boolean looksEnglish(String body) {
        if (body == null || body.isBlank()) {
            return true;
        }
        long letters = 0;
        long ascii = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (!Character.isLetter(c)) {
                continue;
            }
            letters++;
            if (c < 128) {
                ascii++;
            } else {
                Character.UnicodeScript script = Character.UnicodeScript.of(c);
                if (script != Character.UnicodeScript.LATIN) {
                    return false;
                }
            }
        }
        return letters == 0 || (ascii / (double) letters) >= 0.9;
    }

    /** Lowercased key for caching a translation, so the same phrase is only ever fetched once. */
    public static String cacheKey(String body) {
        return collapse(body == null ? "" : body).toLowerCase(Locale.ROOT);
    }

    private static String collapse(String s) {
        return REPEATED_SPACE.matcher(s.trim()).replaceAll(" ").trim();
    }
}
