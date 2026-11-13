package dev.frostguard.tasks.combat;

import dev.frostguard.vision.ocr.TextLine;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A whitelist for the Facility rally joiner. A rally is joined only when its target text names one
 * facility, "Fortress No. 12" or "Stronghold No. 2", and that facility is one the alliance signed up
 * for. Anything else is skipped, so no frame of a player-attack rally is needed to be safe, and a read
 * that fails is a skip, never a join.
 */
final class RallyTargetFilter {

    enum Kind { FORTRESS, STRONGHOLD }

    /** One numbered facility, as the Battle screen lists it ("Fortress No. 12"). */
    record Facility(Kind kind, int number) {
        @Override
        public String toString() {
            return (kind == Kind.FORTRESS ? "Fortress " : "Stronghold ") + number;
        }
    }

    // OCR-tolerant kind: "Fortress", "Fortres", "Fort ress"; "Stronghold", "Strong hold", "Stronghol".
    private static final Pattern FACILITY = Pattern.compile(
            "\\b(?:(f\\s?o\\s?r\\s?t\\s?r\\s?e\\s?s{1,2})|(strong\\s?hol\\w*))\\W{0,3}(?:no\\W{0,3}|number\\W{0,2})?(\\d{1,2})\\b",
            Pattern.CASE_INSENSITIVE);
    // A line that also names a player target is never a facility rally, whatever else it says.
    private static final Pattern PLAYER_TARGET = Pattern.compile(
            "\\b(city|furnace|castle|attack(?:ing)?\\s+(?:player|chief))\\b", Pattern.CASE_INSENSITIVE);

    // The kind alone, for a joiner that does not care which facility: "Fortress", "Fort ress", "Stronghold".
    private static final Pattern FACILITY_KIND = Pattern.compile(
            "\\b(?:f\\s?o\\s?r\\s?t\\s?r\\s?e\\s?s{1,2}|strong\\s?hol\\w*)\\b", Pattern.CASE_INSENSITIVE);

    private RallyTargetFilter() {
    }

    /**
     * The one facility the lines name, or null when they name none, name two different ones, name a
     * facility without its number, or name a player target.
     */
    static Facility facilityOf(List<TextLine> lines) {
        if (lines == null || lines.isEmpty()) return null;
        String text = String.join(" ", lines.stream().map(TextLine::text).toList()).toLowerCase(Locale.ROOT);
        if (PLAYER_TARGET.matcher(text).find()) return null;
        Set<Facility> found = new LinkedHashSet<>();
        Matcher m = FACILITY.matcher(text);
        while (m.find()) {
            Kind kind = m.group(1) != null ? Kind.FORTRESS : Kind.STRONGHOLD;
            found.add(new Facility(kind, Integer.parseInt(m.group(3))));
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }

    /**
     * True when the lines name a Fortress or a Stronghold, whichever number, and no player target. An
     * unreadable or empty line is false, so a failed read never joins.
     */
    static boolean isFacilityRally(List<TextLine> lines) {
        if (lines == null || lines.isEmpty()) return false;
        String text = String.join(" ", lines.stream().map(TextLine::text).toList()).toLowerCase(Locale.ROOT);
        return !PLAYER_TARGET.matcher(text).find() && FACILITY_KIND.matcher(text).find();
    }

    /** True when the lines name exactly one facility and it is on the signed-up list. */
    static boolean isSignedUp(List<TextLine> lines, Set<Facility> signedUp) {
        Facility target = facilityOf(lines);
        return target != null && signedUp.contains(target);
    }

    /** "Stronghold 2, Fortress 2; Fortress No. 12" -> the three facilities. Entries that do not parse are dropped. */
    static Set<Facility> parseSignedUp(String text) {
        Set<Facility> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String part : text.split("[,;\\n]")) {
            Facility f = facilityOf(List.of(new TextLine(part, 0, 0, 0, 0, 100f)));
            if (f != null) out.add(f);
        }
        return out;
    }
}
