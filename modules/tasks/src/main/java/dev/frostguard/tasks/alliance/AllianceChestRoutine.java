package dev.frostguard.tasks.alliance;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.engine.alliance.AllianceGiftLedger;
import dev.frostguard.engine.alliance.AllianceGiftLedger.GiftRow;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.ocr.ChatTextReader;
import dev.frostguard.vision.ocr.OnnxOcrReader;
import dev.frostguard.vision.ocr.PaddleOcrClient;
import dev.frostguard.vision.ocr.TextLine;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

public class AllianceChestRoutine extends DelayedTask {

private static final int TAB_CHANGE_WAIT_TIME_MS = 500;

private static final int CLAIM_WAIT_TIME_MS = 1500;

private static final int SHORT_WAIT_TIME_MS = 300;

public AllianceChestRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
		super(profile, tpDailyTask);
	}

@Override
	protected void execute() {

		if (!reachAllianceScreen()) {
			deferAndExit("Failed to navigate to alliance screen");
			return;
		}

		if (!openUpAllianceChestScreen()) {
			deferAndExit("Failed to open alliance chest screen");
			return;
		}


		int allianceGiftsCollected = 0;
		allianceGiftsCollected += gatherLootChests();
		allianceGiftsCollected += gatherAllianceGifts();
		allianceGiftsCollected += gatherHonorChest();

		// Count what was actually collected this pass, not a per-run +1. The
		// per-row individual-gift loop reports a true claimed count; the loot claim and (config-
		// gated) honor claim are single blind taps with no on-screen success signal, so each is
		// counted as 1 when its claim action is performed.
		if (allianceGiftsCollected > 0) {
			StatisticsService.obtain().addToCounter(profile, "Alliance Gifts Collected", allianceGiftsCollected);
		}


		restoreHomeScreen();


		queueNextRun();
	}

private String routineLogAllianceChestLine(String note) {
        return "AllianceChestRoutine | " + note;
    }

private boolean openUpAllianceChestScreen() {
		ImageSearchResultData allianceChestResult = templateSearchHelper.locatePattern(
				TemplatesEnum.ALLIANCE_CHEST_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);
		if (!allianceChestResult.isFound()) {
			logWarning(routineLogAllianceChestLine("Alliance chest button not detected."));
			return false;
		}

		tapInside(allianceChestResult);
		sleepTask(TAB_CHANGE_WAIT_TIME_MS);
		return true;
	}

private int gatherIndividualGifts() {
		int giftsClaimed = 0;
		int consecutiveFailures = 0;
		int maxConsecutiveFailures = 3;


		while (consecutiveFailures < maxConsecutiveFailures) {
			ImageSearchResultData claimButton = templateSearchHelper.locatePattern(
					TemplatesEnum.ALLIANCE_CHEST_CLAIM_BUTTON,
					SearchConfigConstants.DEFAULT_SINGLE);

			if (claimButton.isFound()) {
				logDebug(routineLogAllianceChestLine("Collecting individual gift #" + (giftsClaimed + 1)));
				tapInside(claimButton);
				sleepTask(CLAIM_WAIT_TIME_MS);
				giftsClaimed++;
				consecutiveFailures = 0;


				dismissPopupIfPresent();
			} else {
				consecutiveFailures++;


				if (consecutiveFailures >= maxConsecutiveFailures) {
					logDebug(routineLogAllianceChestLine("Zero additional individual gifts detected."));
					break;
				}
			}
		}

		if (giftsClaimed > 0) {
			logInfo(routineLogAllianceChestLine("Successfully collected " + giftsClaimed + " individual gifts."));
		} else {
			logInfo(routineLogAllianceChestLine("Zero individual gifts to claim."));
		}

		return giftsClaimed;
	}

private void dismissPopupIfPresent() {


		tapInside(new PointData(578, 1180), new PointData(641, 1200), 2, 200);
		sleepTask(SHORT_WAIT_TIME_MS);
	}

private boolean reachAllianceScreen() {
		logInfo(routineLogAllianceChestLine("Moving to alliance screen"));
		tapInside(new PointData(493, 1187), new PointData(561, 1240));
		sleepTask(3000);


		ImageSearchResultData allianceVerification = templateSearchHelper.locatePattern(
				TemplatesEnum.ALLIANCE_CHEST_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);
		return allianceVerification.isFound();
	}

private int gatherAllianceGifts() {
		logInfo(routineLogAllianceChestLine("Entering alliance gifts section."));
		tapInside(new PointData(410, 375), new PointData(626, 420));
		sleepTask(TAB_CHANGE_WAIT_TIME_MS);

		if (Boolean.TRUE.equals(profile.getConfig(ConfigurationKeyEnum.ALLIANCE_SPEND_ENABLED_BOOL, Boolean.class))) {
			logGiftList();
		}

		ImageSearchResultData claimAllButton = templateSearchHelper.locatePattern(
				TemplatesEnum.ALLIANCE_CHEST_CLAIM_ALL_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);

		int giftsCollected;
		if (claimAllButton.isFound()) {
			// 'Claim All' is only present when at least one gift is claimable, so its presence
			// proves ≥1 real claim. Count the batch as 1 (the per-gift breakdown isn't exposed here).
			logInfo(routineLogAllianceChestLine("'Claim All' button detected. Collecting all gifts."));
			tapInside(claimAllButton);
			sleepTask(CLAIM_WAIT_TIME_MS);


			dismissPopupIfPresent();
			giftsCollected = 1;
		} else {
			logInfo(routineLogAllianceChestLine("Zero 'Claim All' button for gifts. Inspecting for individual gifts."));
			giftsCollected = gatherIndividualGifts();
		}
		sleepTask(SHORT_WAIT_TIME_MS);
		return giftsCollected;
	}

private static final double OCR_MIN_CONFIDENCE = 0.60;

private static final int KEEP_PASS_PHOTOS_DAYS = 7;

/**
 * A short, slow drag. The list travels about 1.5x the drag (a 300px drag moved it 295 to 460px
 * on the live passes, leaving under one card of overlap); 160px keeps about two cards shared.
 */
private static final int SWIPE_FROM_Y = 1000;
private static final int SWIPE_TO_Y = 840;
private static final int SWIPE_MS = 1500;

/** Below this the list did not move, i.e. the bottom has been reached. */
private static final int END_OF_LIST_SHIFT_PX = 8;

/**
 * Photographs and reads the whole Alliance Gifts list before anything is claimed, and logs the
 * purchases that arrived since the last pass. Never stops the claim: any failure here is logged
 * and the pass carries on.
 */
private void logGiftList() {
		boolean anonymise = Boolean.TRUE.equals(
				profile.getConfig(ConfigurationKeyEnum.ALLIANCE_SPEND_ANONYMIZE_BOOL, Boolean.class));
		Integer configuredScrolls = profile.getConfig(ConfigurationKeyEnum.ALLIANCE_SPEND_MAX_SCROLLS_INT, Integer.class);
		int maxScrolls = configuredScrolls == null || configuredScrolls < 1 ? 40 : configuredScrolls;
		LocalDateTime started = LocalDateTime.now();
		AllianceGiftLedger ledger = AllianceGiftLedger.forWorkspace(Paths.get(System.getProperty("user.dir")));
		Path passDir = ledger.passDir(started);
		ChatTextReader reader = null;
		int screens = 0;
		try {
			reader = openReader();
			if (reader == null) {
				logWarning(routineLogAllianceChestLine("Alliance Spend | no OCR reader is up; saving photographs only."));
			}
			java.nio.file.Files.createDirectories(passDir);
			Map<String, List<TextLine>> linesByFrame = new LinkedHashMap<>();
			List<GiftRow> rows = new ArrayList<>();
			BufferedImage previous = null;
			int scrolledBy = 0;
			boolean reachedEnd = false;
			boolean lostTrack = false;
			for (int i = 0; i <= maxScrolls; i++) {
				BufferedImage image = screenNow();
				if (image == null) {
					logWarning(routineLogAllianceChestLine("Alliance Spend | screen capture failed at screen " + i + "."));
					lostTrack = true;
					break;
				}
				if (previous != null) {
					int shift = AllianceGiftLedger.scrollShift(previous, image);
					if (shift < 0) {
						logWarning(routineLogAllianceChestLine("Alliance Spend | screen " + i
								+ " does not line up with the one before; this pass will not be logged."));
						ImageIO.write(image, "png", passDir.resolve(String.format("%03d-unaligned.png", i)).toFile());
						lostTrack = true;
						break;
					}
					if (shift <= END_OF_LIST_SHIFT_PX) {
						reachedEnd = true;
						break;
					}
					scrolledBy += shift;
				}
				String name = String.format("%03d.png", i);
				ImageIO.write(image, "png", passDir.resolve(name).toFile());
				screens++;
				if (reader != null) {
					List<TextLine> lines = reader.read(image, AllianceGiftLedger.TEXT_LEFT, AllianceGiftLedger.LIST_TOP,
							AllianceGiftLedger.TEXT_RIGHT, AllianceGiftLedger.LIST_BOTTOM, "en", OCR_MIN_CONFIDENCE);
					linesByFrame.put(name, lines);
					rows.addAll(AllianceGiftLedger.parseFrame(lines, LocalDateTime.now(), name, scrolledBy));
				}
				previous = image;
				swipe(new PointData(360, SWIPE_FROM_Y), new PointData(360, SWIPE_TO_Y), SWIPE_MS);
				sleepTask(700);
			}
			List<GiftRow> list = AllianceGiftLedger.stitch(rows);
			ledger.writePassEvidence(passDir, linesByFrame, list);
			if (reader == null || lostTrack) {
				logWarning(routineLogAllianceChestLine("Alliance Spend | " + screens + " screens saved, nothing logged. Frames: "
						+ passDir));
			} else {
				if (!reachedEnd) {
					logWarning(routineLogAllianceChestLine("Alliance Spend | stopped after " + screens
							+ " screens without reaching the bottom; raise Max scrolls if the list is longer."));
				}
				AllianceGiftLedger.PassResult result = ledger.recordPass(list, anonymise);
				if (!result.aligned()) {
					logWarning(routineLogAllianceChestLine("Alliance Spend | the last pass's newest rows are not in this list;"
							+ " nothing logged this pass, list kept as the new reference. Frames: " + passDir));
				}
				logInfo(routineLogAllianceChestLine("Alliance Spend | " + screens + " screens, " + result.listed()
						+ " purchases in the list, " + result.fresh() + " new" + (result.firstPass() ? " (first pass: backlog)" : "")
						+ ". Frames: " + passDir));
			}
			ledger.prunePasses(LocalDateTime.now(), KEEP_PASS_PHOTOS_DAYS);
		} catch (RuntimeException | java.io.IOException e) {
			logWarning(routineLogAllianceChestLine("Alliance Spend | gift logging failed: " + e + ". Claiming anyway."));
		} finally {
			if (reader instanceof OnnxOcrReader onnx) {
				onnx.close();
			}
		}
		for (int i = 0; i < screens / 2 + 2; i++) {
			swipe(new PointData(360, 600), new PointData(360, 1080), 250);
		}
		sleepTask(SHORT_WAIT_TIME_MS);
	}

private ChatTextReader openReader() {
		Path here = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
		for (Path up = here; up != null; up = up.getParent()) {
			for (Path candidate : new Path[] {up.resolve("lib").resolve("ocr-models"),
					up.resolve("tools").resolve("ocr").resolve("onnx")}) {
				if (OnnxOcrReader.isAvailable(candidate)) {
					try {
						return new OnnxOcrReader(candidate);
					} catch (Exception e) {
						logWarning(routineLogAllianceChestLine("Alliance Spend | could not open the in-process reader: "
								+ e.getMessage()));
					}
				}
			}
		}
		PaddleOcrClient paddle = new PaddleOcrClient("127.0.0.1", 6975);
		return paddle.isUp() ? paddle : null;
	}

private BufferedImage screenNow() {
		RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
		if (frame == null || !frame.isValid()) {
			return null;
		}
		return ImageConverter.toBufferedImage(frame);
	}

private void restoreHomeScreen() {
		logInfo(routineLogAllianceChestLine("Returning to home screen."));
		pressBack();
		sleepTask(SHORT_WAIT_TIME_MS);
		pressBack();
		sleepTask(SHORT_WAIT_TIME_MS);


	}

private int gatherLootChests() {
		logInfo(routineLogAllianceChestLine("Collecting loot chests."));
		tapInside(new PointData(56, 375), new PointData(320, 420));
		sleepTask(TAB_CHANGE_WAIT_TIME_MS);


		tapNear(new PointData(360, 1204));
		sleepTask(CLAIM_WAIT_TIME_MS);


		dismissPopupIfPresent();
		sleepTask(SHORT_WAIT_TIME_MS);

		// Blind claim tap (no on-screen success signal available here) — count as one loot claim.
		return 1;
	}

private int gatherHonorChest() {
		boolean honorChestEnabled = profile.getConfig(ConfigurationKeyEnum.ALLIANCE_HONOR_CHEST_BOOL, Boolean.class);

		if (honorChestEnabled) {
			logInfo(routineLogAllianceChestLine("Collecting honor chest."));
			tapInside(new PointData(320, 200), new PointData(400, 250));
			sleepTask(TAB_CHANGE_WAIT_TIME_MS);


			dismissPopupIfPresent();
			// Blind claim tap gated only by config — count as one honor claim when enabled.
			return 1;
		} else {
			logInfo(routineLogAllianceChestLine("Honor chest collection is disabled. Skipping."));
			return 0;
		}
	}

private void queueNextRun() {
		int offsetMinutes = profile.getConfig(ConfigurationKeyEnum.ALLIANCE_CHESTS_OFFSET_INT, Integer.class);
		LocalDateTime nextExecutionTime = LocalDateTime.now().plusMinutes(offsetMinutes);
		nextExecutionTime = nextExecutionTime.isAfter(GameTimeUtils.dailyResetTime()) ? GameTimeUtils.dailyResetTime()
				: nextExecutionTime;
		reschedule(nextExecutionTime);
		logInfo(routineLogAllianceChestLine("Alliance chest task completed. Next run at: " + nextExecutionTime.format(DATETIME_FORMATTER)));
	}

private void deferAndExit(String reason) {
		logWarning(routineLogAllianceChestLine(reason + ". Planning next run task to run in 5 minutes."));
		LocalDateTime nextExecutionTime = LocalDateTime.now().plusMinutes(5);
		reschedule(nextExecutionTime);
	}
}
