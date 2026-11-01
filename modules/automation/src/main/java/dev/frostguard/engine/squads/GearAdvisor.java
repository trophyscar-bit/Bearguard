package dev.frostguard.engine.squads;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.api.squads.HeroRecord;
import dev.frostguard.api.squads.SavedSquad;
import dev.frostguard.api.squads.SquadSnapshot;

/**
 * Where to put Essence Stones, enhancement XP and widgets next, from the gear the scan read.
 * The rules come from community guides (whiteoutsurvival.wiki, wos.h5joy-games.com,
 * commonsensegamer.com, the WOSBearHunt gear guide), not from the game itself:
 *
 * <ul>
 *   <li>Goggles and boots give troop Lethality; gloves and belt give troop Health. The scan
 *       agrees: heroes whose goggles and boots are highest have the highest Lethality.</li>
 *   <li>A hero's gear buffs that hero's own troop type in a march the hero is in; in a rally only
 *       the leader's march counts, and joiners add just one skill. So it goes on the three heroes
 *       of the lead squad, one per troop type. Gear keeps its levels when moved to another hero.</li>
 *   <li>Each troop type's main pair is infantry gloves and belt, marksman goggles and boots,
 *       lancer goggles and boots. The guides rank them in that order (infantry holds the front);
 *       here the type with the most troops in your saved squads goes first, since that is how
 *       you fight (Bear Trap squads are nearly all marksmen), with the guide order breaking ties.</li>
 *   <li>Bring those three pairs to Mastery 5 first, lowest first, then raise them one level at a
 *       time in priority order, keeping each pair level. Mastery level n costs 10 x n stones;
 *       levels past 10 also take mythic gear. Mastery reforge refunds only half the stones.</li>
 *   <li>Exclusive gear unlocks its troop bonus at +2, +4 and +10; level n costs 5 x n widgets.</li>
 * </ul>
 * Costs are the guides' figures, not read from the game, so the tab labels them estimates.
 */
public final class GearAdvisor {

    private static final List<String> GUIDE_ORDER = List.of("Infantry", "Marksman", "Lancer");
    private static final String[] PIECES = { "Goggles", "Gloves", "Belt", "Boots" };
    private static final Set<Integer> LETHALITY_SLOTS = Set.of(1, 4);
    private static final int MASTERY_FIRST_TARGET = 5;
    private static final int MASTERY_STONE_ONLY_MAX = 10;
    private static final int MASTERY_MIN_ENHANCEMENT = 20;
    private static final int[] WIDGET_MILESTONES = { 2, 4, 10 };
    private static final int MAX_STEPS = 12;

    private GearAdvisor() {
    }

    /** {@code covered} is true when the stones on hand pay for this step and every one before it. */
    public record Step(String spend, String hero, String piece, String now, String next, Integer cost,
                       boolean covered, String why) {
    }

    public record Advice(List<Step> steps, List<String> notes) {
    }

    public static Advice advise(SquadSnapshot snapshot, SquadPlanner.Plan plan, int stonesOnHand) {
        Map<String, HeroRecord> byName = new LinkedHashMap<>();
        for (HeroRecord hero : snapshot.heroes()) byName.putIfAbsent(hero.name(), hero);
        Map<String, String> classOf = new LinkedHashMap<>();
        for (SquadPlanner.HeroScore score : plan.heroScores()) classOf.put(score.hero(), score.troopClass());

        List<Step> steps = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> order = typeOrder(snapshot, notes);
        Map<String, HeroRecord> rallyLeads = inOrder(leadsByClass(plan.squads().getFirst().heroes(), classOf, byName), order);
        Map<String, HeroRecord> garrisonLeads = leadsByClass(plan.squads().get(6).heroes(), classOf, byName);
        stoneSteps(rallyLeads, stonesOnHand, steps, notes);
        enhancementSteps(rallyLeads, steps);
        widgetSteps(rallyLeads, garrisonLeads, steps);
        heroNotes(snapshot, rallyLeads, garrisonLeads, notes);
        return new Advice(steps, notes);
    }

    // ── Essence stones ──────────────────────────────────────────────

    private static final class Piece {
        final HeroRecord hero;
        final int slot;
        final int tier;
        final int enhancement;
        int mastery;

        Piece(HeroRecord hero, HeroGear gear, int tier) {
            this.hero = hero;
            this.slot = gear.slot();
            this.tier = tier;
            this.enhancement = gear.enhancement();
            this.mastery = gear.mastery() == null ? 0 : gear.mastery();
        }

        String name() {
            return PIECES[slot - 1];
        }

        String stat() {
            return LETHALITY_SLOTS.contains(slot) ? "Lethality" : "Health";
        }
    }

    /**
     * The two pieces that matter most for each lead: infantry Health, the other types Lethality.
     * {@code leads} is in priority order.
     */
    private static List<Piece> topPieces(Map<String, HeroRecord> leads) {
        List<Piece> pieces = new ArrayList<>();
        int tier = -1;
        for (Map.Entry<String, HeroRecord> entry : leads.entrySet()) {
            tier++;
            String troopClass = entry.getKey();
            HeroRecord lead = entry.getValue();
            if (lead.gear() == null) continue;
            boolean wantsLethality = !"Infantry".equals(troopClass);
            for (HeroGear gear : lead.gear()) {
                if (gear.enhancement() == null || LETHALITY_SLOTS.contains(gear.slot()) != wantsLethality) continue;
                pieces.add(new Piece(lead, gear, tier));
            }
        }
        return pieces;
    }

    private static void stoneSteps(Map<String, HeroRecord> leads, int stonesOnHand, List<Step> steps, List<String> notes) {
        List<Piece> pieces = topPieces(leads);
        pieces.removeIf(p -> {
            if (p.enhancement >= MASTERY_MIN_ENHANCEMENT) return false;
            notes.add(p.hero.name() + "'s " + p.name() + " needs +" + MASTERY_MIN_ENHANCEMENT
                    + " before it can take Essence Stones.");
            return true;
        });
        if (pieces.isEmpty()) return;

        int spent = 0;
        boolean stillCovered = true;
        List<Step> stoneSteps = new ArrayList<>();
        for (int i = 0; i < MAX_STEPS; i++) {
            Piece next = nextForStones(pieces);
            if (next == null) break;
            int cost = 10 * (next.mastery + 1);
            stillCovered = stillCovered && spent + cost <= stonesOnHand;
            if (stillCovered) spent += cost;
            String why = next.mastery < MASTERY_FIRST_TARGET
                    ? "Brings the main " + next.hero.troopClass() + " pieces to Mastery 5 first, lowest first."
                    : "Keeps the " + next.hero.troopClass() + " " + next.stat() + " pair level, in priority order.";
            stoneSteps.add(new Step("Essence stones", next.hero.name(), next.name() + " (" + next.stat() + ")",
                    "+" + next.enhancement + " / Lv " + next.mastery, "Lv " + (next.mastery + 1), cost, stillCovered, why));
            next.mastery++;
        }
        steps.addAll(stoneSteps);
        if (!stoneSteps.isEmpty() && !stoneSteps.getFirst().covered()) {
            notes.add("The first stone step costs " + stoneSteps.getFirst().cost() + " and you have " + stonesOnHand
                    + ": save up for it rather than spending on a later step.");
        } else if (!stoneSteps.isEmpty()) {
            notes.add("Your " + stonesOnHand + " stones cover the steps marked yes (" + spent + " stones).");
        }
    }

    /** Lowest piece below Mastery 5 first; after that one level per pair, infantry, marksman, lancer. */
    private static Piece nextForStones(List<Piece> pieces) {
        Comparator<Piece> byPriority = Comparator.<Piece>comparingInt(p -> p.tier).thenComparingInt(p -> p.slot);
        Piece belowTarget = pieces.stream().filter(p -> p.mastery < MASTERY_FIRST_TARGET)
                .min(Comparator.<Piece>comparingInt(p -> p.mastery).thenComparing(byPriority)).orElse(null);
        if (belowTarget != null) return belowTarget;
        // The pair furthest behind the others goes next; within a pair the lower piece.
        return pieces.stream().filter(p -> p.mastery < MASTERY_STONE_ONLY_MAX)
                .min(Comparator.<Piece>comparingInt(p -> pairLevel(pieces, p)).thenComparingInt(p -> p.tier)
                        .thenComparingInt(p -> p.mastery).thenComparingInt(p -> p.slot))
                .orElse(null);
    }

    private static int pairLevel(List<Piece> pieces, Piece piece) {
        return pieces.stream().filter(p -> p.tier == piece.tier).mapToInt(p -> p.mastery).min().orElse(piece.mastery);
    }

    // ── Enhancement XP ──────────────────────────────────────────────

    private static void enhancementSteps(Map<String, HeroRecord> leads, List<Step> steps) {
        List<Piece> pieces = topPieces(leads);
        for (int i = 0; i < MAX_STEPS / 2; i++) {
            Piece next = pieces.stream().filter(p -> p.enhancement < 100)
                    .min(Comparator.<Piece>comparingInt(p -> p.enhancement).thenComparingInt(p -> p.tier)
                            .thenComparingInt(p -> p.slot)).orElse(null);
            if (next == null) break;
            int target = Math.min(100, (next.enhancement / 10 + 1) * 10);
            steps.add(new Step("Enhancement XP", next.hero.name(), next.name() + " (" + next.stat() + ")",
                    "+" + next.enhancement, "+" + target, null, false,
                    "Lowest of the main pieces. XP reforge refunds all of it, so it is safe to move later."));
            pieces.remove(next);
            pieces.add(new Piece(next.hero, new HeroGear(next.slot, target, next.mastery), next.tier));
        }
    }

    // ── Widgets (exclusive gear) ────────────────────────────────────

    private static void widgetSteps(Map<String, HeroRecord> rallyLeads, Map<String, HeroRecord> garrisonLeads,
                                    List<Step> steps) {
        List<HeroRecord> heroes = new ArrayList<>(rallyLeads.values());
        garrisonLeads.values().stream().filter(h -> !heroes.contains(h)).forEach(heroes::add);
        List<Step> widgetSteps = new ArrayList<>();
        for (HeroRecord hero : heroes) {
            if (hero.exclusiveGear() == null) continue;
            int level = hero.exclusiveGearLevel() == null ? 0 : hero.exclusiveGearLevel();
            for (int milestone : WIDGET_MILESTONES) {
                if (level >= milestone) continue;
                int cost = 0;
                for (int n = level + 1; n <= milestone; n++) cost += 5 * n;
                boolean leadsRally = rallyLeads.containsValue(hero);
                widgetSteps.add(new Step("Widgets", hero.name(), hero.exclusiveGear(), "+" + level, "+" + milestone, cost,
                        false, "Unlocks the next troop bonus of " + hero.name() + "'s exclusive gear"
                        + (leadsRally ? " (leads rallies)." : " (leads the garrison).")));
                break;
            }
        }
        widgetSteps.sort(Comparator.comparingInt(Step::cost));
        steps.addAll(widgetSteps);
    }

    // ── Who gear matters on ─────────────────────────────────────────

    private static void heroNotes(SquadSnapshot snapshot, Map<String, HeroRecord> rallyLeads,
                                  Map<String, HeroRecord> garrisonLeads, List<String> notes) {
        List<String> team = new ArrayList<>();
        rallyLeads.forEach((troopClass, lead) -> team.add(lead.name() + " (" + troopClass + ", Lethality "
                + format(lead.expeditionStat("Lethality")) + ", Health " + format(lead.expeditionStat("Health")) + ")"));
        notes.add("Rally leads (strongest per troop type): " + String.join("; ", team)
                + ". Gear there counts in every rally they lead.");

        // The plan's leads come from stats; the squads saved in game may lead with others. The
        // stones follow the piece, not the hero, so it is the piece to move, not the advice.
        Set<String> savedLeads = new LinkedHashSet<>();
        for (SavedSquad squad : snapshot.savedSquads()) {
            if (squad.state() == SavedSquad.State.SAVED && squad.heroes() != null && !squad.heroes().isEmpty()
                    && squad.heroes().getFirst() != null) {
                savedLeads.add(squad.heroes().getFirst());
            }
        }
        List<String> others = savedLeads.stream()
                .filter(name -> rallyLeads.values().stream().noneMatch(h -> h.name().equals(name))).toList();
        if (!others.isEmpty()) {
            notes.add("Your saved squads also lead with " + String.join(", ", others) + ". If one of them leads your"
                    + " rallies instead, move the pieces to that hero if it is the same troop type; per the guides,"
                    + " enhancement and mastery go with the piece (not yet checked on this account).");
        }

        for (HeroRecord hero : snapshot.heroes()) {
            if (!hero.isKey() || rallyLeads.containsValue(hero) || !hasInvestedGear(hero)) continue;
            if (garrisonLeads.containsValue(hero)) {
                notes.add(hero.name() + " leads the garrison, so " + hero.name() + "'s gear counts when your city"
                        + " is attacked. " + hero.troopClass() + " gear on a non-rally hero is last in line for stones.");
            } else {
                notes.add(hero.name() + " leads no march, and a joiner adds only one skill, so gear on "
                        + hero.name() + " helps only solo marches. No stones there.");
            }
        }
        for (HeroRecord lead : rallyLeads.values()) {
            if (lead.gear() == null) continue;
            for (HeroGear gear : lead.gear()) {
                if (Objects.equals(gear.enhancement(), 100) && gear.mastery() != null && gear.mastery() < 10) {
                    int stones = 0;
                    for (int n = gear.mastery() + 1; n <= 10; n++) stones += 10 * n;
                    notes.add(lead.name() + "'s " + PIECES[gear.slot() - 1] + " is +100: Mastery 10 (" + stones
                            + " more stones) plus 2 mythic gear lets it ascend to red gear, which cannot be undone.");
                }
            }
        }
        notes.add("Sources: whiteoutsurvival.wiki, wos.h5joy-games.com, commonsensegamer.com, WOSBearHunt gear"
                + " guide. Reddit could not be read. Check costs in game before spending.");
    }

    /** Troop types by how many troops your saved squads send, most first; guide order breaks ties. */
    private static List<String> typeOrder(SquadSnapshot snapshot, List<String> notes) {
        long[] totals = new long[3];
        for (SavedSquad squad : snapshot.savedSquads()) {
            if (squad.state() != SavedSquad.State.SAVED || squad.troops() == null) continue;
            for (int i = 0; i < Math.min(3, squad.troops().size()); i++) {
                Long count = squad.troops().get(i);
                if (count != null) totals[i] += count;
            }
        }
        // Saved squad troops are infantry, lancer, marksman in that order.
        Map<String, Long> byType = Map.of("Infantry", totals[0], "Lancer", totals[1], "Marksman", totals[2]);
        long all = totals[0] + totals[1] + totals[2];
        if (all == 0) {
            notes.add("Priority: guide default (infantry, marksman, lancer); no saved squad troops were read.");
            return GUIDE_ORDER;
        }
        List<String> order = new ArrayList<>(GUIDE_ORDER);
        order.sort(Comparator.<String>comparingLong(byType::get).reversed());
        List<String> shares = new ArrayList<>();
        for (String type : order) shares.add(type + " " + Math.round(100.0 * byType.get(type) / all) + "%");
        notes.add("Priority follows your saved squads' troops (" + String.join(", ", shares) + "). The guides'"
                + " default is infantry, marksman, lancer.");
        return order;
    }

    private static Map<String, HeroRecord> inOrder(Map<String, HeroRecord> leads, List<String> order) {
        Map<String, HeroRecord> ordered = new LinkedHashMap<>();
        for (String type : order) if (leads.containsKey(type)) ordered.put(type, leads.get(type));
        return ordered;
    }

    private static Map<String, HeroRecord> leadsByClass(List<String> team, Map<String, String> classOf,
                                                        Map<String, HeroRecord> byName) {
        Map<String, HeroRecord> leads = new LinkedHashMap<>();
        for (String name : team) {
            String troopClass = classOf.get(name);
            HeroRecord hero = byName.get(name);
            if (troopClass != null && hero != null) leads.putIfAbsent(troopClass, hero);
        }
        return leads;
    }

    private static boolean hasInvestedGear(HeroRecord hero) {
        return hero.gear() != null && hero.gear().stream().anyMatch(g -> g.mastery() != null && g.mastery() > 0);
    }

    private static String format(Double value) {
        return value == null ? "?" : String.valueOf(Math.round(value));
    }
}
