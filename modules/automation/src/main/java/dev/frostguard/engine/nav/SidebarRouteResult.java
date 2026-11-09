package dev.frostguard.engine.nav;

/** How following a sidebar row ended. */
public enum SidebarRouteResult {
    /** The row's action was tapped and the sidebar closed on the way to the destination. */
    REACHED,
    /**
     * A complete scan of a verified Daily list found no row to follow: the game hid it, or it shows
     * a control this route does not tap. The game decided this, so it is not a fault.
     */
    NOT_OFFERED,
    /** The sidebar did not behave as verified, so nothing is known about the row. */
    FAILED
}
