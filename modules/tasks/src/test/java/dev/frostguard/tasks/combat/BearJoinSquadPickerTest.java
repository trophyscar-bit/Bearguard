package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class BearJoinSquadPickerTest {

    private static final List<Integer> SQUADS = List.of(1, 2, 3);

    private final List<Integer> tapped = new ArrayList<>();
    private int lastTapped;

    private Optional<Integer> pick(Set<Integer> squadsAtHome) {
        return BearJoinSquadPicker.firstLoaded(SQUADS, squad -> {
            tapped.add(squad);
            lastTapped = squad;
            return true;
        }, () -> squadsAtHome.contains(lastTapped));
    }

    @Test
    void squadOneGoesFirstWhenEveryoneIsHome() {
        assertEquals(Optional.of(1), pick(Set.of(1, 2, 3)));
        assertEquals(List.of(1), tapped);
    }

    @Test
    void skipsSquadsWhoseHeroIsAlreadyOut() {
        assertEquals(Optional.of(3), pick(Set.of(3)));
        assertEquals(List.of(1, 2, 3), tapped);
    }

    @Test
    void returnedSquadIsPickedAgainInsteadOfRotatingPastIt() {
        assertEquals(Optional.of(1), pick(Set.of(1)));
    }

    @Test
    void emptyWhenAllHeroSquadsAreOutSoTheMarchGoesTroopsOnly() {
        assertEquals(Optional.empty(), pick(Set.of()));
    }

    @Test
    void skipsSquadThatCannotBeSelected() {
        Optional<Integer> picked = BearJoinSquadPicker.firstLoaded(
                SQUADS, squad -> squad == 2, () -> true);
        assertEquals(Optional.of(2), picked);
    }
}
