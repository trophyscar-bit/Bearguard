package dev.frostguard.tasks.dailies;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.OptionalInt;

import dev.frostguard.engine.schedule.StaminaWaitScheduler;
import dev.frostguard.engine.service.StaminaService;

final class IntelStaminaGate {

    // An unreadable stamina is not a reason to come straight back. At one minute, a stamina read that
    // stayed broken -- the October 2026 profile redesign did exactly that -- made Intel the only task
    // that ever ran: 38 runs in under two hours, each recalling every gather march on the way in.
    private static final Duration UNKNOWN_RETRY_DELAY = Duration.ofMinutes(15);

    private IntelStaminaGate() {
    }

    static Outcome evaluate(StaminaService staminaService, Long profileId, int minimumRequired,
                            LocalDateTime now, StaminaWaitScheduler scheduler) {
        OptionalInt stamina = staminaService.findCurrentStamina(profileId);
        if (stamina.isEmpty()) {
            LocalDateTime retryAt = now.plus(UNKNOWN_RETRY_DELAY);
            scheduler.reschedule(retryAt);
            return new Outcome(Status.UNKNOWN, stamina, retryAt);
        }

        int measured = stamina.getAsInt();
        if (measured < minimumRequired) {
            LocalDateTime retryAt = now.plusMinutes(
                    StaminaService.minutesToRegenerate(measured, minimumRequired));
            scheduler.deferForStamina(minimumRequired, minimumRequired, retryAt, now);
            return new Outcome(Status.INSUFFICIENT, stamina, retryAt);
        }

        return new Outcome(Status.READY, stamina, null);
    }

    enum Status {
        READY,
        UNKNOWN,
        INSUFFICIENT
    }

    record Outcome(Status status, OptionalInt stamina, LocalDateTime retryAt) {
        boolean allowed() {
            return status == Status.READY;
        }
    }
}
