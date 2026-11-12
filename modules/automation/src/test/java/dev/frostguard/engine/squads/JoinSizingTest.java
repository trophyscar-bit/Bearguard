package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.engine.squads.JoinSizing.Join;
import dev.frostguard.engine.squads.JoinSizing.Plan;
import dev.frostguard.engine.squads.JoinSizing.Troops;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JoinSizingTest {

    private static final long T = JoinSizing.TARGET;

    @Test
    void marksmenPlentyFillsToTargetWithMarksmenFirstThenOneToOne() {
        Troops free = new Troops(500_000, 500_000, 500_000);
        assertEquals(new Troops(30_250, 30_250, 39_500), JoinSizing.size(free, 39_500, T));
        // A preset with no marksmen is all infantry and lancers.
        assertEquals(new Troops(50_000, 50_000, 0), JoinSizing.size(free, 0, T));
        // A preset asking for more than the target is capped at the target.
        assertEquals(new Troops(0, 0, T), JoinSizing.size(free, 250_000, T));
    }

    @Test
    void marksmenShortLeavesTheGapToInfantryAndLancers() {
        // The 14:07 deploy screen: only 3,772 marksmen were free.
        Troops free = new Troops(500_000, 500_000, 3_772);
        assertEquals(new Troops(48_114, 48_114, 3_772), JoinSizing.size(free, 39_500, T));
    }

    @Test
    void aShortClassIsMadeUpFromTheOther() {
        Troops t = JoinSizing.size(new Troops(500_000, 10_000, 0), 0, T);
        assertEquals(10_000, t.lancer());
        assertEquals(90_000, t.infantry());
        assertEquals(T, t.total());
    }

    @Test
    void whenEverythingIsShortItSendsWhatIsFree() {
        Troops free = new Troops(20_000, 15_000, 3_000);
        assertEquals(new Troops(20_000, 15_000, 3_000), JoinSizing.size(free, 39_500, T));
    }

    @Test
    void noHostPlansFiveJoinsInFlagOrder() {
        List<Join> flags = List.of(new Join(1, 39_500), new Join(2, 39_500), new Join(3, 39_500), new Join(4, 0), new Join(5, 0));
        Plan plan = JoinSizing.plan(new Troops(400_000, 400_000, 118_500), null, flags, T);
        assertEquals(5, plan.joins().size());
        assertEquals(1, plan.joins().get(0).flag());
        assertTrue(plan.joins().stream().allMatch(m -> m.troops().total() == T));
        assertEquals(0, plan.marksmenShort());
        assertEquals("Joins: 5 × 100k", plan.summary());
    }

    @Test
    void aHostTakesItsTroopsFirst() {
        List<Join> flags = List.of(new Join(1, 39_500), new Join(2, 39_500), new Join(3, 39_500), new Join(4, 0), new Join(5, 0));
        Troops host = new Troops(13_557, 13_557, 108_456);
        // 226,956 marksmen at home, as before the bear.
        Troops free = new Troops(180_000, 180_000, 226_956);
        Plan plan = JoinSizing.plan(free, host, flags, T);
        assertEquals(3 * 39_500, plan.marksmenWanted());
        assertEquals(0, plan.marksmenShort());
        assertTrue(plan.joins().size() >= 3);
        assertTrue(plan.summary().startsWith("Host 135.6k · Joins: "), plan.summary());
        long inf = plan.joins().stream().mapToLong(m -> m.troops().infantry()).sum();
        long lan = plan.joins().stream().mapToLong(m -> m.troops().lancer()).sum();
        assertTrue(inf <= free.infantry() - host.infantry());
        assertTrue(lan <= free.lancer() - host.lancer());
    }

    @Test
    void marksmenShortIsReported() {
        List<Join> flags = List.of(new Join(1, 39_500), new Join(2, 39_500));
        Plan plan = JoinSizing.plan(new Troops(400_000, 400_000, 20_000), null, flags, T);
        assertEquals(79_000, plan.marksmenWanted());
        assertEquals(59_000, plan.marksmenShort());
        assertEquals("Joins: 2 × 100k · Marksmen short by 59k", plan.summary());
    }

    @Test
    void itStopsWhenWhatIsLeftCannotMakeAUsefulMarch() {
        List<Join> flags = List.of(new Join(1, 0), new Join(2, 0), new Join(3, 0));
        assertEquals("Joins: 2 × 100k + 1 × 20k", JoinSizing.plan(new Troops(120_000, 100_000, 0), null, flags, T).summary());
        // 5k is left after two full joins: not worth a march.
        assertEquals("Joins: 2 × 100k", JoinSizing.plan(new Troops(105_000, 100_000, 0), null, flags, T).summary());
    }

    @Test
    void aBusyHeroIsReplacedByTheBestFreeHeroOfTheSameClass() {
        List<SquadPlanner.HeroScore> scores = List.of(
                new SquadPlanner.HeroScore("Jessie", "Infantry", 90, 50),
                new SquadPlanner.HeroScore("Jasser", "Infantry", 80, 50),
                new SquadPlanner.HeroScore("Bahiti", "Marksman", 99, 50));
        assertEquals(Optional.of("Jasser"), JoinSizing.bestFreeHero("infantry", scores, Set.of("Jessie")));
        assertEquals(Optional.empty(), JoinSizing.bestFreeHero("infantry", scores, Set.of("Jessie", "Jasser")));
        assertEquals(Optional.empty(), JoinSizing.bestFreeHero("lancer", scores, Set.of()));
    }
}
