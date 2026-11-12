package dev.frostguard.engine.squads;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which of the scanned heroes the nightly Enhance and Forge work on: every hero the gear scan read,
 * which is the strongest five by power on whatever account it runs on, except those left out.
 */
public final class GearHeroes {

    private GearHeroes() {
    }

    /** The saved leave-out list, comma separated; blank entries are dropped. */
    public static Set<String> skipped(String saved) {
        Set<String> names = new LinkedHashSet<>();
        if (saved == null) return names;
        for (String name : saved.split(",")) {
            if (!name.isBlank()) names.add(name.trim());
        }
        return names;
    }

    public static String join(Collection<String> names) {
        return String.join(",", new LinkedHashSet<>(names));
    }

    /** The scanned heroes in scan order, less the ones left out. */
    public static Set<String> included(Collection<String> scanned, Set<String> skipped) {
        Set<String> names = new LinkedHashSet<>();
        for (String hero : scanned) {
            if (!skipped.contains(hero)) names.add(hero);
        }
        return names;
    }
}
