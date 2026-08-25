package dev.frostguard.app.panel.scheduler;

import static dev.frostguard.app.panel.scheduler.TaskManagerLayoutController.RowStatus;
import static dev.frostguard.app.panel.scheduler.TaskManagerLayoutController.rowStatusFor;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The task list's indicator rule.
 *
 * <p>Yellow is a claim that there is time left on the clock, so it may only appear beside one. A
 * row reading "Ready" next to a yellow dot is the failure these cover: the countdown had reached
 * zero while the colour was still describing the schedule.
 */
class TaskManagerRowStatusTest {

	@Test
	void readyAndScheduledIsActiveEvenThoughNothingIsExecutingYet() {
		// The reported bug: Chat Capture sat at "Ready" showing yellow. Ready means the timer is
		// spent and the queue takes it next, so it is green.
		assertEquals(RowStatus.ACTIVE, rowStatusFor(false, true, true));
	}

	@Test
	void scheduledWithTimeRemainingIsWaiting() {
		assertEquals(RowStatus.WAITING, rowStatusFor(false, true, false));
	}

	@Test
	void executingIsActive() {
		assertEquals(RowStatus.ACTIVE, rowStatusFor(true, true, false));
	}

	@Test
	void executingStaysActiveWhileAlsoReady() {
		assertEquals(RowStatus.ACTIVE, rowStatusFor(true, true, true));
	}

	@Test
	void unscheduledIsIdleRegardlessOfAStaleTimestamp() {
		// A disabled task can still hold a next-execution time from before it was switched off.
		// That timestamp is in the past, so the row reads as ready without being scheduled, and
		// must not light up green for work nobody is going to do.
		assertEquals(RowStatus.IDLE, rowStatusFor(false, false, true));
	}

	@Test
	void unscheduledAndNotReadyIsIdle() {
		assertEquals(RowStatus.IDLE, rowStatusFor(false, false, false));
	}

	@Test
	void yellowNeverAppearsWithoutTimeOnTheClock() {
		// The invariant, stated directly: across every combination, WAITING implies not ready.
		for (boolean executing : new boolean[] { false, true }) {
			for (boolean scheduled : new boolean[] { false, true }) {
				for (boolean ready : new boolean[] { false, true }) {
					if (rowStatusFor(executing, scheduled, ready) == RowStatus.WAITING) {
						assertEquals(false, ready,
								"WAITING (yellow) must never be shown for a row that is ready");
					}
				}
			}
		}
	}
}
