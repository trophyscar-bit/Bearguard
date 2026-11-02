package dev.frostguard.tasks.dailies;

import java.awt.image.BufferedImage;
import java.time.LocalDateTime;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.ImageConverter;

public class ChiefOrderRoutine extends DelayedTask {

	/**
	 * Every order the shelf has shown. Entering the Fire Crystal Age retires Urgent Mobilization,
	 * Productivity Day and Festivities and shelves Crunch Time, Advanced Training and Dawn Alliance
	 * Commendation in their place; Rush Job, Comprehensive Care and Double Time carry over. Which
	 * six an account sees depends on its state, so both generations stay here and a missing one is
	 * read off the shelf rather than assumed. The cooldowns are only a fallback -- the shelf banner
	 * states the real remaining time and is read on every pass.
	 */
	public enum ChiefOrderType {

		RUSH_JOB("Rush Job", TemplatesEnum.CHIEF_ORDER_RUSH_JOB, 23),

		URGENT_MOBILIZATION("Urgent Mobilization", TemplatesEnum.CHIEF_ORDER_URGENT_MOBILISATION, 8),

		PRODUCTIVITY_DAY("Productivity Day", TemplatesEnum.CHIEF_ORDER_PRODUCTIVITY_DAY, 12),

		/** +100% resource output for 24h. */
		CRUNCH_TIME("Crunch Time", null, 12),

		/** Heals every sick survivor; its Enact button is greyed out while none are sick. */
		COMPREHENSIVE_CARE("Comprehensive Care", null, 4),

		/** +20% on city builds started within the next 5 minutes. */
		DOUBLE_TIME("Double Time", null, 23),

		/** +20% troop training speed for 2h. */
		ADVANCED_TRAINING("Advanced Training", null, 47),

		/** A random reward from the Dawn Alliance vault. */
		DAWN_ALLIANCE_COMMENDATION("Dawn Alliance Commendation", null, 47);

		private final String description;
		private final TemplatesEnum template;
		private final int cooldownHours;

		ChiefOrderType(String description, TemplatesEnum template, int cooldownHours) {
			this.description = description;
			this.template = template;
			this.cooldownHours = cooldownHours;
		}

		public String getDescription() {
			return description;
		}

		public TemplatesEnum getTemplate() {
			return template;
		}

		public int getCooldownHours() {
			return cooldownHours;
		}

		/**
		 * Words of the shelf label that belong to this order alone (four letters or more, used by no
		 * other order). Any one of them identifies the slot, so one misread letter does not lose it:
		 * "Dawn Alliance Commendation" wraps onto two lines and reads as "vawn alliancecommendation".
		 */
		java.util.List<String> labelNeedles() {
			java.util.List<String> needles = new java.util.ArrayList<>();
			for (String word : description.toLowerCase().split(" ")) {
				if (word.length() < 4) {
					continue;
				}
				boolean shared = false;
				for (ChiefOrderType other : values()) {
					if (other != this && java.util.Arrays.asList(other.description.toLowerCase().split(" ")).contains(word)) {
						shared = true;
					}
				}
				if (!shared) {
					needles.add(word);
				}
			}
			return needles;
		}

		boolean labelMatches(String label) {
			String text = label.toLowerCase();
			return labelNeedles().stream().anyMatch(text::contains);
		}
	}

private static final int ERROR_RETRY_MINUTES_VALUE = 10;

/** How soon to look again at an order whose Enact button is greyed out (e.g. no sick survivors). */
private static final int DISABLED_RETRY_MINUTES = 60;

/** Labels that must read before a missing order counts as absent rather than the shelf being shut. */
static final int SHELF_CONFIRMED_LABELS = 3;

/** An enabled Enact button is mostly blue behind its white label; a greyed one has none. */
static final double ENACT_BLUE_SHARE_MIN = 0.10;

/** Finds the Enact label on the blue button and on the greyed one (which scores ~88). */
private static final int ENACT_SEARCH_THRESHOLD = 80;

/** Pulls the remaining time out of an "On cooldown hh:mm:ss" banner. */
private static final java.util.regex.Pattern COOLDOWN_REMAINING =
        java.util.regex.Pattern.compile("(\\d{1,2}:\\d{2}:\\d{2})");

private final ChiefOrderType chiefOrderType;

public ChiefOrderRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask, ChiefOrderType chiefOrderType) {
		super(profile, tpTask);
		this.chiefOrderType = chiefOrderType;
	}

@Override
	public LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.HOME;
	}

@Override
	protected void execute() {
		logInfo(routineLogChiefOrderLine("Initiating Chief Order : " + chiefOrderType.getDescription() +
				" (Cooldown: " + chiefOrderType.getCooldownHours() + " hours)"));

		if (!openUpChiefOrderMenu()) {
			manageTaskFailure("Failed to open Chief Order menu");
			return;
		}

		switch (chooseOrderType()) {
			case SCHEDULED_FROM_SCREEN -> {
				// The shelf told us exactly when this order frees up and that time is already
				// recorded. Falling into manageTaskFailure here would immediately overwrite it
				// with a blind 10-minute retry — which is how a 10-hour Rush Job cooldown turned
				// into sixty pointless trips to the shelf.
				pressBack();
				return;
			}
			case UNAVAILABLE -> {
				manageTaskFailure("Order type not available (likely on cooldown)");
				return;
			}
			default -> { /* AVAILABLE — the order is open, carry on and enact it. */ }
		}

		switch (enactOrderFlow()) {
			case NOT_FOUND -> manageTaskFailure("Failed to enact order");
			case DISABLED -> {
				LocalDateTime recheck = LocalDateTime.now().plusMinutes(DISABLED_RETRY_MINUTES);
				reschedule(recheck);
				logInfo(routineLogChiefOrderLine(chiefOrderType.getDescription()
						+ " cannot be enacted right now; looking again at " + recheck + "."));
			}
			case ENACTED -> queueNextRun();
		}
	}

private String routineLogChiefOrderLine(String note) {
        return "ChiefOrderRoutine | " + note;
    }

private boolean openUpChiefOrderMenu() {
		logInfo(routineLogChiefOrderLine("Looking for Chief Order menu access button"));

		ImageSearchResultData menuButton = templateSearchHelper.locatePattern(
				TemplatesEnum.CHIEF_ORDER_MENU_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (!menuButton.isFound()) {
			logError(routineLogChiefOrderLine("Chief Order menu button not detected"));
			return false;
		}

		logInfo(routineLogChiefOrderLine("Chief Order menu button detected. Pressing to open menu"));
		tapInside(menuButton);
		sleepTask(2000);


		return true;
	}

private void manageTaskFailure(String reason) {
		logWarning(routineLogChiefOrderLine("Routine pass did not complete: " + reason));

		LocalDateTime retryTime = LocalDateTime.now().plusMinutes(ERROR_RETRY_MINUTES_VALUE);
		reschedule(retryTime);

		logInfo(routineLogChiefOrderLine("Task rescheduled to retry in " + ERROR_RETRY_MINUTES_VALUE + " minutes"));
	}

	/**
	 * Finds this order on the shelf and opens it when it is actually available.
	 *
	 * <p>watching a live run: Urgent Mobilization was plainly available — red
	 * dot, no cooldown banner — and the bot skipped it twice in one pass. Cause was the icon
	 * template: a 40px crop of dark book art that does not match reliably. Worse, a miss was
	 * logged as "not detected or currently on cooldown", so a broken template read exactly like
	 * normal cooldown behaviour and hid itself.</p>
	 *
	 * <p>This reads the shelf the way a person does. Each order occupies a fixed slot in a 2x3
	 * grid, the label beneath it OCRs cleanly ("Urgent Mobilization", "Rush Job"), and an
	 * unavailable order wears an "On cooldown hh:mm:ss" banner across its cover. So the slot is
	 * located by its label and availability comes from the banner, not from whether an icon
	 * happened to match.</p>
	 */
	/** What the shelf told us about this order. */
	private enum ShelfVerdict {
		/** Cover is clean — the order can be opened and enacted right now. */
		AVAILABLE,
		/** Cover carried a countdown; the next run is already booked from it. */
		SCHEDULED_FROM_SCREEN,
		/** Nothing usable was read — fall back to the generic retry. */
		UNAVAILABLE
	}

	/** What the order's own page allowed. */
	private enum EnactOutcome { ENACTED, NOT_FOUND, DISABLED }

	private ShelfVerdict chooseOrderType() {
		sleepTask(1500);

		logInfo(routineLogChiefOrderLine("Scanning for Chief Order type: " + chiefOrderType.getDescription()));

		ShelfRead read = readShelf();
		OrderSlot slot = read.slot();
		if (slot == null) {
			if (read.labelsRead() >= SHELF_CONFIRMED_LABELS) {
				// The shelf is open and legible; this order simply is not on it. Fire Crystal Age
				// accounts lost Urgent Mobilization and Productivity Day, and those tasks retried
				// every ten minutes for weeks looking for books that no longer exist. Look again
				// after reset in case the shelf changes back, and say plainly why.
				LocalDateTime recheck = GameTimeUtils.dailyResetTime();
				reschedule(recheck);
				logWarning(routineLogChiefOrderLine(chiefOrderType.getDescription()
						+ " is not on this account's shelf (" + read.labelsRead()
						+ " other orders read) -- likely retired by the Fire Crystal Age. Checking again at "
						+ recheck + "; untick it to stop."));
				return ShelfVerdict.SCHEDULED_FROM_SCREEN;
			}
			logWarning(routineLogChiefOrderLine(chiefOrderType.getDescription()
					+ " label not found on the shelf — the menu may not have opened."));
			return ShelfVerdict.UNAVAILABLE;
		}

		String cover = readCoverText(slot.coverTopLeft(), slot.coverBottomRight());

		// An "Active hh:mm:ss" cover is the order currently running, not one
		// waiting to be used. The old check only looked for the word "cooldown", so an active
		// order read as available: the routine opened the book, found no Enact button (the page
		// says "On cooldown: 07:35:22"), and logged a failure. Come back when the effect ends —
		// the cover will have flipped to a cooldown banner by then and can be read properly.
		if (cover != null && cover.toLowerCase().contains("active")) {
			LocalDateTime recheck = remainingFrom(cover)
					.map(d -> LocalDateTime.now().plus(d).plusSeconds(30))
					.orElseGet(() -> LocalDateTime.now().plusMinutes(ERROR_RETRY_MINUTES_VALUE));
			reschedule(recheck);
			logInfo(routineLogChiefOrderLine(chiefOrderType.getDescription()
					+ " is currently active — re-reading the shelf at " + recheck + "."));
			return ShelfVerdict.SCHEDULED_FROM_SCREEN;
		}

		if (cover != null && cover.toLowerCase().contains("cooldown")) {
			// The banner states exactly when this order frees up, so use it
			// instead of the hard-coded cooldownHours guess. Previously a cooled-down order was
			// retried every 10 minutes regardless, and an available one could sit behind a stale
			// fixed interval for hours — the timer was on screen the whole time, unread.
			java.util.Optional<java.time.Duration> remaining = remainingFrom(cover);
			if (remaining.isPresent()) {
				LocalDateTime readyAt = LocalDateTime.now().plus(remaining.get());
				reschedule(readyAt);
				logInfo(routineLogChiefOrderLine(chiefOrderType.getDescription()
						+ " on cooldown — next run scheduled from the screen at " + readyAt + "."));
				return ShelfVerdict.SCHEDULED_FROM_SCREEN;
			}

			logInfo(routineLogChiefOrderLine(chiefOrderType.getDescription()
					+ " is on cooldown (" + cover.replaceAll("\\s+", " ").trim() + ")."));
			return ShelfVerdict.UNAVAILABLE;
		}

		logInfo(routineLogChiefOrderLine(chiefOrderType.getDescription() + " is available — opening it."));
		tapNear(slot.openPoint());
		sleepTask(1500);

		return ShelfVerdict.AVAILABLE;
	}

	/** Pulls the countdown out of a shelf banner, whether it reads "Active" or "On cooldown". */
	private java.util.Optional<java.time.Duration> remainingFrom(String cover) {
		java.util.regex.Matcher m = COOLDOWN_REMAINING.matcher(cover.replace('-', ':'));
		if (!m.find()) {
			return java.util.Optional.empty();
		}
		String stamp = m.group(1);
		if (stamp.indexOf(':') == 1) {
			stamp = "0" + stamp;
		}
		try {
			return java.util.Optional.of(GameTimeUtils.parseDuration(stamp));
		} catch (RuntimeException ex) {
			return java.util.Optional.empty();
		}
	}

	/** One position on the 2x3 Chief Order shelf. */
	record OrderSlot(PointData coverTopLeft, PointData coverBottomRight,
			PointData labelTopLeft, PointData labelBottomRight, PointData openPoint) {
	}

	/** The slot holding this order, if any, and how many labels on the shelf read at all. */
	private record ShelfRead(OrderSlot slot, int labelsRead) {
	}

	/**
	 * The six shelf positions, calibrated from a live 720x1280 capture. Left-column books span
	 * x 120-320, right column x 395-600; rows sit ~320px apart. Unchanged by the Fire Crystal Age,
	 * which swapped the books but kept the shelf.
	 */
	static final java.util.List<OrderSlot> SHELF_SLOTS = java.util.List.of(
			new OrderSlot(new PointData(120, 290), new PointData(320, 370),
					new PointData(110, 452), new PointData(335, 494), new PointData(220, 330)),
			new OrderSlot(new PointData(395, 290), new PointData(600, 370),
					new PointData(385, 452), new PointData(610, 494), new PointData(497, 330)),
			new OrderSlot(new PointData(120, 610), new PointData(320, 690),
					new PointData(110, 775), new PointData(335, 817), new PointData(220, 650)),
			new OrderSlot(new PointData(395, 610), new PointData(600, 690),
					new PointData(385, 775), new PointData(610, 817), new PointData(497, 650)),
			new OrderSlot(new PointData(120, 930), new PointData(320, 1010),
					new PointData(105, 1098), new PointData(335, 1140), new PointData(220, 970)),
			new OrderSlot(new PointData(395, 930), new PointData(600, 1010),
					new PointData(385, 1098), new PointData(610, 1140), new PointData(497, 970)));

	private ShelfRead readShelf() {
		int labelsRead = 0;
		for (OrderSlot slot : SHELF_SLOTS) {
			// Block mode, as for the covers: "Dawn Alliance Commendation" wraps onto two lines and
			// single-line OCR reads it as noise, so that order would never be found.
			String label = readCoverText(slot.labelTopLeft(), slot.labelBottomRight());
			if (label == null || label.isBlank()) {
				continue;
			}
			labelsRead++;
			if (chiefOrderType.labelMatches(label)) {
				return new ShelfRead(slot, labelsRead);
			}
		}
		return new ShelfRead(null, labelsRead);
	}

	/**
	 * Reads a book cover, which stacks the state over the countdown on two lines.
	 *
	 * <p>The default OCR path is single-line (page-segmentation mode 7) and returns nothing at all
	 * for a two-line crop — so a cooled-down order looked exactly like an available one and the
	 * routine walked straight into an enact attempt that could not succeed. Uniform-block mode
	 * reads the same crop cleanly.</p>
	 */
	private String readCoverText(PointData topLeft, PointData bottomRight) {
		try {
			return stringHelper.attemptRecognition(topLeft, bottomRight, 2, 200L,
					dev.frostguard.api.domain.OcrSettingsData.forTextBlock(),
					s -> s != null && !s.isBlank(), String::trim);
		} catch (Exception ex) {
			return null;
		}
	}

	private String readSlotText(PointData topLeft, PointData bottomRight) {
		try {
			return stringHelper.attemptRecognition(topLeft, bottomRight, 2, 200L, null,
					s -> s != null && !s.isBlank(), String::trim);
		} catch (Exception ex) {
			return null;
		}
	}

	/**
	 * True when the Enact button found in {@code area} is the live blue one rather than the greyed
	 * one. The label template matches both (100 and about 88), so the colour decides: the enabled
	 * button is about a quarter blue behind its white text, the greyed one not at all.
	 */
	static boolean enactEnabled(BufferedImage frame, AreaData area) {
		int width = area.bottomRight().getX() - area.topLeft().getX() + 1;
		int height = area.bottomRight().getY() - area.topLeft().getY() + 1;
		int blue = PixelStats.count(frame, area, GameColors::isActionBlue);
		return blue >= ENACT_BLUE_SHARE_MIN * width * height;
	}

private EnactOutcome enactOrderFlow() {
		sleepTask(1500);


		logInfo(routineLogChiefOrderLine("Scanning for Chief Order Enact button"));

		ImageSearchResultData enactButton = templateSearchHelper.locatePattern(
				TemplatesEnum.CHIEF_ORDER_ENACT_BUTTON,
				SearchConfig.builder().withMaxAttempts(1).withDelay(300L)
						.withThreshold(ENACT_SEARCH_THRESHOLD).build());

		if (!enactButton.isFound()) {
			logError(routineLogChiefOrderLine("Chief Order Enact button not detected"));
			return EnactOutcome.NOT_FOUND;
		}

		AreaData buttonArea = enactButton.getMatchedArea();
		BufferedImage frame = captureFrame();
		if (frame != null && buttonArea != null && !enactEnabled(frame, buttonArea)) {
			// Greyed out: the game refuses this order right now -- Comprehensive Care with nobody
			// sick, or not enough Contentment. Tapping it achieves nothing and would be logged as a
			// success that books the full cooldown. The page prints the reason in red above it.
			String reason = readSlotText(
					new PointData(80, Math.max(0, buttonArea.topLeft().getY() - 75)),
					new PointData(640, Math.max(1, buttonArea.topLeft().getY() - 20)));
			logInfo(routineLogChiefOrderLine(chiefOrderType.getDescription() + " Enact button is greyed out"
					+ (reason != null && !reason.isBlank() ? " (" + reason + ")" : "") + "."));
			pressBack();
			sleepTask(1000);
			return EnactOutcome.DISABLED;
		}

		logInfo(routineLogChiefOrderLine("Enact button detected. Pressing to enact order"));
		tapInside(enactButton);
		sleepTask(1000);


		pressBack();
		sleepTask(5000);


		logInfo(routineLogChiefOrderLine(chiefOrderType.getDescription() + " activated finished cleanly"));
		return EnactOutcome.ENACTED;
	}

	private BufferedImage captureFrame() {
		try {
			RawImageData raw = emuManager.captureScreen(EMULATOR_NUMBER);
			return raw == null ? null : ImageConverter.toBufferedImage(raw);
		} catch (Exception ex) {
			logWarning(routineLogChiefOrderLine("Screen capture failed: " + ex.getMessage()));
			return null;
		}
	}

private void queueNextRun() {
		LocalDateTime nextExecutionTime = LocalDateTime.now()
				.plusHours(chiefOrderType.getCooldownHours());

		reschedule(nextExecutionTime);

		logInfo(routineLogChiefOrderLine("Task completed finished cleanly. Next execution in " +
				chiefOrderType.getCooldownHours() + " hours"));
	}
}
