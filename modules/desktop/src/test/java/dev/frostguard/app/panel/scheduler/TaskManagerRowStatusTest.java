package dev.frostguard.app.panel.scheduler;

import static dev.frostguard.app.panel.scheduler.TaskManagerLayoutController.RowStatus;
import static dev.frostguard.app.panel.scheduler.TaskManagerLayoutController.rowStatusFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * The task list's indicator rule, as matt stated it on 2026-08-25:
 *
 * <p><b>Green is any Ready Next Execution status. Yellow is sleeping and counting down the
 * timer.</b>
 *
 * <p>The rule was rewritten five times that day and reverted once, because it lived inside a
 * private method returning a JavaFX ImageView and no test could reach it. These are the record of
 * what it is supposed to do.
 */
class TaskManagerRowStatusTest {

	@Test
	void readyIsGreen() {
		// The reported bug: Chat Capture sat at "Ready" showing yellow.
		assertEquals(RowStatus.READY, rowStatusFor(false, true, true));
	}

	@Test
	void countingDownIsYellow() {
		assertEquals(RowStatus.SLEEPING, rowStatusFor(false, true, false));
	}

	@Test
	void stillCountingDownIsYellowNoMatterHowLittleTimeIsLeft() {
		// There is no threshold. A row thirty seconds out is still sleeping on a countdown, so it
		// is still yellow. An earlier rule turned it green below sixty seconds, which made green
		// mean both "the queue has this now" and "this is nearly due".
		//
		// Seconds never reach this function precisely because of that: readiness is decided in the
		// one place that measures the gap, and everything downstream reads the flag.
		assertEquals(RowStatus.SLEEPING, rowStatusFor(false, true, false));
		assertNotEquals(RowStatus.READY, rowStatusFor(false, true, false));
	}

	@Test
	void executingIsGreen() {
		assertEquals(RowStatus.READY, rowStatusFor(true, true, false));
	}

	@Test
	void executingAndReadyIsStillGreen() {
		assertEquals(RowStatus.READY, rowStatusFor(true, true, true));
	}

	@Test
	void unscheduledIsGreyEvenHoldingAStaleReadyTimestamp() {
		// A disabled task can still hold a next-execution time from before it was switched off.
		// That timestamp is in the past, so the row reads ready without being scheduled, and must
		// not light up green for work nobody is going to do.
		assertEquals(RowStatus.IDLE, rowStatusFor(false, false, true));
	}

	@Test
	void unscheduledAndNotReadyIsGrey() {
		assertEquals(RowStatus.IDLE, rowStatusFor(false, false, false));
	}

	@Test
	void yellowNeverAppearsWithoutACountdown() {
		// The invariant, across every combination: yellow implies not ready.
		for (boolean executing : new boolean[] { false, true }) {
			for (boolean scheduled : new boolean[] { false, true }) {
				for (boolean ready : new boolean[] { false, true }) {
					if (rowStatusFor(executing, scheduled, ready) == RowStatus.SLEEPING) {
						assertEquals(false, ready,
								"yellow means sleeping on a countdown, so it cannot be shown for a ready row");
					}
				}
			}
		}
	}

	@Test
	void everyReadyScheduledRowIsGreen() {
		// The other direction, stated just as plainly: ready and scheduled is always green.
		for (boolean executing : new boolean[] { false, true }) {
			assertEquals(RowStatus.READY, rowStatusFor(executing, true, true));
		}
	}
}
