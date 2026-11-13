package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.tasks.events.EventMissionClassifier.Kind;

class EventMissionClassifierTest {

    private static Kind kind(String title) {
        return EventMissionClassifier.classify(title).kind();
    }

    @Test
    void theWanderTheaterMissionsClassifyAsExpected() {
        assertEquals(Kind.PASSIVE, kind("Train/Promote up 30,000 Troop Power"));
        assertEquals(Kind.DOABLE, kind("Call rally and hunt down 1 Polar Terror(s)"));
        assertEquals(TpDailyTaskEnum.EVENT_POLAR_TERROR,
                EventMissionClassifier.classify("Call rally and hunt down 1 Polar Terror(s)").task());
        assertEquals(TpDailyTaskEnum.ALLIANCE_TECH,
                EventMissionClassifier.classify("Make 5 Alliance Contribution(s)").task());
        assertEquals(Kind.PURCHASE, kind("Make any payment 1 time(s)"));
    }

    @Test
    void chiefGearIsNeverTouched() {
        assertEquals(Kind.EXCLUDED, kind("Upgrade Chief Gear 2 times"));
        assertEquals(Kind.EXCLUDED, kind("Forge any Gear"));
    }

    @Test
    void anythingAskingForMoneyIsAPurchase() {
        assertEquals(Kind.PURCHASE, kind("Purchase any pack 1 time(s)"));
        assertEquals(Kind.PURCHASE, kind("Spend 100 Gems"));
        assertEquals(Kind.PURCHASE, kind("Top-up once"));
        assertEquals(Kind.PURCHASE, kind("Buy 1 Daily Deal"));
    }

    @Test
    void doableMissionsNameTheTaskThatDoesThem() {
        assertEquals(TpDailyTaskEnum.GATHER_RESOURCES, EventMissionClassifier.classify("Gather 5,000 Meat").task());
        assertEquals(TpDailyTaskEnum.BEAST_HUNTING, EventMissionClassifier.classify("Defeat 10 Beasts").task());
        assertEquals(TpDailyTaskEnum.INTEL, EventMissionClassifier.classify("Complete 3 Intel missions").task());
        assertEquals(TpDailyTaskEnum.ARENA, EventMissionClassifier.classify("Win 2 Arena battles").task());
        assertEquals(TpDailyTaskEnum.DO_EXPLORATION, EventMissionClassifier.classify("Clear 2 Exploration stages").task());
    }

    @Test
    void trainingAndHealingAreLeftToTheirOwnTasks() {
        assertEquals(Kind.PASSIVE, kind("Train 500 Infantry"));
        assertEquals(Kind.PASSIVE, kind("Heal 1,000 troops"));
    }

    @Test
    void aTitleThatCannotBeReadIsNeverClaimed() {
        assertEquals(Kind.UNREADABLE, kind(null));
        assertEquals(Kind.UNREADABLE, kind("  "));
        assertEquals(Kind.UNREADABLE, kind("-- 1"));
    }

    @Test
    void theSameTitleReadWithDifferentDigitsKeepsOneKey() {
        assertEquals(EventMissionClassifier.key("Make 5 Alliance Contribution(s)"),
                EventMissionClassifier.key("Make 6 Alliance  Contribution(s)"));
    }
}
