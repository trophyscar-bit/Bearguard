package dev.frostguard.app.panel.today;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;

class MonitoringTasksTest {

    @Test
    void scansAndRankingsAreMonitoring() {
        assertTrue(MonitoringTasks.isMonitoring(TpDailyTaskEnum.SQUAD_SCAN.getId(), null));
        assertTrue(MonitoringTasks.isMonitoring(TpDailyTaskEnum.CHAT_CAPTURE.getId(), "chat_capture"));
        assertTrue(MonitoringTasks.isMonitoring(TpDailyTaskEnum.GAME_ANALYTICS_POWER.getId(), ""));
    }

    @Test
    void telemetryCustomTasksAreMonitoringButOtherCustomTasksAreNot() {
        int custom = TpDailyTaskEnum.CUSTOM_TASK.getId();
        assertTrue(MonitoringTasks.isMonitoring(custom, "bg_telemetry"));
        assertTrue(MonitoringTasks.isMonitoring(custom, "bg_deals_telemetry"));
        assertFalse(MonitoringTasks.isMonitoring(custom, "bg_cryptidrally"));
    }

    @Test
    void tasksThatPlayTheGameAreNotMonitoring() {
        assertFalse(MonitoringTasks.isMonitoring(TpDailyTaskEnum.PET_SKILLS.getId(), null));
        assertFalse(MonitoringTasks.isMonitoring(TpDailyTaskEnum.EXPERT_SKILL_TRAINING.getId(), null));
    }
}
