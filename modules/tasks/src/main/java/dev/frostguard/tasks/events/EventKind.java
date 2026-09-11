package dev.frostguard.tasks.events;

import dev.frostguard.api.configs.TemplatesEnum;

/**
 * The rotating Events-tab entries that {@link EventClaimRoutine} and
 * {@link EventScheduleScanRoutine} both key off of -- one tab template per event, shared so
 * detection ("is this event's tab currently showing") and claiming stay in sync instead of
 * drifting across two independent enums.
 */
public enum EventKind {

    HALL_OF_CHIEFS("Hall of Chiefs", TemplatesEnum.EVENTS_TAB_HALL_OF_CHIEFS),
    DEFEAT_NEARBY_BEASTS("Defeat Nearby Beasts", TemplatesEnum.EVENTS_TAB_DEFEAT_BEASTS),
    // Live-verified by hand -- tab icon cropped fresh from the live account,
    // Claim buttons already green/ready with a real 1,004,289-point ranking behind them (4 tiers
    // claimable at once: 1,000 / 33,000 / 83,000 / 167,000). Same shared Claim-button skin as
    // Hall of Chiefs, so this reuses the exact same claim loop below -- only the tab template
    // differs.
    BROTHERS_IN_ARMS("Brothers in Arms", TemplatesEnum.EVENTS_TAB_BROTHERS_IN_ARMS);

    private final String label;
    private final TemplatesEnum tabTemplate;

    EventKind(String label, TemplatesEnum tabTemplate) {
        this.label = label;
        this.tabTemplate = tabTemplate;
    }

    public String getLabel() {
        return label;
    }

    public TemplatesEnum getTabTemplate() {
        return tabTemplate;
    }
}
