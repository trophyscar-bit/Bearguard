package dev.frostguard.app.shared;

import javafx.beans.value.ObservableStringValue;

/**
 * A settings page that is the only place its options can be edited. Other screens that list a
 * task's options show its read-only summary and a way to open the page, never copies of its controls.
 */
public interface SingleHomeSettingsPage {

    /** One line describing the current settings, kept up to date as they change. */
    ObservableStringValue settingsSummary();

    /** Where the page is in the app, such as "Events > Bear Trap". */
    String settingsHome();
}
