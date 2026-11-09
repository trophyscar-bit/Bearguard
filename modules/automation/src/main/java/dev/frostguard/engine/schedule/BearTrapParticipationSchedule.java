package dev.frostguard.engine.schedule;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.helper.BearTrapHelper;
import dev.frostguard.engine.helper.TimeWindowHelper.WindowState;

/** Resolves the Bear Trap start time into a queue schedule. */
public final class BearTrapParticipationSchedule {

    /** The one start time. Settings left over from the former second timer are not read. */
    public static final ConfigurationKeyEnum SCHEDULE_KEY = ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING;

    private static final int DEFAULT_PREPARATION_MINUTES = 15;
    private static final DateTimeFormatter CONFIG_DATE_TIME =
            DateTimeFormatter.ofPattern("dd-MM-uuuu HH:mm");

    private BearTrapParticipationSchedule() {
    }

    public static Optional<Plan> resolve(AccountDescriptor profile) {
        return resolve(profile, Clock.systemUTC(), ZoneId.systemDefault());
    }

    static Optional<Plan> resolve(AccountDescriptor profile, Clock clock, ZoneId queueZone) {
        if (profile == null) {
            return Optional.empty();
        }

        Optional<LocalDateTime> configuredActivation = configuredActivation(profile);
        if (configuredActivation.isEmpty()) {
            return Optional.empty();
        }
        LocalDateTime activationUtc = configuredActivation.get();

        Integer configuredPreparation = profile.getConfig(
                ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT,
                Integer.class);
        int preparationMinutes = configuredPreparation != null && configuredPreparation >= 0
                ? configuredPreparation
                : DEFAULT_PREPARATION_MINUTES;

        var window = BearTrapHelper.calculateWindow(
                activationUtc.toInstant(ZoneOffset.UTC),
                preparationMinutes,
                30,
                2,
                clock);
        Instant nextRunInstant = window.getState() == WindowState.INSIDE
                ? clock.instant()
                : window.getNextWindowStart();

        return Optional.of(new Plan(
                activationUtc,
                preparationMinutes,
                LocalDateTime.ofInstant(nextRunInstant, queueZone)));
    }

    private static Optional<LocalDateTime> configuredActivation(AccountDescriptor profile) {
        return profile.getConfigs().stream()
                .filter(config -> SCHEDULE_KEY.equals(config.getSettingKey())
                        || SCHEDULE_KEY.name().equalsIgnoreCase(String.valueOf(config.getSettingKey())))
                .map(config -> config.getRawValue())
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .flatMap(BearTrapParticipationSchedule::parseActivation);
    }

    private static Optional<LocalDateTime> parseActivation(String value) {
        try {
            return Optional.of(LocalDateTime.parse(value, CONFIG_DATE_TIME));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    public record Plan(
            LocalDateTime activationUtc,
            int preparationMinutes,
            LocalDateTime nextRun) {
    }
}
