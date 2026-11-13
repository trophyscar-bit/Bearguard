package dev.frostguard.engine.squads;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.frostguard.engine.squads.GearRanker.GearPiece;

/**
 * Plans gear enhancement on role only: Lancer and Marksman heroes get Goggles and Boots, Infantry heroes
 * Gauntlets and Belt, so an off-role piece is never raised and the on-role pieces stay at about twice the
 * off-role level. The hero who fights most goes first: pieces are lifted to their next step of {@value #STEP}
 * in order of the hero's weight (squad use), a hero at weight 0 is skipped, and among heroes of the same
 * weight the lowest piece goes first, ties to the lowest bonus, so they rise together.
 */
public final class GearEnhancePlanner {

    public static final int STEP = 5;
    private static final int MAX_ENHANCEMENT = 100;

    public record Raise(String hero, String slot, int from, int to) {
        public int levels() {
            return to - from;
        }
    }

    private GearEnhancePlanner() {
    }

    /** Heroes the weights say nothing about count as one of equal standing. */
    private static final double UNWEIGHTED = 0.5;

    /** As {@link #plan(List, Set, int, Map)} with every hero weighted alike, so the lowest piece goes first. */
    public static List<Raise> plan(List<GearPiece> gear, Set<String> included, int maxLevels) {
        return plan(gear, included, maxLevels, Map.of());
    }

    /** Raises in order adding up to at most {@code maxLevels} levels, each assuming those before it were done. */
    public static List<Raise> plan(List<GearPiece> gear, Set<String> included, int maxLevels, Map<String, Double> heroWeights) {
        Map<String, GearPiece> pieces = new LinkedHashMap<>();
        for (GearPiece piece : gear) {
            if (piece.known() && piece.onRole() && included.contains(piece.hero()) && weight(heroWeights, piece.hero()) > 0) {
                pieces.put(piece.hero() + "/" + piece.slot(), piece);
            }
        }
        List<Raise> raises = new ArrayList<>();
        int left = maxLevels;
        while (left > 0) {
            GearPiece weakest = weakest(pieces.values(), heroWeights);
            if (weakest == null) break;
            int nextStep = Math.min(MAX_ENHANCEMENT, (weakest.enhancement() / STEP + 1) * STEP);
            int target = Math.min(nextStep, weakest.enhancement() + left);
            raises.add(new Raise(weakest.hero(), weakest.slot(), weakest.enhancement(), target));
            left -= target - weakest.enhancement();
            pieces.put(weakest.hero() + "/" + weakest.slot(), new GearPiece(weakest.hero(), weakest.slot(), target,
                    weakest.masteryPercent(), true));
        }
        return List.copyOf(raises);
    }

    private static double weight(Map<String, Double> weights, String hero) {
        Double weight = weights == null ? null : weights.get(hero);
        return weight == null ? UNWEIGHTED : weight;
    }

    private static GearPiece weakest(Iterable<GearPiece> pieces, Map<String, Double> weights) {
        GearPiece weakest = null;
        for (GearPiece piece : pieces) {
            if (piece.enhancement() < MAX_ENHANCEMENT && (weakest == null || before(piece, weakest, weights))) weakest = piece;
        }
        return weakest;
    }

    private static boolean before(GearPiece piece, GearPiece other, Map<String, Double> weights) {
        int byWeight = Double.compare(weight(weights, other.hero()), weight(weights, piece.hero()));
        if (byWeight != 0) return byWeight < 0;
        int byLevel = Integer.compare(piece.enhancement(), other.enhancement());
        return byLevel != 0 ? byLevel < 0 : piece.bonus() < other.bonus();
    }
}
