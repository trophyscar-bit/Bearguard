package dev.frostguard.engine.squads;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.api.squads.HeroSkill;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Reads the 720x1280 hero page: the Stats tab header, the Hero Overall Stats pop-up, the selected
 * skill on the Skills tab, and the Gear tab. Every reader is a pure function of one frame, so the
 * routine owns all taps and the readers are checked on saved frames.
 */
public final class HeroScreenReader {

    public static final Set<String> TROOP_CLASSES = Set.of("Infantry", "Lancer", "Marksman");

    // A fully lit star measured 1225 cyan pixels in its 61x61 box on a real frame; partly lit
    // stars are reported as the lit fraction of that.
    static final int FULL_STAR_PIXELS = 1225;
    private static final int[] STAR_CENTRE_X = { 215, 288, 360, 432, 505 };
    private static final int STAR_CENTRE_Y = 875;
    private static final int STAR_HALF = 30;

    // Gear tiles in slot order: the "+N" enhancement badge box and the "Lv.N" mastery box.
    private static final int[][] GEAR_PLUS = {
            { 92, 270, 152, 298 }, { 610, 270, 670, 298 }, { 92, 490, 152, 518 }, { 610, 490, 670, 518 } };
    private static final int[][] GEAR_LEVEL = {
            { 96, 348, 154, 373 }, { 614, 348, 672, 373 }, { 96, 568, 154, 593 }, { 614, 568, 672, 593 } };

    // On saved frames a "+N" badge lit 129+ white pixels in its box and a bare tile at most 21;
    // an "Lv.N" lit 133+ yellow in its box and a tile without one none.
    private static final int MARK_MIN_PIXELS = 60;
    private static final int WHITE_FLOOR = 230;

    // Skill levels run 1-5. Particle effects behind some titles add junk after the level
    // ("Snow's Grace Lv. 5 7"), so anything after the level digit is ignored.
    // "Lv" can come back as "lv" glued to the name ("Super Snowballlv.5", "YouthfulRagelv.5").
    // Junk can follow the level digit with no gap ("Soothing Dancelv.200..."), so only the first
    // digit after "Lv" counts; skill levels never pass 5.
    private static final Pattern SKILL_TITLE = Pattern.compile("^(.*?)\\s*[Ll]v\\.?\\s*([1-5]).*$");
    private static final Pattern MASTERY = Pattern.compile("[Ll]v\\.?\\s*(\\d+)");
    // The rarity badge is a gold, purple or blue "SSR" / "SR" / "R"; on real frames each badge
    // lit 1500+ pixels of its own colour and under 5 of the others in the 155x85 box. 500 is a
    // floor well under that, so a badge half hidden by particles still counts.
    private static final int RARITY_MIN_PIXELS = 500;
    static final Set<String> KNOWN_HEROES = Set.of(
            "Mia", "Flint", "Molly", "Natalia", "Zinman", "Alonso", "Jeronimo", "Philly", "Greg", "Logan",
            "Ahmose", "Reina", "Lynn", "Hector", "Norah", "Gwen", "Wu Ming", "Renee", "Wayne", "Edith",
            "Gordon", "Bradley", "Gatot", "Sonya", "Hendrik", "Magnus", "Fred", "Xura", "Blanchette",
            "Bahiti", "Sergey", "Jessie", "Patrick", "Gina", "Jasser", "Seo-yoon", "Lumak Bokan", "Ling Xue",
            "Eugene", "Charlie", "Smith", "Cloris");
    private static final Pattern STAT_LINE = Pattern.compile("^([A-Za-z ]+?)\\s+\\+?([\\d,]+(?:\\.\\d+)?)%?$");

    // A skill glow can wash out the title, so the plain read turns "Mia" into "AE"; the white mask
    // still reads it.
    private static final OcrSettingsData WHITE_NAME = OcrSettingsData.builder()
            .textLayout(TextLayout.SINGLE_LINE).isolateForeground(true).targetColor(Color.WHITE).build();
    // The Upgrade button prints the balance red when it is short of the cost and white otherwise.
    private static final OcrSettingsData WHITE_XP = OcrSettingsData.builder()
            .textLayout(TextLayout.SINGLE_LINE).allowedGlyphs("0123456789,./KMB")
            .isolateForeground(true).targetColor(Color.WHITE).build();
    private static final OcrSettingsData RED_XP = OcrSettingsData.builder()
            .textLayout(TextLayout.SINGLE_LINE).allowedGlyphs("0123456789,")
            .isolateForeground(true).targetColor(new Color(235, 50, 55)).build();
    private static final Pattern XP_BOTH = Pattern.compile("^([\\d,.]+[KMB]?)/([\\d.]+[KMB]?)$");
    private static final Pattern XP_COST = Pattern.compile("^/([\\d.]+[KMB]?)$");

    private HeroScreenReader() {
    }

    public record Detail(String name, String rarity, Long power, Integer level, Double stars,
                         Integer escorts, Long troopCapacity) {
    }

    public record Stats(String troopClass, Map<String, Long> exploration, Map<String, Double> expedition) {
    }

    public record Exclusive(String name, Integer level) {
    }

    /** {@code toNext} comes off the Upgrade button, which rounds it ("2.4M"). */
    public record HeroXp(long have, long toNext) {
    }

    /** True when the Stats / Skills / Gear tab bar of a hero page is on screen. */
    public static boolean isHeroPage(RawImageData frame) {
        String tabs = String.join(" ", SquadOcr.lines(frame, 20, 1205, 700, 1270).stream()
                .map(TextLine::text).toList());
        return tabs.contains("Skills") && tabs.contains("Gear");
    }

    /** True on the Stats tab with no pop-up over it: the Overall Stats button is only there. */
    public static boolean isStatsTab(RawImageData frame) {
        String row = String.join(" ", SquadOcr.lines(frame, 60, 935, 660, 1030).stream()
                .map(TextLine::text).toList());
        return row.contains("Escorts") && row.contains("Troops Capacity");
    }

    /** True on the Gear tab with no dialog over it, so the four tiles are where they should be. */
    public static boolean isGearTab(RawImageData frame) {
        return SquadOcr.text(frame, 70, 1085, 335, 1140, SquadOcr.LINE).contains("Unequip")
                && SquadOcr.text(frame, 385, 1085, 650, 1140, SquadOcr.LINE).contains("Quick Equip");
    }

    /** The hero in the page title, or null unless it reads as a known hero. */
    public static String readName(RawImageData frame) {
        for (OcrSettingsData cfg : List.of(WHITE_NAME, SquadOcr.LINE)) {
            String name = snapToKnownHero(heroName(SquadOcr.text(frame, 200, 15, 520, 62, cfg)));
            if (name != null && KNOWN_HEROES.contains(name)) return name;
        }
        return null;
    }

    /** The XP balance and cost on the Stats tab's Upgrade button, or null when they don't read cleanly. */
    public static HeroXp readHeroXp(RawImageData frame) {
        return heroXp(SquadOcr.text(frame, 270, 1112, 500, 1148, WHITE_XP),
                SquadOcr.text(frame, 270, 1112, 500, 1148, RED_XP));
    }

    static HeroXp heroXp(String white, String red) {
        Matcher both = XP_BOTH.matcher(white);
        if (both.matches()) {
            Long have = abbreviated(both.group(1));
            Long toNext = abbreviated(both.group(2));
            return have != null && toNext != null && have >= toNext ? new HeroXp(have, toNext) : null;
        }
        Matcher cost = XP_COST.matcher(white);
        Long have = SquadOcr.number(red);
        Long toNext = cost.matches() ? abbreviated(cost.group(1)) : null;
        return have != null && toNext != null && have < toNext ? new HeroXp(have, toNext) : null;
    }

    private static Long abbreviated(String text) {
        char last = text.charAt(text.length() - 1);
        long scale = last == 'K' ? 1_000L : last == 'M' ? 1_000_000L : last == 'B' ? 1_000_000_000L : 1L;
        String digits = (scale == 1 ? text : text.substring(0, text.length() - 1)).replace(",", "");
        try {
            return Math.round(Double.parseDouble(digits) * scale);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    public static Detail readDetail(RawImageData frame) {
        String name = snapToKnownHero(heroName(SquadOcr.text(frame, 200, 15, 520, 62, SquadOcr.LINE)));
        String rarity = badgeRarity(frame);
        if (rarity == null) rarity = rarity(SquadOcr.text(frame, 15, 105, 170, 190, SquadOcr.LINE));
        Long power = SquadOcr.number(SquadOcr.text(frame, 290, 770, 430, 808, SquadOcr.LINE));
        Integer level = SquadOcr.smallNumber(SquadOcr.text(frame, 300, 975, 420, 1025, SquadOcr.LINE));
        Integer escorts = SquadOcr.smallNumber(SquadOcr.text(frame, 170, 970, 240, 1010, SquadOcr.LINE));
        Long capacity = SquadOcr.number(SquadOcr.text(frame, 510, 970, 645, 1010, SquadOcr.LINE));
        return new Detail(name, rarity, power, level, stars(frame), escorts, capacity);
    }

    /** Reads the Hero Overall Stats pop-up; returns null when its Expedition header is not visible. */
    public static Stats readStats(RawImageData frame) {
        List<TextLine> lines = SquadOcr.lines(frame, 265, 550, 680, 1030);
        boolean anchored = lines.stream().anyMatch(l -> l.text().trim().equals("Expedition"));
        if (!anchored) return null;
        Map<String, Long> exploration = new LinkedHashMap<>();
        Map<String, Double> expedition = new LinkedHashMap<>();
        String troopClass = null;
        int troopLines = 0;
        for (TextLine line : lines) {
            if (TROOP_CLASSES.contains(line.text().trim().split(" ")[0])) troopLines++;
            Matcher m = STAT_LINE.matcher(line.text().trim());
            if (!m.matches()) continue;
            String label = m.group(1).trim();
            String first = label.split(" ")[0];
            if (TROOP_CLASSES.contains(first)) {
                troopClass = first;
                Double percent = SquadOcr.decimal(m.group(2));
                if (percent != null) expedition.put(label.substring(first.length()).trim(), percent);
            } else if (label.startsWith("Hero ") || label.startsWith("Escort ")) {
                Long number = SquadOcr.number(m.group(2));
                if (number != null) exploration.put(label, number);
            }
        }
        // The game leaves out a troop stat that is 0 (Jessie with no gear shows no Health line).
        // When every troop line on screen was read, a missing Lethality or Health is that 0, not
        // an unreadable line.
        if (troopClass != null && troopLines == expedition.size()) {
            expedition.putIfAbsent("Lethality", 0.0);
            expedition.putIfAbsent("Health", 0.0);
        }
        return new Stats(troopClass, exploration, expedition);
    }

    /** Reads the title and description of the skill currently selected on the Skills tab. */
    public static HeroSkill readSelectedSkill(RawImageData frame) {
        Matcher m = SKILL_TITLE.matcher(SquadOcr.text(frame, 40, 735, 680, 775, SquadOcr.LINE));
        if (!m.matches() || m.group(1).isBlank()) return null;
        StringBuilder description = new StringBuilder();
        for (TextLine line : SquadOcr.lines(frame, 40, 795, 680, 1045)) {
            String text = clean(line.text());
            if (text.startsWith("Upgrade Preview")) break;
            if (!text.isEmpty()) description.append(description.isEmpty() ? "" : " ").append(text);
        }
        // Restore a space OCR dropped between words: "YouthfulRage" -> "Youthful Rage".
        String name = clean(m.group(1)).replaceAll("([a-z])([A-Z])", "$1 $2");
        if (!name.isEmpty()) name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return new HeroSkill(name, Integer.parseInt(m.group(2)),
                description.isEmpty() ? null : description.toString());
    }

    public static List<HeroGear> readGear(RawImageData frame) {
        List<HeroGear> gear = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            int[] p = GEAR_PLUS[slot];
            int[] l = GEAR_LEVEL[slot];
            Integer plus = enhancement(SquadOcr.text(frame, p[0], p[1], p[2], p[3], SquadOcr.WHITE_PLUS_NUMBER),
                    SquadOcr.text(frame, p[0], p[1], p[2], p[3], SquadOcr.PLUS_NUMBER));
            Integer mastery = plus == null ? null
                    : mastery(SquadOcr.text(frame, l[0], l[1], l[2], l[3], SquadOcr.YELLOW_LEVEL));
            gear.add(new HeroGear(slot + 1, plus, mastery));
        }
        return gear;
    }

    /**
     * Like {@link #readGear}, but a tile with no "+N" badge reads +0 and one with no "Lv.N" reads
     * level 0, which leaves null for a mark that is there and didn't read.
     */
    public static List<HeroGear> readTiles(RawImageData frame) {
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        List<HeroGear> tiles = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            int[] p = GEAR_PLUS[slot];
            int[] l = GEAR_LEVEL[slot];
            Integer plus = enhancement(SquadOcr.text(frame, p[0], p[1], p[2], p[3], SquadOcr.WHITE_PLUS_NUMBER),
                    SquadOcr.text(frame, p[0], p[1], p[2], p[3], SquadOcr.PLUS_NUMBER));
            if (plus == null && whitePixels(image, p) < MARK_MIN_PIXELS) plus = 0;
            Integer level = mastery(SquadOcr.text(frame, l[0], l[1], l[2], l[3], SquadOcr.YELLOW_LEVEL));
            if (level == null && yellowPixels(image, l) < MARK_MIN_PIXELS) level = 0;
            tiles.add(new HeroGear(slot + 1, plus, level));
        }
        return tiles;
    }

    private static int whitePixels(BufferedImage image, int[] box) {
        int count = 0;
        for (int x = box[0]; x < box[2]; x++) {
            for (int y = box[1]; y < box[3]; y++) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 16) & 0xFF) >= WHITE_FLOOR && ((rgb >> 8) & 0xFF) >= WHITE_FLOOR && (rgb & 0xFF) >= WHITE_FLOOR) count++;
            }
        }
        return count;
    }

    private static int yellowPixels(BufferedImage image, int[] box) {
        int count = 0;
        for (int x = box[0]; x < box[2]; x++) {
            for (int y = box[1]; y < box[3]; y++) {
                int rgb = image.getRGB(x, y);
                if (Math.abs(((rgb >> 16) & 0xFF) - 250) <= 30 && Math.abs(((rgb >> 8) & 0xFF) - 225) <= 30
                        && Math.abs((rgb & 0xFF) - 100) <= 50) count++;
            }
        }
        return count;
    }

    /**
     * The "+N" enhancement badge. Gear art beside the badge leaks into a plain read ("778",
     * "+7 8"), and white isolation sometimes drops the "+" ("40") or finds a digit in a bare
     * tile ("4"). So a white read that kept its "+" wins, then a plain read that is exactly "+N";
     * anything else, or a value past the +200 ceiling, is unknown.
     */
    static Integer enhancement(String white, String plain) {
        Integer value = null;
        if (white.matches("\\+\\d+")) value = SquadOcr.smallNumber(white);
        else if (plain.matches("\\+\\d+")) value = SquadOcr.smallNumber(plain);
        return value == null || value > 200 ? null : value;
    }

    // A mastered piece prints "Lv.N"; item art alone can read as a bare digit.
    static Integer mastery(String raw) {
        Matcher m = MASTERY.matcher(raw);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    public static Exclusive readExclusive(RawImageData frame) {
        String name = clean(SquadOcr.text(frame, 240, 895, 480, 935, SquadOcr.LINE));
        if (!name.matches("[A-Za-z' ]{3,}")) return null;
        Integer level = SquadOcr.smallNumber(SquadOcr.text(frame, 380, 772, 422, 800, SquadOcr.PLUS_NUMBER));
        return new Exclusive(name, level);
    }

    static Double stars(RawImageData frame) {
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        double total = 0;
        for (int cx : STAR_CENTRE_X) {
            int lit = 0;
            for (int x = cx - STAR_HALF; x <= cx + STAR_HALF; x++) {
                for (int y = STAR_CENTRE_Y - STAR_HALF; y <= STAR_CENTRE_Y + STAR_HALF; y++) {
                    int rgb = image.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                    if (r < 150 && g > 190 && b > 190) lit++;
                }
            }
            total += Math.min(1.0, lit / (double) FULL_STAR_PIXELS);
        }
        return Math.round(total * 10) / 10.0;
    }

    /**
     * Keeps the leading words of a hero title, dropping the generation badge read after it. Stray
     * punctuation read off the title art ("Ling) Xue") is dropped; hyphens stay ("Seo-yoon").
     */
    static String heroName(String raw) {
        StringBuilder name = new StringBuilder();
        for (String token : raw.trim().split("\\s+")) {
            String word = token.replaceAll("[^A-Za-z'-]", "");
            if (word.isEmpty()) continue;
            if (!word.matches("[A-Z][A-Za-z']*(?:-[A-Za-z']+)*") || token.matches("S\\d*")) break;
            name.append(name.isEmpty() ? "" : " ").append(word);
        }
        return name.isEmpty() ? null : name.toString();
    }

    /**
     * The title's animated swoosh can add or swap one letter ("Nataliap"). A read one edit away
     * from exactly one known hero is that hero; anything else is kept as read, so a hero added to
     * the game later still comes through under its own name.
     */
    static String snapToKnownHero(String name) {
        if (name == null || KNOWN_HEROES.contains(name)) return name;
        String match = null;
        for (String known : KNOWN_HEROES) {
            if (withinOneEdit(name, known)) {
                if (match != null) return name;
                match = known;
            }
        }
        return match != null ? match : name;
    }

    private static boolean withinOneEdit(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 1) return false;
        int i = 0, j = 0, edits = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) { i++; j++; continue; }
            if (++edits > 1) return false;
            if (a.length() > b.length()) i++;
            else if (a.length() < b.length()) j++;
            else { i++; j++; }
        }
        return edits + (a.length() - i) + (b.length() - j) <= 1;
    }

    /** Rarity from the badge colour: gold SSR, purple SR, blue R. Null when no colour dominates. */
    static String badgeRarity(RawImageData frame) {
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        int gold = 0, purple = 0, blue = 0;
        for (int x = 15; x < 170; x++) {
            for (int y = 105; y < 190; y++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r > 200 && g > 150 && b < 110) gold++;
                else if (r > 170 && g < 150 && b > 200) purple++;
                else if (r < 120 && g > 120 && b > 200) blue++;
            }
        }
        int best = Math.max(gold, Math.max(purple, blue));
        if (best < RARITY_MIN_PIXELS) return null;
        return best == gold ? "SSR" : best == purple ? "SR" : "R";
    }

    static String rarity(String raw) {
        String text = raw.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
        return switch (text) {
            case "SSR", "SR", "R" -> text;
            default -> null;
        };
    }

    private static String clean(String text) {
        return text.replace('�', '\'').replace('’', '\'').trim();
    }
}
