package dev.frostguard.engine.alliance;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import dev.frostguard.vision.ocr.TextLine;

/**
 * The pack name on an Alliance Gift card is a link to where that pack is sold while it is on sale.
 * Following it once per pack shows where a pack nobody has priced lives, and usually its price.
 *
 * <p>Kept in {@code data/alliance-spend/pack-links.json}, keyed by the normalised pack name, with
 * the page's photograph and text under {@code pack-links/}. Nothing on the opened page is ever
 * tapped; the pass only looks and presses Back.</p>
 */
public final class PackLinks {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Where to tap to follow a link: the middle of one printed line of link text. */
    public record Target(int x, int y) {
    }

    /**
     * What following a pack's link showed: the page's text, every dollar price on it, and the pack's
     * own price ({@code price}: the first one after its title, since a Deals tab lists other packs
     * below it; null when the page shows none, e.g. an event page). {@code link}
     * is false when the pack's name was not a link (the pack is not on sale); {@code tries} counts
     * attempts, so a pack whose page showed no price is retried a few times and then left to the
     * nightly review.
     */
    public record Page(String pack, String seenAt, String frame, List<String> text, List<Double> prices,
                       Double price, boolean link, int tries) {

        /** Pages saved by 3.8.1 carry no link flag; a saved frame means the link opened. */
        boolean opened() {
            return link || frame != null;
        }
    }

    /** A pack not on sale, or whose page showed no price, is tried again after this long. */
    static final long RETRY_HOURS = 24;

    /** Attempts before a priceless page is left to the nightly review. */
    static final int MAX_TRIES = 3;

    private static final Pattern DOLLARS = Pattern.compile("\\$\\s?(\\d{1,3}(?:[.,]\\d{2}))");

    /** Link text needs this many link-blue pixels on a printed line; card edges and icons have none. */
    static final int MIN_LINK_PIXELS = 20;

    /** Rows of link pixels further apart than this are separate printed lines. */
    static final int LINE_GAP_PX = 6;

    /** A card's text runs this far below its first line ("Alliance Gift" to a wrapped pack name). */
    static final int CARD_TEXT_PX = 110;

    private final Path root;
    private final Map<String, Page> pages;

    public PackLinks(Path root) throws IOException {
        this.root = root;
        Path file = root.resolve("pack-links.json");
        this.pages = Files.exists(file)
                ? MAPPER.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Page>>() { })
                : new LinkedHashMap<>();
        // 3.8.1 saved every price on the page and no own price; its first price is the pack's own.
        pages.replaceAll((k, p) -> p.price() == null && p.frame() != null && p.prices() != null && !p.prices().isEmpty()
                ? new Page(p.pack(), p.seenAt(), p.frame(), p.text(), p.prices(), p.prices().get(0), true, Math.max(1, p.tries()))
                : p);
    }

    public boolean followed(String pack) {
        return pack == null || pages.containsKey(AllianceGiftLedger.normalise(pack));
    }

    /**
     * Whether a pack's link should be followed now: never followed, or last seen not on sale or
     * with no price at least {@link #RETRY_HOURS} ago and fewer than {@link #MAX_TRIES} times.
     */
    public boolean due(String pack, LocalDateTime now) {
        if (pack == null || pack.isBlank()) {
            return false;
        }
        Page last = pages.get(AllianceGiftLedger.normalise(pack));
        if (last == null) {
            return true;
        }
        if (last.opened() && last.price() != null || last.tries() >= MAX_TRIES) {
            return false;
        }
        return !LocalDateTime.parse(last.seenAt()).plusHours(RETRY_HOURS).isAfter(now);
    }

    public Map<String, Page> pages() {
        return pages;
    }

    /**
     * Link text is the game's bright link blue, (1, 146, 255) at its core, unlike the card's
     * grey-blue writing (50, 121, 182) and its pale background; measured on the gift lists of
     * 2026-10-26 and 10-27.
     */
    static boolean linkBlue(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r < 60 && g >= 125 && g <= 185 && b >= 235;
    }

    /** Every printed line of link text in the visible list, top to bottom. */
    public static List<Target> find(BufferedImage screen) {
        List<Target> targets = new ArrayList<>();
        int lineTop = -1;
        int lineBottom = -1;
        int pixels = 0;
        int minX = Integer.MAX_VALUE;
        int maxX = -1;
        int top = AllianceGiftLedger.LIST_TOP + 8;
        int bottom = AllianceGiftLedger.LIST_BOTTOM - 8;
        for (int y = top; y <= bottom + LINE_GAP_PX + 1; y++) {
            int count = 0;
            if (y <= bottom) {
                for (int x = AllianceGiftLedger.TEXT_LEFT; x < AllianceGiftLedger.RIGHT_COLUMN; x++) {
                    if (linkBlue(screen.getRGB(x, y))) {
                        count++;
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                    }
                }
            }
            if (count > 0) {
                if (lineTop < 0) {
                    lineTop = y;
                }
                lineBottom = y;
                pixels += count;
            } else if (lineTop >= 0 && y - lineBottom > LINE_GAP_PX) {
                if (pixels >= MIN_LINK_PIXELS && maxX - minX >= 20) {
                    targets.add(new Target((minX + maxX) / 2, (lineTop + lineBottom) / 2));
                }
                lineTop = -1;
                pixels = 0;
                minX = Integer.MAX_VALUE;
                maxX = -1;
            }
        }
        return targets;
    }

    /** The link inside the card whose first line is at {@code cardTop}, or null when its pack is not a link. */
    public static Target inCard(List<Target> targets, int cardTop) {
        for (Target t : targets) {
            if (t.y() > cardTop && t.y() <= cardTop + CARD_TEXT_PX) {
                return t;
            }
        }
        return null;
    }

    /**
     * The pack's own price on its page: the first price at or below the line carrying its name,
     * else the first price on the page. Lines must be in reading order.
     */
    static Double ownPrice(String pack, List<TextLine> lines) {
        String name = AllianceGiftLedger.normalise(pack);
        int titleTop = Integer.MIN_VALUE;
        for (TextLine line : lines) {
            String text = AllianceGiftLedger.normalise(line.text());
            if (!name.isEmpty() && text.length() >= 4 && (text.contains(name) || name.contains(text) && text.length() * 2 >= name.length())) {
                titleTop = line.top();
                break;
            }
        }
        for (TextLine line : lines) {
            if (line.top() >= titleTop) {
                List<Double> found = prices(List.of(line));
                if (!found.isEmpty()) {
                    return found.get(0);
                }
            }
        }
        return null;
    }

    /** Every dollar price written on a page, in reading order. */
    static List<Double> prices(List<TextLine> lines) {
        List<Double> found = new ArrayList<>();
        for (TextLine line : lines) {
            Matcher m = DOLLARS.matcher(line.text() == null ? "" : line.text());
            while (m.find()) {
                found.add(Double.parseDouble(m.group(1).replace(',', '.')));
            }
        }
        return found;
    }

    /** Saves what a followed link opened, so a pack with a price on its page is not followed again. */
    public Page record(String pack, BufferedImage page, List<TextLine> lines, LocalDateTime seenAt) throws IOException {
        String key = AllianceGiftLedger.normalise(pack);
        Files.createDirectories(root.resolve("pack-links"));
        String frame = null;
        if (page != null) {
            frame = "pack-links/" + key + ".png";
            ImageIO.write(page, "png", root.resolve(frame).toFile());
        }
        List<TextLine> ordered = new ArrayList<>(lines);
        ordered.sort((a, b) -> a.top() != b.top() ? Integer.compare(a.top(), b.top()) : Integer.compare(a.left(), b.left()));
        List<String> text = new ArrayList<>();
        for (TextLine line : ordered) {
            text.add(line.text());
        }
        return save(key, new Page(pack, seenAt.truncatedTo(ChronoUnit.SECONDS).toString(), frame, text,
                prices(ordered), ownPrice(pack, ordered), true, tries(key) + 1));
    }

    /** Remembers that a pack's name was not a link, i.e. it is not on sale right now. */
    public Page recordNotALink(String pack, LocalDateTime seenAt) throws IOException {
        String key = AllianceGiftLedger.normalise(pack);
        return save(key, new Page(pack, seenAt.truncatedTo(ChronoUnit.SECONDS).toString(), null, List.of(),
                List.of(), null, false, tries(key) + 1));
    }

    private int tries(String key) {
        Page last = pages.get(key);
        return last == null ? 0 : last.tries();
    }

    private Page save(String key, Page seen) throws IOException {
        pages.put(key, seen);
        Files.createDirectories(root);
        Path tmp = root.resolve("pack-links.json.tmp");
        MAPPER.writeValue(tmp.toFile(), pages);
        Files.move(tmp, root.resolve("pack-links.json"), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        return seen;
    }

    /** Each followed pack's own price, by normalised pack name, for the spend report. */
    public static Map<String, TreeSet<Double>> pricesByPack(Path root) throws IOException {
        Map<String, TreeSet<Double>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Page> e : new PackLinks(root).pages.entrySet()) {
            if (e.getValue().price() != null) {
                out.put(e.getKey(), new TreeSet<>(List.of(e.getValue().price())));
            }
        }
        return out;
    }

    /** Keeps the screen Back led to when it was not the gift list, to see where links lead. */
    public void saveLostScreen(String pack, BufferedImage screen, int backs) throws IOException {
        if (screen != null) {
            Files.createDirectories(root.resolve("pack-links"));
            ImageIO.write(screen, "png", root.resolve("pack-links")
                    .resolve(AllianceGiftLedger.normalise(pack) + "-back" + backs + ".png").toFile());
        }
    }

    /**
     * True when two screens show the same gift-list header: the Loot Chest / Alliance Gift tabs
     * and the "Purchasing a gift pack..." banner. Used after Back to confirm the list is showing
     * again before anything else is tapped.
     */
    public static boolean sameHeader(BufferedImage list, BufferedImage now) {
        int differing = 0;
        int samples = 0;
        for (int y = 362; y < 535; y += 3) {
            for (int x = 30; x < 690; x += 6) {
                int a = list.getRGB(x, y);
                int b = now.getRGB(x, y);
                int d = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                        + Math.abs((a & 0xFF) - (b & 0xFF));
                if (d > 60) {
                    differing++;
                }
                samples++;
            }
        }
        return differing <= samples / 20;
    }
}
