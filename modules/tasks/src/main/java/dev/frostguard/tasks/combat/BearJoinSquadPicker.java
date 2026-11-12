package dev.frostguard.tasks.combat;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/**
 * Picks the hero squad for a Bear Trap rally join. Squads are tried in their configured order on
 * every join, so a squad that has come home always goes back out before any troops-only march.
 */
final class BearJoinSquadPicker {

    private BearJoinSquadPicker() {
    }

    /**
     * @param squads     saved squad slots, highest priority first
     * @param select     taps a squad; false when the slot cannot be selected
     * @param heroLoaded whether the tapped squad actually put a hero on the march
     * @param heroAway   told about each tapped squad whose hero did not load
     * @return the first squad that loaded a hero, or empty when every hero squad is unavailable
     */
    static Optional<Integer> firstLoaded(List<Integer> squads, IntPredicate select, BooleanSupplier heroLoaded,
            IntConsumer heroAway) {
        for (int squad : squads) {
            if (!select.test(squad)) {
                continue;
            }
            if (heroLoaded.getAsBoolean()) {
                return Optional.of(squad);
            }
            heroAway.accept(squad);
        }
        return Optional.empty();
    }
}
