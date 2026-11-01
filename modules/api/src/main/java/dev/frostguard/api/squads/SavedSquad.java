package dev.frostguard.api.squads;

import java.util.List;

/**
 * One of the twelve in-game saved squads as it looked during the scan.
 *
 * @param heroes      hero names in slot order; null entries are cards the scan could not match
 * @param troops      infantry, lancer and marksman counts in that order; null entries were unreadable
 */
public record SavedSquad(int slot, State state, List<String> heroes, List<Integer> heroLevels,
                         List<Long> troops, String capacity, Long power) {

    public enum State {
        SAVED,
        EMPTY_OR_LOCKED,
        // Read identical to the slot before: an empty slot showing the previous squad through,
        // or the same squad saved twice. Nothing on screen tells them apart.
        SAME_AS_PREVIOUS,
        UNREADABLE
    }
}
