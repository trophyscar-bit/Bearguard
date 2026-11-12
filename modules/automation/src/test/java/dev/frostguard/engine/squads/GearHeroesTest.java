package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class GearHeroesTest {

    @Test
    void everyScannedHeroIsIncludedWhenNoneAreLeftOut() {
        assertEquals(List.of("Gwen", "Jeronimo", "Natalia"),
                List.copyOf(GearHeroes.included(List.of("Gwen", "Jeronimo", "Natalia"), Set.of())));
    }

    @Test
    void aLeftOutHeroIsDroppedAndTheScanOrderKept() {
        assertEquals(List.of("Mia", "Molly", "Sergey"),
                List.copyOf(GearHeroes.included(List.of("Mia", "Flint", "Molly", "Sergey"), Set.of("Flint"))));
    }

    @Test
    void aHeroWhoOvertakesAnotherJoinsWithoutAnyoneTickingThem() {
        Set<String> skipped = GearHeroes.skipped("Flint");

        assertEquals(List.of("Mia", "Geronimo"), List.copyOf(GearHeroes.included(List.of("Mia", "Flint", "Geronimo"), skipped)));
    }

    @Test
    void theSavedListToleratesBlanksSpacesAndNull() {
        assertEquals(List.of("Mia", "Flint"), List.copyOf(GearHeroes.skipped(" Mia, ,Flint,")));
        assertEquals(List.of(), List.copyOf(GearHeroes.skipped(null)));
        assertEquals(List.of(), List.copyOf(GearHeroes.skipped("")));
        assertEquals("Mia,Flint", GearHeroes.join(List.of("Mia", "Flint", "Mia")));
    }
}
