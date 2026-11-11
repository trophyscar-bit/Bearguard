package dev.frostguard.tasks.heroes;

import java.util.function.BiFunction;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;

/**
 * Keeps the last frame the gear night read and saves the one a step failed on to
 * {@code logs/snapshot/herogear/}, whatever the snapshot setting says. It never captures or taps.
 */
final class GearFailureFrames {

    static final String ACTIVITY = "herogear";

    private final BiFunction<RawImageData, String, String> save;
    private RawImageData last;
    private String saved;

    GearFailureFrames(BiFunction<RawImageData, String, String> save) {
        this.save = save;
    }

    static GearFailureFrames alwaysOn() {
        return new GearFailureFrames((frame, type) -> TaskDiagnosticSnapshots.saveAlways(frame, ACTIVITY, type));
    }

    RawImageData seen(RawImageData frame) {
        last = frame;
        return frame;
    }

    /** Starts a step, so a frame saved by an earlier step is not reported for this one. */
    void startStep() {
        saved = null;
    }

    /** Saves the frame a read gave up on and returns where it went. */
    String readFailed(String step, RawImageData frame, String failure) {
        saved = save.apply(frame, type(step, failure));
        return saved;
    }

    /** Where this step's failure frame is: the one a read gave up on, or else the last frame read. */
    String stopped(String step, String reason) {
        String path = saved != null ? saved : save.apply(last, type(step, reason));
        saved = null;
        return path;
    }

    // Text after a colon can be an exception message holding a path, which has no place in a file name.
    static String type(String step, String reason) {
        String head = reason == null ? "unknown" : reason.split(":", 2)[0];
        return step + "-" + head;
    }
}
