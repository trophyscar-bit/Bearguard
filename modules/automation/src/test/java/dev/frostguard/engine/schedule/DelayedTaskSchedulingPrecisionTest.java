package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;

class DelayedTaskSchedulingPrecisionTest {

    @Test
    void positiveSubUnitDelayRoundsUpWithoutChangingZeroOrNegativeDelays() {
        assertEquals(1, DelayedTask.delayInUnits(Duration.ofNanos(1), TimeUnit.MILLISECONDS));
        assertEquals(1, DelayedTask.delayInUnits(Duration.ofNanos(1), TimeUnit.SECONDS));
        assertEquals(0, DelayedTask.delayInUnits(Duration.ZERO, TimeUnit.MILLISECONDS));
        assertTrue(DelayedTask.delayInUnits(Duration.ofNanos(-1), TimeUnit.MILLISECONDS) <= 0);
    }

    @Test
    void rescheduleRetainsExactTargetAndDelaySubsecondPrecision() {
        TestTask task = new TestTask();
        LocalDateTime target = LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0);

        // rescheduleExact, because this fork's reschedule() adds a small forward jitter on purpose;
        // the precision this test protects is what that jitter is added to.
        task.rescheduleExact(target);

        assertEquals(target, task.getScheduled());

        LocalDateTime beforeMillis = LocalDateTime.now();
        long delayMillis = task.getDelay(TimeUnit.MILLISECONDS);
        LocalDateTime afterMillis = LocalDateTime.now();
        LocalDateTime beforeNanos = LocalDateTime.now();
        long delayNanos = task.getDelay(TimeUnit.NANOSECONDS);
        LocalDateTime afterNanos = LocalDateTime.now();

        assertDelayFallsWithinCallWindow(delayMillis, beforeMillis, afterMillis, target, TimeUnit.MILLISECONDS);
        assertDelayFallsWithinCallWindow(delayNanos, beforeNanos, afterNanos, target, TimeUnit.NANOSECONDS);
        assertTrue(delayNanos % TimeUnit.SECONDS.toNanos(1) != 0,
                "nanosecond delay should retain the fractional second before an exact-second target");
    }

    @Test
    void rescheduleOnlyEverDelaysAndNeverPastTheJitterCeiling() {
        TestTask task = new TestTask();
        LocalDateTime target = LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0);
        long ceilingSeconds = Long.parseLong(
                ConfigurationKeyEnum.SCHEDULE_JITTER_MAX_SECONDS_INT.getDefaultValue().trim());
        for (int i = 0; i < 20; i++) {
            task.reschedule(target);
            LocalDateTime scheduled = task.getScheduled();
            assertFalse(scheduled.isBefore(target), "jitter must never pull a run earlier");
            assertFalse(scheduled.isAfter(target.plusSeconds(ceilingSeconds)),
                    () -> "jitter exceeded its " + ceilingSeconds + "s ceiling: " + scheduled);
        }
    }

    private static void assertDelayFallsWithinCallWindow(long delay, LocalDateTime before, LocalDateTime after,
            LocalDateTime target, TimeUnit unit) {
        long earliestPossible = unit.convert(Duration.between(after, target));
        long latestPossible = unit.convert(Duration.between(before, target));
        assertTrue(delay >= earliestPossible && delay <= latestPossible,
                () -> unit + " delay should match the target within the time spent making the call");
    }

    @Test
    void delayedQueueDoesNotReleaseTaskBeforeScheduledTarget() throws InterruptedException {
        TestTask task = new TestTask();
        task.reschedule(LocalDateTime.now().plusNanos(TimeUnit.MILLISECONDS.toNanos(300)));
        DelayQueue<DelayedTask> queue = new DelayQueue<>();
        queue.add(task);

        assertNull(queue.poll());
        assertSame(task, queue.poll(1, TimeUnit.SECONDS));
    }

    private static final class TestTask extends DelayedTask {

        private TestTask() {
            super(profile(), TpDailyTaskEnum.INITIALIZE);
        }

        private static AccountDescriptor profile() {
            AccountDescriptor profile = new AccountDescriptor(1L);
            profile.setEmulatorNumber("1");
            return profile;
        }

        @Override
        protected void execute() {
        }
    }
}
