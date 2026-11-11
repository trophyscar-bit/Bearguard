package dev.frostguard.app.panel.today;

import java.util.Set;

import dev.frostguard.api.configs.TpDailyTaskEnum;

/** The tasks that only watch and record the game, which the Today timeline colours apart from the rest. */
final class MonitoringTasks {

    private static final Set<Integer> TASK_IDS = Set.of(
            TpDailyTaskEnum.GAME_ANALYTICS_LABYRINTH.getId(),
            TpDailyTaskEnum.GAME_ANALYTICS_POWER.getId(),
            TpDailyTaskEnum.SQUAD_SCAN.getId(),
            TpDailyTaskEnum.CHAT_CAPTURE.getId(),
            TpDailyTaskEnum.RESOURCE_STOCKPILE_SCAN.getId());

    private static final Set<String> CUSTOM_NAMES = Set.of(
            "bg_telemetry", "bg_deals_telemetry", "bg_calendar", "chat_capture", "chat_reconcile",
            "resource_stockpile_scan");

    private MonitoringTasks() {
    }

    static boolean isMonitoring(int taskId, String customName) {
        return TASK_IDS.contains(taskId) || (customName != null && CUSTOM_NAMES.contains(customName));
    }
}
