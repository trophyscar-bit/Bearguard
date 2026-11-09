package dev.frostguard.api.domain;

import java.time.LocalDateTime;

public record TaskFailureStreakData(
        Long profileId,
        String taskKey,
        String signature,
        int consecutiveFailures,
        LocalDateTime firstFailureAt,
        LocalDateTime lastFailureAt) {

    private static final String HANDLED_PREFIX = "handled: ";
    private static final int MAX_SIGNATURE_LENGTH = 240;

    /** The signature of an attempt a routine reported as failed while still returning normally. */
    public static String handledSignature(String reason) {
        String signature = HANDLED_PREFIX + reason;
        return signature.length() > MAX_SIGNATURE_LENGTH ? signature.substring(0, MAX_SIGNATURE_LENGTH) : signature;
    }

    /** What the routine said went wrong, or null when the queue caught an exception instead. */
    public String handledReason() {
        return signature != null && signature.startsWith(HANDLED_PREFIX)
                ? signature.substring(HANDLED_PREFIX.length())
                : null;
    }
}
