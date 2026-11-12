package dev.frostguard.engine.squads;

import java.util.List;

/**
 * How much a night spends: everything it has, or one step. This replaces the old "most per night" counts, which
 * nobody could pick a sensible number for. The night's time cap still ends a long run.
 */
public enum GearAmount {

    ALL("All"),
    ONE("One per night");

    /** Enough that only the XP items, the stones or the time cap ever stop a night. */
    public static final int ALL_LEVELS = 100;
    public static final int ALL_FORGES = 50;
    /** The two choices, in alphabetical order. */
    public static final List<String> CHOICES = List.of(ALL.label, ONE.label);

    private final String label;

    GearAmount(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * The saved choice, or, when none is saved, what the old count meant: a cap of 1 was "one per night" and any other
     * cap is now "all". A choice already saved always wins, so nothing the player set is overridden.
     */
    public static GearAmount choose(String saved, Integer oldCap) {
        if (ONE.label.equals(saved)) return ONE;
        if (ALL.label.equals(saved)) return ALL;
        return oldCap != null && oldCap == 1 ? ONE : ALL;
    }

    /** Enhance levels this choice allows in one night. */
    public int levels() {
        return this == ONE ? 1 : ALL_LEVELS;
    }

    /** Forges this choice allows in one night. */
    public int forges() {
        return this == ONE ? 1 : ALL_FORGES;
    }
}
