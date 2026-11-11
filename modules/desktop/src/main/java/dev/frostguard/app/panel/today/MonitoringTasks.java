package dev.frostguard.app.panel.today;

import java.util.Map;
import java.util.Optional;

import dev.frostguard.api.configs.TpDailyTaskEnum;

/** The tasks that only watch and record the game, each with the few words Today shows for what it watches. */
final class MonitoringTasks {

    private static final Map<Integer, String> BY_TASK_ID = Map.of(
            TpDailyTaskEnum.GAME_ANALYTICS_LABYRINTH.getId(), "logs rankings",
            TpDailyTaskEnum.GAME_ANALYTICS_POWER.getId(), "logs rankings",
            TpDailyTaskEnum.SQUAD_SCAN.getId(), "logs heroes + gear",
            TpDailyTaskEnum.CHAT_CAPTURE.getId(), "reads chat",
            TpDailyTaskEnum.RESOURCE_STOCKPILE_SCAN.getId(), "counts resources");

    private static final Map<String, String> BY_CUSTOM_NAME = Map.of(
            "bg_telemetry", "logs stats",
            "bg_deals_telemetry", "reads deals",
            "bg_calendar", "reads events",
            "chat_reconcile", "reads chat");

    private MonitoringTasks() {
    }

    /** What the task watches, or empty for a task that plays the game. */
    static Optional<String> watching(int taskId, String customName) {
        String byId = BY_TASK_ID.get(taskId);
        return Optional.ofNullable(byId != null || customName == null ? byId : BY_CUSTOM_NAME.get(customName));
    }
}
