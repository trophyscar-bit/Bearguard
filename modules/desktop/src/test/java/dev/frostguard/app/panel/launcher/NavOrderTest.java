package dev.frostguard.app.panel.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class NavOrderTest {

    @Test
    void ordersCaseInsensitivelyIgnoringArticlesAndPunctuation() {
        assertEquals(List.of("Alliance", "bear Trap", "The Calendar", "Deals", "Zed"),
                NavOrder.sorted(List.of("Zed", "Deals", "The Calendar", "bear Trap", "Alliance")));
        assertEquals(List.of("(Apple)", "Banana"), NavOrder.sorted(List.of("Banana", "(Apple)")));
    }

    @Test
    void eventsListIsAlphabetical() {
        LinkedHashMap<String, Integer> events = new LinkedHashMap<>();
        for (String name : List.of("Myriad Bazaar", "Sign-in & Earn", "Wander Theater", "Dead Shot", "Winter Siege",
                "Mergeventure", "Journey of Light", "Alliance Championship", "Bear Trap", "Labyrinth", "Fishing Tournament")) {
            events.put(name, 0);
        }
        assertEquals(List.of("Alliance Championship", "Bear Trap", "Dead Shot", "Fishing Tournament",
                        "Journey of Light", "Labyrinth", "Mergeventure", "Myriad Bazaar", "Sign-in & Earn",
                        "Wander Theater", "Winter Siege"),
                List.copyOf(NavOrder.sortedByKey(events).keySet()));
    }

    @Test
    void mainNavListIsAlphabetical() {
        assertEquals(List.of("Calendar", "Deal Tracker", "Statistics", "Tasks"),
                NavOrder.sorted(List.of("Tasks", "Statistics", "Calendar", "Deal Tracker")));
        assertEquals(List.of("Account", "Alliance", "City", "Economy", "Events", "Troops"),
                NavOrder.sorted(List.of("Events", "City", "Alliance", "Economy", "Troops", "Account")));
    }
}
