package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.TroopSlotPolicy;

/**
 * The gathering skill stands down when another task is already owed the free slots.
 *
 * <p>It used to decide purely from the march screen: an idle slot meant a free slot. But Gather,
 * Intel, Beast hunting, Cryptid hosting and Polar Terror all publish demand to TroopSlotPolicy
 * before taking one, and Gather recalls marches to satisfy it. Reading only the screen let this
 * skill take the last slot a task had already claimed and was walking towards, which is the exact
 * collision the ledger exists to prevent.
 */
class PetGatheringSlotYieldTest {

    @Test
    void takesAFreeSlotNobodyIsOwed() {
        assertFalse(PetSkillsRoutine.shouldYieldSlot(2, 0));
        assertFalse(PetSkillsRoutine.shouldYieldSlot(1, 0));
    }

    @Test
    void standsDownWhenAClaimantIsStillShort() {
        // One idle slot, another task owed one more: taking it starves them.
        assertTrue(PetSkillsRoutine.shouldYieldSlot(1, 1));
        assertTrue(PetSkillsRoutine.shouldYieldSlot(3, 2));
    }

    @Test
    void standsDownWhenThereIsNothingToTake() {
        // Nobody owed anything, but no idle slot either. Reading that as free would deploy into a
        // slot that does not exist -- and a march-screen read that fails returns zero, so this is
        // also the safe answer when the screen could not be read at all.
        assertTrue(PetSkillsRoutine.shouldYieldSlot(0, 0));
    }

    @Test
    void aRealIntelClaimIsVisibleToThisDecision() {
        // Exercises the ledger itself rather than the arithmetic: a live claim by another task must
        // be what drives the answer. Without this the predicate could be correct while the routine
        // still never consults TroopSlotPolicy, which was the actual bug.
        // A bare descriptor with an id is enough: TroopSlotPolicy is an in-memory map keyed by
        // profile id. Going through ProfileService would drag the real datastore into a test about
        // arithmetic over a map, which is how the neighbouring intel scheduling test became flaky.
        AccountDescriptor profile = new AccountDescriptor(9_900_001L);

        assertTrue(TroopSlotPolicy.activeClaims(profile).isEmpty());
        assertEquals(0, TroopSlotPolicy.slotsToRecallForGather(profile, 1));
        assertFalse(PetSkillsRoutine.shouldYieldSlot(1,
                TroopSlotPolicy.slotsToRecallForGather(profile, 1)));

        // Intel now needs two slots and only one is idle.
        TroopSlotPolicy.claim(profile, TpDailyTaskEnum.INTEL, 2, LocalDateTime.now().plusMinutes(10));
        int owed = TroopSlotPolicy.slotsToRecallForGather(profile, 1);
        assertEquals(1, owed, "one idle against a claim for two leaves one owed");
        assertTrue(PetSkillsRoutine.shouldYieldSlot(1, owed));

        TroopSlotPolicy.release(profile, TpDailyTaskEnum.INTEL);
        assertFalse(PetSkillsRoutine.shouldYieldSlot(1,
                TroopSlotPolicy.slotsToRecallForGather(profile, 1)),
                "the slot is takeable again once the claim is released");
    }
}
