package dev.frostguard.engine.squads;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ranks the next Mastery Forging upgrades across the instructors' gear.
 *
 * <p>Score is stat gain x hero weight x hole factor / stones. Hero weight says how much that hero
 * fights; the hole factor lifts weak pieces. Only pieces on the wearer's role are ranked: Health for
 * Infantry, Lethality for Lancer and Marksman. The costs reproduce all 20 forge costs read off the
 * game, but each one is still re-read on screen before it is paid.
 *
 * <p>Ranking stops at Mastery 10, because the levels past it are paid in Mythic gear.
 */
public final class GearRanker {

    private static final double BEST_ON_ROLE_BONUS = 80.0;
    private static final int MASTERY_CEILING = 100;
    private static final int FIRST_PIP_LEVEL = 4;
    private static final double OFF_ROLE_TARGET = BEST_ON_ROLE_BONUS / 2;
    // A hero ticked on but in no saved squad still needs some weight, or ticking it would do nothing.
    private static final double INCLUDED_FLOOR = 0.1;
    private static final Set<String> MARCH_LEADS = Set.of("Mia", "Flint", "Bahiti");

    public static final String FOCUS_RALLY = "Rally squads";
    public static final String FOCUS_BALANCED = "Balanced";

    /** {@code masteryPercent} includes partial pips, so 42 is Lv 4 with one pip filled. Either figure is null when unread. */
    public record GearPiece(String hero, String slot, Integer enhancement, Integer masteryPercent, boolean onRole) {

        /** False when either figure is unread; such a piece can't be scored or planned, and is never taken as 0. */
        public boolean known() {
            return enhancement != null && masteryPercent != null;
        }

        double base() {
            return 3.33 + 0.4667 * enhancement;
        }

        public double bonus() {
            return base() * (1 + masteryPercent / 100.0);
        }
    }

    /** {@code scannedAt} is when a full scan last read every piece; null when none has. */
    public record GearState(List<GearPiece> pieces, Map<String, Double> heroWeights, String scannedAt) {

        public GearState(List<GearPiece> pieces, Map<String, Double> heroWeights) {
            this(pieces, heroWeights, null);
        }

        public GearState withMastery(String hero, String slot, int mastery) {
            List<GearPiece> updated = pieces.stream()
                    .map(p -> p.hero().equals(hero) && p.slot().equals(slot)
                            ? new GearPiece(hero, slot, p.enhancement(), mastery, p.onRole()) : p)
                    .toList();
            return new GearState(updated, heroWeights, scannedAt);
        }

        public GearState withScannedAt(String when) {
            return new GearState(pieces, heroWeights, when);
        }

        public GearState withEnhancement(String hero, String slot, int enhancement) {
            List<GearPiece> updated = pieces.stream()
                    .map(p -> p.hero().equals(hero) && p.slot().equals(slot)
                            ? new GearPiece(hero, slot, enhancement, p.masteryPercent(), p.onRole()) : p)
                    .toList();
            return new GearState(updated, heroWeights, scannedAt);
        }
    }

    public record Upgrade(String hero, String slot, int fromMastery, int toMastery, int stones,
                          double bonusBefore, double bonusAfter) {
    }

    private GearRanker() {
    }

    /** Levels 0-3 forge whole at 10 stones per target level; from Lv 4 each 2% pip costs 2 x target level. */
    static int forgeCost(int masteryPercent) {
        int target = masteryPercent / 10 + 1;
        return target <= FIRST_PIP_LEVEL ? 10 * target : 2 * target;
    }

    static int forgeGain(int masteryPercent) {
        return masteryPercent / 10 < FIRST_PIP_LEVEL ? 10 : 2;
    }

    /** A to D against the best on-role piece, or against half of it for an off-role piece (the 2:1 rule). */
    public static String grade(GearPiece piece) {
        if (!piece.known()) return "?";
        double ratio = piece.bonus() / (piece.onRole() ? BEST_ON_ROLE_BONUS : OFF_ROLE_TARGET);
        if (ratio >= 0.90) return "A";
        if (ratio >= 0.75) return "B";
        return ratio >= 0.60 ? "C" : "D";
    }

    /** The weights to rank with: only {@code included} heroes count, and {@code focus} picks the scale. */
    public static Map<String, Double> weightsFor(GearState state, String focus, Set<String> included) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String hero : included) {
            double weight = FOCUS_BALANCED.equals(focus)
                    ? (MARCH_LEADS.contains(hero) ? 1.0 : 0.5)
                    : Math.max(state.heroWeights().getOrDefault(hero, 0.0), INCLUDED_FLOOR);
            weights.put(hero, weight);
        }
        return weights;
    }

    /** The best {@code count} upgrades in buying order, each one assuming those before it were bought. */
    public static List<Upgrade> rolling(GearState state, int count) {
        Map<String, GearPiece> pieces = new LinkedHashMap<>();
        for (GearPiece piece : state.pieces()) {
            if (piece.known() && piece.onRole() && state.heroWeights().getOrDefault(piece.hero(), 0.0) > 0) {
                pieces.put(piece.hero() + "/" + piece.slot(), piece);
            }
        }
        List<Upgrade> upgrades = new ArrayList<>();
        while (upgrades.size() < count) {
            GearPiece best = best(pieces.values(), state.heroWeights());
            if (best == null) {
                break;
            }
            GearPiece after = new GearPiece(best.hero(), best.slot(), best.enhancement(),
                    best.masteryPercent() + forgeGain(best.masteryPercent()), true);
            upgrades.add(new Upgrade(best.hero(), best.slot(), best.masteryPercent(), after.masteryPercent(),
                    forgeCost(best.masteryPercent()), best.bonus(), after.bonus()));
            pieces.put(best.hero() + "/" + best.slot(), after);
        }
        return List.copyOf(upgrades);
    }

    /**
     * The next {@code count} forges, weakest first: the ticked hero's piece with the lowest mastery goes first, and a
     * tie goes to the lower gear level, so every hero is brought up evenly. With {@code leadsFirst} the rally leaders
     * (heroes with real weight in a saved lead squad) all come before everyone else, weakest first inside each group.
     * Only heroes with a weight above zero take part, so a hero left unticked never gets a forge.
     */
    public static List<Upgrade> rollingWeakest(GearState state, int count, boolean leadsFirst) {
        Map<String, GearPiece> pieces = new LinkedHashMap<>();
        for (GearPiece piece : state.pieces()) {
            if (piece.known() && piece.onRole() && state.heroWeights().getOrDefault(piece.hero(), 0.0) > 0) {
                pieces.put(piece.hero() + "/" + piece.slot(), piece);
            }
        }
        List<Upgrade> upgrades = new ArrayList<>();
        while (upgrades.size() < count) {
            GearPiece weakest = null;
            for (GearPiece piece : pieces.values()) {
                if (piece.masteryPercent() >= MASTERY_CEILING) continue;
                if (weakest == null || weaker(piece, weakest, state.heroWeights(), leadsFirst)) weakest = piece;
            }
            if (weakest == null) break;
            GearPiece after = new GearPiece(weakest.hero(), weakest.slot(), weakest.enhancement(),
                    weakest.masteryPercent() + forgeGain(weakest.masteryPercent()), true);
            upgrades.add(new Upgrade(weakest.hero(), weakest.slot(), weakest.masteryPercent(), after.masteryPercent(),
                    forgeCost(weakest.masteryPercent()), weakest.bonus(), after.bonus()));
            pieces.put(weakest.hero() + "/" + weakest.slot(), after);
        }
        return List.copyOf(upgrades);
    }

    /** Heroes weighted at least this much in a saved lead squad count as rally leaders. */
    static final double RALLY_LEAD_WEIGHT = 0.25;

    private static boolean weaker(GearPiece a, GearPiece b, Map<String, Double> weights, boolean leadsFirst) {
        if (leadsFirst) {
            boolean aLeads = weights.getOrDefault(a.hero(), 0.0) >= RALLY_LEAD_WEIGHT;
            boolean bLeads = weights.getOrDefault(b.hero(), 0.0) >= RALLY_LEAD_WEIGHT;
            if (aLeads != bLeads) return aLeads;
        }
        if (a.masteryPercent() != b.masteryPercent()) return a.masteryPercent() < b.masteryPercent();
        if (!a.enhancement().equals(b.enhancement())) return a.enhancement() < b.enhancement();
        int byHero = a.hero().compareTo(b.hero());
        return byHero != 0 ? byHero < 0 : a.slot().compareTo(b.slot()) < 0;
    }

    private static GearPiece best(Iterable<GearPiece> pieces, Map<String, Double> weights) {
        GearPiece best = null;
        double bestScore = 0;
        for (GearPiece piece : pieces) {
            if (piece.masteryPercent() < MASTERY_CEILING && score(piece, weights) > bestScore) {
                best = piece;
                bestScore = score(piece, weights);
            }
        }
        return best;
    }

    private static double score(GearPiece piece, Map<String, Double> weights) {
        double gain = piece.base() * forgeGain(piece.masteryPercent()) / 100.0;
        double holeFactor = BEST_ON_ROLE_BONUS / piece.bonus();
        return gain * weights.get(piece.hero()) * holeFactor / forgeCost(piece.masteryPercent());
    }
}
