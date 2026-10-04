package dev.frostguard.api.configs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Evidence level: automated tests.
 */
class TpDailyTaskEnumTest {

    @Test
    void everyTaskHasItsOwnId() {
        // Task state is stored and looked up by id, so two tasks sharing one silently share a row
        // and a queue slot. Two branches each picking the next free id merge without a textual
        // conflict, which is how this happened: this is the only place it can be caught.
        Map<Integer, java.util.List<TpDailyTaskEnum>> byId = Arrays.stream(TpDailyTaskEnum.values())
                .collect(Collectors.groupingBy(TpDailyTaskEnum::getId));
        Map<Integer, java.util.List<TpDailyTaskEnum>> shared = byId.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertEquals(Map.of(), shared, "task ids must be unique");
    }
}
