package dev.frostguard.tasks.exploration;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SearchConfigConstants;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;

/**
 * Task responsible for completing daily labyrinth challenges.
 * This task navigates to the labyrinth menu and executes appropriate challenges
 * in the currently active Land of Heroes dungeon, or (when
 * LABYRINTH_FORMATION_TEST_BOOL is set) drives the free formation-setup-only flow
 * across every Labyrinth zone instead of battling.
 */
public class DailyLabyrinthRoutine extends DelayedTask {

    // =========================== CONSTANTS ===========================

    // Navigation points
    private static final PointData SKIP_BUTTON = new PointData(71, 827);
    private static final PointData RESULT_SKIP_BUTTON = new PointData(640, 175);

    // Timing constants
    private static final int MENU_NAVIGATION_DELAY = 1000;
    private static final int TAB_SWITCH_DELAY = 500;
    private static final int BATTLE_COMPLETION_DELAY = 3000;
    private static final int LABYRINTH_LOAD_DELAY = 2000;

    // ===================================================================
    // Land-of-Heroes formation-setup flow (matt/2026-08-10)
    // ===================================================================
    // ALL coordinates below are BEST-ESTIMATE from 720x1280 screenshots and are marked
    // "LIVE-TUNE" — the orchestrator will calibrate each one via ADB before this runs for real.
    // Gated behind LABYRINTH_FORMATION_TEST_BOOL. This flow sets up (and SAVES) the formation
    // only; it deliberately STOPS before Deploy/battle, because battling burns a daily attempt
    // while formation-setup is free.

    // -- Land of Heroes stage screen --
    /** LIVE-TUNE: "Challenge" button on the Land-of-Heroes stage screen. */
    private static final PointData LOH_CHALLENGE_BTN = new PointData(360, 1218);

    // -- Labyrinth map: Land of Heroes zone banner (purple) + its label for the open/locked check --
    /** LIVE-TUNE: tap point on the "Land of Heroes" purple banner to enter the zone. */
    private static final PointData LOH_ZONE_BANNER = new PointData(460, 337);
    /** LIVE-TUNE: OCR region over the Land-of-Heroes label (name + timer). A LOCKED zone's line reads
     *  "Opens in …"; an OPEN zone shows just name + a bare countdown. matt's rule: "Opens in" ⇒ skip. */
    private static final PointData LOH_ZONE_LABEL_TL = new PointData(358, 302);
    private static final PointData LOH_ZONE_LABEL_BR = new PointData(562, 372);

    // matt/2026-08-13: same formation-setup extended to Cave of Monsters and Charm Mine ("we're up
    // to like three now"). Calibrated live via ADB from the Labyrinth zone map (same map screen as
    // Land of Heroes, different scroll position). Banner = tap point on the zone's structure
    // graphic; label = OCR box over its name+timer banner, same "Opens in" open/locked rule.
    private static final PointData CAVE_ZONE_BANNER = new PointData(195, 340);
    private static final PointData CAVE_ZONE_LABEL_TL = new PointData(80, 465);
    private static final PointData CAVE_ZONE_LABEL_BR = new PointData(330, 525);
    private static final PointData CHARM_ZONE_BANNER = new PointData(505, 550);
    private static final PointData CHARM_ZONE_LABEL_TL = new PointData(390, 595);
    private static final PointData CHARM_ZONE_LABEL_BR = new PointData(640, 655);

    // matt/2026-08-16: Gaia Heart -- live-calibrated via ADB the same day (a Sunday, its actual open
    // rotation), not guessed. Confirmed it renders on the DEFAULT unscrolled map view (no scroll
    // needed) at the bottom of the frame, at least while open -- unconfirmed whether it still renders
    // here on a day it's closed.
    private static final PointData GAIA_ZONE_BANNER = new PointData(300, 1030);
    private static final PointData GAIA_ZONE_LABEL_TL = new PointData(140, 1005);
    private static final PointData GAIA_ZONE_LABEL_BR = new PointData(460, 1080);
    /** White label text over the map/banner. */
    private static final OcrSettingsData ZONE_LABEL_SETTINGS =
            OcrSettingsData.assembler()
                    .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 :")
                    .textLayout(TextLayout.SINGLE_LINE)
                    .stripBackground(true)
                    .setTextColor(new java.awt.Color(255, 255, 255))
                    .build();

    // -- Squad Config screen --
    /** "Quick Deploy" button on the Squad Config screen (auto-fills heroes + troops IN PLACE). */
    private static final PointData LOH_QUICK_DEPLOY_BTN = new PointData(197, 1193);
    /** Squad-1 "Edit Formation" button on the Squad Config screen -> opens the troop-detail screen.
     *  (Quick Deploy only fills the squad in place; the ratio lives one screen deeper.) */
    private static final PointData LOH_EDIT_FORMATION_SQUAD1_BTN = new PointData(360, 357);

    // -- Troop-detail screen (post Edit Formation) --
    /** "Balance" button on the troop-detail screen that opens the troop-ratio popup. matt/2026-08-13:
     *  re-calibrated 1195->1183 live via ADB on Cave of Monsters -- 1195 landed on the Backpack nav
     *  icon underneath (a stray Alliance Vote popup had been interfering with earlier attempts and
     *  masked this; confirmed twice clean at 1183 with no popups in the way). */
    private static final PointData LOH_BALANCE_BTN = new PointData(330, 1183);
    /** LIVE-TUNE: "Edit Formation" button that SAVES the formation (final step before STOP). */
    private static final PointData LOH_EDIT_FORMATION_BTN = new PointData(575, 1285);

    // -- Balance popup: 3 troop rows, each with a minus (~x213) / plus (~x538) nudge + a % readout --
    /** LIVE-TUNE: minus button X, shared by all three rows. */
    private static final int LOH_MINUS_X = 202;
    /** LIVE-TUNE: plus button X, shared by all three rows. */
    private static final int LOH_PLUS_X = 511;
    /** LIVE-TUNE: row Y centres for Infantry / Lancer / Marksman. */
    private static final int LOH_INFANTRY_ROW_Y = 530;
    private static final int LOH_LANCER_ROW_Y = 675;
    private static final int LOH_MARKSMAN_ROW_Y = 820;
    /** LIVE-TUNE: "Use as default" checkbox in the Balance popup. */
    private static final PointData LOH_USE_AS_DEFAULT_CHECKBOX = new PointData(105, 903);
    /** "Confirm" button in the Balance popup. */
    private static final PointData LOH_CONFIRM_BTN = new PointData(360, 978);
    /** Back arrow (top-left) on the troop-detail screen — exiting triggers the Save-and-Exit dialog. */
    private static final PointData LOH_FORMATION_BACK_ARROW = new PointData(40, 40);
    /** "Save and Exit" (blue, right) button on the "save the formation first?" confirmation dialog.
     *  This is what actually persists the ratio — Confirm on the Balance popup alone does not. */
    private static final PointData LOH_SAVE_AND_EXIT_BTN = new PointData(511, 788);

    // -- % readout OCR crops (top-left / bottom-right), one per troop row --
    /** LIVE-TUNE: Infantry % box. */
    private static final PointData LOH_INF_PCT_TL = new PointData(558, 508);
    private static final PointData LOH_INF_PCT_BR = new PointData(632, 552);
    /** LIVE-TUNE: Lancer % box. */
    private static final PointData LOH_LAN_PCT_TL = new PointData(558, 653);
    private static final PointData LOH_LAN_PCT_BR = new PointData(632, 697);
    /** LIVE-TUNE: Marksman % box. */
    private static final PointData LOH_MRK_PCT_TL = new PointData(558, 798);
    private static final PointData LOH_MRK_PCT_BR = new PointData(632, 842);

    // ===================================================================
    // Gaia Heart formation flow (matt/2026-08-16)
    // ===================================================================
    // Live-calibrated the same day Gaia Heart was actually open (a Sunday). Genuinely two-squad, same
    // shape as Land of Heroes -- BUT the commit mechanism is DIFFERENT and was verified live: the
    // troop-detail screen's own bottom-right "Edit Formation" button commits the ratio DIRECTLY and
    // returns to Squad Config -- no back-arrow, no "save the formation first?" dialog, no separate
    // Save-and-Exit tap. Confirmed by round-trip: set 60/40/0, tapped this button, backed all the way
    // out to The Labyrinth map and fully re-entered the zone -- the ratio was still 60/40/0. That's why
    // Gaia gets its own setup method (setupGaiaZone) instead of reusing driveBalanceAndSave, which
    // assumes Land of Heroes' back-arrow+dialog commit.
    //
    // Also: the Balance popup's row Y positions read slightly different from Land of Heroes/Cave/Charm
    // (530/655/800 here vs. 530/675/820 there) -- same popup component, just enough vertical offset
    // that reusing the LOH constants would tap the wrong row. Confirmed live: floor+fill against these
    // Y values landed exactly on target (60/40/0) with zero correction-pass nudges needed.
    private static final PointData GAIA_QUICK_DEPLOY_BTN = new PointData(197, 1193);
    private static final PointData[] GAIA_SQUAD_EDIT_BTNS = new PointData[] {
            new PointData(360, 357),   // Squad 1
            new PointData(360, 700),   // Squad 2
            // Squad 3 unlocks at Stage 15-10 -- not live-verified (still locked on matt's account as
            // of 2026-08-16), so no coordinate here yet. setupGaiaZone() only ever processes 2 squads
            // until this is added AND verified against the real unlocked screen.
    };
    private static final PointData GAIA_BALANCE_BTN = new PointData(330, 1195);
    /** Commits the ratio directly (no dialog) and returns to Squad Config -- see class-level note above. */
    private static final PointData GAIA_EDIT_FORMATION_COMMIT_BTN = new PointData(548, 1213);
    private static final int GAIA_INFANTRY_ROW_Y = 530;
    private static final int GAIA_LANCER_ROW_Y = 655;
    private static final int GAIA_MARKSMAN_ROW_Y = 800;
    // ESTIMATED from the same relative offset as the LOH pct boxes -- NOT live-verified against Gaia's
    // actual popup (the live test that confirmed 60/40/0 landed exactly via open-loop tap counts alone,
    // so the correction pass these boxes feed never had to fire). Safe either way: readPercent()
    // already treats an OCR miss as "leave as-is" rather than guessing a correction.
    private static final PointData GAIA_INF_PCT_TL = new PointData(558, 508);
    private static final PointData GAIA_INF_PCT_BR = new PointData(632, 552);
    private static final PointData GAIA_LAN_PCT_TL = new PointData(558, 633);
    private static final PointData GAIA_LAN_PCT_BR = new PointData(632, 677);
    private static final PointData GAIA_MRK_PCT_TL = new PointData(558, 778);
    private static final PointData GAIA_MRK_PCT_BR = new PointData(632, 822);

    // Per-squad target troop ratios {Infantry, Lancer, Marksman} are read from config at run time
    // (set on the Labyrinth tab). These are the fallback defaults if config is missing/unreadable:
    // research-seeded (Squad 1 = frontline/tank, Squad 2 = marksman/hybrid).
    private static final int[][] LOH_DEFAULT_SQUAD_RATIOS = new int[][] {
            { 60, 40, 0 },   // Squad 1
            { 50, 0, 50 },   // Squad 2
    };
    // Squad-N "Edit Formation" buttons on the Squad Config screen (index-aligned with the ratios).
    private static final PointData[] LOH_SQUAD_EDIT_BTNS = new PointData[] {
            new PointData(360, 357),   // Squad 1
            new PointData(360, 700),   // Squad 2
    };

    // Slider-drive tuning.
    /** Minus taps to guarantee a row is floored at 0% (>100 so it works from any start; inert at 0). */
    private static final int LOH_FLOOR_TAPS = 105;
    /** Delay between deterministic +/- taps. Fast, but slow enough that taps reliably register. */
    private static final int LOH_DET_TAP_DELAY = 90;
    /** Correction passes after the open-loop floor+fill (fixes any dropped taps via static-frame OCR). */
    private static final int LOH_CORRECT_ITERS = 4;
    /** Settle before a correction read so the slider isn't mid-animation (static OCR is reliable). */
    private static final int LOH_SETTLE_BEFORE_READ = 550;
    /** How many times readPercent re-captures before returning null. */
    private static final int LOH_PCT_READ_ATTEMPTS = 4;
    /** Delay between readPercent re-capture attempts. */
    private static final int LOH_PCT_READ_RETRY_DELAY = 200;

    // -- Screen-verification anchors (each a white-text region + expected substring) so the routine
    //    can POLL for the expected screen after a tap instead of blind fixed delays. The desync that
    //    broke unattended runs was slow-load timing; polling absorbs it without risky double-taps.
    private static final int SCREEN_POLL_INTERVAL_MS = 400;   // OCR re-check cadence while waiting
    private static final int SCREEN_POLL_TIMEOUT_MS = 5000;   // give a slow screen up to this long
    private static final OcrSettingsData WHITE_TITLE_SETTINGS =
            OcrSettingsData.assembler()
                    .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ ")
                    .textLayout(TextLayout.SINGLE_LINE)
                    .stripBackground(true)
                    .setTextColor(new java.awt.Color(255, 255, 255))
                    .build();
    // Stage screen: the "Challenge" button (white text, blue button).
    private static final PointData STAGE_ANCHOR_TL = new PointData(255, 1195);
    private static final PointData STAGE_ANCHOR_BR = new PointData(465, 1245);
    private static final String    STAGE_ANCHOR_TEXT = "challenge";
    // Squad Config: the "Squad Config" title (top-left).
    private static final PointData SQUAD_ANCHOR_TL = new PointData(88, 22);
    private static final PointData SQUAD_ANCHOR_BR = new PointData(330, 62);
    private static final String    SQUAD_ANCHOR_TEXT = "squad";
    // Troop-detail: the "Land of Heroes" header (top-left, large white text). Distinct from Squad
    // Config ("Squad Config") — the only other screen reachable at that point — so "heroes" is a
    // robust confirm. (The small "Troop Ratio:" label proved flaky as an anchor.)
    private static final PointData TROOP_ANCHOR_TL = new PointData(88, 22);
    private static final PointData TROOP_ANCHOR_BR = new PointData(360, 62);
    private static final String    TROOP_ANCHOR_TEXT = "heroes";
    // Balance popup: the "Balance" title in the popup header.
    private static final PointData BALANCE_ANCHOR_TL = new PointData(268, 300);
    private static final PointData BALANCE_ANCHOR_BR = new PointData(456, 342);
    private static final String    BALANCE_ANCHOR_TEXT = "balance";
    // "Save the formation first?" dialog: the "Save and Exit" button text.
    private static final PointData SAVE_ANCHOR_TL = new PointData(385, 762);
    private static final PointData SAVE_ANCHOR_BR = new PointData(642, 816);
    private static final String    SAVE_ANCHOR_TEXT = "save";

    // The % digits are a STROKED font: a black fill with a bold WHITE OUTLINE on a pale-blue box.
    // Isolating on the black fill leaves a faint, broken ghost (the outline eats the core) that OCR
    // can't read. Isolating on the WHITE OUTLINE instead — setTextColor(white) — renders the digits
    // as crisp solid black on white. Verified offline against the real popup: 80/10/10 read cleanly.
    private static final OcrSettingsData LOH_PCT_SETTINGS =
            OcrSettingsData.assembler()
                    .charWhitelist("0123456789")
                    .textLayout(TextLayout.SINGLE_LINE)
                    .stripBackground(true)
                    .setTextColor(new java.awt.Color(255, 255, 255))
                    .build();

    // =========================== CONSTRUCTOR ===========================

    public DailyLabyrinthRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    // =========================== TASK OVERRIDES ===========================

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }

    @Override
    public LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected void execute() {

        try {
            // TEST GATE (matt/2026-08-10): when LABYRINTH_FORMATION_TEST_BOOL is on, run ONLY the
            // free Land-of-Heroes formation-setup flow and stop -- skip the normal daily-clear logic
            // so matt can trigger just this without burning a daily battle attempt.
            Boolean formationTestOn =
                    profile.getConfig(ConfigurationKeyEnum.LABYRINTH_FORMATION_TEST_BOOL, Boolean.class);
            if (Boolean.TRUE.equals(formationTestOn)) {
                logInfo("LABYRINTH_FORMATION_TEST_BOOL is ON -- running formation setup only (no battle).");
                if (!navigateToLabyrinthMenu()) {
                    rescheduleOneHourLater("Failed to navigate to the Labyrinth menu (formation test)");
                    return;
                }
                // matt/2026-08-13: runs Land of Heroes, THEN Cave of Monsters, THEN Charm Mine, THEN
                // Gaia Heart, each independently gated by its own open/locked check.
                for (ZoneFormation zone : ZONE_FORMATIONS) {
                    setupZoneFormation(zone);
                }
                reschedule(nextLabyrinthStartTime());
                return;
            }

            // Step 1: Navigate to labyrinth menu
            if (!navigateToLabyrinthMenu()) {
                rescheduleOneHourLater("Failed to navigate to the Labyrinth menu");
                return;
            }

            // The Daily sidebar opens the currently active Land of Heroes dungeon directly.
            executeCurrentChallenge();

            reschedule(nextLabyrinthStartTime());

        } catch (Exception e) {
            logError("An error occurred during the Labyrinth task: " + e.getMessage());
            rescheduleOneHourLater("Unexpected error during execution: " + e.getMessage());
        }
    }

    // =========================== NAVIGATION METHODS ===========================

    private boolean navigateToLabyrinthMenu() {
        logInfo("Navigating to the Labyrinth menu...");

        if (navigationHelper.navigateToLabyrinth()) {
            logInfo("Successfully navigated to the Labyrinth menu.");
            return true;
        }
        logWarning("Labyrinth menu item not found.");
        return false;
    }

    // =========================== CHALLENGE EXECUTION (Land of Heroes, "current") ==========

    private boolean executeCurrentChallenge() {
        logInfo("Executing the active Land of Heroes challenge.");
        if (attemptQuickChallenge()) {
            return true;
        }

        if (attemptRaidChallenge()) {
            return true;
        }

        boolean completed = attemptNormalChallenge();
        if (!completed) {
            logWarning("No supported challenge action was available in Land of Heroes.");
        }
        return completed;
    }

    private boolean attemptQuickChallenge() {
        ImageSearchResultData quickChallengeResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_QUICK_CHALLENGE_CURRENT,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!quickChallengeResult.isFound()) {
            quickChallengeResult = templateSearchHelper.locatePattern(
                    TemplatesEnum.LABYRINTH_QUICK_CHALLENGE,
                    SearchConfigConstants.SINGLE_WITH_RETRIES);
        }
        if (quickChallengeResult.isFound()) {
            logInfo("'Quick Challenge' is available in Land of Heroes.");
            tapInside(quickChallengeResult);
            sleepTask(MENU_NAVIGATION_DELAY);

            tapNear(SKIP_BUTTON);
            sleepTask(300);
            tapInside(SKIP_BUTTON, SKIP_BUTTON, 10, 50);
            pressBack();
            return true;
        }
        return false;
    }

    private boolean attemptRaidChallenge() {
        ImageSearchResultData raidResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_RAID_CHALLENGE,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (raidResult.isFound()) {
            logInfo("'Raid Challenge' is available in Land of Heroes.");
            tapInside(raidResult);
            sleepTask(400);
            tapInside(SKIP_BUTTON, SKIP_BUTTON, 10, 50);
            pressBack();
            sleepTask(400);
            pressBack();
            return true;
        }
        return false;
    }

    private boolean attemptNormalChallenge() {
        ImageSearchResultData normalChallengeResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_NORMAL_CHALLENGE,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (!normalChallengeResult.isFound()) {
            logDebug("No 'Normal Challenge' button found in Land of Heroes.");
            return false;
        }

        tapInside(normalChallengeResult);
        sleepTask(300);

        ImageSearchResultData quickDeployResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_QUICK_DEPLOY,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (quickDeployResult.isFound()) {
            logInfo("'Quick Deploy' button found. Deploying for Land of Heroes.");
            tapInside(quickDeployResult);
            sleepTask(100);
        }

        ImageSearchResultData deployResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_DEPLOY,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (deployResult.isFound()) {
            logInfo("'Deploy' button found. Deploying troops for Land of Heroes.");
            tapInside(deployResult);
            sleepTask(BATTLE_COMPLETION_DELAY);

            tapInside(RESULT_SKIP_BUTTON, RESULT_SKIP_BUTTON, 10, 50);
            pressBack();
            return true;
        }

        logWarning("Could not find 'Deploy' button for Land of Heroes.");
        return false;
    }

    // =================== ZONE FORMATION SETUP ===================

    /**
     * matt/2026-08-13: describes one Labyrinth zone's formation-setup inputs — the map-screen banner
     * tap point + label OCR box (for the open/locked check), and the config keys that drive its
     * troop ratios. {@link #setupZoneFormation} is generic over this.
     *
     * <p>
     * <b>matt/2026-08-13, caught live via ADB (Cave of Monsters):</b> the original design assumed
     * every zone shares Land of Heroes' two-squad structure (Challenge -> Squad Config -> Quick
     * Deploy -> per-squad Edit Formation -> Balance). Watching Cave of Monsters live end-to-end
     * proved that wrong: tapping Challenge on Cave of Monsters lands DIRECTLY on a single combined
     * troop-detail screen (Infantry/Lancer/Marksman, ONE Balance button) -- there is no Squad Config
     * screen, no Quick Deploy, no second squad at all. {@code singleSquad} distinguishes the two
     * shapes; only {@code squad1Keys} is used when true (squad2Keys stays populated but unused, so
     * the existing UI fields keep working without another rework).
     */
    private record ZoneFormation(String zoneName, PointData banner, PointData labelTl, PointData labelBr,
                                  boolean singleSquad,
                                  ConfigurationKeyEnum[] squad1Keys, ConfigurationKeyEnum[] squad2Keys) {}

    private static final ZoneFormation[] ZONE_FORMATIONS = {
        new ZoneFormation("Land of Heroes", LOH_ZONE_BANNER, LOH_ZONE_LABEL_TL, LOH_ZONE_LABEL_BR, false,
                new ConfigurationKeyEnum[] { ConfigurationKeyEnum.LABYRINTH_SQUAD1_INFANTRY_INT,
                        ConfigurationKeyEnum.LABYRINTH_SQUAD1_LANCER_INT, ConfigurationKeyEnum.LABYRINTH_SQUAD1_MARKSMAN_INT },
                new ConfigurationKeyEnum[] { ConfigurationKeyEnum.LABYRINTH_SQUAD2_INFANTRY_INT,
                        ConfigurationKeyEnum.LABYRINTH_SQUAD2_LANCER_INT, ConfigurationKeyEnum.LABYRINTH_SQUAD2_MARKSMAN_INT }),
        new ZoneFormation("Cave of Monsters", CAVE_ZONE_BANNER, CAVE_ZONE_LABEL_TL, CAVE_ZONE_LABEL_BR, true,
                new ConfigurationKeyEnum[] { ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD1_INFANTRY_INT,
                        ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD1_LANCER_INT, ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD1_MARKSMAN_INT },
                new ConfigurationKeyEnum[] { ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD2_INFANTRY_INT,
                        ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD2_LANCER_INT, ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD2_MARKSMAN_INT }),
        new ZoneFormation("Charm Mine", CHARM_ZONE_BANNER, CHARM_ZONE_LABEL_TL, CHARM_ZONE_LABEL_BR, true,
                new ConfigurationKeyEnum[] { ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD1_INFANTRY_INT,
                        ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD1_LANCER_INT, ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD1_MARKSMAN_INT },
                new ConfigurationKeyEnum[] { ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD2_INFANTRY_INT,
                        ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD2_LANCER_INT, ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD2_MARKSMAN_INT }),
        // matt/2026-08-16: Gaia Heart -- two-squad like Land of Heroes, but dispatched to its own
        // setupGaiaZone() (see the "Gaia Heart formation flow" constants above) because its commit
        // mechanism genuinely differs (direct-commit button, no back-arrow/dialog). The `singleSquad`
        // flag here is unused for Gaia -- routing happens by name in setupZoneFormation() below.
        new ZoneFormation("Gaia Heart", GAIA_ZONE_BANNER, GAIA_ZONE_LABEL_TL, GAIA_ZONE_LABEL_BR, false,
                new ConfigurationKeyEnum[] { ConfigurationKeyEnum.LABYRINTH_GAIA_SQUAD1_INFANTRY_INT,
                        ConfigurationKeyEnum.LABYRINTH_GAIA_SQUAD1_LANCER_INT, ConfigurationKeyEnum.LABYRINTH_GAIA_SQUAD1_MARKSMAN_INT },
                new ConfigurationKeyEnum[] { ConfigurationKeyEnum.LABYRINTH_GAIA_SQUAD2_INFANTRY_INT,
                        ConfigurationKeyEnum.LABYRINTH_GAIA_SQUAD2_LANCER_INT, ConfigurationKeyEnum.LABYRINTH_GAIA_SQUAD2_MARKSMAN_INT }),
    };

    private void setupZoneFormation(ZoneFormation zone) {
        String tag = zone.zoneName() + " formation";
        logInfo(tag + ": starting formation setup (setup only, no battle).");

        saveLabyrinthFrame("map", 0); // one-shot: capture the Labyrinth map to calibrate zone-label OCR

        // Enter the zone by reading its map label (matt's rule): a LOCKED zone's line reads
        // "Opens in …"; an OPEN zone shows just name + countdown. Only tap the banner if it's open.
        String zoneLabel = readStringValue(zone.labelTl(), zone.labelBr(), ZONE_LABEL_SETTINGS);
        logInfo(tag + ": label OCR = '" + zoneLabel + "'.");
        if (zoneLabel != null && zoneLabel.toLowerCase().contains("open")) {
            logWarning(tag + ": reads LOCKED ('Opens in') — not open yet, skipping.");
            return;
        }
        logInfo(tag + ": looks open — tapping its banner to enter.");
        // Step 1: banner -> stage screen (poll for the "Challenge" button).
        if (!navStep(zone.banner(), STAGE_ANCHOR_TL, STAGE_ANCHOR_BR, STAGE_ANCHOR_TEXT, tag + " banner->stage")) {
            logWarning(tag + ": never reached the stage screen; aborting.");
            return;
        }
        saveLabyrinthFrame("stage", 0);

        if (zone.singleSquad()) {
            setupSingleSquadZone(zone, tag);
            return;
        }

        if ("Gaia Heart".equals(zone.zoneName())) {
            setupGaiaZone(zone, tag);
            return;
        }

        // Step 2: Challenge -> Squad Config (poll for the "Squad Config" title).
        if (!navStep(LOH_CHALLENGE_BTN, SQUAD_ANCHOR_TL, SQUAD_ANCHOR_BR, SQUAD_ANCHOR_TEXT, tag + " Challenge->SquadConfig")) {
            logWarning(tag + ": never reached Squad Config; aborting.");
            return;
        }
        saveLabyrinthFrame("squad", 0);

        // Step 3: Quick Deploy fills heroes + troops for BOTH squads IN PLACE (stays on Squad Config,
        // so there is no screen change to verify — a short settle is enough).
        logInfo(tag + ": tapping Quick Deploy (fills squads in place).");
        tapNear(LOH_QUICK_DEPLOY_BTN);
        sleepTask(LABYRINTH_LOAD_DELAY);
        saveLabyrinthFrame("squad_filled", 0);

        // Step 4: configure each squad's ratio in turn. After a squad's "Save and Exit" the game drops
        // back to the STAGE screen, so for squads after the first we re-tap Challenge to reach Squad
        // Config again. Quick Deploy above already filled every squad, so we don't repeat it.
        int[][] squadRatios = readSquadRatiosFromConfig(zone);
        for (int i = 0; i < squadRatios.length; i++) {
            if (i > 0) {
                logInfo(tag + ": re-entering Squad Config for squad " + (i + 1) + ".");
                if (!navStep(LOH_CHALLENGE_BTN, SQUAD_ANCHOR_TL, SQUAD_ANCHOR_BR, SQUAD_ANCHOR_TEXT,
                        tag + " Challenge->SquadConfig(sq" + (i + 1) + ")")) {
                    logWarning(tag + ": could not re-enter Squad Config for squad " + (i + 1)
                            + "; aborting remaining squads.");
                    return;
                }
            }
            if (!configureSquadRatio(tag, i + 1, LOH_SQUAD_EDIT_BTNS[i], squadRatios[i])) {
                logWarning(tag + ": squad " + (i + 1) + " setup failed; aborting remaining squads.");
                return;
            }
        }

        logInfo(tag + ": all squads configured. STOPPING before Deploy (no battle attempt spent).");
    }

    /**
     * matt/2026-08-13: Cave of Monsters / Charm Mine's actual flow, proven live via ADB. Challenge
     * lands DIRECTLY on the combined troop-detail screen (Infantry/Lancer/Marksman, ONE Balance
     * button) -- no Squad Config, no Quick Deploy, no second squad. Only squad1Keys is used.
     */
    private void setupSingleSquadZone(ZoneFormation zone, String tag) {
        // Challenge -> troop-detail screen directly. The screen's own title is the zone's name
        // (e.g. "Cave of Monsters"), so that's the anchor text -- generic across any single-squad zone.
        if (!navStep(LOH_CHALLENGE_BTN, TROOP_ANCHOR_TL, TROOP_ANCHOR_BR, zone.zoneName().toLowerCase(),
                tag + " Challenge->troop")) {
            logWarning(tag + ": never reached the troop-detail screen; aborting.");
            return;
        }
        saveLabyrinthFrame("troop", 0);

        int[] ratio = readSingleRatioFromConfig(zone);
        if (!driveBalanceAndSave(tag, "single", zone.zoneName().toLowerCase(), ratio)) {
            logWarning(tag + ": ratio setup failed.");
            return;
        }
        logInfo(tag + ": configured. STOPPING before Deploy (no battle attempt spent).");
    }

    /**
     * matt/2026-08-16: Gaia Heart's real flow, proven live via ADB. Two squads like Land of Heroes
     * (Challenge -> Squad Config -> Quick Deploy -> per-squad Edit Formation -> Balance), but the
     * troop-detail screen's OWN "Edit Formation" button commits the ratio directly and returns to
     * Squad Config -- confirmed live that this alone (no back-arrow, no Save-and-Exit dialog) is
     * enough to persist the ratio across a full exit-to-map-and-back-in. Only squads 1-2 are driven;
     * Squad 3 (locked until Stage 15-10) has no live-verified coordinates yet -- see
     * GAIA_SQUAD_EDIT_BTNS's note.
     */
    private void setupGaiaZone(ZoneFormation zone, String tag) {
        // Challenge -> Squad Config (same anchor as Land of Heroes).
        if (!navStep(LOH_CHALLENGE_BTN, SQUAD_ANCHOR_TL, SQUAD_ANCHOR_BR, SQUAD_ANCHOR_TEXT,
                tag + " Challenge->SquadConfig")) {
            logWarning(tag + ": never reached Squad Config; aborting.");
            return;
        }
        saveLabyrinthFrame("gaia_squad", 0);

        // Quick Deploy fills both squads with REAL troops/heroes in place (not normalized, unlike
        // every other zone) -- idempotent, safe even if squads are already populated from a prior run.
        logInfo(tag + ": tapping Quick Deploy (fills squads with real troops/heroes in place).");
        tapNear(GAIA_QUICK_DEPLOY_BTN);
        sleepTask(LABYRINTH_LOAD_DELAY);
        saveLabyrinthFrame("gaia_squad_filled", 0);

        int[][] squadRatios = readSquadRatiosFromConfig(zone);
        for (int i = 0; i < GAIA_SQUAD_EDIT_BTNS.length; i++) {
            if (i > 0) {
                logInfo(tag + ": re-entering Squad Config for squad " + (i + 1) + ".");
                if (!navStep(LOH_CHALLENGE_BTN, SQUAD_ANCHOR_TL, SQUAD_ANCHOR_BR, SQUAD_ANCHOR_TEXT,
                        tag + " Challenge->SquadConfig(sq" + (i + 1) + ")")) {
                    logWarning(tag + ": could not re-enter Squad Config for squad " + (i + 1)
                            + "; aborting remaining squads.");
                    return;
                }
            }
            if (!driveGaiaBalanceAndSave(tag, "sq" + (i + 1), GAIA_SQUAD_EDIT_BTNS[i], squadRatios[i])) {
                logWarning(tag + ": squad " + (i + 1) + " setup failed; aborting remaining squads.");
                return;
            }
        }

        logInfo(tag + ": squads 1-2 configured. Squad 3 not driven (locked until Stage 15-10, no "
                + "live-verified coordinates yet). STOPPING before Deploy (no battle attempt spent).");
    }

    /**
     * Gaia Heart's squad-ratio commit: Edit Formation -> troop-detail -> Balance -> drive the three
     * sliders (Gaia's own row Y positions) -> Confirm -> the screen's OWN "Edit Formation" button,
     * which commits directly (verified live -- no back-arrow, no dialog, unlike Land of Heroes).
     */
    private boolean driveGaiaBalanceAndSave(String tag, String label, PointData editFormationBtn, int[] ratio) {
        logInfo(tag + ": configuring " + label + " -> " + ratio[0] + "/" + ratio[1] + "/" + ratio[2]
                + " (Inf/Lan/Mrk).");

        if (!navStep(editFormationBtn, TROOP_ANCHOR_TL, TROOP_ANCHOR_BR, "gaia heart",
                tag + " EditFormation->troop(" + label + ")")) {
            logWarning(tag + ": " + label + " -- never reached troop-detail.");
            return false;
        }
        saveLabyrinthFrame("gaia_troop", 0);

        if (!navStep(GAIA_BALANCE_BTN, BALANCE_ANCHOR_TL, BALANCE_ANCHOR_BR, BALANCE_ANCHOR_TEXT,
                tag + " Balance->popup(" + label + ")")) {
            logWarning(tag + ": " + label + " -- never reached the Balance popup.");
            return false;
        }
        saveLabyrinthFrame("gaia_balance_popup", 0);

        floorRowToZero("Infantry", GAIA_INFANTRY_ROW_Y);
        floorRowToZero("Lancer",   GAIA_LANCER_ROW_Y);
        floorRowToZero("Marksman", GAIA_MARKSMAN_ROW_Y);
        fillRowToTarget("Infantry", GAIA_INFANTRY_ROW_Y, GAIA_INF_PCT_TL, GAIA_INF_PCT_BR, ratio[0]);
        fillRowToTarget("Lancer",   GAIA_LANCER_ROW_Y,   GAIA_LAN_PCT_TL, GAIA_LAN_PCT_BR, ratio[1]);
        fillRowToTarget("Marksman", GAIA_MARKSMAN_ROW_Y, GAIA_MRK_PCT_TL, GAIA_MRK_PCT_BR, ratio[2]);

        Integer vi = readPercent(GAIA_INF_PCT_TL, GAIA_INF_PCT_BR);
        Integer vl = readPercent(GAIA_LAN_PCT_TL, GAIA_LAN_PCT_BR);
        Integer vm = readPercent(GAIA_MRK_PCT_TL, GAIA_MRK_PCT_BR);
        logInfo(tag + ": " + label + " post-set readback = "
                + vi + "/" + vl + "/" + vm + " (target " + ratio[0] + "/" + ratio[1] + "/" + ratio[2] + ").");
        saveLabyrinthFrame("gaia_balance_set", 0);

        // Confirm the popup -> back on troop-detail with the new ratio showing.
        if (!navStep(LOH_CONFIRM_BTN, TROOP_ANCHOR_TL, TROOP_ANCHOR_BR, "gaia heart",
                tag + " Confirm->troop(" + label + ")")) {
            logWarning(tag + ": " + label + " -- Confirm didn't return to troop-detail "
                    + "(continuing to the commit step anyway).");
        }

        // Gaia's own "Edit Formation" button commits directly and returns to Squad Config -- no
        // dialog to wait for, just confirm we're back (poll for "Squad Config").
        tapNear(GAIA_EDIT_FORMATION_COMMIT_BTN);
        if (!waitForScreen(SQUAD_ANCHOR_TL, SQUAD_ANCHOR_BR, SQUAD_ANCHOR_TEXT)) {
            logWarning(tag + ": " + label + " -- commit tap didn't visibly return to Squad Config; "
                    + "ratio may not have persisted.");
            return false;
        }
        logInfo(tag + ": " + label + " ratio committed.");
        return true;
    }

    /** Reads the per-squad {Inf,Lan,Mrk} ratios from the zone's config keys, falling back to
     *  {@link #LOH_DEFAULT_SQUAD_RATIOS} for any value that's missing/out of range. */
    private int[][] readSquadRatiosFromConfig(ZoneFormation zone) {
        int[][] d = LOH_DEFAULT_SQUAD_RATIOS;
        return new int[][] {
            { cfgInt(zone.squad1Keys()[0], d[0][0]), cfgInt(zone.squad1Keys()[1], d[0][1]), cfgInt(zone.squad1Keys()[2], d[0][2]) },
            { cfgInt(zone.squad2Keys()[0], d[1][0]), cfgInt(zone.squad2Keys()[1], d[1][1]), cfgInt(zone.squad2Keys()[2], d[1][2]) },
        };
    }

    /** Reads a single-squad zone's {Inf,Lan,Mrk} ratio from its squad1Keys (squad2Keys unused). */
    private int[] readSingleRatioFromConfig(ZoneFormation zone) {
        int[] d = LOH_DEFAULT_SQUAD_RATIOS[0];
        return new int[] {
            cfgInt(zone.squad1Keys()[0], d[0]), cfgInt(zone.squad1Keys()[1], d[1]), cfgInt(zone.squad1Keys()[2], d[2])
        };
    }

    private int cfgInt(ConfigurationKeyEnum key, int fallback) {
        try {
            Integer v = profile.getConfig(key, Integer.class);
            return (v != null && v >= 0 && v <= 100) ? v : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Configures a single squad's Infantry/Lancer/Marksman ratio, then persists it:
     * Edit Formation → Balance → drive the three sliders → Confirm → back → Save and Exit.
     *
     * <p>The sliders are driven in ASCENDING-target order so the rows that need to go DOWN move
     * before the rows that need to go UP — otherwise a raise can stall against the 100% cap (the
     * game refuses to push a row up while the total is already 100%).</p>
     *
     * <p>"Use as default" is deliberately left unticked so each squad keeps its own ratio, and the
     * Save-and-Exit dialog is what actually persists it (Confirm on the popup alone does not —
     * verified live 2026-08-10).</p>
     */
    private boolean configureSquadRatio(String tag, int squadNumber, PointData editFormationBtn, int[] ratio) {
        logInfo(tag + ": configuring Squad " + squadNumber + " -> "
                + ratio[0] + "/" + ratio[1] + "/" + ratio[2] + " (Inf/Lan/Mrk).");

        // Edit Formation -> troop-detail (poll for the "Troop Ratio" label).
        if (!navStep(editFormationBtn, TROOP_ANCHOR_TL, TROOP_ANCHOR_BR, TROOP_ANCHOR_TEXT,
                tag + " EditFormation->troop(sq" + squadNumber + ")")) {
            logWarning(tag + ": squad " + squadNumber + " — never reached troop-detail.");
            return false;
        }
        saveLabyrinthFrame("troop", squadNumber);

        return driveBalanceAndSave(tag, "sq" + squadNumber, TROOP_ANCHOR_TEXT, ratio);
    }
    /**
     * matt/2026-08-13: extracted from {@code configureSquadRatio} so both the two-squad (Land of
     * Heroes) and single-squad (Cave of Monsters / Charm Mine) flows share one implementation.
     * Assumes we're ALREADY on the troop-detail screen (Balance button visible) -- drives
     * Balance → the three sliders → Confirm → back → Save and Exit.
     *
     * <p>The sliders are driven in ASCENDING-target order so the rows that need to go DOWN move
     * before the rows that need to go UP — otherwise a raise can stall against the 100% cap (the
     * game refuses to push a row up while the total is already 100%).</p>
     *
     * <p>"Use as default" is deliberately left unticked so each squad/zone keeps its own ratio, and
     * the Save-and-Exit dialog is what actually persists it (Confirm on the popup alone does not —
     * verified live 2026-08-10).</p>
     *
     * @param label a short tag for logging (e.g. "sq1", "sq2", "single")
     * @param troopAnchorText the expected text on the troop-detail screen's title (e.g. "heroes" for
     *                        Land of Heroes, or the zone's own lowercased name for single-squad zones)
     *                        -- used to confirm Confirm returned us there, not a fixed "heroes" string
     */
    private boolean driveBalanceAndSave(String tag, String label, String troopAnchorText, int[] ratio) {
        // Balance -> ratio popup (poll for the "Balance" popup title). This is the critical gate:
        // the OCR slider-driver only works once we are genuinely on the popup.
        if (!navStep(LOH_BALANCE_BTN, BALANCE_ANCHOR_TL, BALANCE_ANCHOR_BR, BALANCE_ANCHOR_TEXT,
                tag + " Balance->popup(" + label + ")")) {
            logWarning(tag + ": " + label + " — never reached the Balance popup.");
            return false;
        }
        saveLabyrinthFrame("balance_popup", 0);

        // matt's approach: ZERO all three rows first, THEN fill each to target top-to-bottom. Zeroing
        // everything up front means the running total is 0 when we start adding, so a fill can never be
        // blocked by the 100% cap. 1 tap == 1% (verified live). No mid-drive OCR — the small stroked
        // digits only read reliably on a settled/static frame, so OCR is used ONLY for the correction
        // pass in fillRowToTarget (which re-adds any taps the game dropped).
        floorRowToZero("Infantry", LOH_INFANTRY_ROW_Y);
        floorRowToZero("Lancer",   LOH_LANCER_ROW_Y);
        floorRowToZero("Marksman", LOH_MARKSMAN_ROW_Y);
        fillRowToTarget("Infantry", LOH_INFANTRY_ROW_Y, LOH_INF_PCT_TL, LOH_INF_PCT_BR, ratio[0]);
        fillRowToTarget("Lancer",   LOH_LANCER_ROW_Y,   LOH_LAN_PCT_TL, LOH_LAN_PCT_BR, ratio[1]);
        fillRowToTarget("Marksman", LOH_MARKSMAN_ROW_Y, LOH_MRK_PCT_TL, LOH_MRK_PCT_BR, ratio[2]);

        // Best-effort verify readback (logged) + a frame for eyeballing the final ratio.
        Integer vi = readPercent(LOH_INF_PCT_TL, LOH_INF_PCT_BR);
        Integer vl = readPercent(LOH_LAN_PCT_TL, LOH_LAN_PCT_BR);
        Integer vm = readPercent(LOH_MRK_PCT_TL, LOH_MRK_PCT_BR);
        logInfo(tag + ": " + label + " post-set readback = "
                + vi + "/" + vl + "/" + vm + " (target " + ratio[0] + "/" + ratio[1] + "/" + ratio[2] + ").");
        saveLabyrinthFrame("balance_set", 0);

        // Confirm the popup (per-squad; no "use as default"). Popup closes -> back on troop-detail.
        if (!navStep(LOH_CONFIRM_BTN, TROOP_ANCHOR_TL, TROOP_ANCHOR_BR, troopAnchorText,
                tag + " Confirm->troop(" + label + ")")) {
            logWarning(tag + ": " + label + " — Confirm didn't return to troop-detail "
                    + "(continuing to the save step anyway).");
        }

        // Exit the troop-detail -> "save the formation first?" dialog (poll for the "Save and Exit"
        // button) -> tap it to persist. Confirm on the popup alone does NOT save.
        if (!navStep(LOH_FORMATION_BACK_ARROW, SAVE_ANCHOR_TL, SAVE_ANCHOR_BR, SAVE_ANCHOR_TEXT,
                tag + " back->saveDialog(" + label + ")")) {
            logWarning(tag + ": " + label + " — save dialog never appeared; "
                    + "ratio may not have persisted.");
            return false;
        }
        tapNear(LOH_SAVE_AND_EXIT_BTN);
        sleepTask(MENU_NAVIGATION_DELAY);
        logInfo(tag + ": " + label + " ratio saved.");
        return true;
    }

    /**
     * Taps {@code target}, then POLLS (via OCR of {@code vtl..vbr}) for the expected screen to appear,
     * up to {@link #SCREEN_POLL_TIMEOUT_MS}. If it doesn't appear, taps ONCE more and polls again.
     * Returns true once the screen is confirmed.
     *
     * <p>Polling instead of a fixed sleep is what fixes the unattended desync: a slow-loading screen
     * is simply waited for, and we only re-tap once (covering a genuinely missed tap) rather than
     * blindly firing the next tap into whatever happens to be on screen.</p>
     */
    private boolean navStep(PointData target, PointData vtl, PointData vbr, String expectLower, String desc) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            tapNear(target);
            if (waitForScreen(vtl, vbr, expectLower)) {
                if (attempt > 1) logInfo("LoH nav [" + desc + "]: reached on retry.");
                return true;
            }
            logWarning("LoH nav [" + desc + "]: '" + expectLower + "' not present after tap " + attempt
                    + "; " + (attempt < 2 ? "retrying." : "giving up."));
        }
        saveLabyrinthFrame("navfail", 9); // debug: capture where we actually landed on give-up
        return false;
    }

    /** Polls the OCR region {@code vtl..vbr} until its text contains {@code expectLower} or timeout. */
    private boolean waitForScreen(PointData vtl, PointData vbr, String expectLower) {
        int waited = 0;
        while (waited < SCREEN_POLL_TIMEOUT_MS) {
            String s = readStringValue(vtl, vbr, WHITE_TITLE_SETTINGS);
            if (s != null && s.toLowerCase().contains(expectLower)) return true;
            sleepTask(SCREEN_POLL_INTERVAL_MS);
            waited += SCREEN_POLL_INTERVAL_MS;
        }
        return false;
    }

    /** Taps a row's minus button enough times to guarantee it sits at 0% (extra taps at 0 are inert). */
    private void floorRowToZero(String label, int rowY) {
        logInfo("LoH slider [" + label + "]: flooring to 0%.");
        PointData minus = new PointData(LOH_MINUS_X, rowY);
        for (int i = 0; i < LOH_FLOOR_TAPS; i++) {
            tapNear(minus);
            sleepTask(LOH_DET_TAP_DELAY);
        }
    }

    /**
     * Fills a row FROM 0% up to {@code targetPct}: taps plus {@code targetPct} times (1 tap == 1%),
     * then runs a few correction passes — read the settled value, and tap the exact remaining delta —
     * to re-add any taps the game dropped. The row must already be floored to 0 before calling.
     */
    private void fillRowToTarget(String label, int rowY, PointData pctTl, PointData pctBr, int targetPct) {
        PointData plus = new PointData(LOH_PLUS_X, rowY);
        PointData minus = new PointData(LOH_MINUS_X, rowY);
        logInfo("LoH slider [" + label + "]: filling 0 -> " + targetPct + "%.");
        for (int i = 0; i < targetPct; i++) {
            tapNear(plus);
            sleepTask(LOH_DET_TAP_DELAY);
        }
        // Correction passes: fix dropped taps using the reliable static-frame OCR.
        for (int iter = 0; iter < LOH_CORRECT_ITERS; iter++) {
            sleepTask(LOH_SETTLE_BEFORE_READ);
            Integer cur = readPercent(pctTl, pctBr);
            if (cur == null) {
                logWarning("LoH slider [" + label + "]: correction read failed (iter " + (iter + 1)
                        + "); leaving as-is.");
                continue;
            }
            if (cur == targetPct) {
                logInfo("LoH slider [" + label + "]: at target " + targetPct + "%.");
                return;
            }
            int delta = targetPct - cur;
            logInfo("LoH slider [" + label + "]: read " + cur + "%, nudging " + (delta > 0 ? "+" : "")
                    + delta + " to hit " + targetPct + "%.");
            PointData btn = (delta > 0) ? plus : minus;
            for (int k = 0; k < Math.abs(delta); k++) {
                tapNear(btn);
                sleepTask(LOH_DET_TAP_DELAY);
            }
        }
        logWarning("LoH slider [" + label + "]: could not confirm " + targetPct + "% after "
                + LOH_CORRECT_ITERS + " correction passes.");
    }

    /**
     * OCRs a % readout box and parses it to an int in 0..100, or null if unreadable. The digits are
     * borderline-OCR (small, stroked font, and the slider briefly animates after a nudge), so this
     * RE-READS a few times before giving up — a fresh frame each attempt smooths over transient misses.
     */
    private Integer readPercent(PointData tl, PointData br) {
        for (int attempt = 1; attempt <= LOH_PCT_READ_ATTEMPTS; attempt++) {
            String raw = readStringValue(tl, br, LOH_PCT_SETTINGS);
            if (raw != null && !raw.isBlank()) {
                String digits = raw.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) {
                    try {
                        int v = Integer.parseInt(digits);
                        if (v >= 0 && v <= 100) return v;   // reject OCR noise outside the valid range
                    } catch (NumberFormatException ignored) { /* retry */ }
                }
            }
            if (attempt < LOH_PCT_READ_ATTEMPTS) sleepTask(LOH_PCT_READ_RETRY_DELAY);
        }
        return null;
    }

    // =========================== UTILITY METHODS ===========================

    /**
     * matt/2026-08-10 (Labyrinth observation phase): dumps the current emulator frame to
     * labyrinth-debug/ so we can build enemy-type + win/loss detection from real battle
     * screens. Pure observation -- never changes deploy behaviour. The game has NO labyrinth
     * victory/defeat templates yet, so this is how we collect the training data for them.
     */
    private void saveLabyrinthFrame(String label, int tag) {
        try {
            RawImageData frame = emuManager.captureScreen(String.valueOf(EMULATOR_NUMBER));
            BufferedImage img = dev.frostguard.vision.convert.ImageConverter.toBufferedImage(frame);
            File dir = new File(System.getProperty("user.dir"), "labyrinth-debug");
            dir.mkdirs();
            File out = new File(dir, "lab_t" + tag + "_" + label + "_" + System.currentTimeMillis() + ".png");
            ImageIO.write(img, "png", out);
            logInfo("Labyrinth observation: saved " + out.getName());
        } catch (Exception e) {
            logWarning("Labyrinth observation: failed to save frame (" + label + "): " + e.getMessage());
        }
    }

    private void rescheduleOneHourLater(String reason) {
        LocalDateTime nextExecution = LocalDateTime.now().plusHours(1);
        logWarning(reason + ". Rescheduling task for one hour later.");
        this.reschedule(nextExecution);
    }

    /**
     * matt/2026-08-13: "kick Labyrinth off at noon every day" -- reads the picked local start time
     * (LABYRINTH_DAILY_START_TIME_STRING, HH:mm, defaults to noon) instead of always following the
     * game's own 00:00 UTC reset boundary.
     */
    private LocalDateTime nextLabyrinthStartTime() {
        String startTime = profile.getConfig(ConfigurationKeyEnum.LABYRINTH_DAILY_START_TIME_STRING, String.class);
        return GameTimeUtils.nextLocalTime(startTime);
    }

}
