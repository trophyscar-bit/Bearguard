package dev.frostguard.app.panel.today;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;

class MonitoringTasksTest {

    @Test
    void scansAndRankingsSayWhatTheyWatch() {
        assertEquals(Optional.of("logs heroes + gear"), MonitoringTasks.watching(TpDailyTaskEnum.SQUAD_SCAN.getId(), null));
        assertEquals(Optional.of("reads chat"), MonitoringTasks.watching(TpDailyTaskEnum.CHAT_CAPTURE.getId(), "chat_capture"));
        assertEquals(Optional.of("logs rankings"), MonitoringTasks.watching(TpDailyTaskEnum.GAME_ANALYTICS_POWER.getId(), ""));
    }

    @Test
    void telemetryCustomTasksAreMonitoringButOtherCustomTasksAreNot() {
        int custom = TpDailyTaskEnum.CUSTOM_TASK.getId();
        assertEquals(Optional.of("logs stats"), MonitoringTasks.watching(custom, "bg_telemetry"));
        assertEquals(Optional.of("reads deals"), MonitoringTasks.watching(custom, "bg_deals_telemetry"));
        assertTrue(MonitoringTasks.watching(custom, "bg_cryptidrally").isEmpty());
    }

    @Test
    void tasksThatPlayTheGameAreNotMonitoring() {
        assertTrue(MonitoringTasks.watching(TpDailyTaskEnum.PET_SKILLS.getId(), null).isEmpty());
        assertTrue(MonitoringTasks.watching(TpDailyTaskEnum.EXPERT_SKILL_TRAINING.getId(), null).isEmpty());
    }
}
