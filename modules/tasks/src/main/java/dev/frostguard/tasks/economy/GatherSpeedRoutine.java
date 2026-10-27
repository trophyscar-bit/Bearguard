package dev.frostguard.tasks.economy;

import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;

/**
 * Activates the Gathering Speed boost from City Bonus (the blue arrow under the chief avatar).
 *
 * <p>Every step is checked against the screen. The old blind version tapped the profile spot at
 * (40,118), which the HUD moved: the City Bonus button now sits at (34,142). It then tapped empty
 * screen and reported "Activating" every 8h without spending a gem (27 Oct 2026: 464,517 gems
 * before and after).</p>
 *
 * <p>Success means the gem count fell by the boost's price, or the item was used from the
 * backpack (no Obtain dialog). Anything unverified retries in 15 minutes with a saved frame. A
 * "replace the active boost" prompt is declined, because the boost is already running.</p>
 */
public class GatherSpeedRoutine extends DelayedTask {

	static final AreaData CITY_BONUS_BUTTON_AREA = new AreaData(new PointData(0, 95), new PointData(100, 190));
	private static final PointData CITY_BONUS_BUTTON_FALLBACK = new PointData(34, 142);
	private static final PointData GROWTH_TAB = new PointData(530, 122);
	private static final PointData GATHERING_SPEED_SECTION = new PointData(313, 406);

	/** The Use button sits this far from the top-left of its boost row's title. */
	static final int USE_BUTTON_DX = 482;
	static final int USE_BUTTON_DY = 48;

	static final PointData GEMS_TOP_LEFT = new PointData(575, 22);
	static final PointData GEMS_BOTTOM_RIGHT = new PointData(700, 58);
	private static final OcrSettingsData WHITE_TEXT = OcrSettingsData.forWhiteTextOnDark();

	private static final PointData OBTAIN_BUY_AND_USE_BUTTON = new PointData(580, 390);
	private static final PointData PURCHASE_DONT_SHOW_CHECKBOX = new PointData(210, 712);
	private static final PointData PURCHASE_CONFIRM_BUTTON = new PointData(370, 790);

	private static final PointData BOOST_REPLACE_TEXT_TOP_LEFT = new PointData(235, 549);
	private static final PointData BOOST_REPLACE_TEXT_BOTTOM_RIGHT = new PointData(580, 584);

	private static final Pattern GEM_DIGITS = Pattern.compile("\\d[\\d,.]*");

	private BoostType boostType;

	public GatherSpeedRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
		super(profile, tpTask);
	}

	private void loadConfiguration() {
		String boostTypeValue = profile.getConfig(
				ConfigurationKeyEnum.GATHER_SPEED_BOOST_TYPE_STRING, String.class);
		// The ComboBox stores its label, e.g. "8h (250 gems)" or "24h (600 gems)".
		this.boostType = boostTypeValue != null && boostTypeValue.startsWith("24h")
				? BoostType.BOOST_24H
				: BoostType.BOOST_8H;
	}

	@Override
	protected void execute() {
		loadConfiguration();
		logInfo(String.format("Starting gather speed boost (%s).", boostType.getName()));

		if (!openGatheringSpeed()) {
			retryLater("the Gathering Speed boosts did not open", "navigation");
			return;
		}
		ImageSearchResultData row = templateSearchHelper.locatePattern(boostType.rowTemplate(),
				SearchConfig.builder().withMaxAttempts(2).build());
		if (!row.isFound()) {
			retryLater("the " + boostType.getName() + " boost row was not found", "row-missing");
			return;
		}
		Long gemsBefore = readGems();

		PointData rowTopLeft = row.getMatchedArea().topLeft();
		tapNear(new PointData(rowTopLeft.getX() + USE_BUTTON_DX, rowTopLeft.getY() + USE_BUTTON_DY), 6);
		sleepTask(1500);

		boolean bought = false;
		if (found(TemplatesEnum.GATHER_SPEED_OBTAIN_MORE_TITLE, 2)) {
			logInfo("Boost not owned; buying and using it for " + boostType.getGemCost() + " gems.");
			tapNear(OBTAIN_BUY_AND_USE_BUTTON, 6);
			sleepTask(1200);
			if (found(TemplatesEnum.GATHER_SPEED_PURCHASE_TITLE, 2)) {
				tapNear(PURCHASE_DONT_SHOW_CHECKBOX, 4);
				sleepTask(300);
				tapNear(PURCHASE_CONFIRM_BUTTON, 6);
				sleepTask(2000);
			}
			bought = true;
		} else if (declineReplacementIfAsked()) {
			closeMenu();
			LocalDateTime recheck = LocalDateTime.now().plusHours(1);
			logInfo("A gathering boost is already active; checking again at " + recheck.format(DATETIME_FORMATTER) + ".");
			reschedule(recheck);
			return;
		}

		if (bought) {
			returnToCityBonus();
			Long gemsAfter = readGems();
			if (!spentFor(gemsBefore, gemsAfter, boostType.getGemCost())) {
				closeMenu();
				retryLater("the gem count did not fall by " + boostType.getGemCost() + " (" + gemsBefore + " -> "
						+ gemsAfter + ")", "purchase-unverified");
				return;
			}
			logInfo("Boost bought and active (gems " + gemsBefore + " -> " + gemsAfter + ").");
		} else {
			logInfo("Boost used from the backpack.");
		}
		closeMenu();
		LocalDateTime next = LocalDateTime.now().plusHours(boostType.getDurationHours());
		reschedule(next);
		logInfo("Next gather speed boost at " + next.format(DATETIME_FORMATTER) + ".");
	}

	/**
	 * True when the gem count fell by at least the price. Both counts must be read; an unreadable
	 * count is never taken as proof of a purchase.
	 */
	static boolean spentFor(Long before, Long after, int cost) {
		return before != null && after != null && before - after >= cost;
	}

	/** Reads "464,517" as 464517; null when no digits are legible. */
	static Long parseGems(String text) {
		if (text == null) {
			return null;
		}
		Matcher matcher = GEM_DIGITS.matcher(text.replace(" ", ""));
		if (!matcher.find()) {
			return null;
		}
		try {
			return Long.parseLong(matcher.group().replaceAll("[,.]", ""));
		} catch (NumberFormatException unreadable) {
			return null;
		}
	}

	private boolean openGatheringSpeed() {
		ImageSearchResultData button = templateSearchHelper.locatePattern(TemplatesEnum.GATHER_SPEED_CITY_BONUS_BUTTON,
				SearchConfig.builder()
						.withCoordinates(CITY_BONUS_BUTTON_AREA.topLeft(), CITY_BONUS_BUTTON_AREA.bottomRight())
						.withMaxAttempts(2)
						.build());
		if (button.isFound()) {
			tapInside(button);
		} else {
			tapNear(CITY_BONUS_BUTTON_FALLBACK, 4);
		}
		sleepTask(1500);
		if (!found(TemplatesEnum.GATHER_SPEED_CITY_BONUS_TITLE, 3)) {
			return false;
		}
		tapNear(GROWTH_TAB, 6);
		sleepTask(1000);
		tapNear(GATHERING_SPEED_SECTION, 6);
		sleepTask(1200);
		return found(TemplatesEnum.GATHER_SPEED_BOOST_8H_ROW, 2) || found(TemplatesEnum.GATHER_SPEED_BOOST_24H_ROW, 1);
	}

	private Long readGems() {
		String raw = stringHelper.attemptRecognition(GEMS_TOP_LEFT, GEMS_BOTTOM_RIGHT, 3, 200L, WHITE_TEXT,
				text -> parseGems(text) != null, String::trim);
		return parseGems(raw);
	}

	/** After a purchase the game may stack a pack advert; Back clears it without buying anything. */
	private void returnToCityBonus() {
		for (int attempt = 0; attempt < 3 && !found(TemplatesEnum.GATHER_SPEED_CITY_BONUS_TITLE, 1); attempt++) {
			pressBack();
			sleepTask(1200);
		}
	}

	private boolean declineReplacementIfAsked() {
		String text = readStringValue(BOOST_REPLACE_TEXT_TOP_LEFT, BOOST_REPLACE_TEXT_BOTTOM_RIGHT, null);
		if (text == null) {
			return false;
		}
		String clean = text.toLowerCase();
		if (!(clean.contains("activating") || clean.contains("another") || clean.contains("bonus"))) {
			return false;
		}
		logInfo("Replacement prompt for an active boost; declining.");
		pressBack();
		sleepTask(800);
		return true;
	}

	private boolean found(TemplatesEnum template, int attempts) {
		return templateSearchHelper.locatePattern(template,
				SearchConfig.builder().withMaxAttempts(attempts).build()).isFound();
	}

	private void closeMenu() {
		for (int attempt = 0; attempt < 3 && found(TemplatesEnum.GATHER_SPEED_CITY_BONUS_TITLE, 1); attempt++) {
			pressBack();
			sleepTask(800);
		}
	}

	private void retryLater(String reason, String snapshotType) {
		String snapshot = TaskDiagnosticSnapshots.capture(emuManager, EMULATOR_NUMBER, "gatherspeed", snapshotType);
		closeMenu();
		LocalDateTime retry = LocalDateTime.now().plusMinutes(15);
		logWarning("Gather speed boost not confirmed: " + reason + ". Retrying at " + retry.format(DATETIME_FORMATTER)
				+ "; " + snapshot + ".");
		reschedule(retry);
	}

	@Override
	protected LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.HOME;
	}

	@Override
	public boolean provideDailyMissionProgress() {
		return false;
	}

	private enum BoostType {
		BOOST_24H("24h", 24, 600, TemplatesEnum.GATHER_SPEED_BOOST_24H_ROW),
		BOOST_8H("8h", 8, 250, TemplatesEnum.GATHER_SPEED_BOOST_8H_ROW);

		private final String name;
		private final int durationHours;
		private final int gemCost;
		private final TemplatesEnum rowTemplate;

		BoostType(String name, int durationHours, int gemCost, TemplatesEnum rowTemplate) {
			this.name = name;
			this.durationHours = durationHours;
			this.gemCost = gemCost;
			this.rowTemplate = rowTemplate;
		}

		String getName() {
			return name;
		}

		int getDurationHours() {
			return durationHours;
		}

		int getGemCost() {
			return gemCost;
		}

		TemplatesEnum rowTemplate() {
			return rowTemplate;
		}
	}
}
