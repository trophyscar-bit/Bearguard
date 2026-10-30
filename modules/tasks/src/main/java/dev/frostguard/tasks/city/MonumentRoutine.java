package dev.frostguard.tasks.city;

import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.SizeData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.convert.RegexNumberParser;

/**
 * Task responsible for the Monument / "Explore the World" Atlas feature: claiming
 * ready milestone rewards and cracking open owned Scene Fragment Packs from the
 * shared Fragment Backpack.
 *
 * <p>
 * The original {@code LaunchPoint.HOME}-then-pan-search approach
 * (see the old header below) turned out to be unreliable in practice -- camera pan
 * position drifts run to run depending on whatever the prior task left it at, so the
 * badge was often not where the pan sweep expected. Real fix, live-verified on the
 * Testing profile before merging here: anchor off Lancer Camp instead (a fixed
 * building, always reachable the same way via the left-menu queue list), then a
 * single confirmed 300px right swipe brings Monument into view every time.
 * See {@link #findAndOpenBadgeViaLancer()}.
 *
 * <p>
 * Even that swipe still fell back into a full camera-pan sweep
 * whenever a template search for the reward badge came up empty right after landing
 * -- "it pans to the right of lancer, you see monument, then it just starts
 * scrolling around." The 8-direction pan-fallback (the actual "scrolling around")
 * is removed entirely. A first attempt to also drop the template search itself in
 * favor of a fixed-pixel tap was WRONG -- that coordinate was guessed from an old
 * debug frame instead of a confirmed one, and it mis-tapped the Archer Camp instead
 * of Monument. Fixed for real this time: {@link #MONUMENT_BADGE_TAP_POINT} is
 * verified against 3 real screenshots (shop-debug/monument_find.png,
 * monument_check2.png, monument_check3.png) all showing the scroll-with-a-feather
 * badge in the identical spot after this exact swipe, and confirmed the
 * reference frame live before this landed. No template search, no pan fallback.
 *
 * <p>
 * <b>Flow:</b>
 * <pre>
 * Home -> open left-menu City section -> tap Lancer row -> tap the camp building
 * -> wait 5s -> swipe right 300px -> tap the fixed badge point, VERIFY the badge
 * is no longer detectable (confirms something actually opened, instead of
 * assuming) -> claim any ready rows -> X close -> back arrow -> Tundra Albums hub
 * -> Fragment Backpack (bottom-right of the hub) -> open every owned pack (rescanning
 * from scratch after each open, since the panel reflows) -> close -> milestone chest
 * track -> back arrow -> Home, badge cleared.
 * </pre>
 *
 * <p>
 * <b>Fragment Backpack bug fixed 2026-08-14:</b> this used to fire one screen too
 * early, at a coordinate (470,1275) that doesn't correspond to a real button on the
 * screen the badge tap actually leads to -- it always missed, read garbled OCR text,
 * and silently skipped the whole backpack pass every single run. Moved to fire AFTER
 * the back-arrow (on the real Tundra Albums hub) at the hub's own Fragment Backpack
 * button (628,1197) -- live-verified hand-driven, screenshot-confirmed: opened 2 real
 * packs (General Album, Nature's Strength), rewards actually landed (fragment count
 * advanced 827/1347 -> 846/1347), panel reflow after each open behaved exactly as the
 * candidate-rescan logic below expects.
 *
 * <p>
 * <b>Alliance Trade deliberately NOT run automatically (by design):</b>
 * the request/send logic below is real and was live-verified working correctly (Ally
 * Requests skip owned:1 rows and only send genuine duplicates, confirmed against a
 * live panel) -- but the intent is to handle Alliance Trade manually for now, so
 * {@code execute()} no longer calls it. Left in place, unused, for a future re-enable
 * rather than deleted.
 *
 * <p>
 * <b>Known gaps (not built):</b> Ally Requests list is only scanned for rows already
 * visible on open (no deep-scroll dedup), matching the same scroll-list limitation
 * already known in ChatCaptureRoutine.
 */
public class MonumentRoutine extends DelayedTask {

    /** Guards against sweeping the Fragment Backpack twice in one pass when a run reaches the hub
     *  by more than one route. Reset at the top of every execute(). */
    private boolean backpackSweptThisRun;

    // ========== Stray-popup clearing (game-rendered modals ignore Android back) ==========
    /** Every close-X position observed so far across different tasks' leftover
     *  panels (Resource Stockpile Scan's "Overview", the "Resource &amp; Speedup
     *  Summary" panel) -- tapped every round alongside back presses. Not exhaustive
     *  by design; the round-and-recheck loop in clearStrayPopups() is what actually
     *  makes this generic, this list just gives it a head start on known cases. */
    private static final PointData[] KNOWN_STRAY_PANEL_CLOSE_SPOTS = {
            new PointData(690, 358),
            new PointData(665, 258),
    };

    // ========== Lancer-relative navigation to Monument  ==========
    // Same coordinates as TrainingRoutine.LANCER_AREA_VALUE / TRAINING_CAMP_TAP_MIN/MAX_VALUE --
    // the Lancer row in the left-menu City queue list, then the camp building itself.
    private static final PointData LANCER_AREA_TOP_LEFT = new PointData(161, 636);
    private static final PointData LANCER_AREA_BOTTOM_RIGHT = new PointData(289, 664);
    private static final PointData CAMP_TAP_TOP_LEFT = new PointData(310, 650);
    private static final PointData CAMP_TAP_BOTTOM_RIGHT = new PointData(450, 730);
    private static final int POST_LANCER_WAIT_MS = 5000;
    // Widened 300px -> 350px by design, paired with the real
    // template search below (not a coordinate change) -- the search finds the badge wherever it
    // actually lands, so this only needs to get Monument reliably into frame, not to a precise spot.
    private static final PointData SWIPE_RIGHT_START = new PointData(550, 700);
    private static final PointData SWIPE_RIGHT_END = new PointData(200, 700);
    private static final int SWIPE_DURATION_MS = 400;
    private static final int POST_SWIPE_WAIT_MS = 1000;

    // FOURTH pass -- every fixed-pixel guess so far (330,460 / then 471,550 / then
    // a 516,506-586,576 tolerant box measured off a screenshot) has been wrong at least once live.
    // Guessing a fifth coordinate off a fifth screenshot has the exact same failure mode as the
    // first four -- the camera's landing spot after the swipe isn't perfectly fixed run to run, so
    // no static point or box is ever going to be reliable here. Stopped guessing coordinates for
    // this entirely; see findAndOpenBadgeViaLancer() below, which now finds the badge with a real
    // template search every run and taps wherever it actually is.


    // ========== Quest-list modal + Atlas grid (shared skin across categories) ==========
    private static final PointData MODAL_CLOSE_X = new PointData(662, 157);
    private static final PointData ATLAS_BACK_ARROW = new PointData(41, 52);
    private static final int MAX_CLAIM_LOOPS = 10;

    // ========== Tundra Albums hub ==========
    private static final PointData ALBUMS_BACK_ARROW = new PointData(41, 52);
    private static final PointData ALBUMS_FRAGMENT_BACKPACK_BTN = new PointData(626, 1197);
    private static final PointData ALBUMS_ALLIANCE_TRADE_BTN = new PointData(445, 1193);

    // ========== Fragment Backpack panel (shared across all Atlas categories) ==========
    private static final PointData BACKPACK_CLOSE_X = new PointData(662, 138);
    private static final PointData BACKPACK_TITLE_TL = new PointData(215, 105);
    private static final PointData BACKPACK_TITLE_BR = new PointData(505, 145);

    // ========== Puzzle-ready chain (Assemble Now -> congrats -> lore card) ==========
    // Real chain, hand-driven tap-by-tap on a genuine live 15/15
    // puzzle the same day (see chat transcript). Two different confidence levels here:
    // (1) ASSEMBLE_REGION_TL/BR, ASSEMBLE_NOW_BTN, ASSEMBLED_TAP_ANYWHERE, and
    //     LORE_CARD_CLOSE_X are all estimates RESCALED from a desktop capture of that
    //     walkthrough, NOT a native 720x1280 ADB frame -- the live puzzle got fully
    //     consumed assembling it during the walkthrough itself, so there was nothing left
    //     to crop a real template from. The reported symptom: "if you did grab it at that time,
    //     it's gone... we're gonna have to wait till we have another one going." These are
    //     first-pass numbers, not verified -- see handlePuzzleReadyChain()'s hard re-anchor
    //     below before anything downstream is trusted.
    // (2) MONUMENT_PUZZLE_OVERVIEW_FRAGMENT_BACKPACK_ICON (used inside handlePuzzleReadyChain)
    //     IS a real native ADB template, cropped live the same day -- normal confidence.
    // "it's clicking the red book, but it's missing the assemble now button in the
    // top right." The screen is finally captured --
    // ocr-debug/monument-puzzle-overview-no-assemble-2026-08-20T08-51-10 -- and everything he
    // described is on it: the "Assemble Now" button top-right, the six-sided blue reward hexagon
    // beside it, and the green-ticked album thumbnails along the bottom.
    //
    // The (450,220)-(660,300) region above was an explicit ESTIMATE (see the note above) that was
    // never checked against a real frame. Measured against the actual capture with the bundled
    // tesseract:
    //     (270,100)-(640,138) banner -> "All fragments found! Assemble to obtain"   clean
    //     (450,220)-(660,300) old    -> "m Assemble"                                marginal
    //     the button itself          -> "oe"     white-on-blue, dies in stripBackground
    // So gate on the banner. It sits on flat orange with dark text, reads perfectly, and states
    // outright that the puzzle is complete -- a better signal than the button's own label.
    private static final PointData PUZZLE_OVERVIEW_ASSEMBLE_REGION_TL = new PointData(270, 100);
    private static final PointData PUZZLE_OVERVIEW_ASSEMBLE_REGION_BR = new PointData(640, 138);
    /** Kept as a secondary read: it does contain "Assemble", just less reliably than the banner. */
    private static final PointData PUZZLE_OVERVIEW_ASSEMBLE_FALLBACK_TL = new PointData(450, 220);
    private static final PointData PUZZLE_OVERVIEW_ASSEMBLE_FALLBACK_BR = new PointData(660, 300);
    /** Button measured at x 485-632, y 258-315 on the live frame; (549,264) sat near its top edge. */
    private static final PointData PUZZLE_OVERVIEW_ASSEMBLE_NOW_BTN = new PointData(558, 286);
    /** The hexagonal blue commit button on the jigsaw screen -- see isAssembleConfirmHexPresent(). */
    private static final PointData PUZZLE_ASSEMBLE_CONFIRM_BTN = new PointData(654, 1193);
    private static final PointData ASSEMBLE_HEX_BOX_TL = new PointData(612, 1155);
    private static final PointData ASSEMBLE_HEX_BOX_BR = new PointData(697, 1232);
    private static final int ASSEMBLE_HEX_MIN_BLUE = 2200;
    private static final int ASSEMBLE_HEX_MIN_GREEN = 300;

    /** Live-verified today: a center-body tap closes the "Well done, you assembled the
     *  Puzzle!" congrats screen. */
    private static final PointData PUZZLE_ASSEMBLED_TAP_ANYWHERE = new PointData(350, 640);
    /** Live-verified today: unlike the congrats screen above, the lore card's own
     *  "Tap anywhere to close" text is unreliable -- two separate body taps at different
     *  points both failed to close it live; only its own X button worked. */
    private static final PointData PUZZLE_LORE_CARD_CLOSE_X = new PointData(645, 90);
    private static final int PUZZLE_ASSEMBLE_ANIM_SETTLE_MS = 1500;
    /** Dismiss/look cycles after the assemble commit -- see pollForPuzzleOverviewAnchor(). Sized to
     *  cover roughly 18s, comfortably past the ~5s the fixed sequence allowed and failed at, while
     *  still bounded so a genuinely wrong screen gives up and dumps a frame rather than tapping on. */
    private static final int PUZZLE_CHAIN_POLL_ATTEMPTS = 12;
    private static final int PUZZLE_CHAIN_POLL_DELAY_MS = 1500;

    // ========== Fragment Pack detail (Enable) screen ==========
    /** Quantity defaults to the full owned count already -- one Enable tap consumes
     *  all of them (confirmed live twice: stacks of 2 fully consumed in one tap). */
    private static final PointData PACK_DETAIL_ENABLE_BTN = new PointData(358, 905);
    /** The Enable button's own label, the positive anchor for tapping it. Not the pack title:
     *  "Explore the World - Mythic Scene Fragment Pack" wraps to two lines and read as junk on
     *  every pass of 2026-10-29, leaving five packs unopened. */
    static final PointData PACK_DETAIL_ENABLE_LABEL_TL = new PointData(250, 876);
    static final PointData PACK_DETAIL_ENABLE_LABEL_BR = new PointData(470, 932);
    /** "Tap anywhere to close" reward-reveal screen -- tap near the text, not dead-center. */
    private static final PointData REWARD_REVEAL_TAP_ANYWHERE = new PointData(358, 1198);

    // ========== Alliance Trade panel ==========
    /**
     * The Alliance Trade panel's own title box ("Alliance Trade", centred in the wooden header).
     * Measured off a live frame, 2026-08-20: the text spans roughly x 258-465, y 133-163; the box
     * below is padded generously around that so a slightly different render still lands inside.
     */
    private static final PointData TRADE_PANEL_TITLE_TL = new PointData(150, 122);
    private static final PointData TRADE_PANEL_TITLE_BR = new PointData(580, 180);

    private static final PointData TRADE_CLOSE_X = new PointData(662, 155);
    private static final PointData MY_REQUESTS_REQUEST_BTN = new PointData(358, 370);
    // Measured off the first live capture of this panel: "Requests Left Today (3/3)"
    // spans roughly y 262-288, so the old 268 top edge sliced the caps off every glyph. Widened both
    // ways with padding.
    private static final PointData MY_REQUESTS_LEFT_TL = new PointData(195, 256);
    private static final PointData MY_REQUESTS_LEFT_BR = new PointData(575, 298);
    private static final PointData PIECE_PICKER_REQUEST_BTN = new PointData(543, 891);
    private static final int MAX_REQUEST_LOOPS = 5;

    // ========== Piece picker (opened by My Requests' Request button) ==========
    // Request does not open a piece card. It opens a scene's piece grid with an animated hand
    // pointing at a missing piece; a card only appears once a cell is tapped. Missing cards are
    // flat grey and owned cards saturated blue, so the scan keys on colour, never on the hand
    // (it animates). Measured on two live scenes, 2026-10-28: missing rows blue <= 0.00, owned
    // cards blue >= 0.36. Grid rows shift between scenes (row centres 410/590/778/965 in one,
    // 450/635/820/1000 in the other), so cards are found by scanning each column, not by fixed y.
    /** "Complete this Scene to obtain." on the picker's own header card. */
    private static final PointData PIECE_PICKER_HEADER_TL = new PointData(280, 100);
    private static final PointData PIECE_PICKER_HEADER_BR = new PointData(630, 140);
    private static final PointData PIECE_PICKER_CLOSE_X = new PointData(680, 40);
    private static final int[] PIECE_GRID_COLUMN_X = {162, 361, 560};
    private static final int PIECE_GRID_HALF_WIDTH = 60;
    /** Below the header card, which covers a partly scrolled top row, and above the scene strip. */
    private static final int PIECE_GRID_TOP_Y = 350;
    private static final int PIECE_GRID_BOTTOM_Y = 1064;
    /** A card interior is ~150px tall; anything shorter is a sliver at the grid edge. */
    private static final int PIECE_GRID_MIN_RUN = 80;
    /** The gem-spending "Obtain more" sits left of Request on the same card; its label proves the card. */
    private static final PointData PIECE_CARD_OBTAIN_LABEL_TL = new PointData(40, 865);
    private static final PointData PIECE_CARD_OBTAIN_LABEL_BR = new PointData(320, 915);
    /** First request raises a one-button Tips dialog; a second open request a Cancel/Confirm pair. */
    private static final PointData REQUEST_TIPS_CONFIRM_BTN = new PointData(358, 788);
    private static final PointData REQUEST_TIPS_CONFIRM_LABEL_TL = new PointData(250, 765);
    private static final PointData REQUEST_TIPS_CONFIRM_LABEL_BR = new PointData(466, 812);
    private static final PointData REQUEST_REPLACE_CANCEL_BTN = new PointData(208, 788);
    private static final PointData REQUEST_REPLACE_CANCEL_LABEL_TL = new PointData(110, 765);
    private static final PointData REQUEST_REPLACE_CANCEL_LABEL_BR = new PointData(306, 812);
    /** Taps of "Tap anywhere to close" before giving up on getting back to the trade panel. */
    private static final int TRADE_PANEL_RETURN_ATTEMPTS = 4;

    // My Requests row has three states, each told apart by a solid button colour:
    //   1. Request -- blue slab centred at ~358: no open request, free to ask.
    //   2. Claim -- green slab at ~583: an ally filled the request (see isClaimButtonPresent()).
    //   3. Requesting... -- grey-green text with an orange Cancel slab at ~583: one is open.
    // Measured 2026-10-28: Request state 11350 blue px in REQUEST_BTN_BOX and 0 orange in
    // CLAIM_BTN_BOX; open-request state 0 blue and 4464 orange. An OCR box over the label read
    // "Requesting..." as "Request", so the label is not read.
    private static final PointData REQUEST_BTN_BOX_TL = new PointData(230, 340);
    private static final PointData REQUEST_BTN_BOX_BR = new PointData(490, 395);
    private static final int REQUEST_BLUE_MIN_PIXELS = 3000;
    private static final int CANCEL_ORANGE_MIN_PIXELS = 1500;

    /** The Claim button's own slab, measured live; used for the colour check, not for OCR. */
    private static final PointData CLAIM_BTN_BOX_TL = new PointData(515, 345);
    private static final PointData CLAIM_BTN_BOX_BR = new PointData(655, 392);
    /** Live sample of the real button is (37,183,86): g-r=146, g-b=97. These sit well under that. */
    private static final int CLAIM_GREEN_MIN_G = 140;
    private static final int CLAIM_GREEN_DOMINANCE = 60;
    /** The real button fills ~3400 px of that box; 800 is a wide margin under it. */
    private static final int CLAIM_GREEN_MIN_PIXELS = 800;
    private static final PointData MY_REQUESTS_CLAIM_BTN = new PointData(574, 356);

    // ========== Ally Requests (sending spare pieces) ==========
    // Measured live 2026-10-28 on the panel as it opens, list at its top. Rows run highest Owned
    // first (Owned 1 rows above Owned 0 rows, whose Send is grey), so the first row is the only
    // one that can hold a spare. Owned 1 is the player's only copy and is never sent. Each Send
    // raises a "Confirmation" dialog that repeats the Owned count; it has Confirm and an X, no Cancel.
    static final PointData TRADES_LEFT_TL = new PointData(195, 505);
    static final PointData TRADES_LEFT_BR = new PointData(575, 545);
    private static final PointData ALLY_FIRST_ROW_SEND_BTN = new PointData(583, 710);
    static final PointData ALLY_FIRST_ROW_OWNED_TL = new PointData(520, 642);
    static final PointData ALLY_FIRST_ROW_OWNED_BR = new PointData(655, 678);
    /** Blue Send slab: 5286 blue px when active, 0 when grey or covered by the dialog. */
    private static final PointData ALLY_FIRST_ROW_SEND_BOX_TL = new PointData(515, 685);
    private static final PointData ALLY_FIRST_ROW_SEND_BOX_BR = new PointData(655, 735);
    private static final int SEND_BLUE_MIN_PIXELS = 2000;
    private static final PointData SEND_CONFIRM_TITLE_TL = new PointData(150, 412);
    private static final PointData SEND_CONFIRM_TITLE_BR = new PointData(570, 455);
    static final PointData SEND_CONFIRM_OWNED_TL = new PointData(105, 603);
    static final PointData SEND_CONFIRM_OWNED_BR = new PointData(250, 642);
    private static final PointData SEND_CONFIRM_BTN = new PointData(360, 760);
    /** Blue Confirm slab: 17271 blue px on the dialog, 0 on the trade panel. */
    private static final PointData SEND_CONFIRM_BOX_TL = new PointData(215, 725);
    private static final PointData SEND_CONFIRM_BOX_BR = new PointData(505, 795);
    private static final int SEND_CONFIRM_BLUE_MIN_PIXELS = 6000;
    private static final PointData SEND_CONFIRM_CLOSE_X = new PointData(662, 440);
    private static final int MIN_OWNED_TO_SEND = 2;
    private static final int MAX_SENDS_PER_PASS = 5;

    /**
     * Left edge of the right-hand featured-event icon rail (Events / Deals / Snowbusters and
     * friends) on this 720x1280 layout, measured off a live frame: the icons sit around x 630-700,
     * each with a red notification dot. Any MONUMENT_REWARD_BADGE match at or past this X is one of
     * those dots, not the Monument badge -- see processMonumentBadge() for the logged evidence.
     */
    private static final int EVENT_RAIL_MIN_X = 640;

    private static final int IDLE_RECHECK_MINUTES = 60;
    private static final int PANEL_SETTLE_MS = 1200;
    private static final int ACTION_SETTLE_MS = 900;
    /** The reward-reveal animation after Enable runs noticeably longer than a normal
     *  panel transition -- root cause of an earlier stuck-owned-count bug. */
    private static final int PACK_OPEN_SETTLE_MS = 1800;

    static final OcrSettingsData PANEL_TITLE_OCR_SETTINGS = OcrSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ ")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .build();

    /**
     * The "Requests Left Today (3/3)" counter was being read with
     * OWNED_COUNT_OCR_SETTINGS, whose whitelist is "OwnedOWNED:0123456789 " -- no '(' and no '/'.
     * processAllianceTradeRequests() then parses it with a regex that requires a literal open
     * paren followed by digits and a slash -- both characters the whitelist forbids. Tesseract
     * cannot emit a glyph outside its whitelist, so that parse could never match and the Request
     * path always bailed out with "No My Requests left today (or couldn't read the counter)" no
     * matter how many requests were actually available. Give the counter a whitelist that can
     * actually spell what's on screen.
     */
    static final OcrSettingsData REQUESTS_LEFT_OCR_SETTINGS = OcrSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789()/ ")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .build();

    private static final Pattern OWNED_COUNT_PATTERN = Pattern.compile("\\d+");

    static final OcrSettingsData OWNED_COUNT_OCR_SETTINGS = OcrSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("OwnedOWNED:0123456789 ")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .build();

    public MonumentRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }

    @Override
    protected void execute() {
        // A prior task (Resource Stockpile Scan, in particular) can
        // leave its own popup open when Monument's turn comes up. Clear it first,
        // unconditionally, before searching for anything.
        clearStrayPopups();

        backpackSweptThisRun = false;

        if (!findAndOpenBadgeViaLancer()) {
            // The Fragment Backpack is checked on EVERY visit, not only when a badge let us in.
            // Requested directly after a live run: "every single scenario of the monument, before
            // you exit, you always have to check the frag backpack."
            //
            // Without this the routine has a catch-22 that hides owned packs indefinitely. All three
            // entry states -- the scroll-and-quill badge, the red binder, and the puzzle-ready icon
            // -- are TRIGGERS that get consumed. Once they are spent there is nothing to detect, so
            // the routine turns around at the door and reschedules; but packs sitting in the shared
            // backpack are exactly the case where no trigger is showing. The thing most likely to
            // hold unclaimed rewards was therefore the one thing never looked at.
            sweepFragmentBackpackWithoutBadge();
            logInfo(logLine("No rewards-ready badge found right now. Rechecking in "
                    + IDLE_RECHECK_MINUTES + " minutes."));
            reschedule(LocalDateTime.now().plusMinutes(IDLE_RECHECK_MINUTES));
            return;
        }

        logInfo(logLine("Badge opened. Claiming any ready rows."));
        claimAllReadyRows();

        tapNear(MODAL_CLOSE_X);
        sleepTask(PANEL_SETTLE_MS);

        tapNear(ATLAS_BACK_ARROW);
        sleepTask(ACTION_SETTLE_MS);

        // Moved here (was called before the back-arrow, at a coordinate that
        // doesn't exist on that screen -- see class header). This is the real Tundra Albums
        // hub, where Fragment Backpack's actual button lives.
        //
        // Routed through the one shared hub sweep so this path and the no-badge path cannot drift:
        // whichever way we got here, the chest track, a completed album and the Fragment Backpack
        // all get handled.
        logInfo(logLine("On Tundra Albums. Running the hub sweep."));
        sweepAlbumsHub();

        tapNear(ALBUMS_BACK_ARROW);
        sleepTask(ACTION_SETTLE_MS);

        StatisticsService.obtain().addToCounter(profile, "Monument Pass Completed", 1);
        logInfo(logLine("Monument pass complete. Rechecking in " + IDLE_RECHECK_MINUTES + " minutes."));
        reschedule(LocalDateTime.now().plusMinutes(IDLE_RECHECK_MINUTES));
    }

    // "moved right three hundred pixels...
    // then it just, like, went to the events tab." Root cause found from evidence, not guessed --
    // the ONLY post-tap check below was "is the old badge template no longer visible", which is a
    // bad proxy for "the tap actually opened something": a tap that misses the badge (scroll drift,
    // a slightly different swipe landing spot, whatever) also makes the old badge unmatchable, so
    // this check reported false success just as often as real success. On false success, execute()
    // cascades straight into tapNear(MODAL_CLOSE_X) at (662,157) -- which sits almost exactly on
    // the real Events-tab icon's screen position. That's the mechanism: missed tap -> false "opened"
    // -> blind tap at (662,157) -> Events tab opens. This was flagged as a known risk in this same
    // method's comment history before it was actually observed live.
    //
    // Fix: EVENTS_TAB_HALL_OF_CHIEFS / _DEFEAT_BEASTS / _BROTHERS_IN_ARMS / _HERO_RALLY /
    // _LUCKY_WHEEL are the Events panel's own tab-selector icons -- all of them render together
    // across the top of the Events panel regardless of which sub-tab is active (see
    // EventClaimRoutine), so any one of them matching is a reliable "we ended up in Events, not
    // Monument" signal, independent of whether the Monument badge template happens to still be
    // readable. Checked explicitly before treating the badge tap as a success; on a hit this backs
    // out with pressBack() (which already carries the quit-dialog guard) instead of ever tapping
    // MODAL_CLOSE_X on a screen that isn't the Atlas panel.
    private static final TemplatesEnum[] EVENTS_TAB_LANDING_SIGNS = {
            TemplatesEnum.EVENTS_TAB_HALL_OF_CHIEFS,
            TemplatesEnum.EVENTS_TAB_DEFEAT_BEASTS,
            TemplatesEnum.EVENTS_TAB_BROTHERS_IN_ARMS,
            TemplatesEnum.EVENTS_TAB_HERO_RALLY,
            TemplatesEnum.EVENTS_TAB_LUCKY_WHEEL,
    };

    /**
     * Navigates to Lancer Camp (a fixed, always-reachable building) then a single
     * confirmed 300px right swipe brings Monument's badge into view. rebuilt from
     * scratch (fourth pass): no fixed-pixel tap of any kind anymore. After the swipe, this does a
     * real template search for {@link TemplatesEnum#MONUMENT_REWARD_BADGE} and taps exactly where
     * it's actually found -- immune to whatever's making the camera's landing spot vary run to run,
     * since a guessed static point can never account for that but a live search always finds the
     * real position. Uses {@link SearchConfigConstants#MONUMENT_BADGE_SEARCH} (a deliberately loose
     * threshold -- see that constant's comment for why) and logs the real match score every time,
     * hit or miss, so the threshold can be tightened later against real evidence instead of another
     * guess. If the badge genuinely isn't found, this returns false and taps nothing at all -- no
     * fallback coordinate, ever. The tap is verified two ways afterward: the same template must no
     * longer be findable (something actually opened), and none of the Events panel's own tab icons
     * can be detected (in case a tap that did land somewhere still missed the badge specifically).
     */
    private boolean findAndOpenBadgeViaLancer() {
        marchHelper.openLeftMenuCitySection(true);
        sleepTask(500);

        tapInside(LANCER_AREA_TOP_LEFT, LANCER_AREA_BOTTOM_RIGHT, 1, 500);
        tapInside(CAMP_TAP_TOP_LEFT, CAMP_TAP_BOTTOM_RIGHT, 1, 300);
        sleepTask(POST_LANCER_WAIT_MS);

        swipe(SWIPE_RIGHT_START, SWIPE_RIGHT_END, SWIPE_DURATION_MS);
        sleepTask(POST_SWIPE_WAIT_MS);

        // Observed live: a completed Scene Fragment set shows a SEPARATE icon at this
        // same landing spot -- a spiral notebook with an orange puzzle-piece speech bubble -- distinct
        // from the scroll-with-a-feather MONUMENT_REWARD_BADGE. Template cropped from a live 720x1280
        // ADB frame, self-verified 1.0 match against its source. Gated first step by explicit
        // request: identify it, tap it, and stop here -- the Assemble/puzzle-solve/lore-card/Fragment-
        // Backpack chain after it is a separate, deliberately-untested-yet next step, not guessed now.
        // Observed live: threshold=30 (MONUMENT_BADGE_SEARCH)
        // was letting an unrelated building's badge (an Alliance-Tech-style scale/briefcase icon)
        // false-match this template at 35.965%/40.535% across two real runs, short-circuiting the
        // whole routine before it ever reached the real Monument tower or Claim All. See
        // MONUMENT_PUZZLE_READY_ICON_SEARCH's own comment for the full evidence.
        // "it pans to the right of lancer, you see monument, then it just starts
        // scrolling around." The 8-direction pan-fallback that used to live here is gone for good --
        // that's the actual "scrolling around" behavior flagged. This is a single search at the
        // landing spot, nothing more; on a miss it stops and reschedules (see below), it does not pan.
        //
        // Evidence: two consecutive live misses
        // logged real scores of 40.7 and 50.6 (threshold 65) -- both well below, and different from
        // each other on a supposedly-static template, which single-scale correlation is known to do
        // when the on-screen icon renders at a slightly different size than the template was captured
        // at. Switched to locatePatternMultiScale (already used elsewhere, e.g.
        // UpgradeBuildingsRoutine.tapAllianceHelp()) to test multiple scales per attempt instead of
        // exactly one -- if this raises the logged score meaningfully, that confirms scale was the
        // real problem; if it doesn't, that's real evidence pointing somewhere else next.
        // The badge has (at least) TWO states and only one was ever searched for.
        // Watched both live within four minutes of each other on the same tower: the gold puzzle
        // piece with the blue swap arrow (which opens Alliance Trade), and, once its Claim had been
        // collected, a scroll-and-quill -- the "scroll+feather" this file's comments have described
        // all along. Scored against the real frames, each template is decisive on its own state and
        // clearly negative on the other's (97-98% / 89.97% vs 42-45%), so they're separate icons,
        // not one icon rendering differently. Search both instead of walking a single threshold down
        // until noise matches, which is precisely how this ended up at threshold=30 tapping the
        // Events rail and empty snow.
        ImageSearchResultData badge = templateSearchHelper.locatePatternMultiScale(
                TemplatesEnum.MONUMENT_REWARD_BADGE, SearchConfigConstants.MONUMENT_BADGE_SEARCH);
        logInfo(logLine("Badge template search result (puzzle/swap state, multi-scale): " + badge));
        if (!badge.isFound()) {
            badge = templateSearchHelper.locatePatternMultiScale(
                    TemplatesEnum.MONUMENT_REWARD_BADGE_SCROLL, SearchConfigConstants.MONUMENT_BADGE_SEARCH);
            logInfo(logLine("Badge template search result (scroll/quill state, multi-scale): " + badge));
        }

        if (!badge.isFound()) {
            // This puzzle-ready search used to run BEFORE the badge search, and
            // overnight that cost six consecutive hourly passes. Once the tower switched to the
            // scroll/quill badge, MONUMENT_PUZZLE_READY_ICON -- a cluttered 115x115 crop of a brown
            // scroll -- began matching that badge and hijacking it into handlePuzzleReadyChain(),
            // which then failed every time on its own "Assemble Now" gate:
            //     01:26 HIT 72.779 -> no 'Assemble Now' (read: 'null')
            //     02:29 HIT 57.331 -> no 'Assemble Now' (read: 'WP neseraie')
            //     03:31 HIT 58.607, 04:34 HIT 74.692, 05:36 HIT 71.874, 06:38 -- same, every hour.
            // Note the scores swinging 41 -> 75 on a static icon: that template is unstable, which
            // is also how it missed at 41.344 in the one pass that DID take the badge route -- and
            // that pass claimed a reward and opened 2 packs. So the badge route works and this one
            // does not; the only reason the badge route ever ran was that unstable score dipping.
            // Search the badges first (both score 87-98% and are stable) and only fall back to
            // puzzle-ready when neither badge state is present.
            ImageSearchResultData puzzleReady = templateSearchHelper.locatePatternMultiScale(
                    TemplatesEnum.MONUMENT_PUZZLE_READY_ICON, SearchConfigConstants.MONUMENT_PUZZLE_READY_ICON_SEARCH);
            logInfo(logLine("No badge in either state; puzzle-ready icon search result (multi-scale): "
                    + puzzleReady));
            if (puzzleReady.isFound()) {
                logInfo(logLine("Puzzle-ready icon found at " + puzzleReady.getPoint()
                        + " -- tapping it and running the assemble/fragment-backpack chain."));
                tapNear(puzzleReady.getPoint());
                sleepTask(PANEL_SETTLE_MS);
                handlePuzzleReadyChain();
                return false;
            }

            logInfo(logLine("Badge not found this pass -- nothing to tap, not guessing a coordinate. "
                    + dumpDiagnosticFrame("badge-not-found")));
            return false;
        }

        // "you're clicking the monument, and you're clicking the fucking events tab
        // in the top right." The coordinates prove him exactly right. The right-hand UI rail carries
        // the featured-event icons (currently Events / Deals / Snowbusters) stacked around x 630-700,
        // and each one wears a small red notification dot. MONUMENT_BADGE_SEARCH runs a full-screen
        // multi-scale search at threshold=30, which is loose enough for one of those red dots to win
        // outright. Logged evidence across four consecutive runs, and it splits perfectly by X:
        //
        //   20:17  HIT @(515,316) score=45.068  -> reached Tundra Albums   (real Monument)
        //   23:29  HIT @(456,318) score=45.814  -> reached Tundra Albums   (real Monument)
        //   23:41  HIT @(699,417) score=50.013  -> landed on the wrong screen
        //   00:43  HIT @(693,417)               -> landed on the wrong screen
        //
        // Every good match is central; every bad one is out in the rail. The scores don't separate
        // them (45.0 vs 50.0 -- and the file's own notes record a genuine tap scoring 89.44), so no
        // threshold tweak can tell these apart without also throwing away real hits. Position can.
        // Monument is a building out in the city view and is never in that rail, so a match there is
        // a red notification dot, not the Monument badge. Reject it instead of tapping it.
        if (badge.getPoint().getX() >= EVENT_RAIL_MIN_X) {
            logWarning(logLine("Badge matched at " + badge.getPoint() + " -- that's inside the right-hand "
                    + "event rail (x >= " + EVENT_RAIL_MIN_X + "), where the Events/Deals/Snowbusters icons "
                    + "carry red notification dots that this template matches at threshold=30. Monument is a "
                    + "city building and is never there, so this is a false positive, not the badge. Not "
                    + "tapping it -- that tap is what opens the Events tab. "
                    + dumpDiagnosticFrame("badge-matched-in-event-rail")));
            return false;
        }

        tapNear(badge.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        // Observed live twice, two days apart -- "it just went to the events
        // tab"): the EVENTS_TAB_LANDING_SIGNS check below enumerated 5 SPECIFIC rotating event
        // banners (Hall of Chiefs, Defeat Beasts, Brothers in Arms, Hero Rally, Lucky Wheel).
        // Whiteout Survival's live event roster changes -- confirmed live via screenshot the
        // featured events right now are Events/Deals/Snowbusters, none of which are in that list
        // -- so this "did we land on Events" check can never fire once the seasonal events differ
        // from whatever 5 were hardcoded, no matter how badly it actually did land there. A
        // negative check enumerating every possible wrong screen is the wrong shape entirely; a
        // positive check for "did we actually land on Monument" doesn't care what's on Events.
        // MONUMENT_TUNDRA_ALBUMS_OPTION / MONUMENT_ATLAS_CLAIM_BUTTON / _CLAIM_ALL_BUTTON are all
        // Monument-Atlas-panel-specific UI, not tied to any rotating content -- any one present
        // means we're really on Monument; none present means we're not, whatever screen this is.
        boolean onMonumentPanel =
                templateSearchHelper.locatePattern(TemplatesEnum.MONUMENT_TUNDRA_ALBUMS_OPTION, SearchConfigConstants.QUICK_SEARCH).isFound()
                || templateSearchHelper.locatePattern(TemplatesEnum.MONUMENT_ATLAS_CLAIM_BUTTON, SearchConfigConstants.QUICK_SEARCH).isFound()
                || templateSearchHelper.locatePattern(TemplatesEnum.MONUMENT_ATLAS_CLAIM_ALL_BUTTON, SearchConfigConstants.QUICK_SEARCH).isFound();
        if (!onMonumentPanel) {
            // With the badge template finally correct (see 761450f), the very first
            // real badge tap -- at (367,537), the actual badge rather than noise -- landed straight on
            // the ALLIANCE TRADE panel, captured in
            // ocr-debug/monument-landed-off-monument-2026-08-20T00-56-15. Not the Monument panel, and
            // not a wrong screen either: the gold-puzzle-piece-with-blue-swap-arrow badge IS the
            // Alliance Trade badge, and tapping it is a direct shortcut. This whole routine assumed
            // badge -> Monument -> Tundra Albums -> Alliance Trade button, so the one screen this has
            // been asking for all night was being opened and then immediately backed out of as
            // "whatever screen this is".
            //
            // The panel's own coordinates were never the problem -- checked against that live frame,
            // every existing constant is right (close X 662,155 vs 662,157; Claim 574,356 inside the
            // real button at 583,367; ally Send 583,712 vs 583,710; row spacing 237 vs a measured
            // 238). They had simply never been reached. So: recognise the screen and do the work.
            String tradeTitle = stringHelper.attemptRecognition(
                    TRADE_PANEL_TITLE_TL, TRADE_PANEL_TITLE_BR,
                    2, 150L, PANEL_TITLE_OCR_SETTINGS,
                    s -> s != null && !s.isBlank(),
                    s -> s.toLowerCase());
            if (tradeTitle != null && (tradeTitle.contains("alliance") || tradeTitle.contains("trade"))) {
                logInfo(logLine("Badge opened Alliance Trade directly (title read as '" + tradeTitle
                        + "') -- that badge is the Alliance Trade badge, not a Monument-panel badge. "
                        + "Running the My Requests pass right here instead of backing out."));
                processAllianceTradeRequests();
                tapNear(TRADE_CLOSE_X);
                sleepTask(ACTION_SETTLE_MS);
                return false;
            }

            logWarning(logLine("Tapped the real matched badge at " + badge.getPoint()
                    + " but none of Monument's own panel signals (Tundra Albums option / Claim / Claim All) "
                    + "are present, and the panel title didn't read as Alliance Trade either (read: '"
                    + tradeTitle + "') -- didn't actually land on Monument, whatever screen this is. Backing "
                    + "out instead of cascading blind taps onto the wrong screen. Recovering toward Home. "
                    + dumpDiagnosticFrame("landed-off-monument")));
            recoverTowardHome();
            return false;
        }

        // A verification check must actually rule things OUT to mean anything -- MONUMENT_BADGE_SEARCH's
        // threshold=30 is tuned for finding the real badge pre-tap (including at the low point of its
        // own bounce animation), not for ruling it out on the DIFFERENT screen that opens post-tap. Real
        // logged evidence this was a false positive every single pass: see
        // SearchConfigConstants#MONUMENT_BADGE_STILL_THERE_CHECK's header.
        ImageSearchResultData badgeStillThere = templateSearchHelper.locatePatternMultiScale(
                TemplatesEnum.MONUMENT_REWARD_BADGE, SearchConfigConstants.MONUMENT_BADGE_STILL_THERE_CHECK);
        if (badgeStillThere.isFound()) {
            logWarning(logLine("Tapped the real matched badge at " + badge.getPoint()
                    + " but a badge match is still detectable afterward (" + badgeStillThere
                    + ") -- nothing opened. Stopping here instead of cascading into the rest of the chain blind."));
            return false;
        }

        return true;
    }

    /**
     * The assemble/congrats/lore-card/Fragment-Backpack chain that
     * {@link #findAndOpenBadgeViaLancer()} used to gate and stop before. See the class-level
     * "Puzzle-ready chain" constants comment above for which coordinates here are real
     * native templates vs first-pass rescaled estimates. Every estimated tap is followed by
     * a real check before the next step trusts it -- this never cascades blind.
     */
    private void handlePuzzleReadyChain() {
        // "Assemble Now" only renders once the puzzle genuinely has every fragment (15/15).
        // If the icon tap instead opened an in-progress puzzle, there's nothing to assemble --
        // bail out cleanly rather than tapping an estimated button that isn't there.
        String overviewText = stringHelper.attemptRecognition(
                PUZZLE_OVERVIEW_ASSEMBLE_REGION_TL, PUZZLE_OVERVIEW_ASSEMBLE_REGION_BR,
                2, 150L, PANEL_TITLE_OCR_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s);
        if (overviewText == null || !overviewText.toLowerCase().contains("assemble")) {
            String fallback = stringHelper.attemptRecognition(
                    PUZZLE_OVERVIEW_ASSEMBLE_FALLBACK_TL, PUZZLE_OVERVIEW_ASSEMBLE_FALLBACK_BR,
                    2, 150L, PANEL_TITLE_OCR_SETTINGS,
                    s -> s != null && !s.isBlank(),
                    s -> s);
            logInfo(logLine("Assemble banner read as '" + overviewText + "'; button-area fallback read as '"
                    + fallback + "'."));
            if (fallback != null && fallback.toLowerCase().contains("assemble")) {
                overviewText = fallback;
            }
        }
        if (overviewText == null || !overviewText.toLowerCase().contains("assemble")) {
            // "it's clicking the red book, but it's missing the assemble now button
            // in the top right." This region was an ESTIMATE that has never once been checked against
            // a real capture of this screen -- it failed six times overnight reading 'null',
            // 'WP neseraie', 'WP ncceraie', 'WP reverie'. Dump the frame so the button's actual
            // position can be measured instead of estimated a second time.
            logInfo(logLine("Puzzle-ready icon opened, but no 'Assemble Now' text confirmed via OCR "
                    + "(read: '" + overviewText + "') in region " + PUZZLE_OVERVIEW_ASSEMBLE_REGION_TL
                    + "->" + PUZZLE_OVERVIEW_ASSEMBLE_REGION_BR + " -- either the puzzle isn't actually "
                    + "complete yet, or the estimated OCR region is off. Not tapping blind; recovering "
                    + "toward Home. " + dumpDiagnosticFrame("puzzle-overview-no-assemble")));
            // Standing rule: the Fragment Backpack is checked on EVERY visit. A frame captured here
            // 2026-08-21 shows exactly why that matters -- the album detail screen (Rekindled Flames,
            // 9/9, every card Complete) carries its OWN Fragment Backpack button, and it was wearing a
            // count of 8. Walking away because the assemble step could not be confirmed left eight
            // packs sitting in plain sight. The backpack is worth sweeping from wherever it is
            // reachable, not only from the hub.
            sweepFragmentBackpackIfVisible();
            recoverTowardHome();
            return;
        }

        logInfo(logLine("'Assemble Now' confirmed via OCR -- tapping."));
        tapNear(PUZZLE_OVERVIEW_ASSEMBLE_NOW_BTN);
        sleepTask(PUZZLE_ASSEMBLE_ANIM_SETTLE_MS);

        // Describing this from memory before it was ever captured: "there should be
        // [a] six sided blue [button] with a puzzle in it with a green checkbox maybe in the bottom
        // right." Exactly right. Tapping Assemble Now does not finish anything -- it opens an
        // interactive jigsaw screen with the pieces scattered on a wooden board, and the assembly is
        // only committed by a hexagonal blue button carrying a 4-piece puzzle and a green tick, at
        // (654,1193). The chain never tapped it: it went straight to a blind "tap anywhere" at
        // (350,640), which on that screen just prods the board, so the puzzle was never assembled
        // and the run ended at "Fragment Backpack icon not found".
        if (isAssembleConfirmHexPresent()) {
            logInfo(logLine("Assemble confirm hexagon present -- tapping it to commit the assembly."));
            tapNear(PUZZLE_ASSEMBLE_CONFIRM_BTN);
            sleepTask(PUZZLE_ASSEMBLE_ANIM_SETTLE_MS);
        } else {
            logInfo(logLine("No assemble-confirm hexagon on screen after tapping Assemble Now -- either "
                    + "this album commits without the jigsaw step, or the tap didn't land. Continuing to "
                    + "the congrats/lore-card sequence, which re-anchors on a real template below."));
        }

        // Observed live 2026-08-20 22:24: the commit above worked -- the banner read
        // "Assemble Now", the hexagon measured 4633 blue / 410 green and was tapped -- and the run
        // still died here on "Fragment Backpack icon not found after 1 attempts".
        //
        // The old sequence was tap(congrats) / sleep 900 / tap(loreX) / sleep 1200 / look ONCE:
        // 3.6s of FIXED timing covering an assembly animation, a congrats screen and a lore card,
        // with two blind taps in between. Both of those tap coordinates are rescaled desktop
        // estimates that have never been checked against a native 720x1280 frame (see the
        // "Puzzle-ready chain" constants comment). So the taps fire into whatever happens to be on
        // screen at 900ms boundaries, and a single look decides the outcome.
        //
        // Poll for the real anchor instead of timing the animation. The Fragment Backpack icon IS a
        // real native template, so it is the thing worth waiting for; between looks, dismiss with
        // the congrats body tap and the lore-card X in turn, since only the X closes the lore card
        // and only a body tap closes the congrats screen. Whichever screen is actually up receives
        // the tap that closes it, order does not matter, and the loop exits the moment the anchor
        // appears -- including immediately, before any tap, if the chain already landed.
        ImageSearchResultData backpackIcon = pollForPuzzleOverviewAnchor();
        if (!backpackIcon.isFound()) {
            logWarning(logLine("Fragment Backpack icon never appeared across "
                    + PUZZLE_CHAIN_POLL_ATTEMPTS + " dismiss/look cycles (~"
                    + (PUZZLE_CHAIN_POLL_ATTEMPTS * PUZZLE_CHAIN_POLL_DELAY_MS / 1000)
                    + "s) after the assemble commit. Recovering toward Home instead of continuing "
                    + "blind. " + dumpDiagnosticFrame("puzzle-chain-no-backpack-icon")));
            recoverTowardHome();
            return;
        }

        logInfo(logLine("Puzzle overview confirmed (Fragment Backpack icon found at " + backpackIcon.getPoint()
                + "). Processing the shared Fragment Backpack."));
        processFragmentBackpack(backpackIcon.getPoint());

        // Two levels deep here (puzzle overview -> Tundra Albums hub -> City/Home), vs one
        // level for the normal MONUMENT_REWARD_BADGE flow -- confirmed live today (back arrow
        // from the puzzle overview landed on Tundra Albums, a second back arrow from there
        // landed on City). ALBUMS_BACK_ARROW's coordinate matches both screens closely enough
        // (both top-left back arrows render in the same spot across this shared skin).
        tapNear(ALBUMS_BACK_ARROW);
        sleepTask(ACTION_SETTLE_MS);
        tapNear(ALBUMS_BACK_ARROW);
        sleepTask(ACTION_SETTLE_MS);

        StatisticsService.obtain().addToCounter(profile, "Monument Puzzle Assembled", 1);
        logInfo(logLine("Puzzle-ready chain complete."));
    }

    /**
     * Waits for the puzzle overview to come back after an assemble commit, dismissing whatever
     * celebration screen is in the way, and returns the Fragment Backpack icon once it is really on
     * screen.
     *
     * <p>Written as look-then-dismiss rather than dismiss-then-look on purpose: if the chain already
     * landed on the overview, this returns on the very first look without tapping anything, so it
     * cannot knock a correct screen off course. Only when the anchor is absent does it tap, and it
     * alternates the two dismissals because they are not interchangeable -- the congrats screen
     * closes on a body tap, while the lore card ignores body taps and closes only on its own X
     * (both verified live). Alternating means the order the two screens appear in does not matter.
     *
     * @return the located icon, or the last unsuccessful result if it never appeared
     */
    private ImageSearchResultData pollForPuzzleOverviewAnchor() {
        ImageSearchResultData icon = null;
        for (int attempt = 1; attempt <= PUZZLE_CHAIN_POLL_ATTEMPTS; attempt++) {
            icon = templateSearchHelper.locatePattern(
                    TemplatesEnum.MONUMENT_PUZZLE_OVERVIEW_FRAGMENT_BACKPACK_ICON,
                    SearchConfigConstants.DEFAULT_SINGLE);
            if (icon.isFound()) {
                logInfo(logLine("Puzzle overview anchor found on dismiss/look cycle " + attempt
                        + " of " + PUZZLE_CHAIN_POLL_ATTEMPTS + "."));
                return icon;
            }
            if (attempt % 2 == 1) {
                tapNear(PUZZLE_ASSEMBLED_TAP_ANYWHERE);
            } else {
                tapNear(PUZZLE_LORE_CARD_CLOSE_X);
            }
            sleepTask(PUZZLE_CHAIN_POLL_DELAY_MS);
        }
        return icon;
    }

    /**
     * Color sanity check for a template match, by design ("it's a
     * big green claim button... how accurate do you have to be?"). Averages the RGB pixels inside
     * the matched region straight from a live emulator frame and requires green to genuinely
     * dominate red and blue -- not just edge them out. Measured live margins make this an easy
     * call: the real button averages roughly (82,179,100) -- green beats red by ~97 and blue by
     * ~79 -- while the disabled lookalike that fooled the shape match averages roughly
     * (122,124,126), i.e. red/green/blue within 4 of each other, nowhere close to green-dominant.
     * GREEN_DOMINANCE_MARGIN=40 sits well under the real button's ~79-97 margins and well over the
     * lookalike's ~2, a real gap either side rather than a fragile guess.
     *
     * <p>Only handles the common 32bpp (RGBA_8888) capture format this emulator normally returns;
     * on any other format this can't verify color and returns true (fail-open to the template
     * match result alone, i.e. today's prior behavior) rather than silently blocking every claim.
     */
    /**
     * Every Monument fix so far has been a guess at coordinates, because when an
     * OCR gate fails all it reports is the garbled text -- never WHAT SCREEN it was looking at.
     * "read as 'd to perform i epic pbuyy'" says the region holds prose instead of a button label,
     * but not whether the Alliance Trade panel failed to open, opened on a different default tab,
     * or opened behind a first-run Tips dialog. Those need different fixes and the log cannot tell
     * them apart. So on failure, write the actual frame to disk; the next run leaves real evidence
     * to calibrate against instead of another round of guessing.
     *
     * <p>Deliberately best-effort: a diagnostic must never be able to break the routine it is
     * diagnosing, so every failure here is swallowed and reported inline in the caller's log line.
     */
    private String dumpDiagnosticFrame(String tag) {
        try {
            RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
            if (frame == null) {
                return "(diagnostic frame unavailable: capture returned null)";
            }
            // Beside the installation rather than at one machine's copy of it. Hardcoded, this
            // wrote diagnostics to C:/Bearguard on every machine that ran it -- which on anybody
            // else's is a directory that has nothing to do with them, and on a drive that may not
            // exist. The frame is then saved somewhere the person looking for it will not think to
            // look, which for a diagnostic is the same as not saving it.
            java.io.File dir = new java.io.File(
                    System.getProperty("user.dir"), "ocr-debug");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return "(diagnostic frame not saved: could not create " + dir + ")";
            }
            String stamp = LocalDateTime.now().toString().replace(':', '-').replace('.', '-');
            java.io.File out = new java.io.File(dir, "monument-" + tag + "-" + stamp + ".png");
            javax.imageio.ImageIO.write(
                    dev.frostguard.vision.convert.ImageConverter.toBufferedImage(frame), "png", out);
            return "Diagnostic frame saved: " + out.getAbsolutePath();
        } catch (Exception e) {
            return "(diagnostic frame not saved: " + e.getMessage() + ")";
        }
    }

    private boolean isRegionPredominantlyGreen(ImageSearchResultData match) {
        RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
        if (frame == null || frame.getBpp() != 32) {
            return true;
        }

        SizeData size = match.getTemplateSize();
        int w = size != null ? size.getWidth() : 40;
        int h = size != null ? size.getHeight() : 20;
        int x0 = Math.max(0, match.getPoint().getX() - w / 2);
        int y0 = Math.max(0, match.getPoint().getY() - h / 2);
        int x1 = Math.min(frame.getWidth(), x0 + w);
        int y1 = Math.min(frame.getHeight(), y0 + h);

        byte[] px = frame.getFrameBytes();
        int stride = frame.getWidth() * 4;
        long r = 0, g = 0, b = 0;
        int count = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int offset = y * stride + x * 4;
                if (offset + 2 >= px.length) continue;
                r += px[offset] & 0xFF;
                g += px[offset + 1] & 0xFF;
                b += px[offset + 2] & 0xFF;
                count++;
            }
        }
        if (count == 0) return true;

        double avgR = (double) r / count;
        double avgG = (double) g / count;
        double avgB = (double) b / count;
        boolean isGreen = avgG - avgR >= GREEN_DOMINANCE_MARGIN && avgG - avgB >= GREEN_DOMINANCE_MARGIN;
        logInfo(logLine(String.format(
                "Color check at %s: avgRGB=(%.0f,%.0f,%.0f) -- %s",
                match.getPoint(), avgR, avgG, avgB, isGreen ? "green, trusting the match" : "NOT green, rejecting")));
        return isGreen;
    }

    /**
     * Stop trying to OCR the Claim button. The bundled tesseract reads "Claim"
     * cleanly off a raw crop of it, but through the app's own pipeline -- PANEL_TITLE_OCR_SETTINGS
     * has stripBackground(true) -- the same button comes back as 's' or as nothing at all, twice
     * live. White text on a saturated green fill does not survive that preprocessing, and the panel
     * title ("Alliance Trade", white on flat orange) reading fine through the identical settings is
     * what makes the difference easy to miss.
     *
     * <p>The button doesn't need reading. It's a big solid green slab, and this was already established as
     * much about the other one in this file: "it's a big green claim button... how accurate do you
     * have to be?" Sampled straight off the live panel, the separation is not close:
     * <pre>
     *   Claim button   (37,183,86)  x3406 px   green dominant
     *   Send button    (79,165,252)            blue, different row anyway
     *   centre chevrons(36,227,59)  x265 px    G=227 vs 183, far off, and a tiny area
     * </pre>
     * So count pixels near the Claim green inside the button's own box. Present means claimable.
     *
     * <p>Fails CLOSED (returns false) if the frame is missing or isn't the usual 32bpp format --
     * an unread frame must not be reported as a claimable button, since the caller taps on a true.
     */
    private boolean isClaimButtonPresent() {
        RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
        if (frame == null || frame.getBpp() != 32) {
            logInfo(logLine("Claim-button colour check skipped (frame unavailable or not 32bpp) -- "
                    + "treating the row as not claimable rather than tapping on a guess."));
            return false;
        }

        byte[] px = frame.getFrameBytes();
        int stride = frame.getWidth() * 4;
        int matched = 0;
        for (int y = CLAIM_BTN_BOX_TL.getY(); y < CLAIM_BTN_BOX_BR.getY() && y < frame.getHeight(); y++) {
            for (int x = CLAIM_BTN_BOX_TL.getX(); x < CLAIM_BTN_BOX_BR.getX() && x < frame.getWidth(); x++) {
                int offset = y * stride + x * 4;
                if (offset + 2 >= px.length) continue;
                int c0 = px[offset] & 0xFF, g = px[offset + 1] & 0xFF, c2 = px[offset + 2] & 0xFF;
                // Deliberately channel-order agnostic. Green is the middle byte in both RGBA and
                // BGRA, but the outer two swap between them, so testing "green dominates the other
                // two" holds either way where testing a literal (37,183,86) would silently fail on
                // a BGRA frame. Checked against every colour measured on the real panel:
                //   Claim  (37,183,86)   g-r=146 g-b=97   -> passes
                //   tan bg (239,203,153) g-r=-36          -> fails
                //   white  (254,254,254) g-r=0            -> fails
                //   Send   (79,165,252)  g-b=-87          -> fails
                if (g >= CLAIM_GREEN_MIN_G
                        && g - c0 >= CLAIM_GREEN_DOMINANCE
                        && g - c2 >= CLAIM_GREEN_DOMINANCE) {
                    matched++;
                }
            }
        }
        boolean present = matched >= CLAIM_GREEN_MIN_PIXELS;
        logInfo(logLine("Claim-button colour check: " + matched + " green pixels in "
                + CLAIM_BTN_BOX_TL + "->" + CLAIM_BTN_BOX_BR + " (need " + CLAIM_GREEN_MIN_PIXELS
                + ") -- " + (present ? "Claim is present" : "no Claim button")));
        return present;
    }

    /**
     * The hexagonal blue "commit the assembly" button on the jigsaw screen: a 4-piece puzzle glyph
     * with a green tick, measured at x 616-693, y 1158-1228 on a live frame.
     *
     * <p>Detected on TWO signals together, because neither alone separates it cleanly. Measured
     * across four real captured screens:
     * <pre>
     *                        blue-hex px   green-tick px
     *   jigsaw screen            2768           409      <- the button
     *   puzzle overview             0           192      (album thumbnails carry green ticks)
     *   city map                 1538             0      (the mail/scales icons are blue)
     *   alliance trade panel        -             0
     * </pre>
     * Blue alone would fire on the city map at 1538; green alone would fire on the puzzle overview
     * at 192. Requiring both leaves a wide gap on either side and makes each threshold non-critical.
     *
     * <p>Fails closed on a missing or non-32bpp frame, since the caller taps on a true.
     */
    private boolean isAssembleConfirmHexPresent() {
        RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
        if (frame == null || frame.getBpp() != 32) {
            return false;
        }

        byte[] px = frame.getFrameBytes();
        int stride = frame.getWidth() * 4;
        int blue = 0, green = 0;
        for (int y = ASSEMBLE_HEX_BOX_TL.getY(); y < ASSEMBLE_HEX_BOX_BR.getY() && y < frame.getHeight(); y++) {
            for (int x = ASSEMBLE_HEX_BOX_TL.getX(); x < ASSEMBLE_HEX_BOX_BR.getX() && x < frame.getWidth(); x++) {
                int offset = y * stride + x * 4;
                if (offset + 2 >= px.length) continue;
                int c0 = px[offset] & 0xFF, g = px[offset + 1] & 0xFF, c2 = px[offset + 2] & 0xFF;
                // Green is the middle byte in both RGBA and BGRA, so "green dominates both others"
                // is channel-order safe. The hexagon body is the opposite case -- green sits BETWEEN
                // the other two -- which is equally order-safe to test.
                if (g >= 150 && g - c0 >= 50 && g - c2 >= 50) {
                    green++;
                } else {
                    int hi = Math.max(c0, c2), lo = Math.min(c0, c2);
                    if (hi > 150 && hi - lo > 40 && hi - g > 20) {
                        blue++;
                    }
                }
            }
        }
        boolean present = blue >= ASSEMBLE_HEX_MIN_BLUE && green >= ASSEMBLE_HEX_MIN_GREEN;
        logInfo(logLine("Assemble-confirm hexagon check: " + blue + " blue px (need "
                + ASSEMBLE_HEX_MIN_BLUE + ") and " + green + " green-tick px (need "
                + ASSEMBLE_HEX_MIN_GREEN + ") -- " + (present ? "present" : "absent")));
        return present;
    }

    private String logLine(String note) {
        return "MonumentRoutine | " + note;
    }

    /**
     * Confirmed live that Resource Stockpile Scan's "Overview"
     * panel does NOT close on a back press -- it's a game-rendered modal, not a
     * native Android view, so the system back key is simply ignored by it. A
     * different task later left a DIFFERENT panel open ("Resource &amp; Speedup
     * Summary") proving one hardcoded close spot will never keep up with however
     * many other tasks can leave something open -- rebuilt as a real loop: repeat
     * (press back several times, tap every known stray-panel close spot) and
     * re-check via search after each round, stopping the moment the badge or a
     * clean Home screen is confirmed.
     *
     * <p>
     * Root-caused live, by watching the actual screen after a run --
     * this game's own back-button behavior on the City/Home view is to ZOOM OUT to
     * the World strategic map, not to close nothing-there / exit. With nothing open
     * (the overwhelmingly common case), the blind {@code pressBack()} x3 x4-rounds
     * batch below zoomed the camera all the way out to World every single time --
     * confirmed by a live screenshot immediately after a run landing squarely on the
     * World map. From World, Monument's badge/building templates (Home-only) can
     * never match again, so it got permanently stuck until some unrelated task
     * happened to navigate back to Home for its own purposes. This is exactly what
     * described as the app "freaking out" / "almost exiting."
     * <p>
     * Fix: stop reinventing Home-recovery with raw back-presses. The framework
     * already has {@link dev.frostguard.engine.helper.NavigationHelper#ensureCorrectScreenLocation}
     * for exactly this -- it tells Home and World apart by template, taps the
     * correct zoom icon to get back to Home instead of guessing with more back
     * presses, and only falls back to a cautious single back-press-and-recheck loop
     * when the screen is genuinely unrecognized. Still try the known stray-panel
     * close spots first (real game-rendered modals that DON'T respond to back at
     * all), then hand recovery to the framework instead of a local back-press loop.
     */
    private void clearStrayPopups() {
        if (isScreenClear()) {
            logInfo(logLine("Screen already clear, no clearing needed."));
            return;
        }

        for (PointData closeSpot : KNOWN_STRAY_PANEL_CLOSE_SPOTS) {
            tapNear(closeSpot);
            sleepTask(300);
            if (isScreenClear()) {
                logInfo(logLine("Screen confirmed clear after a stray-panel close tap."));
                return;
            }
        }

        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);

        if (isScreenClear()) {
            logInfo(logLine("Screen confirmed clear after ensureCorrectScreenLocation(HOME)."));
        } else {
            logInfo(logLine("Home confirmed by ensureCorrectScreenLocation, but neither the reward badge "
                    + "nor the Monument building anchor matched -- proceeding anyway, the badge search "
                    + "right after this will catch a genuinely blocked screen."));
        }
    }

    /**
     * Opens the Monument with no badge to lead the way, and runs the full hub sweep.
     *
     * <p>This exists because all three entry states -- the scroll-and-quill badge, the red binder,
     * and the puzzle-ready icon -- are consumable triggers. Once spent, the old routine had nothing
     * to detect and turned around at the door, which is precisely the state in which owned fragment
     * packs and a claimable milestone chest sit waiting. findAndOpenBadgeViaLancer() has already
     * driven the camera to the Monument by the time this runs, so the building itself is on screen;
     * MONUMENT_BUILDING_ANCHOR is a real native template and is used as the tap target rather than a
     * guessed coordinate.
     *
     * <p>Nothing here cascades blind: if the anchor is not found we stop, and the hub sweep's own
     * steps each verify their screen (processFragmentBackpack OCRs the panel title and dumps a frame
     * on a miss).
     */
    private void sweepFragmentBackpackWithoutBadge() {
        if (backpackSweptThisRun) {
            return;
        }

        ImageSearchResultData building = templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_BUILDING_ANCHOR, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!building.isFound()) {
            logInfo(logLine("No badge and no Monument building anchor on screen either -- nothing to "
                    + "open, so the Fragment Backpack cannot be checked this pass. "
                    + dumpDiagnosticFrame("no-badge-no-building-anchor")));
            return;
        }

        logInfo(logLine("No badge, but the Monument building is on screen at " + building.getPoint()
                + " -- opening it anyway to check the milestone chest, any completed album, and the "
                + "Fragment Backpack."));
        tapNear(building.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        // The building no longer opens the hub directly: it raises a two-option chooser
        // (Monument / Tundra Albums) beside the tower. Every hourly pass on 2026-10-28 read that
        // chooser as "not the hub" and turned back. The option template scores 98.6-99.4 on the
        // chooser against at most 45.5 on the city view and the hub.
        ImageSearchResultData albumsOption = templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_TUNDRA_ALBUMS_OPTION, SearchConfigConstants.SINGLE_WITH_2_RETRIES);
        if (albumsOption.isFound()) {
            logInfo(logLine("Building chooser open -- tapping Tundra Albums at " + albumsOption.getPoint() + "."));
            tapNear(albumsOption.getPoint());
            sleepTask(PANEL_SETTLE_MS);
        }

        // Confirm the hub before touching anything on it. The first live run of this path tapped
        // the building anchor and landed on HERO RECRUITMENT, then swept anyway: the album-book
        // detector duly fired on that screen's glowing Points Chest (7076 px) and the chain opened a
        // recruitment panel looking for "Assemble Now". A tap that opens the wrong screen has to
        // stop here, not cascade -- which is the same rule the badge path already follows.
        if (!isOnAlbumsHub()) {
            logWarning(logLine("Tapping the Monument building did not land on the Tundra Albums hub "
                    + "-- not sweeping a screen this code hasn't identified. "
                    + dumpDiagnosticFrame("no-badge-tap-missed-hub")));
            recoverTowardHome();
            return;
        }

        sweepAlbumsHub();
        recoverTowardHome();
    }

    /** The hub's own "Tundra Albums" title, top-left. Measured on a native 720x1280 capture and
     *  read back clean with the bundled tesseract ("Tundra Albums", psm 7). */
    private static final PointData ALBUMS_HUB_TITLE_TL = new PointData(80, 16);
    private static final PointData ALBUMS_HUB_TITLE_BR = new PointData(400, 66);

    /**
     * Confirms the Tundra Albums hub is really on screen.
     *
     * <p>Gates on the panel's own title rather than on the presence of some button, because the
     * failure this exists to stop was landing on a completely different screen (Hero Recruitment)
     * that happens to carry glowing artwork and orange buttons of its own. Matching "albums" alone
     * keeps it tolerant of the OCR dropping a character from "Tundra".
     */
    private boolean isOnAlbumsHub() {
        String title = stringHelper.attemptRecognition(
                ALBUMS_HUB_TITLE_TL, ALBUMS_HUB_TITLE_BR,
                3, 200L, PANEL_TITLE_OCR_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s);
        boolean onHub = title != null && title.toLowerCase().contains("album");
        logInfo(logLine("Albums hub check: title read as '" + title + "' -- "
                + (onHub ? "on the hub" : "NOT the hub")));
        return onHub;
    }

    private boolean isScreenClear() {
        return templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_REWARD_BADGE, SearchConfigConstants.QUICK_SEARCH).isFound()
                || templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_BUILDING_ANCHOR, SearchConfigConstants.QUICK_SEARCH).isFound();
    }

    // The camera-pan fallback that used to live here (findBadgeWithPanFallback(),
    // an 8-direction, up-to-16-tap sweep) is removed entirely -- "it pans to the right of lancer,
    // you see monument, then it just starts scrolling around." Removed for good; on a miss the
    // routine now just stops and reschedules (see findAndOpenBadgeViaLancer() above) instead of
    // panning around OR guessing at an unverified fixed-pixel coordinate.

    // Real fix by design after the threshold-tuning approach above
    // (kept, still a real improvement) drew a fair "why fight a fragile number instead of the
    // obvious signal" pushback: the individual "Claim" button is solid green; the disabled
    // lookalike that fooled the shape-based template match is NOT (measured live: real button
    // RGB avg ~(82,179,100), disabled lookalike ~(122,124,126) -- essentially grey, R/G/B all
    // within 4 of each other). Rather than rely on template-match score alone, every match is now
    // also color-verified against the live screen before it's trusted -- a shape match on a grey
    // button no longer gets tapped just because its score happened to clear a threshold.
    private static final int GREEN_DOMINANCE_MARGIN = 40;

    private void claimAllReadyRows() {
        for (int i = 0; i < MAX_CLAIM_LOOPS; i++) {
            ImageSearchResultData claimBtn = templateSearchHelper.locatePattern(
                    TemplatesEnum.MONUMENT_ATLAS_CLAIM_BUTTON, SearchConfigConstants.MONUMENT_ATLAS_CLAIM_BUTTON_SEARCH);
            if (!claimBtn.isFound()) {
                logInfo(logLine("No more Claim buttons visible (" + i + " claimed)."));
                break;
            }
            if (!isRegionPredominantlyGreen(claimBtn)) {
                logInfo(logLine("Claim button shape-matched at " + claimBtn.getPoint()
                        + " but the region isn't actually green -- almost certainly the disabled "
                        + "lookalike button, not a real ready claim. Stopping instead of tapping it."));
                break;
            }
            tapNear(claimBtn.getPoint());
            sleepTask(ACTION_SETTLE_MS);
            if (i == MAX_CLAIM_LOOPS - 1) {
                logWarning(logLine("Hit the claim-loop safety cap (" + MAX_CLAIM_LOOPS + ")."));
            }
        }

        // Observed live: navigation into this panel is fixed, but the routine never
        // tapped the bottom "Claim All" button at all -- the loop above only ever scans individual
        // per-row Claim buttons visible in the CURRENT scroll position, so a ready reward scrolled
        // out of view was silently left unclaimed. Claim All batches every currently-claimable
        // reward regardless of scroll position, so it's tapped once here as a real second pass, not
        // a fallback -- template search (real screenshot, tight crop), matching this routine's own
        // hard-learned lesson from the badge-tap saga: no fixed-pixel guessing.
        ImageSearchResultData claimAllBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_ATLAS_CLAIM_ALL_BUTTON, SearchConfigConstants.QUICK_SEARCH);
        if (claimAllBtn.isFound()) {
            logInfo(logLine("Claim All button found -- tapping it to sweep any remaining ready rewards."));
            tapNear(claimAllBtn.getPoint());
            sleepTask(ACTION_SETTLE_MS);
        } else {
            logInfo(logLine("No Claim All button visible (nothing left to batch-claim)."));
        }
    }

    // Caught live -- the Tundra Albums hub has its own fragment-count milestone
    // chest track at the top (separate from the per-category Atlas rewards handled above) that this
    // routine's own header comment had flagged as a known, unhandled gap. Live-verified by hand: the
    // currently-claimable chest is visually lit/glowing; tapping it opens a real "Rewards" popup
    // (confirmed: 100 diamonds + 2 mystery chests on a live claim), and the whole row scrolls left
    // afterward as the next threshold becomes the new rightmost slot. Only one calibration pass was
    // possible tonight, so this scans a few plausible slot positions along the row rather than
    // trusting a single fixed point -- the row's exact scroll offset at any given moment isn't fully
    // characterized yet.
    // Measured off a real native 720x1280 capture of the Tundra Albums hub
    // (ocr-debug/albums-hub/tundra-albums-hub-live.png, saved as a test fixture). The six chest
    // sprites were located by column profile against the flat orange panel background:
    //
    //     chest 1  x 173-209  centre 191      chest 4  x 433-480  centre 456
    //     chest 2  x 252-299  centre 275      chest 5  x 524-570  centre 547
    //     chest 3  x 342-389  centre 365      chest 6  x 614-660  centre 637
    //
    // all on the same row, sprite centre y = 173.
    //
    // The previous list was (245,178), (340,178), (428,178): only three entries, and every one of
    // them landed in a GAP BETWEEN two chests -- 245 sits between chests 1 and 2, 340 between 2 and
    // 3, 428 between 3 and 4. So the track was tapped three times per pass and could never claim
    // anything. Reported as "there's a treasure chest at the top that has to be clicked" while the
    // log cheerfully said "No milestone chest currently ready".
    //
    // On the captured frame the account is at 940/1347 with milestones at 815/855/895/935/975/1015,
    // so chest 4 (935) is genuinely claimable and renders gold with sparkles while the rest are
    // grey -- and chest 4's real centre, 456, was not covered by any old candidate.
    private static final PointData[] MILESTONE_CHEST_CANDIDATES = {
            new PointData(191, 173),
            new PointData(275, 173),
            new PointData(365, 173),
            new PointData(456, 173),
            new PointData(547, 173),
            new PointData(637, 173),
    };
    private static final PointData MILESTONE_REWARDS_TAP_ANYWHERE = new PointData(360, 1198);
    private static final int MAX_MILESTONE_CHEST_CLAIMS = 6;

    private void claimMilestoneChestsIfReady() {
        for (int claimed = 0; claimed < MAX_MILESTONE_CHEST_CLAIMS; claimed++) {
            boolean claimedThisPass = false;
            for (PointData candidate : MILESTONE_CHEST_CANDIDATES) {
                tapNear(candidate);
                sleepTask(600);

                String popupTitle = stringHelper.attemptRecognition(
                        new PointData(200, 260), new PointData(520, 340),
                        2, 150L, PANEL_TITLE_OCR_SETTINGS,
                        s -> s != null && !s.isBlank(),
                        s -> s);
                if (popupTitle != null && popupTitle.toLowerCase().contains("reward")) {
                    logInfo(logLine("Milestone chest ready at " + candidate + " -- claimed. Rewards: '"
                            + popupTitle + "'."));
                    tapNear(MILESTONE_REWARDS_TAP_ANYWHERE);
                    sleepTask(ACTION_SETTLE_MS);
                    claimedThisPass = true;
                    break;
                }
            }
            if (!claimedThisPass) {
                if (claimed == 0) {
                    logInfo(logLine("No milestone chest currently ready."));
                } else {
                    logInfo(logLine("Milestone chest track exhausted after " + claimed + " claim(s)."));
                }
                return;
            }
        }
        logWarning(logLine("Hit the milestone-chest safety cap (" + MAX_MILESTONE_CHEST_CLAIMS + ")."));
    }

    // Caught live -- ALBUMS_FRAGMENT_BACKPACK_BTN was documented as the Tundra
    // Albums hub's own button, but this method used to fire BEFORE the back-arrow tap, on a
    // screen where that coordinate doesn't correspond to a real button at all -- 0 rows ever
    // opened, with no error, because the panel-title check below just correctly declined every
    // time. : fixed by moving the call to after the back-arrow (see execute()) so
    // this now genuinely runs on the Tundra Albums hub, where ALBUMS_FRAGMENT_BACKPACK_BTN is
    // the real button -- live-verified hand-driven, screenshot-confirmed.

    // Every album has its own shelf: owned packs sit as icons above the shelf banner with a small
    // count box under them, and an album with none shows a "Complete Album Missions ... Obtain"
    // panel instead. The list runs about four screens. Fixed icon slots with an OCR'd count never
    // worked here: on 2026-10-28 a lone "1" under the General Album pack never read as a number,
    // and nothing below the first screen was ever looked at. Packs are found by colour instead --
    // saturated gold/red/purple art, the only icon-sized blob of it on the panel. Banners and
    // planks of the same colours are list-wide and the Obtain buttons are blue, so neither passes
    // the size and hue gates (saved frames: packs 100-120px square; nothing else 80-200px both ways).
    private static final int BACKPACK_LIST_LEFT_X = 40;
    private static final int BACKPACK_LIST_RIGHT_X = 680;
    private static final int BACKPACK_LIST_TOP_Y = 170;
    private static final int BACKPACK_LIST_BOTTOM_Y = 1120;
    private static final int PACK_SCAN_CELL = 20;
    private static final int PACK_ICON_MIN_SIZE = 80;
    private static final int PACK_ICON_MAX_SIZE = 200;
    private static final PointData BACKPACK_SCROLL_FROM = new PointData(360, 950);
    private static final PointData BACKPACK_SCROLL_TO = new PointData(360, 350);
    private static final int BACKPACK_SCROLL_MS = 600;
    /** Four swipes reach the bottom today; the cap only bounds a list that never stops moving. */
    private static final int BACKPACK_MAX_SCROLLS = 10;
    /** Mean grey difference between list frames before and after a swipe: 31-43 while it moves,
     *  0.07 once the bottom is reached. */
    private static final double BACKPACK_LIST_END_MAX_DIFF = 3.0;
    private static final int BACKPACK_MAX_TOTAL_OPENS = 40;
    private static final int BACKPACK_REVEAL_DISMISS_TAPS = 6;

    // Observed live: the earlier fixed-slot finder false-positived on candidate (360,587) --
    // the OCR "owned count" read at that offset actually landed on the unrelated Labyrinth hub's own
    // milestone-chest track digits, not a real pack. The bot tapped it, tapped where Enable should be,
    // tapped where a reward-reveal close should be -- all blind, all on the wrong screen -- and ended
    // up stuck on a completely different "Rewards ... Tap anywhere to exit" chest-reveal screen (a
    // different UI skin REWARD_REVEAL_TAP_ANYWHERE doesn't clear) for 7+ minutes until manually
    // quit. Two real fixes: (1) a hard wall-clock time budget on the whole pass, so an unrecognized
    // screen can never again silently eat minutes; (2) active recovery (repeated back-presses, which
    // already carry the quit-game-dialog safety net) instead of one blind close-tap that assumes
    // we're still on the screen it expects.
    private static final long BACKPACK_PASS_TIME_BUDGET_MS = 90_000;

    // ========== Album ready-book (a completed album waiting to be assembled) ==========
    // Measured off the same real hub capture. Each album row carries a small book icon at the right
    // end of its progress bar. A COMPLETED album's book blazes with a golden halo; an incomplete
    // one shows the identical icon flat, with no glow. Measured on that frame:
    //
    //     Rekindled Flames  9/9  glow pixels 1859   bbox (556,510)-(628,584)  centre (592,547)
    //     Song of Heroes    7/9  glow pixels  522   bbox (569,848)-(625,904)  centre (597,876)
    //
    // 3.5x apart, so this is a wide-margin call rather than a tuned threshold. Counting the halo
    // beats template matching here for the same reason Life Essence moved off templates: the glow
    // animates, so no single correlation score is stable, while the amount of glow is.
    private static final int ALBUM_BOOK_BAND_LEFT = 548;
    private static final int ALBUM_BOOK_BAND_RIGHT = 644;
    private static final int ALBUM_BOOK_BAND_TOP = 300;
    private static final int ALBUM_BOOK_BAND_BOTTOM = 1130;
    private static final int ALBUM_BOOK_WINDOW = 90;
    private static final int ALBUM_BOOK_STEP = 15;
    /** Between the measured 522 (not ready) and 1859 (ready), nearer the low side so a partly
     *  occluded ready book still counts. */
    private static final int ALBUM_BOOK_MIN_GLOW_PX = 1100;

    /**
     * Finds a completed album's glowing ready-book on the Tundra Albums hub, if one is showing.
     *
     * <p>Slides a window down the book column and returns the centre of the topmost window whose
     * glow count clears the threshold, so it picks the first ready album rather than assuming a
     * fixed row -- the hub scrolls, and which album is complete changes week to week.
     *
     * @return the tap point of a ready album's book, or {@code null} if none is showing
     */
    private PointData findReadyAlbumBook() {
        RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
        if (frame == null || frame.getBpp() != 32) {
            return null;
        }
        byte[] px = frame.getFrameBytes();
        int stride = frame.getWidth() * 4;

        int bestCount = 0;
        PointData best = null;
        for (int top = ALBUM_BOOK_BAND_TOP;
                top + ALBUM_BOOK_WINDOW < Math.min(ALBUM_BOOK_BAND_BOTTOM, frame.getHeight());
                top += ALBUM_BOOK_STEP) {
            int glow = 0;
            for (int y = top; y < top + ALBUM_BOOK_WINDOW; y++) {
                for (int x = ALBUM_BOOK_BAND_LEFT; x < ALBUM_BOOK_BAND_RIGHT && x < frame.getWidth(); x++) {
                    int offset = y * stride + x * 4;
                    if (offset + 2 >= px.length) continue;
                    int c0 = px[offset] & 0xFF, g = px[offset + 1] & 0xFF, c2 = px[offset + 2] & 0xFF;
                    // Channel-order agnostic: green is the middle byte in both RGBA and BGRA, and the
                    // outer two swap. A warm glow is one outer channel high (red) and the other low
                    // (blue), so test max/min of the pair rather than naming them.
                    int hi = Math.max(c0, c2), lo = Math.min(c0, c2);
                    if (hi >= 220 && g >= 170 && lo < 140) {
                        glow++;
                    }
                }
            }
            if (glow > bestCount) {
                bestCount = glow;
                best = new PointData((ALBUM_BOOK_BAND_LEFT + ALBUM_BOOK_BAND_RIGHT) / 2,
                        top + ALBUM_BOOK_WINDOW / 2);
            }
        }

        if (bestCount >= ALBUM_BOOK_MIN_GLOW_PX && best != null) {
            logInfo(logLine("Ready album book detected at " + best + " (" + bestCount
                    + " glow px, need " + ALBUM_BOOK_MIN_GLOW_PX + ")."));
            return best;
        }
        logInfo(logLine("No completed album waiting to be assembled (best glow count " + bestCount
                + ", need " + ALBUM_BOOK_MIN_GLOW_PX + ")."));
        return null;
    }

    /**
     * Everything that must happen on the Tundra Albums hub before leaving it, whatever route got
     * us here: claim the milestone chest track, assemble a completed album if one is waiting, and
     * empty the Fragment Backpack.
     *
     * <p>Kept as one method and called from every hub-reaching path so the three cannot drift apart
     * -- the standing rule is that the Fragment Backpack is checked on EVERY Monument visit.
     */
    private void sweepAlbumsHub() {
        logInfo(logLine("Checking the milestone chest track."));
        claimMilestoneChestsIfReady();

        PointData readyBook = findReadyAlbumBook();
        if (readyBook != null) {
            logInfo(logLine("Completed album waiting -- opening it to run the assemble chain."));
            tapNear(readyBook);
            sleepTask(PANEL_SETTLE_MS);
            handlePuzzleReadyChain();

            // The assemble chain leaves the screen wherever it ended -- including recovered to Home
            // on a failure -- so the backpack cannot just be run next. Come back to the hub first,
            // and only sweep it if we are genuinely back. The standing rule is that the Fragment
            // Backpack is checked on every visit; an earlier version of this method returned here
            // and silently skipped it whenever an album was ready, which is precisely backwards --
            // an assembled album is when new fragments are most likely to be waiting.
            if (!isOnAlbumsHub()) {
                logInfo(logLine("Not back on the Albums hub after the assemble chain -- skipping the "
                        + "Fragment Backpack this pass rather than tapping an unidentified screen."));
                return;
            }
        }

        logInfo(logLine("Processing the shared Fragment Backpack."));
        processFragmentBackpack();

        runAllianceTradePass();
    }

    /**
     * Opens Alliance Trade from the hub and runs My Requests: claim a filled request, then file a
     * new one. Until 2026-10-28 this only ran when a city badge happened to open the trade panel
     * directly, so the ordinary hub path never asked the alliance for a piece at all.
     */
    private void runAllianceTradePass() {
        if (!isOnAlbumsHub()) {
            logInfo(logLine("Not on the Albums hub after the backpack -- skipping Alliance Trade this "
                    + "pass rather than tapping an unidentified screen."));
            return;
        }
        logInfo(logLine("Opening Alliance Trade for My Requests."));
        tapNear(ALBUMS_ALLIANCE_TRADE_BTN);
        sleepTask(PANEL_SETTLE_MS);
        if (!isOnTradePanel()) {
            logWarning(logLine("Tapping " + ALBUMS_ALLIANCE_TRADE_BTN + " did not open Alliance Trade -- "
                    + "not touching an unidentified panel. " + dumpDiagnosticFrame("alliance-trade-not-opened")));
            return;
        }
        processAllianceTradeRequests();
        if (isOnTradePanel()) {
            processAllianceTradeSends();
        }
        if (isOnTradePanel()) {
            tapNear(TRADE_CLOSE_X);
            sleepTask(ACTION_SETTLE_MS);
        }
    }

    private boolean isOnTradePanel() {
        String title = readButtonLabel(TRADE_PANEL_TITLE_TL, TRADE_PANEL_TITLE_BR);
        return title != null && (title.contains("alliance") || title.contains("trade"));
    }

    /**
     * Sweeps the Fragment Backpack from whatever screen we are on, if its button is visible.
     *
     * <p>The backpack button is not unique to the Tundra Albums hub -- an album's own detail screen
     * carries one too, in a different place (the hub has four bottom buttons, the album screen two).
     * Locating it by its real template rather than by a fixed coordinate means the sweep works from
     * either, which is what makes "check the backpack on every visit" actually hold.
     *
     * @return true if a backpack panel was opened and processed
     */
    private boolean sweepFragmentBackpackIfVisible() {
        if (backpackSweptThisRun) {
            return false;
        }
        ImageSearchResultData icon = templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_PUZZLE_OVERVIEW_FRAGMENT_BACKPACK_ICON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!icon.isFound()) {
            logInfo(logLine("No Fragment Backpack button visible on this screen -- nothing to sweep "
                    + "from here."));
            return false;
        }
        logInfo(logLine("Fragment Backpack button visible at " + icon.getPoint()
                + " -- sweeping it before leaving."));
        processFragmentBackpack(icon.getPoint());
        return true;
    }

    private void processFragmentBackpack() {
        processFragmentBackpack(ALBUMS_FRAGMENT_BACKPACK_BTN);
    }

    /**
     * Extracted to accept the open-button location, so
     * {@link #handlePuzzleReadyChain()} can reuse this same hardened loop from the puzzle
     * overview screen's own Fragment Backpack icon (found via real template search) instead
     * of the Tundra Albums hub's fixed {@link #ALBUMS_FRAGMENT_BACKPACK_BTN} -- two different
     * screens, same shared Fragment Backpack panel underneath.
     */
    private void processFragmentBackpack(PointData openButton) {
        long deadline = System.currentTimeMillis() + BACKPACK_PASS_TIME_BUDGET_MS;

        tapNear(openButton);
        sleepTask(PANEL_SETTLE_MS);

        // Observed live: this came back null the same way the Alliance Trade
        // gate did earlier tonight -- right region this time (BACKPACK_TITLE_TL/BR is genuinely
        // this panel's own title box), but only one OCR pass right after PANEL_SETTLE_MS with no
        // second attempt if the panel just hadn't finished rendering yet. Per the's direct
        // instruction to stop over-gating on exact keyword matches ("worst case is a false
        // positive and it exits out anyway, who cares"): retry once after a longer settle before
        // giving up, and accept ANY non-blank read as confirmation instead of requiring the
        // literal word "fragment".
        String panelTitle = stringHelper.attemptRecognition(
                BACKPACK_TITLE_TL, BACKPACK_TITLE_BR,
                2, 150L, PANEL_TITLE_OCR_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s);
        if (panelTitle == null) {
            sleepTask(PANEL_SETTLE_MS);
            panelTitle = stringHelper.attemptRecognition(
                    BACKPACK_TITLE_TL, BACKPACK_TITLE_BR,
                    2, 150L, PANEL_TITLE_OCR_SETTINGS,
                    s -> s != null && !s.isBlank(),
                    s -> s);
        }
        if (panelTitle == null) {
            logWarning(logLine("Fragment Backpack panel not confirmed after tapping "
                    + openButton + " (read: '" + panelTitle
                    + "') -- skipping the backpack pass this run rather than guessing blindly on the "
                    + "wrong screen. " + dumpDiagnosticFrame("backpack-title-null")));
            recoverTowardHome();
            return;
        }

        // The panel is confirmed open, so this run has genuinely swept the backpack. Recorded here
        // rather than at the call sites: an intent to sweep is not a sweep, and marking it early is
        // what would let a later, reachable backpack be skipped.
        backpackSweptThisRun = true;

        int opened = 0;
        int scrolls = 0;
        while (opened < BACKPACK_MAX_TOTAL_OPENS) {
            if (System.currentTimeMillis() > deadline) {
                logWarning(logLine("Fragment Backpack pass exceeded its " + (BACKPACK_PASS_TIME_BUDGET_MS / 1000)
                        + "s time budget -- something is stuck on a screen this code doesn't recognize. "
                        + "Aborting and recovering rather than hanging. Opened " + opened + " total."));
                recoverTowardHome();
                return;
            }

            if (!waitForFragmentBackpackPanel()) {
                logWarning(logLine("Fragment Backpack panel didn't come back after the last pack open "
                        + "-- likely tapped something that wasn't actually a pack. Recovering instead of "
                        + "assuming the normal close tap still applies. Opened " + opened + " total."));
                recoverTowardHome();
                return;
            }

            BufferedImage listFrame = captureFrame();
            if (listFrame == null) {
                logWarning(logLine("Fragment Backpack frame could not be captured -- ending the backpack "
                        + "pass. Opened " + opened + " total."));
                break;
            }
            List<PointData> packs = findPackIcons(listFrame);
            if (packs.isEmpty()) {
                if (scrolls >= BACKPACK_MAX_SCROLLS) {
                    logWarning(logLine("Fragment Backpack list still moving after " + scrolls
                            + " swipes -- ending the backpack pass. Opened " + opened + " total. "
                            + dumpDiagnosticFrame("backpack-scroll-cap")));
                    break;
                }
                swipe(BACKPACK_SCROLL_FROM, BACKPACK_SCROLL_TO, BACKPACK_SCROLL_MS);
                sleepTask(ACTION_SETTLE_MS);
                scrolls++;
                BufferedImage afterSwipe = captureFrame();
                if (afterSwipe == null || listUnchanged(listFrame, afterSwipe)) {
                    logInfo(logLine("No more owned packs found (list end after " + scrolls + " swipe(s)). Opened "
                            + opened + " total."));
                    break;
                }
                continue;
            }

            PointData target = packs.get(0);
            tapNear(target);
            sleepTask(ACTION_SETTLE_MS);

            // Only the pack detail's own Enable button gets tapped. Anything else -- the "Obtain more"
            // gold-key purchase dialog above all -- does not read "Enable" there.
            String enableLabel = readButtonLabel(PACK_DETAIL_ENABLE_LABEL_TL, PACK_DETAIL_ENABLE_LABEL_BR);
            if (enableLabel == null || !enableLabel.contains("enable")) {
                logWarning(logLine("Tapped the pack icon at " + target + " but the pack detail's Enable button did "
                        + "not read (read: '" + enableLabel + "') -- not tapping on an unidentified screen. Opened "
                        + opened + " total. " + dumpDiagnosticFrame("backpack-pack-detail-unknown")));
                recoverTowardHome();
                return;
            }

            opened++;
            scrolls = 0;
            logInfo(logLine("Opening pack " + opened + " at " + target + "."));

            tapNear(PACK_DETAIL_ENABLE_BTN);
            sleepTask(PACK_OPEN_SETTLE_MS);

            // The reveal has a quick single-icon variant and a slower multi-piece grid that ignores
            // taps mid-animation, and a 6-fragment Mythic General pack on 2026-10-28 was still not
            // back on the list after two fixed taps. Tap until the list title reads again.
            if (!dismissRevealToFragmentBackpack()) {
                logWarning(logLine("Fragment Backpack list did not come back after opening pack " + opened
                        + " -- recovering instead of tapping an unidentified screen. "
                        + dumpDiagnosticFrame("backpack-after-open-unknown")));
                recoverTowardHome();
                return;
            }
        }

        if (opened >= BACKPACK_MAX_TOTAL_OPENS) {
            logWarning(logLine("Hit the total-opens safety cap (" + BACKPACK_MAX_TOTAL_OPENS + ")."));
        }

        tapNear(BACKPACK_CLOSE_X);
        sleepTask(ACTION_SETTLE_MS);
    }

    /** Active recovery back toward Home when the Fragment Backpack flow lands somewhere unrecognized --
     *  several back-presses (each carrying the shared quit-game-dialog safety net) rather than a single
     *  blind tap at a coordinate that assumed a screen state that turned out to be wrong. */
    private void recoverTowardHome() {
        for (int i = 0; i < 4 && !isScreenClear(); i++) {
            pressBack();
            sleepTask(500);
        }
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
    }

    private BufferedImage captureFrame() {
        RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
        return frame == null ? null : ImageConverter.toBufferedImage(frame);
    }

    /**
     * Pack icons on the Fragment Backpack list, top to bottom. Cells of {@link #PACK_SCAN_CELL} px
     * that are at least 30% pack-art colour are joined into blobs; a blob 80-200px both ways is a
     * pack. Native 720x1280 only, so any other frame size returns nothing.
     */
    static List<PointData> findPackIcons(BufferedImage img) {
        List<PointData> packs = new ArrayList<>();
        if (img.getWidth() != 720 || img.getHeight() != 1280) {
            return packs;
        }
        int cols = (BACKPACK_LIST_RIGHT_X - BACKPACK_LIST_LEFT_X) / PACK_SCAN_CELL;
        int rows = (BACKPACK_LIST_BOTTOM_Y - BACKPACK_LIST_TOP_Y) / PACK_SCAN_CELL;
        boolean[][] art = new boolean[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int x0 = BACKPACK_LIST_LEFT_X + c * PACK_SCAN_CELL;
                int y0 = BACKPACK_LIST_TOP_Y + r * PACK_SCAN_CELL;
                int samples = 0;
                int hits = 0;
                for (int y = y0; y < y0 + PACK_SCAN_CELL; y += 2) {
                    for (int x = x0; x < x0 + PACK_SCAN_CELL; x += 2) {
                        int rgb = img.getRGB(x, y);
                        samples++;
                        if (isPackArt((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) {
                            hits++;
                        }
                    }
                }
                art[r][c] = hits * 10 >= samples * 3;
            }
        }
        boolean[][] seen = new boolean[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (!art[r][c] || seen[r][c]) {
                    continue;
                }
                int minR = r, maxR = r, minC = c, maxC = c;
                List<int[]> stack = new ArrayList<>();
                stack.add(new int[] {r, c});
                seen[r][c] = true;
                while (!stack.isEmpty()) {
                    int[] cell = stack.remove(stack.size() - 1);
                    minR = Math.min(minR, cell[0]);
                    maxR = Math.max(maxR, cell[0]);
                    minC = Math.min(minC, cell[1]);
                    maxC = Math.max(maxC, cell[1]);
                    int[][] next = {{cell[0] + 1, cell[1]}, {cell[0] - 1, cell[1]},
                            {cell[0], cell[1] + 1}, {cell[0], cell[1] - 1}};
                    for (int[] n : next) {
                        if (n[0] >= 0 && n[0] < rows && n[1] >= 0 && n[1] < cols
                                && art[n[0]][n[1]] && !seen[n[0]][n[1]]) {
                            seen[n[0]][n[1]] = true;
                            stack.add(n);
                        }
                    }
                }
                int w = (maxC - minC + 1) * PACK_SCAN_CELL;
                int h = (maxR - minR + 1) * PACK_SCAN_CELL;
                if (w >= PACK_ICON_MIN_SIZE && w <= PACK_ICON_MAX_SIZE
                        && h >= PACK_ICON_MIN_SIZE && h <= PACK_ICON_MAX_SIZE) {
                    packs.add(new PointData(BACKPACK_LIST_LEFT_X + (minC + maxC + 1) * PACK_SCAN_CELL / 2,
                            BACKPACK_LIST_TOP_Y + (minR + maxR + 1) * PACK_SCAN_CELL / 2));
                }
            }
        }
        packs.sort(Comparator.comparingInt(PointData::getY).thenComparingInt(PointData::getX));
        return packs;
    }

    /** Pack art: bright saturated gold/red, or the purple packs. Wood is too dull, the Obtain button
     *  is blue (green above red), and the dark panels are neither. */
    private static boolean isPackArt(int r, int g, int b) {
        if (b > g + 50 && r > g + 30 && Math.max(r, b) > 140) {
            return true;
        }
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max < 200 || max - min < 120) {
            return false;
        }
        return !(b >= r && g > r);
    }

    /** True once a swipe no longer moves the list, i.e. its bottom has been reached. */
    static boolean listUnchanged(BufferedImage before, BufferedImage after) {
        if (before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight()) {
            return false;
        }
        long total = 0;
        int samples = 0;
        for (int y = BACKPACK_LIST_TOP_Y; y < BACKPACK_LIST_BOTTOM_Y; y += 4) {
            for (int x = BACKPACK_LIST_LEFT_X; x < BACKPACK_LIST_RIGHT_X; x += 4) {
                total += Math.abs(grey(before.getRGB(x, y)) - grey(after.getRGB(x, y)));
                samples++;
            }
        }
        return (double) total / samples <= BACKPACK_LIST_END_MAX_DIFF;
    }

    private static int grey(int rgb) {
        return (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
    }

    private boolean dismissRevealToFragmentBackpack() {
        for (int i = 0; i < BACKPACK_REVEAL_DISMISS_TAPS; i++) {
            tapNear(REWARD_REVEAL_TAP_ANYWHERE);
            sleepTask(PACK_OPEN_SETTLE_MS);
            String title = readButtonLabel(BACKPACK_TITLE_TL, BACKPACK_TITLE_BR);
            if (title != null && title.contains("fragment")) {
                return true;
            }
        }
        return false;
    }

    /** Waits (with extra retries) for the Fragment Backpack title to actually be back
     *  on screen after a pack-open cycle, instead of assuming a fixed sleep was enough. */
    private boolean waitForFragmentBackpackPanel() {
        for (int attempt = 0; attempt < 3; attempt++) {
            String title = stringHelper.attemptRecognition(
                    BACKPACK_TITLE_TL, BACKPACK_TITLE_BR,
                    2, 150L, PANEL_TITLE_OCR_SETTINGS,
                    s -> s != null && !s.isBlank(),
                    s -> s);
            if (title != null && title.toLowerCase().contains("fragment")) {
                return true;
            }
            sleepTask(PACK_OPEN_SETTLE_MS);
        }
        return false;
    }

    private static final PointData PIECE_PICKER_REQUEST_BTN_LABEL_TL = new PointData(480, 865);
    private static final PointData PIECE_PICKER_REQUEST_BTN_LABEL_BR = new PointData(610, 915);

    /**
     * Rebuilt around the real My Requests row's three states (see the class-
     * level "Alliance Trade panel" constants comment for the live-verified detail). Reads the
     * button label via OCR every loop instead of assuming it's always "Request" -- a Claim is
     * claimed, a Requesting row is skipped, and only a genuine Request tap spends one of the
     * 3 daily requests.
     */
    /** Reads one button-label region, lower-cased, or null when nothing legible is there. */
    private String readButtonLabel(PointData tl, PointData br) {
        return stringHelper.attemptRecognition(
                tl, br, 2, 150L, PANEL_TITLE_OCR_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s.toLowerCase());
    }

    /**
     * The owned-copies count from an Alliance Trade row, taken from the end of the line.
     *
     * <p>Not the first digit run. The crop reads through a whitelist of
     * {@code "OwnedOWNED:0123456789 "}, which admits both {@code O} and {@code 0}, so the label
     * itself can come back as "0wned: 2". Taking the first run then reads that leading zero as the
     * count and skips a row that should have been sent -- and a stray digit picked up ahead of the
     * label could just as easily read as two or more and send one that should not have been. The
     * value always trails the label, so the last run is the one that means something.
     *
     * @param text the raw OCR text for the row, may be {@code null}
     * @return the count, or {@code null} when the row carries no digits at all
     */
    static Integer ownedCountFrom(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m = OWNED_COUNT_PATTERN.matcher(text);
        String last = null;
        while (m.find()) {
            last = m.group();
        }
        if (last == null) {
            return null;
        }
        try {
            return Integer.valueOf(last);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void processAllianceTradeRequests() {
        for (int i = 0; i < MAX_REQUEST_LOOPS; i++) {
            // Claim is decided by colour, not OCR -- see isClaimButtonPresent(). It's checked first
            // because claiming frees the single request slot for a new request in the same pass.
            if (isClaimButtonPresent()) {
                logInfo(logLine("My Requests row is claimable -- tapping Claim."));
                tapNear(MY_REQUESTS_CLAIM_BTN);
                sleepTask(ACTION_SETTLE_MS);
                // The claim raises a reward reveal and then a "Like and thanks" card, each closed by
                // a tap. One fixed tap left the second card over the panel and the label read blank.
                if (!returnToTradePanel()) {
                    logWarning(logLine("Claimed, but the trade panel did not come back after "
                            + TRADE_PANEL_RETURN_ATTEMPTS + " taps -- stopping here. "
                            + dumpDiagnosticFrame("after-claim-no-trade-panel")));
                    return;
                }
                continue;
            }

            // No Claim slab, so the row is either the blue Request button or an open request with
            // its orange Cancel. Decided by colour: an OCR box over the label read "Requesting..."
            // as "Request" and re-opened the picker with a request already open.
            RawImageData rowFrame = emuManager.captureScreen(EMULATOR_NUMBER);
            BufferedImage row = rowFrame == null || rowFrame.getBpp() != 32
                    ? null : ImageConverter.toBufferedImage(rowFrame);
            if (row != null && showsOpenRequest(row)) {
                logInfo(logLine("My Requests already has an open request (orange Cancel) -- nothing to do."));
                return;
            }
            if (row == null || !showsRequestButton(row)) {
                logInfo(logLine("My Requests shows neither the blue Request button nor an open request -- "
                        + "moving on rather than guessing. " + dumpDiagnosticFrame("my-requests-state-unknown")));
                return;
            }

            String leftText = stringHelper.attemptRecognition(
                    MY_REQUESTS_LEFT_TL, MY_REQUESTS_LEFT_BR,
                    2, 150L, REQUESTS_LEFT_OCR_SETTINGS,
                    s -> s != null && !s.isBlank(),
                    s -> s);
            // extractByPattern() anchors with Matcher.matches(), so a fragment pattern such as
            // "\\((\\d+)\\s*/" never fires on the whole line "Requests Left Today (3/3)".
            // numerator() reads the left side of a fraction through a full-line-anchored pattern.
            Integer requestsLeft = leftText == null ? null : RegexNumberParser.numerator(leftText);
            if (requestsLeft == null || requestsLeft <= 0) {
                // A null is ambiguous between an exhausted counter and an OCR miss, so report the
                // raw text and the screen rather than just "couldn't read it".
                logInfo(logLine("Not filing a new request: counter parsed as " + requestsLeft
                        + " from raw text '" + (leftText == null ? "<null>" : leftText.replace("\n", " ").trim())
                        + "' (region " + MY_REQUESTS_LEFT_TL + "->" + MY_REQUESTS_LEFT_BR + "). "
                        + dumpDiagnosticFrame("my-requests-counter-unparsed")));
                return;
            }

            fileOnePieceRequest(requestsLeft);
            return;
        }
        logWarning(logLine("Hit the request-loop safety cap (" + MAX_REQUEST_LOOPS + ")."));
    }

    /**
     * Requests one missing piece: Request opens the piece grid, a missing (grey) card is tapped to
     * open its card, and the card's own Request is tapped only once both of the card's buttons are
     * read. Success is the My Requests row reading "Requesting" afterwards, not the taps.
     */
    private void fileOnePieceRequest(int requestsLeft) {
        tapNear(MY_REQUESTS_REQUEST_BTN);
        sleepTask(PANEL_SETTLE_MS);

        String header = readButtonLabel(PIECE_PICKER_HEADER_TL, PIECE_PICKER_HEADER_BR);
        if (header == null || !header.contains("scene")) {
            logWarning(logLine("Tapped Request but the piece grid's header did not read (read: '" + header
                    + "') -- not tapping an unidentified screen. " + dumpDiagnosticFrame("piece-grid-not-open")));
            recoverTowardHome();
            return;
        }

        RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
        List<PointData> missing = frame == null || frame.getBpp() != 32
                ? List.of()
                : findMissingPieceCells(ImageConverter.toBufferedImage(frame));
        if (missing.isEmpty()) {
            logWarning(logLine("Piece grid open but no missing (grey) card is visible -- not requesting. "
                    + dumpDiagnosticFrame("piece-grid-no-missing-card")));
            tapNear(PIECE_PICKER_CLOSE_X);
            sleepTask(ACTION_SETTLE_MS);
            return;
        }

        PointData cell = missing.get(0);
        logInfo(logLine("Piece grid shows " + missing.size() + " missing card(s) " + missing
                + " -- opening " + cell + "."));
        tapNear(cell);
        sleepTask(ACTION_SETTLE_MS);

        String requestLabel = readButtonLabel(PIECE_PICKER_REQUEST_BTN_LABEL_TL, PIECE_PICKER_REQUEST_BTN_LABEL_BR);
        String obtainLabel = readButtonLabel(PIECE_CARD_OBTAIN_LABEL_TL, PIECE_CARD_OBTAIN_LABEL_BR);
        if (requestLabel == null || !requestLabel.contains("request")
                || obtainLabel == null || !obtainLabel.contains("obtain")) {
            logWarning(logLine("Piece card did not show both of its buttons (Request read '" + requestLabel
                    + "', Obtain more read '" + obtainLabel + "') -- not tapping beside the gem button blind. "
                    + dumpDiagnosticFrame("piece-card-unconfirmed")));
            recoverTowardHome();
            return;
        }

        tapNear(PIECE_PICKER_REQUEST_BTN);
        sleepTask(ACTION_SETTLE_MS);

        String cancelLabel = readButtonLabel(REQUEST_REPLACE_CANCEL_LABEL_TL, REQUEST_REPLACE_CANCEL_LABEL_BR);
        if (cancelLabel != null && cancelLabel.contains("cancel")) {
            // Only raised when a request is already open, which the Requesting check should rule out.
            logWarning(logLine("Request raised the replace-open-request dialog -- cancelling so the open "
                    + "request stands. " + dumpDiagnosticFrame("request-replace-dialog")));
            tapNear(REQUEST_REPLACE_CANCEL_BTN);
            sleepTask(ACTION_SETTLE_MS);
        } else {
            String confirmLabel = readButtonLabel(REQUEST_TIPS_CONFIRM_LABEL_TL, REQUEST_TIPS_CONFIRM_LABEL_BR);
            if (confirmLabel != null && confirmLabel.contains("confirm")) {
                logInfo(logLine("Request raised the Tips dialog -- confirming."));
                tapNear(REQUEST_TIPS_CONFIRM_BTN);
                sleepTask(ACTION_SETTLE_MS);
            }
        }

        // The game stays on the piece grid after Confirm (live 2026-10-28: the card read
        // "Requesting..." with the grid still up), and "tap anywhere" there lands on the scene
        // strip. Re-read the screen every attempt rather than once.
        if (!leavePieceGridToTradePanel()) {
            logWarning(logLine("Request tapped but the trade panel did not come back -- outcome unknown, "
                    + "not counting it. " + dumpDiagnosticFrame("after-request-no-trade-panel")));
            return;
        }

        RawImageData afterFrame = emuManager.captureScreen(EMULATOR_NUMBER);
        if (afterFrame != null && afterFrame.getBpp() == 32
                && showsOpenRequest(ImageConverter.toBufferedImage(afterFrame))) {
            StatisticsService.obtain().addToCounter(profile, "Monument Pieces Requested", 1);
            logInfo(logLine("Piece request filed and confirmed: My Requests shows the open request's "
                    + "Cancel (" + (requestsLeft - 1) + " of today's requests left)."));
        } else {
            logWarning(logLine("Request tapped but My Requests shows no open request -- not counting it "
                    + "as filed. " + dumpDiagnosticFrame("request-not-confirmed")));
        }
    }

    /** The blue Request button: My Requests has no open request. */
    static boolean showsRequestButton(BufferedImage img) {
        return countPixels(img, REQUEST_BTN_BOX_TL, REQUEST_BTN_BOX_BR, (r, g, b) ->
                b >= 200 && b - r >= 100 && b - g >= 40) >= REQUEST_BLUE_MIN_PIXELS;
    }

    /** The orange Cancel button beside "Requesting...": a request is open. */
    static boolean showsOpenRequest(BufferedImage img) {
        return countPixels(img, CLAIM_BTN_BOX_TL, CLAIM_BTN_BOX_BR, (r, g, b) ->
                r >= 200 && r - b >= 150 && r - g >= 80) >= CANCEL_ORANGE_MIN_PIXELS;
    }

    private interface RgbTest {
        boolean matches(int r, int g, int b);
    }

    private static int countPixels(BufferedImage img, PointData tl, PointData br, RgbTest test) {
        int count = 0;
        for (int y = tl.getY(); y < br.getY() && y < img.getHeight(); y++) {
            for (int x = tl.getX(); x < br.getX() && x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                if (test.matches((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Closes the piece grid by its X, or a dialog over it by a tap, until the trade title reads. */
    private boolean leavePieceGridToTradePanel() {
        for (int i = 0; i < TRADE_PANEL_RETURN_ATTEMPTS; i++) {
            if (isOnTradePanel()) {
                return true;
            }
            String header = readButtonLabel(PIECE_PICKER_HEADER_TL, PIECE_PICKER_HEADER_BR);
            tapNear(header != null && header.contains("scene") ? PIECE_PICKER_CLOSE_X : REWARD_REVEAL_TAP_ANYWHERE);
            sleepTask(ACTION_SETTLE_MS);
        }
        return isOnTradePanel();
    }

    /**
     * Taps "Tap anywhere to close" until the Alliance Trade title reads again. The title is read
     * before every tap, so the panel itself is never tapped below its frame (which would close it).
     */
    private boolean returnToTradePanel() {
        for (int i = 0; i < TRADE_PANEL_RETURN_ATTEMPTS; i++) {
            if (isOnTradePanel()) {
                return true;
            }
            tapNear(REWARD_REVEAL_TAP_ANYWHERE);
            sleepTask(ACTION_SETTLE_MS);
        }
        return isOnTradePanel();
    }

    /**
     * Centres of the missing (grey) piece cards visible on the piece grid, top to bottom.
     *
     * <p>Each grid column is scanned in horizontal strips; a strip with almost no saturated blue
     * and mostly flat grey belongs to a missing card, and a long enough run of such strips is one
     * card. The hand pointer may cover the top of a card, which only shortens its run.
     *
     * @param img a 720x1280 RGB frame of the piece grid; any other size yields no cells
     * @return the missing card centres, possibly empty
     */
    static List<PointData> findMissingPieceCells(BufferedImage img) {
        List<PointData> cells = new ArrayList<>();
        if (img.getWidth() != 720 || img.getHeight() != 1280) {
            return cells;
        }
        for (int x : PIECE_GRID_COLUMN_X) {
            int runStart = -1;
            int runEnd = -1;
            for (int y = PIECE_GRID_TOP_Y; y <= PIECE_GRID_BOTTOM_Y; y += 2) {
                if (isMissingCardStrip(img, x, y)) {
                    if (runStart < 0) {
                        runStart = y;
                    }
                    runEnd = y;
                } else {
                    addRunIfCard(cells, x, runStart, runEnd);
                    runStart = -1;
                }
            }
            addRunIfCard(cells, x, runStart, runEnd);
        }
        cells.sort(Comparator.comparingInt(PointData::getY).thenComparingInt(PointData::getX));
        return cells;
    }

    private static void addRunIfCard(List<PointData> cells, int x, int runStart, int runEnd) {
        if (runStart >= 0 && runEnd - runStart >= PIECE_GRID_MIN_RUN) {
            cells.add(new PointData(x, (runStart + runEnd) / 2));
        }
    }

    private static boolean isMissingCardStrip(BufferedImage img, int cx, int y) {
        int samples = 0;
        int grey = 0;
        int blue = 0;
        for (int x = cx - PIECE_GRID_HALF_WIDTH; x <= cx + PIECE_GRID_HALF_WIDTH; x += 3) {
            int rgb = img.getRGB(x, y);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            samples++;
            if (max - min < 40 && max > 90 && max < 200) {
                grey++;
            }
            if (b > 150 && b - r > 60) {
                blue++;
            }
        }
        return blue * 20 < samples && grey * 2 > samples;
    }

    /**
     * Sends spare pieces to the top Ally Request while daily trades remain. Only a first row reading
     * Owned 2 or more, both on the list and again on the Confirmation dialog, is ever sent; a send
     * counts only once Trades Left has dropped.
     */
    private void processAllianceTradeSends() {
        int sent = 0;
        while (sent < MAX_SENDS_PER_PASS) {
            Integer tradesLeft = readTradesLeft();
            if (tradesLeft == null) {
                logWarning(logLine("Ally Requests: Trades Left counter did not read -- not sending. "
                        + dumpDiagnosticFrame("ally-trades-left-unread")));
                return;
            }
            if (tradesLeft <= 0) {
                logInfo(logLine("Ally Requests: no trades left today. Sent " + sent + " this pass."));
                return;
            }
            Integer owned = readOwnedAgreed(ALLY_FIRST_ROW_OWNED_TL, ALLY_FIRST_ROW_OWNED_BR);
            if (owned == null || owned < MIN_OWNED_TO_SEND) {
                logInfo(logLine("Ally Requests: top row reads Owned " + owned + " -- no spare piece to send "
                        + "(rows run highest Owned first). Sent " + sent + " this pass."));
                return;
            }
            BufferedImage rowFrame = captureFrame();
            if (rowFrame == null || !showsActiveSendButton(rowFrame)) {
                logWarning(logLine("Ally Requests: top row reads Owned " + owned + " but its Send button is not "
                        + "blue -- not sending. " + dumpDiagnosticFrame("ally-send-not-active")));
                return;
            }

            tapNear(ALLY_FIRST_ROW_SEND_BTN);
            sleepTask(ACTION_SETTLE_MS);
            String title = readButtonLabel(SEND_CONFIRM_TITLE_TL, SEND_CONFIRM_TITLE_BR);
            boolean onDialog = title != null && title.contains("confirm");
            BufferedImage dialogFrame = captureFrame();
            Integer dialogOwned = readOwnedAgreed(SEND_CONFIRM_OWNED_TL, SEND_CONFIRM_OWNED_BR);
            if (!onDialog || dialogFrame == null || !showsSendConfirmation(dialogFrame)
                    || dialogOwned == null || dialogOwned < MIN_OWNED_TO_SEND) {
                logWarning(logLine("Ally Requests: send not confirmed (dialog title '" + title + "', dialog Owned "
                        + dialogOwned + ") -- closing without sending. " + dumpDiagnosticFrame("ally-send-dialog-unknown")));
                if (onDialog) {
                    tapNear(SEND_CONFIRM_CLOSE_X);
                } else {
                    pressBack();
                }
                sleepTask(ACTION_SETTLE_MS);
                return;
            }

            tapNear(SEND_CONFIRM_BTN);
            sleepTask(ACTION_SETTLE_MS);
            if (!returnToTradePanel()) {
                logWarning(logLine("Ally Requests: Alliance Trade did not come back after Confirm. "
                        + dumpDiagnosticFrame("ally-after-send-no-trade-panel")));
                return;
            }
            Integer after = readTradesLeft();
            if (after == null || after >= tradesLeft) {
                logWarning(logLine("Ally Requests: Trades Left read " + after + " after a send from " + tradesLeft
                        + " -- not counting it and not sending again. " + dumpDiagnosticFrame("ally-send-not-counted")));
                return;
            }
            sent++;
            StatisticsService.obtain().addToCounter(profile, "Monument Pieces Sent", 1);
            logInfo(logLine("Ally Requests: sent a spare piece (Owned " + owned + "); Trades Left " + tradesLeft
                    + " -> " + after + "."));
        }
        logInfo(logLine("Ally Requests: hit the per-pass cap of " + MAX_SENDS_PER_PASS + " sends."));
    }

    private Integer readTradesLeft() {
        String text = stringHelper.attemptRecognition(
                TRADES_LEFT_TL, TRADES_LEFT_BR,
                2, 150L, REQUESTS_LEFT_OCR_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s);
        return text == null ? null : RegexNumberParser.numerator(text);
    }

    /** Two separate reads that must agree, so one misread digit cannot turn an only copy into a spare. */
    private Integer readOwnedAgreed(PointData tl, PointData br) {
        Integer first = ownedCountFrom(readStringValueSafe(tl, br));
        Integer second = ownedCountFrom(readStringValueSafe(tl, br));
        return first != null && first.equals(second) ? first : null;
    }

    /** The first Ally Request row's blue (active) Send button. */
    static boolean showsActiveSendButton(BufferedImage img) {
        return countPixels(img, ALLY_FIRST_ROW_SEND_BOX_TL, ALLY_FIRST_ROW_SEND_BOX_BR, (r, g, b) ->
                b >= 200 && b - r >= 100 && b - g >= 40) >= SEND_BLUE_MIN_PIXELS;
    }

    /** The send Confirmation dialog's blue Confirm button. */
    static boolean showsSendConfirmation(BufferedImage img) {
        return countPixels(img, SEND_CONFIRM_BOX_TL, SEND_CONFIRM_BOX_BR, (r, g, b) ->
                b >= 200 && b - r >= 100 && b - g >= 40) >= SEND_CONFIRM_BLUE_MIN_PIXELS;
    }

    private String readStringValueSafe(PointData tl, PointData br) {
        return stringHelper.attemptRecognition(
                tl, br, 2, 150L, OWNED_COUNT_OCR_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s);
    }
}
