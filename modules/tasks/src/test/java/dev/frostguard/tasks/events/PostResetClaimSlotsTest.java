package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class PostResetClaimSlotsTest {

    private static final LocalDateTime NEXT_RESET = LocalDateTime.of(2026, 11, 13, 19, 0);

    @Test
    void theRunAfterTodaysSlotIsTomorrowsSlot() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 12, 22, 40);
        assertEquals(LocalDateTime.of(2026, 11, 13, 19, 25),
                PostResetClaimSlots.next(now, NEXT_RESET, PostResetClaimSlots.HALL_OF_CHIEFS));
    }

    @Test
    void aRunJustBeforeTheSlotKeepsTodaysSlot() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 13, 19, 20);
        assertEquals(LocalDateTime.of(2026, 11, 13, 19, 25),
                PostResetClaimSlots.next(now, NEXT_RESET, PostResetClaimSlots.HALL_OF_CHIEFS));
    }

    @Test
    void aRunAfterTheResetButPastItsSlotWaitsForTheNextReset() {
        // The bot already counts tomorrow's reset, so the slot of the reset that just passed is behind it.
        LocalDateTime now = LocalDateTime.of(2026, 11, 12, 19, 30);
        assertEquals(LocalDateTime.of(2026, 11, 13, 19, 25),
                PostResetClaimSlots.next(now, LocalDateTime.of(2026, 11, 13, 19, 0), PostResetClaimSlots.HALL_OF_CHIEFS));
    }

    @Test
    void everyEventClaimPassHasItsOwnSlotAtLeastFiveMinutesFromTheOthers() {
        List<Integer> slots = List.of(PostResetClaimSlots.HALL_OF_CHIEFS, PostResetClaimSlots.DEFEAT_NEARBY_BEASTS,
                PostResetClaimSlots.HERO_RALLY, PostResetClaimSlots.LUCKY_CHIP,
                PostResetClaimSlots.BROTHERS_IN_ARMS);
        for (int i = 1; i < slots.size(); i++) {
            assertTrue(slots.get(i) - slots.get(i - 1) >= 5, "slot " + i);
        }
        assertTrue(Duration.ofMinutes(60 - slots.get(slots.size() - 1)).toMinutes() >= 5,
                "the last claim ends before Dead Shot and Endless Trial at an hour after reset");
    }

    @Test
    void theEventKindsCarryTheirSlots() {
        assertEquals(PostResetClaimSlots.HALL_OF_CHIEFS, EventKind.HALL_OF_CHIEFS.getMinutesAfterReset());
        assertEquals(PostResetClaimSlots.DEFEAT_NEARBY_BEASTS, EventKind.DEFEAT_NEARBY_BEASTS.getMinutesAfterReset());
        assertEquals(PostResetClaimSlots.BROTHERS_IN_ARMS, EventKind.BROTHERS_IN_ARMS.getMinutesAfterReset());
    }
}
