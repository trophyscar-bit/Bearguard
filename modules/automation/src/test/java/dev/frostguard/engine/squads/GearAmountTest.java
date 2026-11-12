package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class GearAmountTest {

    @Test
    void aSavedChoiceAlwaysWins() {
        assertEquals(GearAmount.ONE, GearAmount.choose("One per night", 25));
        assertEquals(GearAmount.ALL, GearAmount.choose("All", 1));
    }

    @Test
    void withNoChoiceSavedAnOldCountOfOneMeansOnePerNightAndAnythingElseMeansAll() {
        assertEquals(GearAmount.ONE, GearAmount.choose(null, 1));
        assertEquals(GearAmount.ONE, GearAmount.choose("", 1));
        assertEquals(GearAmount.ALL, GearAmount.choose("", 5));
        assertEquals(GearAmount.ALL, GearAmount.choose("", 10));
        assertEquals(GearAmount.ALL, GearAmount.choose(null, null));
        assertEquals(GearAmount.ALL, GearAmount.choose("nonsense", null));
    }

    @Test
    void allHasNoRealCapOfItsOwnAndOnePerNightIsOne() {
        assertEquals(1, GearAmount.ONE.levels());
        assertEquals(1, GearAmount.ONE.forges());
        assertEquals(GearAmount.ALL_LEVELS, GearAmount.ALL.levels());
        assertEquals(GearAmount.ALL_FORGES, GearAmount.ALL.forges());
    }

    @Test
    void theChoicesAreInAlphabeticalOrder() {
        assertEquals(List.of("All", "One per night"), GearAmount.CHOICES);
    }
}
