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
import dev.frostguard.engine.alliance.AllianceSpendReport;
import dev.frostguard.engine.alliance.PackLinks;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import javax.imageio.ImageIO;

public class AllianceChestRoutine extends DelayedTask {

private static final int TAB_CHANGE_WAIT_TIME_MS = 500;

private static final int CLAIM_WAIT_TIME_MS = 1500;

private static final int SHORT_WAIT_TIME_MS = 300;

/** Set when Back from a followed pack link did not bring the gift list back; nothing more is tapped blind. */
private boolean lostTheList;

/** Pack names on the list this pass read, as the pass spelled them; the link walk checks its reads against them. */
private List<String> passPacks = List.of();

public AllianceChestRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
		super(profile, tpDailyTask);
	}

@Override
	protected void execute() {
		lostTheList = false;
		passPacks = List.of();

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
		if (!lostTheList) {
			allianceGiftsCollected += gatherHonorChest();
		}

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
		openGiftsTab();

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
		if (!lostTheList
				&& Boolean.TRUE.equals(profile.getConfig(ConfigurationKeyEnum.ALLIANCE_SPEND_ENABLED_BOOL, Boolean.class))) {
			followPackLinks();
		}
		return giftsCollected;
	}

private void openGiftsTab() {
		tapInside(new PointData(410, 375), new PointData(626, 420));
		sleepTask(TAB_CHANGE_WAIT_TIME_MS);
	}

/**
 * Brings the Alliance Gifts list back after something took the game off it. A gift link that
 * opens an event page returns, on Back, to the Alliance screen rather than to the list (50 of the
 * 51 links lost from 2026-11-09 to 11-11; the other went to the city), and further Backs from
 * there reach Home and then the game's "Quit game?" dialog. So: no more Backs. From the Alliance
 * screen, Chests is tapped again; from anywhere else the navigator goes Home first. True once the
 * list shows again.
 */
private boolean reopenGiftList(BufferedImage listScreen) {
		ImageSearchResultData chests = templateSearchHelper.locatePattern(
				TemplatesEnum.ALLIANCE_CHEST_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);
		if (!chests.isFound()) {
			navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
			if (!reachAllianceScreen()) {
				return false;
			}
		}
		if (!openUpAllianceChestScreen()) {
			return false;
		}
		openGiftsTab();
		BufferedImage now = screenNow();
		return now != null && PackLinks.sameHeader(listScreen, now);
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

/**
 * Screens a pass may read. The list ran past 41 screens (55 cards) three times on 2026-10-27,
 * when the setting was 40, so a lower saved setting is raised to this.
 */
private static final int MIN_SCROLLS = 80;

/** Tries at one pass; the list redraws at the top when a gift arrives mid-pass. */
private static final int MAX_PASS_ATTEMPTS = 3;

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
		int maxScrolls = Math.max(MIN_SCROLLS, configuredScrolls == null ? 0 : configuredScrolls);
		LocalDateTime started = LocalDateTime.now();
		AllianceGiftLedger ledger = AllianceGiftLedger.forWorkspace(dev.frostguard.api.runtime.WorkspacePaths.current().root());
		Path passDir = ledger.passDir(started);
		ChatTextReader reader = null;
		int screens = 0;
		boolean offTheList = false;
		BufferedImage listScreen = null;
		try {
			reader = openReader();
			if (reader == null) {
				logWarning(routineLogAllianceChestLine("Alliance Spend | no OCR reader is up; saving photographs only."));
			}
			java.nio.file.Files.createDirectories(passDir);
			Map<String, List<TextLine>> linesByFrame = new LinkedHashMap<>();
			List<GiftRow> rows = new ArrayList<>();
			// The longest stretch read before the list was lost, in case no try reaches the bottom.
			List<GiftRow> bestPartial = List.of();
			Map<String, List<TextLine>> bestPartialLines = Map.of();
			boolean reachedEnd = false;
			boolean lostTrack = false;
			for (int attempt = 0; attempt < MAX_PASS_ATTEMPTS; attempt++) {
				linesByFrame.clear();
				rows.clear();
				reachedEnd = false;
				lostTrack = false;
				AllianceGiftLedger.Loss loss = AllianceGiftLedger.Loss.NONE;
				String prefix = attempt == 0 ? "" : "r" + attempt + "-";
				BufferedImage previous = null;
				BufferedImage top = null;
				int scrolledBy = 0;
				int screensThisTry = 0;
				for (int i = 0; i <= maxScrolls; i++) {
					BufferedImage image = screenNow();
					if (image == null) {
						logWarning(routineLogAllianceChestLine("Alliance Spend | screen capture failed at screen " + i + "."));
						lostTrack = true;
						break;
					}
					if (top == null) {
						top = image;
						if (listScreen == null) {
							listScreen = image;
						}
					}
					if (previous != null) {
						AllianceGiftLedger.ShiftMatch match = AllianceGiftLedger.scrollMatch(previous, image,
								AllianceGiftLedger.EXPECTED_TRAVEL_PX);
						if (match.shift() < 0 && AllianceGiftLedger.classify(top, image, match) != AllianceGiftLedger.Loss.REDRAWN) {
							// A toast ("273 Troop(s) healed!", "Power +20,293") can cover the list for a
							// moment (2026-11-10 19:23); look once more before giving up.
							sleepTask(OVERLAY_WAIT_MS);
							BufferedImage again = screenNow();
							if (again != null) {
								AllianceGiftLedger.ShiftMatch second = AllianceGiftLedger.scrollMatch(previous, again,
										AllianceGiftLedger.EXPECTED_TRAVEL_PX);
								if (second.shift() >= 0) {
									image = again;
									match = second;
								}
							}
						}
						if (match.shift() < 0) {
							ImageIO.write(image, "png", passDir.resolve(prefix + String.format("%03d-unaligned.png", i)).toFile());
							lostTrack = true;
							loss = AllianceGiftLedger.classify(top, image, match);
							logWarning(routineLogAllianceChestLine("Alliance Spend | screen " + i + " does not line up with the one before"
									+ loss.why() + "."));
							break;
						}
						if (match.shift() <= END_OF_LIST_SHIFT_PX) {
							// One drag in the middle of the list was once ignored (2026-10-27 13:49, 7 of 50
							// cards); only a second drag that also does not move it is the bottom.
							swipe(new PointData(330, SWIPE_FROM_Y), new PointData(330, SWIPE_TO_Y), SWIPE_MS);
							sleepTask(700);
							BufferedImage again = screenNow();
							AllianceGiftLedger.ShiftMatch retry = again == null ? match
									: AllianceGiftLedger.scrollMatch(previous, again, AllianceGiftLedger.EXPECTED_TRAVEL_PX);
							if (retry.shift() >= 0 && retry.shift() <= END_OF_LIST_SHIFT_PX) {
								reachedEnd = true;
								break;
							}
							if (retry.shift() < 0) {
								ImageIO.write(again, "png", passDir.resolve(prefix + String.format("%03d-unaligned.png", i)).toFile());
								lostTrack = true;
								loss = AllianceGiftLedger.classify(top, again, retry);
								logWarning(routineLogAllianceChestLine("Alliance Spend | screen " + i
										+ " does not line up after a second drag" + loss.why() + "."));
								break;
							}
							image = again;
							match = retry;
						}
						scrolledBy += match.shift();
					}
					String name = prefix + String.format("%03d.png", i);
					ImageIO.write(image, "png", passDir.resolve(name).toFile());
					screens++;
					screensThisTry++;
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
				if (lostTrack) {
					List<GiftRow> partial = AllianceGiftLedger.stitch(rows);
					if (partial.size() > bestPartial.size()) {
						bestPartial = partial;
						bestPartialLines = new LinkedHashMap<>(linesByFrame);
					}
				}
				if (loss == AllianceGiftLedger.Loss.LEFT_THE_LIST) {
					offTheList = true;
					break;
				}
				boolean tryAgain = loss == AllianceGiftLedger.Loss.REDRAWN || loss == AllianceGiftLedger.Loss.MISALIGNED;
				if (!tryAgain || attempt + 1 >= MAX_PASS_ATTEMPTS) {
					break;
				}
				// A redrawn list starts again from the top; so does the pass. So does a pass that lost its
				// place on a misread screen: on 2026-11-11 12:54 one stopped after 5 purchases and logged nothing.
				logInfo(routineLogAllianceChestLine("Alliance Spend | starting the pass again from the top."));
				for (int k = 0; k < screensThisTry / 2 + 3; k++) {
					swipe(new PointData(360, 600), new PointData(360, 1080), 250);
				}
				sleepTask(1000);
				// From mid-list the flicks may fall short; make sure the next try starts at the top.
				for (int extra = 0; extra < 5; extra++) {
					BufferedImage check = screenNow();
					if (check == null || listScreen == null || AllianceGiftLedger.backAtTop(listScreen, check)) {
						break;
					}
					swipe(new PointData(360, 600), new PointData(360, 1080), 250);
					sleepTask(700);
				}
			}
			List<GiftRow> list = lostTrack ? bestPartial : AllianceGiftLedger.stitch(rows);
			ledger.writePassEvidence(passDir, lostTrack ? bestPartialLines : linesByFrame, list);
			// Only a whole list says which packs are on it; after a cut-short pass the walk trusts its own reads.
			passPacks = lostTrack || !reachedEnd ? List.of()
					: list.stream().map(GiftRow::pack).filter(java.util.Objects::nonNull).distinct().toList();
			if (reader == null || list.isEmpty()) {
				logWarning(routineLogAllianceChestLine("Alliance Spend | " + screens + " screens saved, nothing logged. Frames: "
						+ passDir));
			} else if (lostTrack) {
				// The newest purchases are at the top, so the stretch read before the list was lost
				// is enough whenever it reaches the last logged list (recordPass checks that).
				AllianceGiftLedger.PassResult result = ledger.recordPass(list, anonymise, false);
				if (result.aligned()) {
					logInfo(routineLogAllianceChestLine("Alliance Spend | " + screens + " screens, part of the list read ("
							+ result.listed() + " purchases) before it was lost; it reaches the last logged list, "
							+ result.fresh() + " new. Frames: " + passDir));
				} else {
					logWarning(routineLogAllianceChestLine("Alliance Spend | " + screens + " screens saved, nothing logged: the "
							+ result.listed() + " purchases read before the list was lost do not reach the last logged list."
							+ " Frames: " + passDir));
				}
			} else {
				if (!reachedEnd) {
					logWarning(routineLogAllianceChestLine("Alliance Spend | stopped after " + screens
							+ " screens without reaching the bottom; raise Max scrolls if the list is longer."));
				}
				AllianceGiftLedger.PassResult result = ledger.recordPass(list, anonymise, reachedEnd);
				if (result.afterGap()) {
					logWarning(routineLogAllianceChestLine("Alliance Spend | the last logged list has left the gift list;"
							+ " logging all " + result.fresh() + " listed purchases as new, marked afterGap. Frames: " + passDir));
				} else if (!result.aligned()) {
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
		if (offTheList) {
			// The game left the Chests screen mid-pass (2026-11-10 18:14 it was in the city); swiping
			// there does nothing useful, so the list is reopened instead.
			if (listScreen == null || !reopenGiftList(listScreen)) {
				lostTheList = true;
				logWarning(routineLogAllianceChestLine("Alliance Spend | the game left the gift list and it could not be"
						+ " reopened; claiming nothing more this run."));
			}
			return;
		}
		for (int i = 0; i < screens / 2 + 2; i++) {
			swipe(new PointData(360, 600), new PointData(360, 1080), 250);
		}
		sleepTask(SHORT_WAIT_TIME_MS);
	}

/** How long a toast over the list is given to fade before a screen is read again. */
private static final int OVERLAY_WAIT_MS = 1500;

/** Links followed per pass; any further unknown packs wait for the next pass, an hour later. */
private static final int MAX_LINKS_PER_PASS = 5;

private static final int LINK_PAGE_WAIT_MS = 2500;

/** A card whose text ends this far above the list's bottom edge is whole, so a missing link is real. */
private static final int WHOLE_CARD_PX = 110;

/**
 * For every pack on the gift list whose price is not known, follows the pack name's link, saves
 * and reads the page it opens, and presses Back; nothing on that page is tapped. Walks the whole
 * list screen by screen, up to {@link #MAX_LINKS_PER_PASS} links a pass. A pack whose name is not
 * a link is recorded as not on sale; both that and a page with no price are retried a day later
 * (see {@link PackLinks#due}). If Back does not show the list again, it is reopened
 * ({@link #reopenGiftList}); only if that fails does the pass stop and set {@link #lostTheList}.
 */
private void followPackLinks() {
		ChatTextReader reader = null;
		int scrolls = 0;
		try {
			Path workspace = dev.frostguard.api.runtime.WorkspacePaths.current().root();
			PackLinks links = new PackLinks(AllianceGiftLedger.forWorkspace(workspace).root());
			BiPredicate<String, String> known = AllianceSpendReport.knownPrice(workspace);
			reader = openReader();
			if (reader == null) {
				return;
			}
			Integer configuredScrolls = profile.getConfig(ConfigurationKeyEnum.ALLIANCE_SPEND_MAX_SCROLLS_INT, Integer.class);
			int maxScrolls = Math.max(MIN_SCROLLS, configuredScrolls == null ? 0 : configuredScrolls);
			Set<String> handled = new HashSet<>();
			int followed = 0;
			BufferedImage screen = screenNow();
			while (screen != null && followed < MAX_LINKS_PER_PASS && scrolls <= maxScrolls) {
				List<GiftRow> rows = AllianceGiftLedger.parseFrame(reader.read(screen, AllianceGiftLedger.TEXT_LEFT,
						AllianceGiftLedger.LIST_TOP, AllianceGiftLedger.TEXT_RIGHT, AllianceGiftLedger.LIST_BOTTOM, "en",
						OCR_MIN_CONFIDENCE), LocalDateTime.now(), "links", 0);
				List<PackLinks.Target> targets = PackLinks.find(screen);
				BufferedImage backOnList = null;
				for (GiftRow row : rows) {
					String pack = row.pack();
					if (pack == null) {
						continue;
					}
					if (!passPacks.isEmpty()) {
						String spelled = PackLinks.passSpelling(pack, passPacks);
						if (spelled == null) {
							// No pack of that name on the list the pass read: a misread of this one screen, so
							// the card waits for the next screen's read (or the next pass, if it is truly new).
							logDebug(routineLogAllianceChestLine("Alliance Spend | \"" + pack
									+ "\" is not a pack this pass read; not following it on this read."));
							continue;
						}
						pack = spelled;
					}
					if (!handled.add(packKey(pack))) {
						continue;
					}
					LocalDateTime now = LocalDateTime.now();
					if (known.test(pack, row.tier()) || !links.due(pack, now)) {
						continue;
					}
					PackLinks.Target target = PackLinks.inCard(targets, row.listY());
					if (target == null) {
						if (row.listY() + WHOLE_CARD_PX < AllianceGiftLedger.LIST_BOTTOM) {
							links.recordNotALink(pack, now);
							logInfo(routineLogAllianceChestLine("Alliance Spend | \"" + pack
									+ "\" is not a link (not on sale now); will look again tomorrow."));
						} else {
							handled.remove(packKey(pack));
						}
						continue;
					}
					tapInside(new PointData(target.x() - 4, target.y() - 2), new PointData(target.x() + 4, target.y() + 2));
					sleepTask(LINK_PAGE_WAIT_MS);
					BufferedImage page = screenNow();
					List<TextLine> pageLines = page == null ? List.of()
							: reader.read(page, 0, 0, page.getWidth(), page.getHeight(), "en", OCR_MIN_CONFIDENCE);
					PackLinks.Page seen = links.record(pack, page, pageLines, now);
					followed++;
					logInfo(routineLogAllianceChestLine("Alliance Spend | followed the link of \"" + pack + "\": price "
							+ seen.price() + " (all on page " + seen.prices() + "), page saved to " + seen.frame()));
					// One Back closes a pack pop-up or a Deals tab and shows the list again. From an event
					// page it lands on the Alliance screen instead, and a second Back from there heads for
					// the quit dialog, so the list is reopened rather than Backed into.
					pressBack();
					sleepTask(1500);
					BufferedImage back = screenNow();
					if (back == null || !PackLinks.sameHeader(screen, back)) {
						links.saveLostScreen(pack, back, 1);
						if (!reopenGiftList(screen)) {
							lostTheList = true;
							logWarning(routineLogAllianceChestLine("Alliance Spend | could not get back to the gift list after"
									+ " the link of \"" + pack + "\"; stopping here. Screen: pack-links/"));
							return;
						}
						logInfo(routineLogAllianceChestLine("Alliance Spend | the link of \"" + pack
								+ "\" left the gift list; reopened it."));
						back = screenNow();
						if (back == null) {
							return;
						}
					}
					// Back may leave the list scrolled elsewhere, or reopened at the top; read it again where it is.
					backOnList = back;
					break;
				}
				if (backOnList != null) {
					screen = backOnList;
					continue;
				}
				swipe(new PointData(360, SWIPE_FROM_Y), new PointData(360, SWIPE_TO_Y), SWIPE_MS);
				sleepTask(700);
				scrolls++;
				BufferedImage next = screenNow();
				if (next == null) {
					break;
				}
				int shift = AllianceGiftLedger.scrollShift(screen, next);
				if (shift >= 0 && shift <= END_OF_LIST_SHIFT_PX) {
					break;
				}
				screen = next;
			}
		} catch (RuntimeException | java.io.IOException e) {
			logWarning(routineLogAllianceChestLine("Alliance Spend | following pack links failed: " + e));
		} finally {
			if (reader instanceof OnnxOcrReader onnx) {
				onnx.close();
			}
		}
		if (!lostTheList) {
			for (int i = 0; i < scrolls / 2 + 2; i++) {
				swipe(new PointData(360, 600), new PointData(360, 1080), 250);
			}
			sleepTask(SHORT_WAIT_TIME_MS);
		}
	}

/** A pack name with case, spacing and punctuation dropped, so two reads of one pack match. */
private static String packKey(String pack) {
		return pack.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
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
		// Two fixed Backs raised the game's quit dialog whenever the screen was already Home.
		navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
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
