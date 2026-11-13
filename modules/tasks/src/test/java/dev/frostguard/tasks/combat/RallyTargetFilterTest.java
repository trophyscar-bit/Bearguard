package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.tasks.combat.RallyTargetFilter.Facility;
import dev.frostguard.tasks.combat.RallyTargetFilter.Kind;
import dev.frostguard.vision.ocr.TextLine;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The whitelist is the safety rule. The signed-up list is the one General read from Alliance >
 * Battle > Fortress > Signed up on Nov 12: Stronghold 2, Fortress 2, Fortress 9 and Fortress 12. The
 * rally texts are stand-ins until a live War-list frame is measured; the rules they check are what matter.
 */
class RallyTargetFilterTest {

    private static final Set<Facility> SIGNED_UP = RallyTargetFilter.parseSignedUp(
            "Stronghold No. 2, Fortress No. 2, Fortress No. 9, Fortress No. 12");

    private static List<TextLine> lines(String... texts) {
        return java.util.Arrays.stream(texts).map(t -> new TextLine(t, 100, 300, 300, 24, 90f)).toList();
    }

    @Test
    void theSignedUpListIsReadFromTheBattleScreensNames() {
        assertEquals(Set.of(new Facility(Kind.STRONGHOLD, 2), new Facility(Kind.FORTRESS, 2),
                new Facility(Kind.FORTRESS, 9), new Facility(Kind.FORTRESS, 12)), SIGNED_UP);
        assertEquals(Set.of(), RallyTargetFilter.parseSignedUp(""));
        assertEquals(Set.of(), RallyTargetFilter.parseSignedUp(null));
        assertEquals(Set.of(new Facility(Kind.FORTRESS, 9)), RallyTargetFilter.parseSignedUp("Fortress 9; junk"));
    }

    @Test
    void aSignedUpFacilityIsJoinedInEitherSpelling() {
        assertTrue(RallyTargetFilter.isSignedUp(lines("Fortress No. 12"), SIGNED_UP));
        assertTrue(RallyTargetFilter.isSignedUp(lines("Stronghold 2"), SIGNED_UP));
        assertTrue(RallyTargetFilter.isSignedUp(lines("Rally: Fortress #9"), SIGNED_UP));
    }

    @Test
    void aFacilityNotSignedUpIsSkipped() {
        assertFalse(RallyTargetFilter.isSignedUp(lines("Fortress No. 10"), SIGNED_UP));
        assertFalse(RallyTargetFilter.isSignedUp(lines("Stronghold No. 3"), SIGNED_UP));
        // the number matters: Fortress 1 is not Fortress 12 or Fortress 2
        assertFalse(RallyTargetFilter.isSignedUp(lines("Fortress No. 1"), SIGNED_UP));
        assertEquals(new Facility(Kind.FORTRESS, 12), RallyTargetFilter.facilityOf(lines("Fortress No. 12")));
    }

    @Test
    void aSlightMisreadStillCounts() {
        assertEquals(new Facility(Kind.FORTRESS, 9), RallyTargetFilter.facilityOf(lines("FORTRES No. 9")));
        assertEquals(new Facility(Kind.STRONGHOLD, 2), RallyTargetFilter.facilityOf(lines("Strong hold No. 2")));
        assertEquals(new Facility(Kind.STRONGHOLD, 2), RallyTargetFilter.facilityOf(lines("Stronghol 2")));
    }

    @Test
    void anythingElseIsSkipped() {
        assertNull(RallyTargetFilter.facilityOf(lines("Raging Bear")));
        assertNull(RallyTargetFilter.facilityOf(lines("Berserk Cryptid")));
        assertNull(RallyTargetFilter.facilityOf(lines("Polar Terror Lv. 5")));
        assertNull(RallyTargetFilter.facilityOf(lines("")));
    }

    @Test
    void aPlayerTargetIsNeverJoinedEvenIfALineMentionsAFacility() {
        assertFalse(RallyTargetFilter.isSignedUp(lines("Attacking player", "Fortress No. 12"), SIGNED_UP));
        assertNull(RallyTargetFilter.facilityOf(lines("Furnace Lv. 30")));
        assertNull(RallyTargetFilter.facilityOf(lines("City of Fortress 12")));
    }

    @Test
    void anUnreadOrAmbiguousTargetIsASkipNotAJoin() {
        assertNull(RallyTargetFilter.facilityOf(null));
        assertNull(RallyTargetFilter.facilityOf(List.of()));
        assertNull(RallyTargetFilter.facilityOf(lines("Fortress")), "a facility without its number is not enough");
        assertNull(RallyTargetFilter.facilityOf(lines("Fortress No. 12", "Stronghold No. 2")), "two facilities is not a clean read");
        assertFalse(RallyTargetFilter.isSignedUp(lines("Fortress No. 12"), Set.of()), "an empty list joins nothing");
    }

    @Test
    void anyFortressOrStrongholdRallyIsJoinedWhateverItsNumber() {
        assertEquals(true, RallyTargetFilter.isFacilityRally(lines("Fortress No. 12")));
        assertEquals(true, RallyTargetFilter.isFacilityRally(lines("Stronghold 2")));
        assertEquals(true, RallyTargetFilter.isFacilityRally(lines("Fort ress")));
    }

    @Test
    void anUnreadablePlayerOrUnrelatedRallyIsNeverJoined() {
        assertEquals(false, RallyTargetFilter.isFacilityRally(lines("")));
        assertEquals(false, RallyTargetFilter.isFacilityRally(null));
        assertEquals(false, RallyTargetFilter.isFacilityRally(lines("Bear Trap")));
        assertEquals(false, RallyTargetFilter.isFacilityRally(lines("Attacking player Fortress City")));
    }
}
