package dev.frostguard.app.panel.today;

import java.util.function.Predicate;
import java.util.function.Supplier;

/** Which task the side panel shows: the running one while following, otherwise the one picked. */
final class TodayPanelSelection {

    private String selectedKey;
    private boolean following = true;

    String selectedKey() {
        return selectedKey;
    }

    boolean following() {
        return following;
    }

    void reset() {
        selectedKey = null;
        following = true;
    }

    /** Clicking the running task is the same as following it. */
    void clicked(String key, String runningKey) {
        following = key.equals(runningKey);
        selectedKey = key;
    }

    void pin(String key) {
        following = false;
        selectedKey = key;
    }

    void resumeFollowing(String runningKey) {
        following = true;
        if (runningKey != null) {
            selectedKey = runningKey;
        }
    }

    /**
     * @param editing       a half-typed value is in the panel, so following waits until it is finished
     * @param exists        whether a task is still on the profile
     * @param firstUpcoming the fallback when the selected task is gone, however far an edit had got
     */
    void onReload(String runningKey, boolean editing, Predicate<String> exists, Supplier<String> firstUpcoming) {
        if (following && runningKey != null && !editing) {
            selectedKey = runningKey;
        }
        if (selectedKey == null || !exists.test(selectedKey)) {
            selectedKey = firstUpcoming.get();
        }
    }
}
