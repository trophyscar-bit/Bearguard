package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.squads.SavedSquad;
import dev.frostguard.api.squads.SquadSnapshot;
import dev.frostguard.api.squads.TroopOverview;
import dev.frostguard.api.squads.TroopStock;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The nightly join plan: the setting, the capacity cap, and "changed since the night before". */
class JoinPlanStoreTest {

    @TempDir
    Path dir;

    private static SavedSquad saved(int slot, long inf, long lan, long mrk) {
        return new SavedSquad(slot, SavedSquad.State.SAVED, List.of("Hero"), List.of(78), List.of(inf, lan, mrk), "141,610/141,610", null);
    }

    /** Troops at home and the Nov 10 presets: sq1-3 carry 39,500 marksmen, sq4-5 none, sq8 is the host. */
    private static SquadSnapshot snapshot(long inf, long lan, long mrk) {
        TroopOverview troops = new TroopOverview("0/0", "0/0", List.of(
                new TroopStock("Apex Infantry", "Infantry", inf),
                new TroopStock("Apex Lancer", "Lancer", lan),
                new TroopStock("Apex Marksman", "Marksman", mrk)));
        return new SquadSnapshot("2026-11-13T07:15:00Z", List.of(), troops, List.of(
                saved(1, 2000, 2000, 39_500), saved(2, 2000, 2000, 39_500), saved(3, 2000, 2000, 39_500),
                saved(4, 9000, 71_000, 0), saved(5, 9000, 71_000, 0), saved(8, 13_557, 13_557, 108_456)), List.of());
    }

    @Test
    void theSettingDefaultsToOneHundredThousand() {
        assertEquals("100000", ConfigurationKeyEnum.BEAR_TRAP_JOIN_TROOPS_INT.getDefaultValue());
        assertEquals(100_000, JoinSizing.perJoinTarget(100_000));
        assertEquals(100_000, JoinSizing.perJoinTarget(0), "an unset or zero setting falls back to the default");
    }

    @Test
    void anOverrideIsUsed() {
        assertEquals(80_000, JoinSizing.perJoinTarget(80_000));
        assertEquals(250_000, JoinSizing.perJoinTarget(250_000));
        assertEquals(101_400, JoinSizing.FALLBACK_CAPACITY);
    }

    @Test
    void theCapacityComesFromEachPresetNotOneConstant() {
        assertEquals(141_610, JoinSizing.capacityOf("141,610/141,610"));
        assertEquals(126_850, JoinSizing.capacityOf("69,698/126,850"));
        assertEquals(0, JoinSizing.capacityOf(null));
        assertEquals(0, JoinSizing.capacityOf("n/a"));
        // A hero-less preset carries about 101.4k, so a larger setting is held to it; a preset with
        // heroes (141.6k) can take 120k; an unread capacity falls back to 101.4k.
        JoinSizing.Plan plan = JoinSizing.plan(new JoinSizing.Troops(500_000, 500_000, 0), null,
                List.of(new JoinSizing.Join(1, 0, 101_400), new JoinSizing.Join(2, 0, 141_610), new JoinSizing.Join(3, 0)), 120_000);
        assertEquals(101_400, plan.joins().get(0).troops().total());
        assertEquals(120_000, plan.joins().get(1).troops().total());
        assertEquals(101_400, plan.joins().get(2).troops().total());
    }

    @Test
    void theOverrideChangesThePlan() {
        SquadSnapshot snap = snapshot(600_000, 600_000, 400_000);
        JoinSizing.Plan standard = JoinSizing.fromSnapshot(snap, false, 8, List.of(1, 2, 3), List.of(4, 5), 100_000);
        JoinSizing.Plan smaller = JoinSizing.fromSnapshot(snap, false, 8, List.of(1, 2, 3), List.of(4, 5), 60_000);
        assertEquals("Joins: 5 × 100k", standard.summary());
        assertEquals("Joins: 5 × 60k", smaller.summary());
        JoinSizing.Plan capped = JoinSizing.fromSnapshot(snap, false, 8, List.of(1, 2, 3), List.of(4, 5), 500_000);
        // Every preset here reads 141,610, so a 500k setting is held to that, not to 101.4k.
        assertEquals("Joins: 5 × 141.6k", capped.summary());
    }

    @Test
    void hostingTakesTheRallySquadFirstSoMarksmenRunShort() {
        // 226,956 marksmen, as before the bear: the host's 108,456 leaves 118,500, the joins want 118,500.
        SquadSnapshot snap = snapshot(300_000, 300_000, 226_956);
        JoinSizing.Plan hosting = JoinSizing.fromSnapshot(snap, true, 8, List.of(1, 2, 3), List.of(4, 5), 100_000);
        assertEquals(0, hosting.marksmenShort());
        assertEquals(true, hosting.summary().startsWith("Host 135.6k · Joins: "), hosting.summary());
        // A smaller stock of marksmen is reported as short.
        JoinSizing.Plan fewer = JoinSizing.fromSnapshot(snapshot(300_000, 300_000, 150_000), true, 8, List.of(1, 2, 3), List.of(4, 5), 100_000);
        // 150,000 - 108,456 = 41,544 left of the 118,500 the three presets want.
        assertEquals(118_500, fewer.marksmenWanted());
        assertEquals(76_956, fewer.marksmenShort());
    }

    @Test
    void aPlanThatDiffersFromTheNightBeforeIsRememberedAsChanged() throws IOException {
        JoinPlanStore store = new JoinPlanStore(dir);
        List<Integer> hero = List.of(1, 2, 3);
        List<Integer> troop = List.of(4, 5);
        Instant night1 = Instant.parse("2026-11-13T07:20:00Z");
        Instant night2 = Instant.parse("2026-11-14T07:20:00Z");
        Instant night3 = Instant.parse("2026-11-15T07:20:00Z");

        JoinPlanStore.Stored first = store.save(1, JoinSizing.fromSnapshot(snapshot(600_000, 600_000, 400_000), false, 8, hero, troop, 100_000), 100_000, night1);
        assertNull(first.previousSummary());
        assertNull(first.changedAt());

        // Unchanged: nothing to report.
        JoinPlanStore.Stored same = store.save(1, JoinSizing.fromSnapshot(snapshot(600_000, 600_000, 400_000), false, 8, hero, troop, 100_000), 100_000, night2);
        assertNull(same.changedAt());

        // Changed: fewer troops, so the last join is smaller.
        JoinPlanStore.Stored changed = store.save(1, JoinSizing.fromSnapshot(snapshot(250_000, 250_000, 0), false, 8, hero, troop, 100_000), 100_000, night3);
        assertEquals("Joins: 5 × 100k", changed.previousSummary());
        assertNotNull(changed.changedAt());
        assertEquals("Joins: 5 × 100k".equals(changed.summary()), false);

        // A second scan the same night with the same plan keeps what changed.
        JoinPlanStore.Stored again = store.save(1, JoinSizing.fromSnapshot(snapshot(250_000, 250_000, 0), false, 8, hero, troop, 100_000), 100_000, night3.plusSeconds(3600));
        assertEquals(changed.previousSummary(), again.previousSummary());
        assertEquals(changed.changedAt(), again.changedAt());
        assertEquals(again.summary(), store.load(1).orElseThrow().summary());
    }

    @Test
    void changingThePerJoinSettingCountsAsAChangedPlan() throws IOException {
        JoinPlanStore store = new JoinPlanStore(dir);
        SquadSnapshot snap = snapshot(600_000, 600_000, 400_000);
        List<Integer> hero = List.of(1, 2, 3);
        List<Integer> troop = List.of(4, 5);
        store.save(2, JoinSizing.fromSnapshot(snap, false, 8, hero, troop, 100_000), 100_000, Instant.parse("2026-11-13T07:20:00Z"));
        JoinPlanStore.Stored after = store.save(2, JoinSizing.fromSnapshot(snap, false, 8, hero, troop, 80_000), 80_000, Instant.parse("2026-11-14T07:20:00Z"));
        assertEquals("Joins: 5 × 100k", after.previousSummary());
        assertEquals("Joins: 5 × 80k", after.summary());
    }
}
