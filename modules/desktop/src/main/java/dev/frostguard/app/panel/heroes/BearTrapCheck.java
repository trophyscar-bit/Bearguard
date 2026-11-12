package dev.frostguard.app.panel.heroes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.frostguard.api.squads.HeroRecord;
import dev.frostguard.api.squads.SavedSquad;
import dev.frostguard.api.squads.SquadSnapshot;
import dev.frostguard.api.squads.TroopStock;
import dev.frostguard.engine.squads.SquadPlanner;

/**
 * Answers the owner's five-second question before a Bear Trap: are my saved squads usable, do they
 * differ from what the scan would suggest on purpose, and are there enough marksmen at home?
 *
 * <p>Each fact is said once. The headline and the marksmen pill carry a marksmen shortfall, the
 * banner carries what the scan missed, the "fix first" line and the "also" line carry heroes that
 * are in more than one squad, and a line's pill carries how that squad compares.
 *
 * <p>A saved squad that differs from the suggestion is the player's choice, not a fault. A squad
 * the scan did not read, a hero whose stats it could not read, one hero placed in two squads, or
 * too few marksmen at home are things to check, and never show as a match.
 *
 * <p>A line compares only the first hero: the lead of the lead squad, the one hero that counts when
 * joining. Every Bear Trap squad is a line, so a squad named anywhere is one the table shows.
 */
final class BearTrapCheck {

    /** How a squad compares with the suggestion. A hero in two squads is a separate badge on the line. */
    enum Result { MATCH, CHOICE, NOT_READ, DUPLICATE }

    /** What kind of thing is wrong. */
    enum Kind { MARKSMEN_SHORT, MARKSMEN_UNREAD, SQUADS_UNREAD, SQUADS_EMPTY, DUPLICATE, STATS_UNREAD, HEROES_LOST, SCAN_OTHER }

    /** The mark for a value that does not apply: a suggestion for a squad that was not read. */
    static final String NONE = "—";

    /** One line of the strip: one value per cell. {@code badge} is set when a hero in the squad is in another squad too. */
    record Line(String role, int slot, String saved, String suggested, Result result, String pill, String tip,
            String badge, String badgeTip) {
    }

    /**
     * One thing to check, in a player's words: what it is, why, and what to do. {@code shortName} is
     * the noun for "the scan missed ..." and {@code headline} the short form for the card's verdict.
     */
    record Issue(Kind kind, String title, String why, String fix, String shortName, int count, String headline) {
        /** True when the scan itself missed something, as opposed to a problem in the squads. */
        boolean fromScan() {
            return kind != Kind.MARKSMEN_SHORT && kind != Kind.DUPLICATE && kind != Kind.SQUADS_EMPTY;
        }
    }

    /**
     * {@code needed} is the sum over the squads that were read; {@code unread} lists the slots that were
     * not, so a partial figure is never passed off as the whole.
     */
    record Marksmen(long needed, List<Integer> unread, Long home, Long shortBy, boolean read, String breakdown, boolean uncertain) {
        boolean complete() {
            return unread.isEmpty();
        }

        boolean ok() {
            return read && complete() && shortBy != null && shortBy <= 0;
        }

        /** Short even on the squads that were read; unread squads can only make it worse. */
        boolean short_() {
            return read && shortBy != null && shortBy > 0;
        }
    }

    /**
     * {@code fixFirst} is the one thing to do first, or null. {@code alsoDuplicates} are the other heroes
     * in more than one squad, when there are several; the first is in {@code fixFirst}.
     */
    record Report(List<Line> lines, Marksmen marksmen, List<Issue> issues, String fixFirst, List<String> alsoDuplicates) {
        boolean squadsRead() {
            return marksmen.complete();
        }

        boolean everySquadUnread() {
            // Only when there is nothing to show beside them either: no squad read, no suggestion.
            return !lines.isEmpty() && lines.stream().allMatch(l -> l.result() == Result.NOT_READ && l.saved().equals(NONE)
                    && (l.suggested().equals(NONE) || l.suggested().equals("Troops only")));
        }
    }

    private BearTrapCheck() {
    }

    static Report check(SquadSnapshot snapshot, SquadPlanner.Plan plan, int leadSlot, List<Integer> joinSlots,
            List<Integer> troopSlots) {
        return check(snapshot, plan, leadSlot, joinSlots, troopSlots, true);
    }

    /**
     * @param suggestionsOk false when the scan could not read all the heroes: the suggestions are then
     *                      built from a partial roster, so nothing is compared against them
     */
    static Report check(SquadSnapshot snapshot, SquadPlanner.Plan plan, int leadSlot, List<Integer> joinSlots,
            List<Integer> troopSlots, boolean suggestionsOk) {
        Set<String> flagged = flaggedHeroes(snapshot);
        List<Line> lines = new ArrayList<>();
        List<Integer> unreadSlots = new ArrayList<>();

        // Which squads each hero is in, across every Bear Trap squad that was read.
        List<Integer> all = new ArrayList<>();
        all.add(leadSlot);
        all.addAll(joinSlots);
        all.addAll(troopSlots);
        // Only squads that march at the same time can clash: the lead and the joiners. A hero also in a
        // troops-only or unused squad is not a problem.
        List<Integer> marching = new ArrayList<>();
        marching.add(leadSlot);
        marching.addAll(joinSlots);
        Map<String, List<Integer>> where = new LinkedHashMap<>();
        for (int slot : marching) {
            Optional<SavedSquad> sq = saved(snapshot, slot);
            if (sq.isEmpty() || !readable(sq.get())) continue;
            for (String hero : sq.get().heroes()) {
                if (hero == null) continue;
                where.computeIfAbsent(hero, k -> new ArrayList<>()).add(slot);
            }
        }
        Map<String, List<Integer>> twice = new LinkedHashMap<>();
        where.forEach((hero, slots) -> {
            if (slots.size() > 1) {
                List<Integer> sorted = new ArrayList<>(slots);
                java.util.Collections.sort(sorted);
                twice.put(hero, sorted);
            }
        });
        List<Integer> emptySlots = new ArrayList<>();

        SquadPlanner.Suggestion lead = plan.squads().get(0);
        Optional<SavedSquad> savedLead = saved(snapshot, leadSlot);
        if (savedLead.isEmpty() || !readable(savedLead.get())) {
            String word = emptyWord(savedLead);
            (word == null ? unreadSlots : emptySlots).add(leadSlot);
            // The suggestion does not depend on reading your squad, so it is shown beside the dash.
            lines.add(notRead("Lead", leadSlot, !suggestionsOk ? NONE : lead.heroes().isEmpty() ? "No hero left" : lead.heroes().get(0), word, slot0(leadSlot)));
        } else {
            lines.add(compare("Lead", leadSlot, savedLead.get().heroes(), lead, suggestionsOk, flagged, twice, true));
        }

        for (int i = 0; i < joinSlots.size(); i++) {
            int slot = joinSlots.get(i);
            SquadPlanner.Suggestion wanted = i + 1 < plan.squads().size() ? plan.squads().get(i + 1) : null;
            String label = "Join " + (i + 1);
            Optional<SavedSquad> saved = saved(snapshot, slot);
            if (saved.isEmpty() || !readable(saved.get())) {
                String word = emptyWord(saved);
                (word == null ? unreadSlots : emptySlots).add(slot);
                List<String> wantedHeroes = wanted == null ? List.of() : wanted.heroes();
                lines.add(notRead(label, slot, !suggestionsOk ? NONE : wantedHeroes.isEmpty() ? "No hero left" : wantedHeroes.get(0), word, slot0(slot)));
                continue;
            }
            lines.add(compare(label, slot, saved.get().heroes(), wanted, suggestionsOk, flagged, twice, false));
        }

        // Troop-only squads carry no heroes, so there is nothing to compare; they are lines so a squad named anywhere is shown.
        for (int i = 0; i < troopSlots.size(); i++) {
            int slot = troopSlots.get(i);
            String label = "Troops " + (i + 1);
            Optional<SavedSquad> saved = saved(snapshot, slot);
            if (saved.isEmpty() || !readable(saved.get())) {
                String word = emptyWord(saved);
                (word == null ? unreadSlots : emptySlots).add(slot);
                lines.add(notRead(label, slot, "Troops only", word, slot0(slot)));
                continue;
            }
            List<String> have = saved.get().heroes();
            if (have.isEmpty()) {
                lines.add(new Line(label, slot, "No heroes", "Troops only", Result.MATCH, "Troops only", null, null, null));
            } else {
                lines.add(new Line(label, slot, String.valueOf(have.get(0)), "Troops only", Result.CHOICE,
                        "Has heroes", "A troop-only squad is meant to carry no heroes.", null, null));
            }
        }

        long needed = 0;
        List<String> parts = new ArrayList<>();
        for (int slot : all) {
            Optional<SavedSquad> s = saved(snapshot, slot);
            if (s.isEmpty() || !readable(s.get())) {
                if (emptyWord(s) != null) {
                    parts.add("squad " + slot + ": " + emptyWord(s).toLowerCase(Locale.ROOT));
                    continue;
                }
                if (!unreadSlots.contains(slot)) unreadSlots.add(slot);
                parts.add("squad " + slot + ": not read");
                continue;
            }
            List<Long> troops = s.get().troops();
            long m = troops != null && troops.size() > 2 && troops.get(2) != null ? troops.get(2) : 0;
            needed += m;
            parts.add("squad " + slot + ": " + number(m));
        }
        String breakdown = "Marksmen in squad " + leadSlot + " + squads " + joinSlots.stream().map(String::valueOf)
                .reduce((a, b) -> a + ", " + b).orElse("") + " + squads " + troopSlots.stream().map(String::valueOf)
                .reduce((a, b) -> a + ", " + b).orElse("") + "\n" + String.join("\n", parts);

        Long home = null;
        if (snapshot.troops() != null) {
            long sum = 0;
            for (TroopStock stock : snapshot.troops().stocks()) {
                if ("Marksman".equalsIgnoreCase(stock.troopType())) sum += stock.count();
            }
            home = sum;
        }
        // A troop count under ten in a saved squad is probably a misread, so a total that includes it is approximate.
        boolean uncertain = false;
        for (int slot : all) {
            Optional<SavedSquad> sq = saved(snapshot, slot);
            if (sq.isPresent() && readable(sq.get()) && sq.get().troops() != null
                    && sq.get().troops().stream().anyMatch(n -> n != null && n > 0 && n < 10)) {
                uncertain = true;
            }
        }
        Marksmen marksmen = new Marksmen(needed, List.copyOf(unreadSlots), home, home == null ? null : needed - home,
                home != null, breakdown, uncertain);

        String why = scanWhy(snapshot);
        List<Issue> issues = new ArrayList<>();
        if (!marksmen.read()) {
            issues.add(new Issue(Kind.MARKSMEN_UNREAD, "Couldn't read your marksmen at home", why, "Scan again.",
                    "marksmen", 1, "marksmen not read"));
        } else if (marksmen.short_()) {
            String atLeast = !marksmen.complete() ? "at least " : marksmen.uncertain() ? "about " : "";
            issues.add(new Issue(Kind.MARKSMEN_SHORT, "Short of marksmen by " + atLeast + number(marksmen.shortBy()),
                    !marksmen.complete() ? "Some squads were not read, so the real shortfall may be larger."
                            : marksmen.uncertain() ? "A saved squad has troop counts that look misread, so this is approximate." : "",
                    "Bring marksmen home, or lower a squad's marksmen in the game.", "marksmen", 1,
                    "short " + atLeast + number(marksmen.shortBy()) + " marksmen"));
        }
        if (!unreadSlots.isEmpty()) {
            boolean one = unreadSlots.size() == 1;
            issues.add(new Issue(Kind.SQUADS_UNREAD, "Couldn't read " + slotList(unreadSlots),
                    snapshot.troops() == null ? why : "The scan found " + (one ? "it" : "them") + " empty, locked or unreadable.",
                    "Open " + (one ? "it" : "them") + " once in the game, then Scan again.",
                    one ? "squad " + unreadSlots.get(0) : unreadSlots.size() + " squads", unreadSlots.size(),
                    one ? "squad " + unreadSlots.get(0) + " not read" : unreadSlots.size() + " squads not read"));
        }
        if (!emptySlots.isEmpty()) {
            boolean one = emptySlots.size() == 1;
            issues.add(new Issue(Kind.SQUADS_EMPTY, capital(slotList(emptySlots)) + (one ? " is" : " are") + " empty or locked", "",
                    "Fill " + (one ? "it" : "them") + " in the game, or leave " + (one ? "it" : "them") + " out of the Bear Trap.",
                    slotList(emptySlots), emptySlots.size(), slotList(emptySlots) + " empty or locked"));
        }
        String fixFirst = null;
        List<String> also = new ArrayList<>();
        if (!twice.isEmpty()) {
            List<String> each = new ArrayList<>();
            twice.forEach((hero, slots) -> each.add(hero + " (" + slots.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")) + ")"));
            boolean allTwo = twice.values().stream().allMatch(sl -> sl.size() == 2);
            String clash = twice.size() + (twice.size() == 1 ? " hero is in " : " heroes are in ")
                    + (allTwo ? "two" : "more than one") + " Bear Trap squads: " + String.join(", ", each) + ".";
            String count = twice.size() + (twice.size() == 1 ? " hero" : " heroes") + " in more than one Bear Trap squad";
            issues.add(new Issue(Kind.DUPLICATE, clash, "",
                    "One hero can't march in two squads. Change one of them in the game.", count, twice.size(), count));
            fixFirst = "Fix first: " + clash;
        }
        for (String hero : flagged) {
            boolean used = lines.stream().anyMatch(l -> l.saved().equals(hero));
            if (used) {
                issues.add(new Issue(Kind.STATS_UNREAD, "Couldn't read " + hero + "'s stats",
                        "The scan could not read all of " + hero + "'s stats.",
                        "Open Heroes > " + hero + " once, then Scan again.", hero + "'s stats", 1,
                        hero + "'s stats not read"));
            }
        }
        return new Report(lines, marksmen, issues, fixFirst, also);
    }

    /** Why the troops and squads were not read, when the scan says. */
    static String scanWhy(SquadSnapshot snapshot) {
        for (String p : snapshot.problems()) {
            if (p.startsWith("Chief Profile did not open")) return "Chief Profile didn't open during the scan.";
            if (p.contains("Squads screen did not open")) return "The Squads screen didn't open during the scan.";
        }
        return snapshot.troops() == null ? "The scan stopped before it reached your troops." : "The scan could not read them.";
    }

    private static String slotList(List<Integer> slots) {
        if (slots.size() == 1) return "squad " + slots.get(0);
        List<String> s = slots.stream().map(String::valueOf).toList();
        return "squads " + String.join(", ", s.subList(0, s.size() - 1)) + " and " + s.get(s.size() - 1);
    }

    // A squad that was not read has nothing to compare and nothing to suggest for: the dash, and one pill.
    /** The scanner's own word for a squad that is not usable as saved, or null when it simply could not read it. */
    private static String emptyWord(Optional<SavedSquad> squad) {
        if (squad.isEmpty()) return null;
        return switch (squad.get().state()) {
            case EMPTY_OR_LOCKED -> "Empty or locked";
            case SAME_AS_PREVIOUS -> "Same as previous";
            default -> null;
        };
    }

    private static int slot0(int slot) {
        return slot;
    }

    private static String capital(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // The same word the Saved table uses for the squad; "Not read" only when the scan really could not read it.
    private static Line notRead(String role, int slot, String suggested, String word, int unused) {
        String pill = word == null ? "Not read" : word;
        String tip = word == null
                ? "The scan could not read squad " + slot + ". Open it once in the game, then Scan again."
                : "The scan found squad " + slot + " " + word.toLowerCase(Locale.ROOT) + ".";
        return new Line(role, slot, NONE, suggested, Result.NOT_READ, pill, tip, null, null);
    }

    /**
     * The Result cell is how the squad compares, and nothing else. How many of this squad's heroes also sit in
     * another Bear Trap squad is its own value in its own column, "2 shared", or blank.
     */
    private static Line withDuplicate(Line line, List<String> have, Map<String, List<Integer>> twice) {
        List<String> tips = new ArrayList<>();
        int shared = 0;
        for (String h : have) {
            if (h != null && twice.containsKey(h)) {
                shared++;
                List<String> others = new ArrayList<>();
                for (int other : twice.get(h)) if (other != line.slot()) others.add("squad " + other);
                tips.add(h + " is also in " + String.join(" and ", others));
            }
        }
        if (shared == 0) return line;
        return new Line(line.role(), line.slot(), line.saved(), line.suggested(), line.result(), line.pill(), line.tip(),
                shared + " shared", String.join(". ", tips) + ". One hero can't march in two squads.");
    }

    private static String duplicateBadge(List<String> have, Map<String, List<Integer>> twice) {
        for (String h : have) if (h != null && twice.containsKey(h)) return "Duplicate hero";
        return null;
    }

    private static String duplicateTip(List<String> have, Map<String, List<Integer>> twice) {
        List<String> each = new ArrayList<>();
        for (String h : have) {
            if (h != null && twice.containsKey(h)) each.add(h + " is in " + slotList(twice.get(h)));
        }
        return String.join(". ", each) + ". One hero can't march in two squads.";
    }

    // One decision for a line, on the first hero only. How it compares: unread stats, nothing to compare
    // with, a match, or a difference. Whether a hero is in two squads is a separate badge.
    private static Line compare(String role, int slot, List<String> have, SquadPlanner.Suggestion wanted,
            boolean suggestionsOk, Set<String> flagged, Map<String, List<Integer>> twice, boolean isLead) {
        String mine = have.isEmpty() || have.get(0) == null ? "No hero" : have.get(0);
        List<String> wantedHeroes = wanted == null ? List.of() : wanted.heroes();
        String suggested = !suggestionsOk ? NONE : wantedHeroes.isEmpty() ? "No hero left" : wantedHeroes.get(0);
        String stats = flagged.contains(mine) ? mine : !wantedHeroes.isEmpty() && flagged.contains(wantedHeroes.get(0)) ? wantedHeroes.get(0) : null;
        Line line;
        if (stats != null) {
            line = new Line(role, slot, mine, suggested, Result.NOT_READ, "Stats not read",
                    "The squad was read, but the scan could not read all of " + stats + "'s stats, so this line can't be checked.",
                    null, null);
        } else if (!suggestionsOk) {
            line = new Line(role, slot, mine, suggested, Result.NOT_READ, "Not compared",
                    "The scan could not read all your heroes, so there is nothing to compare this squad with yet.", null, null);
        } else if (!wantedHeroes.isEmpty() && mine.equals(wantedHeroes.get(0))) {
            line = new Line(role, slot, mine, suggested, Result.MATCH, "Match", null, null, null);
        } else {
            String tip = isLead ? choiceTip(wanted)
                    : "Only the first hero of a joining squad counts, and the scan suggests " + suggested + ".";
            line = new Line(role, slot, mine, suggested, Result.CHOICE, "Differs", tip, null, null);
        }
        return withDuplicate(line, have, twice);
    }

    private static String choiceTip(SquadPlanner.Suggestion lead) {
        String why = lead.why() == null ? "" : lead.why().replaceAll("\\.$", "");
        if (why.length() > 1) why = Character.toLowerCase(why.charAt(0)) + why.substring(1);
        String leader = lead.heroes().isEmpty() ? "no one" : lead.heroes().get(0);
        return "The scan suggests " + leader + " to lead (" + String.join(", ", lead.heroes()) + "): it has the " + why
                + ". Keep yours if you chose it on purpose.";
    }

    /** Heroes a scan problem names, e.g. "Expedition stats incomplete for Jessie". */
    static Set<String> flaggedHeroes(SquadSnapshot snapshot) {
        Set<String> out = new LinkedHashSet<>();
        for (String problem : snapshot.problems()) {
            if (problem.startsWith("Hero card") || problem.startsWith("Chief Profile")) continue;
            for (HeroRecord hero : snapshot.heroes()) {
                if (hero != null && hero.name() != null && problem.contains(hero.name())) out.add(hero.name());
            }
        }
        return out;
    }

    private static boolean readable(SavedSquad squad) {
        return squad.state() == SavedSquad.State.SAVED && squad.heroes() != null;
    }

    private static Optional<SavedSquad> saved(SquadSnapshot snapshot, int slot) {
        return snapshot.savedSquads().stream().filter(s -> s.slot() == slot).findFirst();
    }

    static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
