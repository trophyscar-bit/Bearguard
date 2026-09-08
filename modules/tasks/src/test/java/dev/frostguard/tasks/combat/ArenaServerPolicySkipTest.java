package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The "never attack my own server" filter must not block a list that has no server column.
 *
 * <p>Arena resets its season roughly weekly, and the reset list renders in the compact layout,
 * which shows no server for anyone. The filter treated that identically to a failed read, so on
 * 2026-09-07 all five opponents were skipped as "server not visible/readable", the run made zero
 * attacks and spent seven gem refreshes re-rolling a list it would always reject. The same reset
 * list on 2026-08-31 was attacked normally, before the filter was switched on -- the layout did not
 * change, the policy did.
 */
class ArenaServerPolicySkipTest {

    @Test
    void aLayoutWithNoServerColumnDoesNotBlockTheAttack() {
        // Exactly the 2026-09-07 reset case: compact layout, no server shown anywhere.
        assertNull(ArenaRoutine.serverPolicySkipReason(
                ArenaRoutine.ServerStatus.NOT_SHOWN, "4527", null),
                "a field the game never displays cannot be filtered on");
    }

    @Test
    void aFailedReadStillBlocks() {
        // The column was on screen and OCR came back empty. That is a real read failure and the
        // filter should keep refusing to guess -- this is the case the fix must NOT loosen.
        assertEquals("server unreadable", ArenaRoutine.serverPolicySkipReason(
                ArenaRoutine.ServerStatus.UNREADABLE, "4527", null));
    }

    @Test
    void ownServerIsStillSkipped() {
        assertEquals("profile server", ArenaRoutine.serverPolicySkipReason(
                ArenaRoutine.ServerStatus.READ, "4527", "4527"));
    }

    @Test
    void anotherServerIsAllowed() {
        assertNull(ArenaRoutine.serverPolicySkipReason(
                ArenaRoutine.ServerStatus.READ, "4527", "1188"));
    }

    @Test
    void anUnconfirmedLayoutIsTreatedAsAbsentNotUnreadable() {
        // A failed layout probe returns UNKNOWN, which assumes the server layout rather than
        // establishing it. If a blank read there were called UNREADABLE, a mis-probed reset list
        // would drop straight back into the zero-attack loop. NOT_SHOWN keeps it attacking.
        assertNull(ArenaRoutine.serverPolicySkipReason(
                ArenaRoutine.ServerStatus.NOT_SHOWN, "4527", null));
    }

    @Test
    void anUnsetProfileServerNeverBlocks() {
        assertNull(ArenaRoutine.serverPolicySkipReason(
                ArenaRoutine.ServerStatus.READ, null, "4527"));
    }
}
