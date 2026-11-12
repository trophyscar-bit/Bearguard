package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.frostguard.tasks.economy.GatherRoutine.ActiveGatherMarchCandidate;
import dev.frostguard.tasks.economy.GatherRoutine.GatherType;
import dev.frostguard.tasks.economy.GatherRoutine.RecallAttempt;

/**
 * Recalling more than one march in a row.
 *
 * <p>Built from the live run of 2026-11-12 13:31, where two recalls landed and the third was
 * refused: {@code March row #3 does not read as Gathering on the World list (rows reading
 * Gathering: #4)}. The recalled marches had become Returning rows at the top of the World list and
 * pushed the last gather down a row, so the index read before the first recall no longer pointed
 * at it. The numbers below are the ones the log printed, not a frame -- the frame of that moment
 * shows other players' names.
 */
class GatherRecallBatchTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 13, 31);

    @Test
    void reducedQueueLimitBlocksNewDeploymentsWithoutRequiringRecall() {
        assertTrue(GatherRoutine.hasReachedConfiguredQueueLimit(3, 1));
        assertTrue(GatherRoutine.hasReachedConfiguredQueueLimit(1, 1));
        assertEquals(false, GatherRoutine.hasReachedConfiguredQueueLimit(0, 1));
    }

    @Test
    void rereadsTheQueueBeforeEveryRecallInsteadOfCarryingTheIndex() {
        // The 13:31 queue: iron 2h32m at row #1, coal 44m at row #2, wood 3h49m at row #3.
        List<ActiveGatherMarchCandidate> queue = new ArrayList<>(List.of(
                new ActiveGatherMarchCandidate(GatherType.IRON, 0, NOW.plusMinutes(152)),
                new ActiveGatherMarchCandidate(GatherType.COAL, 1, NOW.plusMinutes(44)),
                new ActiveGatherMarchCandidate(GatherType.WOOD, 2, NOW.plusMinutes(229))));
        List<Integer> indicesHandedToRecall = new ArrayList<>();

        GatherRoutine.RecallRun run = GatherRoutine.runRecallLoop(3,
                () -> renumber(queue),
                GatherRoutine.LONGEST_RETURNING_FIRST,
                candidate -> {
                    indicesHandedToRecall.add(candidate.queueIndex());
                    queue.removeIf(c -> c.type() == candidate.type());
                    return RecallAttempt.RECALLED;
                });

        assertEquals(3, run.recalled());
        // Every index comes from a read taken after the previous recall. Wood is recalled first at
        // row #3, and the two marches left then close up, so iron and coal are each read at the
        // top. Carrying the first read's indices would have handed over 2, 0, 1 and tapped coal's
        // old row -- which is exactly how the live run lost its third march.
        assertEquals(List.of(2, 0, 0), indicesHandedToRecall);
        assertTrue(queue.isEmpty());
    }

    @Test
    void recallsTheLongestReturningMarchFirst() {
        List<ActiveGatherMarchCandidate> queue = new ArrayList<>(List.of(
                new ActiveGatherMarchCandidate(GatherType.IRON, 0, NOW.plusMinutes(152)),
                new ActiveGatherMarchCandidate(GatherType.COAL, 1, NOW.plusMinutes(44)),
                new ActiveGatherMarchCandidate(GatherType.WOOD, 2, NOW.plusMinutes(229))));
        List<GatherType> order = new ArrayList<>();

        GatherRoutine.runRecallLoop(2, () -> renumber(queue),
                GatherRoutine.LONGEST_RETURNING_FIRST,
                candidate -> {
                    order.add(candidate.type());
                    queue.removeIf(c -> c.type() == candidate.type());
                    return RecallAttempt.RECALLED;
                });

        assertEquals(List.of(GatherType.WOOD, GatherType.IRON), order,
                "The longest-returning march has earned the most and is the cheapest to give up");
    }

    @Test
    void stopsAndReportsHowManyWereLeftOut() {
        List<ActiveGatherMarchCandidate> queue = new ArrayList<>(List.of(
                new ActiveGatherMarchCandidate(GatherType.IRON, 0, NOW.plusMinutes(152)),
                new ActiveGatherMarchCandidate(GatherType.COAL, 1, NOW.plusMinutes(44)),
                new ActiveGatherMarchCandidate(GatherType.WOOD, 2, NOW.plusMinutes(229))));
        AtomicInteger attempts = new AtomicInteger();

        GatherRoutine.RecallRun run = GatherRoutine.runRecallLoop(3, () -> renumber(queue),
                GatherRoutine.LONGEST_RETURNING_FIRST,
                candidate -> {
                    if (attempts.incrementAndGet() == 1) {
                        queue.removeIf(c -> c.type() == candidate.type());
                        return RecallAttempt.RECALLED;
                    }
                    return RecallAttempt.ROW_MISMATCH;
                });

        assertEquals(1, run.recalled());
        assertEquals(RecallAttempt.ROW_MISMATCH, run.stoppedBy());
        assertEquals(2, run.remaining());
        assertEquals(2, attempts.get(), "A refusal stops the run rather than trying the next row");
    }

    @Test
    void stopsWhenNothingIsGatheringAnyMore() {
        GatherRoutine.RecallRun run = GatherRoutine.runRecallLoop(3,
                () -> List.<ActiveGatherMarchCandidate>of(),
                GatherRoutine.LONGEST_RETURNING_FIRST,
                candidate -> RecallAttempt.RECALLED);

        assertEquals(0, run.recalled());
        assertNull(run.stoppedBy());
    }

    @Test
    void namesTheRowMismatchInsteadOfBlamingMissingControls() {
        // The old text said "recall controls not detected" for this case although two controls
        // were on screen, which sent the next reader hunting a template that was working.
        String message = GatherRoutine.describeRecallStop(RecallAttempt.ROW_MISMATCH, 1);

        assertTrue(message.contains("did not line up with a Gathering row"), message);
        assertFalse(message.contains("no recall control"), message);
        assertTrue(message.contains("left out=1"), message);
    }

    @Test
    void stillNamesMissingControlsWhenThatIsWhatHappened() {
        String message = GatherRoutine.describeRecallStop(RecallAttempt.CONTROLS_NOT_FOUND, 2);

        assertTrue(message.contains("no recall control anywhere"), message);
        assertTrue(message.contains("left out=2"), message);
    }

    @Test
    void readsTheGatheringRowsAgainWhileTheListIsStillSettling() {
        // 13:31:57 read one Gathering row with two recall controls on screen; the frame a second
        // later showed two Gathering rows. Fewer labels than controls means the list is moving.
        assertTrue(GatherRoutine.shouldRereadGatheringRows(1, 2));
        assertFalse(GatherRoutine.shouldRereadGatheringRows(2, 2));
        assertFalse(GatherRoutine.shouldRereadGatheringRows(3, 2));
    }

    /** What a fresh queue read returns: the rows renumbered from the top as the game shows them. */
    private static List<ActiveGatherMarchCandidate> renumber(List<ActiveGatherMarchCandidate> queue) {
        List<ActiveGatherMarchCandidate> read = new ArrayList<>();
        for (int i = 0; i < queue.size(); i++) {
            ActiveGatherMarchCandidate c = queue.get(i);
            read.add(new ActiveGatherMarchCandidate(c.type(), i, c.returnTime()));
        }
        return read;
    }
}
