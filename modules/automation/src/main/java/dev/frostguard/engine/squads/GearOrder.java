package dev.frostguard.engine.squads;

import java.util.List;

import dev.frostguard.engine.squads.GearRanker.GearState;
import dev.frostguard.engine.squads.GearRanker.Upgrade;

/** Which gear the forge works on first. Saved as plain words; two older words still load. */
public final class GearOrder {

    /**
     * The default: on-role pieces only, each scored by the stat it adds per stone and weighted by how much
     * the hero fights (Bahiti 1.14, Mia and Flint 0.30, Sergey 0.105, Molly 0), not by who is lowest.
     */
    public static final String BEST_RETURN = "Best return per stone";
    public static final String WEAKEST = "Weakest first";
    public static final String LEADS_THEN_WEAKEST = "Rally leaders first, then weakest";
    /** The choices, in alphabetical order. */
    public static final List<String> CHOICES = List.of(BEST_RETURN, LEADS_THEN_WEAKEST, WEAKEST);

    private GearOrder() {
    }

    /**
     * The saved order as one of {@link #CHOICES}. "Rally squads" (the old default, which ranked by squad weight and
     * return per stone), "Balanced" and anything unreadable become best return per stone.
     */
    public static String normalize(String saved) {
        if (LEADS_THEN_WEAKEST.equals(saved)) return LEADS_THEN_WEAKEST;
        if (WEAKEST.equals(saved)) return WEAKEST;
        return BEST_RETURN;
    }

    /** The next {@code count} forges in the chosen order, for the heroes with a weight above zero. */
    public static List<Upgrade> plan(GearState state, String saved, int count) {
        String order = normalize(saved);
        if (BEST_RETURN.equals(order)) return GearRanker.rolling(state, count);
        return GearRanker.rollingWeakest(state, count, LEADS_THEN_WEAKEST.equals(order));
    }
}
