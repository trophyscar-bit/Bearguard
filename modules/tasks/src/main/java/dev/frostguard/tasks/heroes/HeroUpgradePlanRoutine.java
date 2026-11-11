package dev.frostguard.tasks.heroes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.squads.GearRanker;
import dev.frostguard.engine.squads.HeroUpgradePlanner;

/**
 * Dry run: logs what {@link HeroUpgradePlanner} would spend, from
 * {@code squads/hero-inventory-<profile>.json}. It never touches the game screen, so it cannot
 * spend anything. The spending phase comes after the screen readers are proven against frames.
 */
public final class HeroUpgradePlanRoutine extends DelayedTask {

    private static final int ROLLING_COUNT = 10;

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public HeroUpgradePlanRoutine(AccountDescriptor profile, TpDailyTaskEnum task) {
        super(profile, task);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.ANY;
    }

    @Override
    protected boolean acceptsInjections() {
        return false;
    }

    @Override
    protected void execute() {
        setRecurring(false);
        Path squads = WorkspacePaths.current().root().resolve("squads");
        logRollingUpgrades(squads.resolve("hero-gear-" + profile.getId() + ".json"));
        Path file = squads.resolve("hero-inventory-" + profile.getId() + ".json");
        if (!Files.exists(file)) {
            logInfo("Hero upgrade plan: no inventory at " + file);
            return;
        }
        try {
            HeroUpgradePlanner.Plan plan = HeroUpgradePlanner.plan(
                    mapper.readValue(file.toFile(), HeroUpgradePlanner.Inventory.class));
            plan.steps().forEach(step -> logInfo("Hero upgrade plan (dry run): " + step));
            plan.waiting().forEach(line -> logInfo("Hero upgrade plan, waiting: " + line));
        } catch (IOException e) {
            logWarning("Hero upgrade plan: inventory unreadable: " + e.getMessage());
        }
    }

    private void logRollingUpgrades(Path file) {
        if (!Files.exists(file)) {
            logInfo("Hero upgrade plan: no gear state at " + file);
            return;
        }
        try {
            GearRanker.rolling(mapper.readValue(file.toFile(), GearRanker.GearState.class), ROLLING_COUNT)
                    .forEach(upgrade -> logInfo("Hero upgrade plan, rolling: " + upgrade));
        } catch (IOException e) {
            logWarning("Hero upgrade plan: gear state unreadable: " + e.getMessage());
        }
    }
}
