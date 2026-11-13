package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.tasks.events.RedDotSweepPolicy.LabelVerdict;
import dev.frostguard.tasks.events.RedDotSweepPolicy.Outcome;

class RedDotSweepPolicyTest {

    // 19:00 local is the 00:00 UTC reset in winter time (EST).
    private static LocalDateTime at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 11, day, hour, minute);
    }

    @Test
    void chiefGearIsRefusedByAnyNameForIt() {
        assertEquals(LabelVerdict.EXCLUDED, RedDotSweepPolicy.judgeLabel("Chief Gear Upgrade"));
        assertEquals(LabelVerdict.EXCLUDED, RedDotSweepPolicy.judgeLabel("  CHIEF  GEAR "));
        assertEquals(LabelVerdict.EXCLUDED, RedDotSweepPolicy.judgeLabel("Officer Project Gear Forge"));
    }

    @Test
    void aPageThatCannotBeNamedIsNeverTreatedAsSafe() {
        assertEquals(LabelVerdict.UNREADABLE, RedDotSweepPolicy.judgeLabel(null));
        assertEquals(LabelVerdict.UNREADABLE, RedDotSweepPolicy.judgeLabel(""));
        assertEquals(LabelVerdict.UNREADABLE, RedDotSweepPolicy.judgeLabel(" 1 : - "));
    }

    @Test
    void anOrdinaryEventBannerIsAllowed() {
        assertEquals(LabelVerdict.OK, RedDotSweepPolicy.judgeLabel("Officer Project Good tools are the key"));
    }

    @Test
    void spendingRerollingRecruitingAndRedeemingTextIsForbidden() {
        for (String text : new String[] {"Buy", "Shoot!", "Reset", "Recruit", "Redeem", "Mythic Shard", "$4.99",
                "Top Up Now", "500 Gems", "Refresh", "Purchase All"}) {
            assertTrue(RedDotSweepPolicy.isForbiddenText(text), text);
        }
        assertFalse(RedDotSweepPolicy.isForbiddenText("Claim"));
        assertFalse(RedDotSweepPolicy.isForbiddenText("Claim All"));
        assertFalse(RedDotSweepPolicy.isForbiddenText("Free"));
        assertFalse(RedDotSweepPolicy.isForbiddenText(null));
    }

    @Test
    void mailTheOnlineChestAndTheVipChestAreLeftToTheirOwnTasks() {
        assertEquals(List.of(TpDailyTaskEnum.MAIL_REWARDS, TpDailyTaskEnum.STOREHOUSE_CHEST,
                TpDailyTaskEnum.VIP_POINTS), RedDotSweepPolicy.DEDICATED_PANELS.stream()
                .map(RedDotSweepPolicy.Dedicated::task).toList());
    }

    @Test
    void theSweepIsOffByDefaultAndSitsInTheEventCategory() {
        assertFalse(Boolean.parseBoolean(TpDailyTaskEnum.RED_DOT_SWEEP.activationSwitch().getDefaultValue()));
        assertEquals(TpDailyTaskEnum.RoutineCategory.EVENT, TpDailyTaskEnum.RED_DOT_SWEEP.routineCategory());
    }

    @Test
    void afterTodaysSweepTheNextOneIsMiddayTomorrowWhenResetIsFarOff() {
        LocalDateTime next = RedDotSweepPolicy.nextRun(at(12, 22, 40), at(13, 19, 0), 3, Outcome.CLEAN, 0);
        assertEquals(at(13, 11, 15), next);
    }

    @Test
    void theMiddayRunIsFollowedByTheRunTenMinutesAfterReset() {
        LocalDateTime next = RedDotSweepPolicy.nextRun(at(13, 11, 17), at(13, 19, 0), 3, Outcome.CLEAN, 0);
        assertEquals(at(13, 19, 13), next);
    }

    @Test
    void justBeforeResetTheNextRunIsTenMinutesAfterIt() {
        assertEquals(at(12, 19, 10), RedDotSweepPolicy.nextRun(at(12, 18, 55), at(12, 19, 0), 0, Outcome.CLEAN, 0));
    }

    @Test
    void justAfterResetButBeforeTheTenMinutesTheRunStillComesThatDay() {
        // The bot's next reset is already tomorrow's; the slot of the reset that just passed is still ahead.
        assertEquals(at(12, 19, 10), RedDotSweepPolicy.nextRun(at(12, 19, 3), at(13, 19, 0), 0, Outcome.CLEAN, 0));
    }

    @Test
    void jitterIsCappedAtFiveMinutes() {
        assertEquals(at(12, 19, 15), RedDotSweepPolicy.nextRun(at(12, 18, 55), at(12, 19, 0), 99, Outcome.CLEAN, 0));
    }

    @Test
    void anUnknownVisitRetriesInThirtyMinutesButNotPastTheRegularSlot() {
        assertEquals(at(13, 0, 10), RedDotSweepPolicy.nextRun(at(12, 23, 40), at(13, 19, 0), 0, Outcome.UNKNOWN, 0));
        assertEquals(at(13, 11, 15), RedDotSweepPolicy.nextRun(at(13, 11, 0), at(13, 19, 0), 0, Outcome.UNKNOWN, 0));
    }

    @Test
    void anUnknownVisitDoesNotRetryForeverAndNeverSchedulesFromAFailure() {
        LocalDateTime now = at(12, 23, 40);
        LocalDateTime regular = RedDotSweepPolicy.regularSlot(now, at(13, 19, 0), 0);
        assertEquals(regular, RedDotSweepPolicy.nextRun(now, at(13, 19, 0), 0, Outcome.UNKNOWN,
                RedDotSweepPolicy.MAX_FAILED_ATTEMPTS));
        assertTrue(RedDotSweepPolicy.nextRun(now, at(13, 19, 0), 0, Outcome.UNKNOWN, 1).isBefore(regular));
    }
}
