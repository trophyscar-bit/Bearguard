package dev.frostguard.app.panel.today;

/** What the Run now control may do in each bot and task state. */
public final class RunNowPolicy {

    /**
     * @param note      shown beside the button before it is pressed, or null
     * @param afterRun  replaces the note once the button has been pressed, or null
     */
    public record Decision(String label, boolean enabled, String note, String afterRun) {
    }

    private static final String RUN_NOW = "Run now";
    private static final String RUN_ONCE = "Run once";

    private RunNowPolicy() {
    }

    public static Decision decide(boolean botRunning, boolean paused, boolean executing, boolean taskOn) {
        if (!botRunning) {
            return new Decision(RUN_NOW, false, "Start the bot first", null);
        }
        if (executing) {
            return new Decision(RUN_NOW, false, "Already running", null);
        }
        String label = taskOn ? RUN_NOW : RUN_ONCE;
        return paused
                ? new Decision(label, true, null, "Queued, bot is paused")
                : new Decision(label, true, null, null);
    }
}
