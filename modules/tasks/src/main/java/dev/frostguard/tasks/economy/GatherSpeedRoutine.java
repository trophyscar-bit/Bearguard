package dev.frostguard.tasks.economy;

import java.time.LocalDateTime;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.helper.HudGemReader;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.CommonGameAreas;
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
 * backpack (no Obtain dialog). A "replace the active boost" prompt is declined, because the boost
 * is already running.</p>
 *
 * <p>Nothing is bought while the gem count is unknown; that retries in 15 minutes. A buy whose
 * spend cannot be confirmed is never repeated before the boost would run out: on 12 Nov 2026 a
 * real 59,293 balance read as 99,293 after a real buy, so the buy looked unconfirmed and the
 * routine went back every 15 minutes, about ten times, each visit able to spend 250 gems again.
 * Every unknown gem read keeps its frame, whatever the snapshot setting says.</p>
 */
public class GatherSpeedRoutine extends DelayedTask {

	static final AreaData CITY_BONUS_BUTTON_AREA = new AreaData(new PointData(0, 95), new PointData(100, 190));
	private static final PointData CITY_BONUS_BUTTON_FALLBACK = new PointData(34, 142);
	private static final PointData GROWTH_TAB = new PointData(530, 122);
	private static final PointData GATHERING_SPEED_SECTION = new PointData(313, 406);

	/** The Use button sits this far from the top-left of its boost row's title. */
	static final int USE_BUTTON_DX = 482;
	static final int USE_BUTTON_DY = 48;

	/** The count can still be settling after a dialog closes; a few fresh frames ride that out. */
	private static final int GEM_READ_ATTEMPTS = 3;
	private static final long GEM_READ_INTERVAL_MS = 300L;

	private static final PointData OBTAIN_BUY_AND_USE_BUTTON = new PointData(580, 390);
	private static final PointData PURCHASE_DONT_SHOW_CHECKBOX = new PointData(210, 712);
	private static final PointData PURCHASE_CONFIRM_BUTTON = new PointData(370, 790);

	private static final PointData BOOST_REPLACE_TEXT_TOP_LEFT = new PointData(235, 549);
	private static final PointData BOOST_REPLACE_TEXT_BOTTOM_RIGHT = new PointData(580, 584);

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
			retryLater("the Gathering Speed boosts did not open", snapshot("navigation"));
			return;
		}
		ImageSearchResultData row = locateBoostRow();
		if (!row.isFound()) {
			retryLater("the " + boostType.getName() + " boost row was not found", snapshot("row-missing"));
			return;
		}
		GemReading before = readGems();
		if (before.gems() == null) {
			String snapshot = saveGemFrame(before, "gems-unknown-before");
			retryLater("the gem count before buying is unknown (" + before.describe()
					+ "), so nothing was tapped or bought", snapshot);
			return;
		}

		UseOutcome outcome = useBoost(row);
		switch (outcome) {
			case REPLACEMENT_DECLINED -> {
				closeMenu();
				LocalDateTime recheck = LocalDateTime.now().plusHours(1);
				logInfo("A gathering boost is already active; checking again at " + recheck.format(DATETIME_FORMATTER)
						+ ".");
				reschedule(recheck);
			}
			case USED_FROM_BACKPACK -> {
				logInfo("Boost used from the backpack.");
				closeMenu();
				scheduleNextBoost();
			}
			case BOUGHT_PURCHASE_CONFIRMED, BOUGHT_PURCHASE_NOT_SEEN -> confirmPurchase(before, outcome);
		}
	}

	/**
	 * After Buy &amp; Use the gem count is the only proof. An unconfirmed buy may still have spent the
	 * gems and started the boost, so it waits out the boost like a confirmed one instead of buying
	 * again in 15 minutes.
	 */
	private void confirmPurchase(GemReading before, UseOutcome outcome) {
		returnToCityBonus();
		GemReading after = readGems();
		if (spentFor(before.gems(), after.gems(), boostType.getGemCost())) {
			closeMenu();
			logInfo("Boost bought and active (gems " + before.gems() + " -> " + after.gems() + "; "
					+ outcome.describe() + ").");
			scheduleNextBoost();
			return;
		}
		String snapshot = saveGemFrame(after, "purchase-unverified");
		closeMenu();
		LocalDateTime next = LocalDateTime.now().plusHours(boostType.getDurationHours());
		reschedule(next);
		logWarning("Gather speed boost buy unconfirmed: gems before " + before.describe() + ", after "
				+ after.describe() + "; " + outcome.describe() + ". It may have spent " + boostType.getGemCost()
				+ " gems and started the boost, so it will not be bought again before " + next.format(DATETIME_FORMATTER)
				+ "; " + snapshot + ".");
		reportFailedAttempt("Gather speed boost buy unconfirmed");
	}

	private void scheduleNextBoost() {
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

	ImageSearchResultData locateBoostRow() {
		return templateSearchHelper.locatePattern(boostType.rowTemplate(),
				SearchConfig.builder().withMaxAttempts(2).build());
	}

	/** Taps Use on the row and, when the boost is not owned, buys it; the tap sequence is the whole step. */
	UseOutcome useBoost(ImageSearchResultData row) {
		PointData rowTopLeft = row.getMatchedArea().topLeft();
		tapNear(new PointData(rowTopLeft.getX() + USE_BUTTON_DX, rowTopLeft.getY() + USE_BUTTON_DY), 6);
		sleepTask(1500);

		if (found(TemplatesEnum.GATHER_SPEED_OBTAIN_MORE_TITLE, 2)) {
			logInfo("Boost not owned; buying and using it for " + boostType.getGemCost() + " gems.");
			tapNear(OBTAIN_BUY_AND_USE_BUTTON, 6);
			sleepTask(1200);
			if (found(TemplatesEnum.GATHER_SPEED_PURCHASE_TITLE, 2)) {
				tapNear(PURCHASE_DONT_SHOW_CHECKBOX, 4);
				sleepTask(300);
				tapNear(PURCHASE_CONFIRM_BUTTON, 6);
				sleepTask(2000);
				return UseOutcome.BOUGHT_PURCHASE_CONFIRMED;
			}
			return UseOutcome.BOUGHT_PURCHASE_NOT_SEEN;
		}
		return declineReplacementIfAsked() ? UseOutcome.REPLACEMENT_DECLINED : UseOutcome.USED_FROM_BACKPACK;
	}

	boolean openGatheringSpeed() {
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

	/** Reads City Bonus's gem count from fresh frames, keeping the frame behind the last reading. */
	GemReading readGems() {
		GemReading reading = null;
		for (int attempt = 0; attempt < GEM_READ_ATTEMPTS; attempt++) {
			if (attempt > 0) {
				sleepTask(GEM_READ_INTERVAL_MS);
			}
			RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
			reading = new GemReading(frame, HudGemReader.read(frame, CommonGameAreas.CITY_BONUS_GEM_BAND));
			if (reading.gems() != null) {
				break;
			}
		}
		return reading;
	}

	/** Keeps the frame that was read and its gem band, whatever the operator's snapshot setting says. */
	String saveGemFrame(GemReading reading, String type) {
		return TaskDiagnosticSnapshots.saveAlwaysWithRegion(reading.frame(), "gatherspeed", type,
				CommonGameAreas.CITY_BONUS_GEM_BAND);
	}

	/** After a purchase the game may stack a pack advert; Back clears it without buying anything. */
	void returnToCityBonus() {
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

	void closeMenu() {
		for (int attempt = 0; attempt < 3 && found(TemplatesEnum.GATHER_SPEED_CITY_BONUS_TITLE, 1); attempt++) {
			pressBack();
			sleepTask(800);
		}
	}

	private String snapshot(String type) {
		return TaskDiagnosticSnapshots.capture(emuManager, EMULATOR_NUMBER, "gatherspeed", type);
	}

	/** Nothing was spent on this path, so a quick retry is safe. */
	private void retryLater(String reason, String snapshot) {
		closeMenu();
		LocalDateTime retry = LocalDateTime.now().plusMinutes(15);
		logWarning("Gather speed boost not confirmed: " + reason + ". Retrying at " + retry.format(DATETIME_FORMATTER)
				+ "; " + snapshot + ".");
		reportFailedAttempt("Gather speed boost not confirmed");
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

	/** What the Use tap led to. */
	enum UseOutcome {
		USED_FROM_BACKPACK("used from the backpack"),
		REPLACEMENT_DECLINED("replacement declined"),
		BOUGHT_PURCHASE_CONFIRMED("Purchase dialog seen and confirmed"),
		BOUGHT_PURCHASE_NOT_SEEN("Buy & Use tapped, Purchase dialog not seen");

		private final String description;

		UseOutcome(String description) {
			this.description = description;
		}

		String describe() {
			return description;
		}
	}

	/** A gem read and the frame it came from; {@link #gems()} is null when the count is unknown. */
	record GemReading(RawImageData frame, HudGemReader.Reading reading) {
		Long gems() {
			return reading.gems().isPresent() ? reading.gems().getAsLong() : null;
		}

		String describe() {
			return (gems() == null ? "unknown" : gems().toString()) + " (" + reading.evidence() + ")";
		}
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
