package dev.frostguard.tasks.pets;

import java.time.LocalDateTime;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SidebarRouteResult;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;

public class LifeEssenceCaringRoutine extends DelayedTask {

	// ===================== CONSTANTS =====================
	// Navigation coordinates
	private static final PointData CARING_TAB_BUTTON = new PointData(670, 100);
	private static final PointData BACK_TO_MAP_BUTTON = new PointData(42, 28);

	private static final PointData ISLAND_LIST_SCROLL_START = new PointData(350, 1100);
	private static final PointData ISLAND_LIST_SCROLL_END = new PointData(350, 670);

	// Retry limits
	private static final int MAX_ISLAND_SCROLL_ATTEMPTS = 10;
	private static final int MAX_CARING_BUTTON_SEARCHES = 3;

	// Default configuration values
	private static final int DEFAULT_RETRY_OFFSET_MINUTES = Integer.parseInt(
			ConfigurationKeyEnum.ALLIANCE_LIFE_ESSENCE_OFFSET_INT.getDefaultValue());

	// Configuration (loaded fresh each execution)
	private int retryOffsetMinutes;

	// Sidebar failures in a row, for the short backoff below. Only FAILED feeds this; a row the
	// game is not listing is NOT_OFFERED and defers instead.
	private final LifeEssenceCaringRetry navigationRetry = new LifeEssenceCaringRetry();

	public LifeEssenceCaringRoutine(AccountDescriptor profile, TpDailyTaskEnum dailyMission) {
		super(profile, dailyMission);
	}

	@Override
	protected void execute() {

		// Load configuration
		loadConfiguration();

		// Navigate to Life Essence menu
		SidebarRouteResult route = navigateToLifeEssenceMenu();
		if (route == SidebarRouteResult.NOT_OFFERED) {
			deferHiddenDailyRow("Life Essence");
			return;
		}
		if (route != SidebarRouteResult.REACHED) {
			reportFailedAttempt("Could not reach the Life Essence menu");
			rescheduleAfterSidebarFailure("Failed to navigate to Life Essence menu");
			return;
		}
		navigationRetry.afterSuccess();

		int islandsCared = 0;
		for (int i = 0; i < 4; i++) {
			// Caring for an island travels to it on the world map and comes back to the map, not to
			// this menu, so every pass after the first has to re-enter it. Without this the next
			// attempts check taps the Caring tab coordinate while the world map is up, finds nothing,
			// and reports the day's attempts as spent after a single island.
			route = i > 0 ? navigateToLifeEssenceMenu() : SidebarRouteResult.REACHED;
			if (route == SidebarRouteResult.NOT_OFFERED) {
				deferHiddenDailyRow("Life Essence");
				return;
			}
			if (route != SidebarRouteResult.REACHED) {
				rescheduleAfterSidebarFailure("Could not return to the Life Essence menu after caring for "
						+ islandsCared + " island(s)");
				return;
			}

			// Check if daily attempts are available
			if (!checkDailyAttemptsAvailable()) {
				closeAllMenus();
				reschedule(GameTimeUtils.dailyResetTime());
				return;
			}

			// Attempt to find and care for an island
			if (findAndCareForIsland()) {
				islandsCared++;
				logInfo("Life Essence caring completed successfully for " + islandsCared + " islands.");
				if (islandsCared >= 3) {
					logInfo("Life Essence caring completed successfully for all islands today. Rescheduling for next game reset.");
					closeAllMenus();
					reschedule(GameTimeUtils.dailyResetTime());
					return;
				}
			}
		}

		// No island found - retry later

		closeAllMenus();
		reschedule(LocalDateTime.now().plusMinutes(retryOffsetMinutes));
	}

	/**
	 * Reschedules after the sidebar failed to carry us to the Life Essence menu, on a short backoff
	 * rather than the configured hour. See {@link LifeEssenceCaringRetry} for why.
	 *
	 * <p>Only {@link SidebarRouteResult#FAILED} comes here. A row the game is simply not listing is
	 * {@code NOT_OFFERED} and keeps its own defer: there is nothing to collect until it returns, so
	 * probing it sooner would only churn.
	 */
	private void rescheduleAfterSidebarFailure(String reason) {
		LifeEssenceRetryPolicy.Decision decision = navigationRetry.afterNavigationFailure();
		LocalDateTime next = LocalDateTime.now().plus(decision.retryDelay());
		logWarning(reason + ". Consecutive sidebar failures: " + navigationRetry.failures()
				+ ". Retrying in " + decision.retryDelay().toMinutes() + " minutes at "
				+ next.format(DATETIME_FORMATTER) + ".");
		reschedule(next);
	}

	/**
	 * Load configuration from profile after refresh
	 */
	private void loadConfiguration() {
		Integer configOffset = profile.getConfig(
				ConfigurationKeyEnum.ALLIANCE_LIFE_ESSENCE_OFFSET_INT,
				Integer.class);

		this.retryOffsetMinutes = (configOffset != null && configOffset > 0)
				? configOffset
				: DEFAULT_RETRY_OFFSET_MINUTES;

		logDebug("Configuration loaded: retryOffsetMinutes=" + retryOffsetMinutes);
	}

	/**
	 * Navigate to the Life Essence menu
	 * 
	 * Navigation flow:
	 * Uses the shared sidebar navigator to select Daily, locate the Life Essence row,
	 * and tap the Go control associated with that detected row.
	 * 
	 * @return how following the Daily row ended
	 */
	private SidebarRouteResult navigateToLifeEssenceMenu() {
		logInfo("Navigating to Life Essence menu");
		return navigationHelper.openSidebarDestination(SidebarDestination.LIFE_ESSENCE);
	}

	/**
	 * Check if daily caring attempts are available
	 * 
	 * UI Navigation:
	 * 1. Open the Caring tab (top-right icon)
	 * 2. Search for "Daily Caring Available" indicator
	 * 
	 * @return true if attempts available, false if exhausted
	 */
	private boolean checkDailyAttemptsAvailable() {
		logInfo("Checking for daily caring attempts");

		// Open the Caring tab (alliance caring list)
		logDebug("Opening Caring tab");
		tapNear(CARING_TAB_BUTTON);
		sleepTask(2000);

		// Search for daily attempt indicator
		ImageSearchResultData dailyAttemptIndicator = templateSearchHelper.locatePattern(
				TemplatesEnum.LIFE_ESSENCE_DAILY_CARING_AVAILABLE,
				SearchConfig.builder().build());

		if (dailyAttemptIndicator.isFound()) {
			logInfo("Daily caring attempt is available");
			return true;
		}

		// A missing indicator is only evidence of an exhausted allowance if the Caring tab is really
		// open. The tab is reached by a fixed tap, so any screen the routine did not expect produces
		// the same empty read, and treating that as "spent" throws away the rest of the day. Re-enter
		// the menu and look once more before believing it.
		logInfo("Daily caring indicator not found. Re-entering the menu to tell an empty allowance"
				+ " apart from the wrong screen.");
		if (navigateToLifeEssenceMenu() != SidebarRouteResult.REACHED) {
			logWarning("Could not re-enter the Life Essence menu, so the allowance is unknown rather"
					+ " than spent.");
			return false;
		}

		tapNear(CARING_TAB_BUTTON);
		sleepTask(2000);
		if (templateSearchHelper.locatePattern(TemplatesEnum.LIFE_ESSENCE_DAILY_CARING_AVAILABLE,
				SearchConfig.builder().build()).isFound()) {
			logInfo("Daily caring attempt is available after re-entering the menu");
			return true;
		}

		logInfo("No daily caring attempts remaining. Rescheduling for next game reset.");
		return false;
	}

	/**
	 * Search through the alliance island list to find one that needs caring
	 * 
	 * Strategy:
	 * 1. Scroll through island list (up to MAX_ISLAND_SCROLL_ATTEMPTS times)
	 * 2. Look for "Go to Island" button on each screen
	 * 3. If found, navigate to island and perform caring
	 * 
	 * @return true if island found and cared for, false if none found
	 */
	private boolean findAndCareForIsland() {
		logInfo("Searching for island that needs caring");

		for (int scrollAttempt = 0; scrollAttempt < MAX_ISLAND_SCROLL_ATTEMPTS; scrollAttempt++) {
			logDebug("Searching islands on screen (scroll attempt " + (scrollAttempt + 1) +
					"/" + MAX_ISLAND_SCROLL_ATTEMPTS + ")");

			// Search for "Go to Island" button indicating island needs care
			ImageSearchResultData gotoIslandButton = templateSearchHelper.locatePattern(
					TemplatesEnum.LIFE_ESSENCE_DAILY_CARING_GOTO_ISLAND,
					SearchConfig.builder().build());

			if (gotoIslandButton.isFound()) {
				logInfo("Found island needing care (after " + (scrollAttempt + 1) + " scroll(s))");
				return performCaringOnIsland(gotoIslandButton);
			}

			// Not found on this screen - scroll down to see more islands
			if (scrollAttempt < MAX_ISLAND_SCROLL_ATTEMPTS - 1) {
				logDebug("No island found on this screen. Scrolling to see more islands.");
				swipe(ISLAND_LIST_SCROLL_START, ISLAND_LIST_SCROLL_END);
				sleepTask(2000);
			}
		}

		logInfo("No island needing care found after " + MAX_ISLAND_SCROLL_ATTEMPTS + " scroll attempts");
		return false;
	}

	/**
	 * Navigate to an island and perform the caring action
	 * 
	 * Process:
	 * 1. Click "Go to Island" button to navigate to island on map
	 * 2. Wait for map navigation to complete
	 * 3. Search for "Caring" button on the island
	 * 4. Click caring button to complete the action
	 * 5. Return to world map
	 * 
	 * @param gotoButton The button to navigate to the island
	 * @return true if caring performed successfully, false if button not found
	 */
	private boolean performCaringOnIsland(ImageSearchResultData gotoButton) {
		logInfo("Navigating to island location");

		// Click "Go to Island" to navigate on world map
		tapInside(gotoButton);
		sleepTask(5000);

		// Search for the caring button multiple times
		// (May take a moment for island UI to fully load after navigation)
		for (int attempt = 0; attempt < MAX_CARING_BUTTON_SEARCHES; attempt++) {
			logDebug("Searching for caring button (attempt " + (attempt + 1) +
					"/" + MAX_CARING_BUTTON_SEARCHES + ")");

			ImageSearchResultData caringButton = templateSearchHelper.locatePattern(
					TemplatesEnum.LIFE_ESSENCE_DAILY_CARING_BUTTON_CURRENT,
					SearchConfig.builder().build());
			if (!caringButton.isFound()) {
				caringButton = templateSearchHelper.locatePattern(
						TemplatesEnum.LIFE_ESSENCE_DAILY_CARING_BUTTON,
						SearchConfig.builder().build());
			}

			if (caringButton.isFound()) {
				logInfo("Caring button found. Performing caring action.");

				// Click the caring button
				tapInside(caringButton);
				sleepTask(5000);

				// Go back to our island view we're back on main map
				tapNear(BACK_TO_MAP_BUTTON);
				sleepTask(3000);

				logInfo("Caring action completed successfully");
				return true;
			}

			// Not found yet - wait a bit longer for UI to load
			if (attempt < MAX_CARING_BUTTON_SEARCHES - 1) {
				sleepTask(1000);
			}
		}

		logWarning("Caring button not found after " + MAX_CARING_BUTTON_SEARCHES +
				" attempts. Island may not need care or UI didn't load properly.");

		// Return to world map even if caring failed
		tapNear(BACK_TO_MAP_BUTTON);
		sleepTask(1000);

		return false;
	}

	/**
	 * Close all menus and return to world map
	 * 
	 * Uses multiple taps of the back-to-map button to ensure
	 * all overlays and menus are closed
	 */
	private void closeAllMenus() {
		logDebug("Closing all menus and returning to world map");
		sleepTask(300);
		tapInside(BACK_TO_MAP_BUTTON, BACK_TO_MAP_BUTTON, 2, 1000);
	}

	@Override
	protected LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.ANY;
	}

}
