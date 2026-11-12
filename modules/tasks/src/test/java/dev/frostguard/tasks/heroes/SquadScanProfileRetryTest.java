package dev.frostguard.tasks.heroes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.domain.RawImageData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One missed Chief Profile read (Nov 12, 02:27) cost the whole night's troops and squads. */
class SquadScanProfileRetryTest {

    /** Opens the profile on the given 1-based look (counted across the whole run); never when 0. */
    private static final class Screen implements SquadScanRoutine.ProfileSteps {
        final int opensOnLook;
        int looks, taps, homes, kept;
        final List<String> events = new ArrayList<>();

        Screen(int opensOnLook) { this.opensOnLook = opensOnLook; }

        public void tapAvatar() { taps++; events.add("tap"); }
        public void pause(int ms) { }
        public RawImageData look() { looks++; return null; }
        public boolean isProfile(RawImageData frame) { return opensOnLook > 0 && looks >= opensOnLook; }
        public void dismissCard() { events.add("card"); }
        public void failed(RawImageData frame, int attempt) { kept++; events.add("kept" + attempt); }
        public void backHome() { homes++; events.add("home"); }
    }

    @Test
    void aProfileThatOpensStraightAwayIsOneTapAndNoRetry() {
        Screen s = new Screen(1);
        assertTrue(SquadScanRoutine.openChiefProfile(s));
        assertEquals(1, s.taps);
        assertEquals(0, s.kept);
    }

    @Test
    void aSlowProfileIsWaitedForBeforeAnyRetry() {
        // The old code looked twice, 2.5 s after the tap; here the fourth look still counts.
        Screen s = new Screen(4);
        assertTrue(SquadScanRoutine.openChiefProfile(s));
        assertEquals(1, s.taps);
        assertEquals(0, s.homes);
    }

    @Test
    void aMissedTapIsRetriedFromHomeAndTheFailedFrameIsKept() {
        // Attempt 1 uses 5 looks and fails; attempt 2 opens on its first look (the 6th overall).
        Screen s = new Screen(6);
        assertTrue(SquadScanRoutine.openChiefProfile(s));
        assertEquals(2, s.taps);
        assertEquals(1, s.homes);
        assertEquals(1, s.kept);
        assertEquals("kept1", s.events.stream().filter(e -> e.startsWith("kept")).findFirst().orElse(""));
    }

    @Test
    void itGivesUpAfterThreeTriesKeepingEachFrame() {
        Screen s = new Screen(0);
        assertFalse(SquadScanRoutine.openChiefProfile(s));
        assertEquals(3, s.taps);
        assertEquals(3, s.kept);
        assertEquals(2, s.homes); // no trip home after the last try; the scan does that itself
    }
}
