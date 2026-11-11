package dev.frostguard.engine.squads;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.frostguard.engine.squads.GearRanker.GearPiece;

/**
 * Plans gear enhancement by always lifting the lowest on-role piece to its next step, ties going to
 * the lowest bonus, so the included heroes rise together instead of one piece running ahead.
 */
public final class GearEnhancePlanner {

    private static final int MAX_ENHANCEMENT = 100;

    public record Raise(String hero, String slot, int from, int to) {
    }

    private GearEnhancePlanner() {
    }

    /** The next {@code count} raises in order, each assuming those before it were done. */
    public static List<Raise> plan(List<GearPiece> gear, Set<String> included, int step, int count) {
        if (step < 1) throw new IllegalArgumentException("step must be at least 1, was " + step);
        Map<String, GearPiece> pieces = new LinkedHashMap<>();
        for (GearPiece piece : gear) {
            if (piece.known() && piece.onRole() && included.contains(piece.hero())) {
                pieces.put(piece.hero() + "/" + piece.slot(), piece);
            }
        }
        List<Raise> raises = new ArrayList<>();
        while (raises.size() < count) {
            GearPiece weakest = weakest(pieces.values());
            if (weakest == null) break;
            int target = Math.min(MAX_ENHANCEMENT, (weakest.enhancement() / step + 1) * step);
            raises.add(new Raise(weakest.hero(), weakest.slot(), weakest.enhancement(), target));
            pieces.put(weakest.hero() + "/" + weakest.slot(), new GearPiece(weakest.hero(), weakest.slot(), target,
                    weakest.masteryPercent(), true));
        }
        return List.copyOf(raises);
    }

    private static GearPiece weakest(Iterable<GearPiece> pieces) {
        GearPiece weakest = null;
        for (GearPiece piece : pieces) {
            if (piece.enhancement() < MAX_ENHANCEMENT && (weakest == null || lower(piece, weakest))) weakest = piece;
        }
        return weakest;
    }

    private static boolean lower(GearPiece piece, GearPiece other) {
        int byLevel = Integer.compare(piece.enhancement(), other.enhancement());
        return byLevel != 0 ? byLevel < 0 : piece.bonus() < other.bonus();
    }
}
