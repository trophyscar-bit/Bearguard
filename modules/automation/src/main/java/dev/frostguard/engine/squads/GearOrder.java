package dev.frostguard.engine.squads;

import java.util.List;

import dev.frostguard.engine.squads.GearRanker.GearState;
import dev.frostguard.engine.squads.GearRanker.Upgrade;

/** Which gear the forge works on first. Saved as plain words; two older words still load. */
public final class GearOrder {

    public static final String WEAKEST = "Weakest first";
    public static final String LEADS_THEN_WEAKEST = "Rally leaders first, then weakest";
    /** The choices, in alphabetical order. */
    public static final List<String> CHOICES = List.of(LEADS_THEN_WEAKEST, WEAKEST);

    private GearOrder() {
    }

    /**
     * The saved order as one of {@link #CHOICES}. "Rally squads" (the old default) becomes rally leaders first, and
     * "Balanced" or anything unreadable becomes weakest first.
     */
    public static String normalize(String saved) {
        if (LEADS_THEN_WEAKEST.equals(saved) || GearRanker.FOCUS_RALLY.equals(saved)) return LEADS_THEN_WEAKEST;
        return WEAKEST;
    }

    /** The next {@code count} forges in the chosen order, for the heroes with a weight above zero. */
    public static List<Upgrade> plan(GearState state, String saved, int count) {
        return GearRanker.rollingWeakest(state, count, LEADS_THEN_WEAKEST.equals(normalize(saved)));
    }
}
