package dev.frostguard.engine.helper;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.DailyHideCompletedCheckbox;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SidebarFrameClassifier;
import dev.frostguard.engine.nav.SidebarRouteResult;
import dev.frostguard.engine.nav.SidebarRowAction;
import dev.frostguard.engine.nav.RecallConfirmationGuard;
import dev.frostguard.engine.nav.SidebarRowLookup;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.engine.nav.SidebarViewportChangeDetector;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.nav.WorldTilePopupGuard;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.logging.ProfileContextLogger;

/** State-verified navigation through the City, Wilderness, and Daily sidebar. */
public final class SidebarNavigator {

    private static final int SECTION_SETTLE_MS = 400;
    static final int TRANSITION_POLL_MS = 200;
    static final int TRANSITION_POLL_CHECKS = 9;
    static final int MAX_TRIGGER_TAPS = 2;
    static final int SIDEBAR_OPEN_SETTLE_MS = 2_000;
    static final int SCROLL_SETTLE_MS = 2_000;
    static final int SCROLL_DISTANCE_PX = 120;
    private static final int SCROLL_DURATION_MS = 500;
    private static final int MAX_BOUNDARY_STEPS = 10;
    private static final int DESTINATION_SETTLE_MS = 2_000;
    private static final int SCREEN_SWITCH_SETTLE_MS = 2_000;
    private static final String SNAPSHOT_ACTIVITY = "sidebar";
    private static final int RECALL_DIALOG_THRESHOLD = 90;
    private static final int TILE_POPUP_THRESHOLD = 90;
    private static final int TILE_POPUP_BACK_SETTLE_MS = 1_000;
    private static final int ROW_ICON_THRESHOLD = 88;
    private static final int ROW_ACTION_THRESHOLD = 88;
    private static final int GO_LEFT_OFFSET = 315;
    private static final int GO_RIGHT_OFFSET = 394;
    private static final int GO_HALF_HEIGHT = 30;
    private static final int DAILY_LISTING_SETTLE_MS = 1_000;

    /**
     * Accounts whose Daily "hide completed" box needs no more attention this run. Added before the
     * tap, so a tap that does not take is never repeated and the box can never be toggled back.
     */
    private static final Set<String> DAILY_LISTING_SETTLED = ConcurrentHashMap.newKeySet();

    private final EmulatorController emu;
    private final String device;
    private final TapInteractionService taps;
    private final ScreenStateReader screenStates;
    private final SectionReader sections;
    private final RecallConfirmationGuard recallGuard;
    private final WorldTilePopupGuard tilePopupGuard;
    private final Waiter transitionWaiter;
    private final ProfileContextLogger log;
    private final SidebarHomeFallback homeFallback;
    private final String dailyListingKey;

    public SidebarNavigator(EmulatorController emu, String device, AccountDescriptor profile) {
        this.emu = emu;
        this.device = device;
        this.dailyListingKey = device + "/" + (profile == null ? "" : profile.getId());
        this.taps = TapInteractionService.forController(emu, device);
        this.screenStates = this::captureScreenState;
        this.sections = this::captureSelectedSection;
        this.recallGuard = new EmulatorRecallGuard();
        this.tilePopupGuard = new EmulatorTilePopupGuard();
        this.transitionWaiter = this::interruptibleWait;
        this.log = new ProfileContextLogger(SidebarNavigator.class, profile);
        this.homeFallback = new SidebarHomeFallback(new EmulatorScreens());
    }

    public boolean openSection(SidebarSection target) {
        return homeFallback.open(() -> openSectionPastTilePopup(target));
    }

    /**
     * One open attempt, retried once when it ends with a World tile popup over the map.
     *
     * <p>The popup is either already up when the attempt starts, in which case the attempt backs
     * out of it before the trigger tap, or it is opened during the attempt: a tab tap sent as the
     * panel closes lands on the map and selects whatever tile is under the tab strip (the beast and
     * player-city frames of 2026-11-13 both follow "Sidebar section selection failed"). Either way
     * the retry starts by backing out of it, so it is spent on a clean map.
     */
    private boolean openSectionPastTilePopup(SidebarSection target) {
        if (openSectionInternal(target)) {
            return true;
        }
        if (!tilePopupGuard.popupOnScreen()) {
            return false;
        }
        log.info("Sidebar open for " + target
                + " ended with a World tile popup over the map; retrying once after closing it");
        return openSectionInternal(target);
    }

    public SidebarRouteResult navigateTo(SidebarDestination destination) {
        SidebarRowLookup lookup = findRowWithStatus(destination);
        if (lookup.status() == SidebarRowLookup.Status.SIDEBAR_UNAVAILABLE) {
            return SidebarRouteResult.FAILED;
        }
        if (!lookup.isFound()) {
            return notOffered(destination);
        }

        RawImageData actionFrame = emu.captureScreen(device);
        ImageSearchResultData rowIcon = locateRowIcon(destination, actionFrame);
        if (!rowIcon.isFound()) {
            log.info("Sidebar row moved before its action could be verified: " + destination);
            return SidebarRouteResult.FAILED;
        }

        AreaData actionArea = rowActionAreaFor(rowIcon);
        ImageSearchResultData action = locateRowAction(destination, actionArea, actionFrame);
        if (action == null || !action.isFound()) {
            log.info("Sidebar row found without an expected action: " + destination
                    + " icon=" + rowIcon + " actionArea=" + actionArea + "; frame saved: "
                    + saveFrame(actionFrame, "row-action-unknown"));
            return notOffered(destination);
        }

        log.info("Sidebar destination found: " + destination + " icon=" + rowIcon
                + " action=" + action);
        taps.tapInside(action, 1, DESTINATION_SETTLE_MS);
        homeFallback.forget();
        if (selectedSection().isEmpty()) {
            return SidebarRouteResult.REACHED;
        }
        log.warn("Sidebar stayed open after tapping destination " + destination);
        return SidebarRouteResult.FAILED;
    }

    /** Only Daily hides finished rows, so a row missing anywhere else is still a fault. */
    private static SidebarRouteResult notOffered(SidebarDestination destination) {
        return destination.section() == SidebarSection.DAILY
                ? SidebarRouteResult.NOT_OFFERED
                : SidebarRouteResult.FAILED;
    }

    /** Finds a row from its left icon in the current viewport and after every settled scroll. */
    public ImageSearchResultData findRow(SidebarDestination destination) {
        return findRowWithStatus(destination).rowIcon();
    }

    /** Distinguishes a failed sidebar transition from a completed scan with no matching row. */
    public SidebarRowLookup findRowWithStatus(SidebarDestination destination) {
        if (!openSection(destination.section())) {
            return SidebarRowLookup.sidebarUnavailable();
        }

        RawImageData frame = emu.captureScreen(device);
        if (frame == null || selectedSection(frame).orElse(null) != destination.section()) {
            log.warn("Sidebar state changed before scanning for " + destination);
            return SidebarRowLookup.sidebarUnavailable();
        }
        if (destination.section() == SidebarSection.DAILY) {
            frame = keepCompletedDailyRowsListed(frame);
            if (frame == null || selectedSection(frame).orElse(null) != destination.section()) {
                log.warn("Sidebar state changed after checking the Daily listing for " + destination);
                return SidebarRowLookup.sidebarUnavailable();
            }
        }
        ImageSearchResultData current = locateRowIcon(destination, frame);
        if (current.isFound()) {
            return SidebarRowLookup.fromRow(current);
        }

        ScrollScanResult bottomScan = scan(destination, ScrollDirection.TOWARD_BOTTOM, frame);
        if (!bottomScan.completed()) {
            log.warn("Sidebar scan did not complete on a verified " + destination.section()
                    + " section: " + destination);
            return SidebarRowLookup.sidebarUnavailable();
        }
        if (bottomScan.rowIcon().isFound()) {
            return SidebarRowLookup.fromRow(bottomScan.rowIcon());
        }

        // The panel keeps whatever scroll offset the previous routine left behind, so the row may
        // well sit above the opening viewport. A downward-only scan can never reach it: the first
        // swipe at the bottom boundary changes nothing, the scan calls that a finished list, and
        // the destination is reported absent while it is on screen one swipe up. Scan back toward
        // the top before concluding anything.
        ScrollScanResult topScan = scan(destination, ScrollDirection.TOWARD_TOP, bottomScan.frame());
        if (!topScan.completed()) {
            log.warn("Sidebar scan did not complete on a verified " + destination.section()
                    + " section: " + destination);
            return SidebarRowLookup.sidebarUnavailable();
        }
        if (topScan.rowIcon().isFound()) {
            return SidebarRowLookup.fromRow(topScan.rowIcon());
        }
        log.info("Sidebar destination unavailable after bounded icon scan: " + destination
                + " bottomBoundary=" + bottomScan.boundaryReached()
                + " topBoundary=" + topScan.boundaryReached()
                + " hideCompleted=" + hideCompletedState(topScan.frame()) + "; frame saved: "
                + saveFrame(topScan.frame(), "row-unavailable"));
        return SidebarRowLookup.fromRow(ImageSearchResultData.miss());
    }

    /**
     * Unticks Daily's "hide completed" box the first time it is seen ticked, so every route's row
     * stays on the list. The tap needs a same-frame reading of a tick inside the box on the selected
     * Daily tab; any other reading leaves the screen alone. Returns the frame to scan.
     */
    private RawImageData keepCompletedDailyRowsListed(RawImageData frame) {
        if (DAILY_LISTING_SETTLED.contains(dailyListingKey)) {
            return frame;
        }
        DailyHideCompletedCheckbox.State state = hideCompletedState(frame);
        if (state == DailyHideCompletedCheckbox.State.UNREADABLE) {
            log.debug("Daily hide-completed checkbox could not be read; leaving it alone");
            return frame;
        }
        DAILY_LISTING_SETTLED.add(dailyListingKey);
        if (state == DailyHideCompletedCheckbox.State.UNTICKED) {
            log.info("Daily hide-completed checkbox is already off");
            return frame;
        }

        taps.tapInside(CommonGameAreas.DAILY_HIDE_COMPLETED_TAP, 1, DAILY_LISTING_SETTLE_MS);
        RawImageData after = emu.captureScreen(device);
        DailyHideCompletedCheckbox.State verified = hideCompletedState(after);
        if (verified == DailyHideCompletedCheckbox.State.UNTICKED) {
            log.info("Unticked the Daily hide-completed checkbox once, so completed rows stay listed");
        } else {
            log.warn("Tapped the Daily hide-completed checkbox once but read " + verified
                    + " afterwards; it will not be tapped again this run; frame saved: "
                    + saveFrame(after, "hide-completed-unverified"));
        }
        return after;
    }

    private static DailyHideCompletedCheckbox.State hideCompletedState(RawImageData frame) {
        return frame == null
                ? DailyHideCompletedCheckbox.State.UNREADABLE
                : DailyHideCompletedCheckbox.read(ImageConverter.toBufferedImage(frame));
    }

    private static String saveFrame(RawImageData frame, String label) {
        if (frame == null) {
            return "(no frame)";
        }
        return new DiagnosticSnapshotStore(WorkspacePaths.current().root())
                .write(frame, SNAPSHOT_ACTIVITY, label, Instant.now())
                .map(Object::toString).orElse("(write failed)");
    }

    public boolean close() {
        if (selectedSection().isEmpty()) {
            homeFallback.restoreWorld();
            return true;
        }
        taps.tapInside(CommonGameAreas.LEFT_MENU_CLOSE, 1, SECTION_SETTLE_MS);
        boolean closed = selectedSection().isEmpty();
        if (closed) {
            homeFallback.restoreWorld();
        } else {
            log.warn("Sidebar close control did not close the panel");
        }
        return closed;
    }

    private boolean openSectionInternal(SidebarSection target) {
        // Nothing here may tap while the march Recall confirmation is up: its Confirm destroys a
        // running gather march. Back out of it first, and if it will not clear, tap nothing.
        if (!recallGuard.clear()) {
            log.warn("Recall confirmation is on screen and would not clear; tapping nothing for "
                    + target);
            return false;
        }
        ScreenState initialState = screenStates.read();
        Optional<SidebarSection> current = initialState.section();

        if (current.isEmpty()) {
            if (!initialState.rootScreen()) {
                log.warn("Refusing to tap the sidebar trigger without a Home or World anchor");
                return false;
            }
            // A tile popup leaves the World anchor visible, so the screen above reads as a clean
            // root while the trigger tap would go into the popup. Close it with back first; never
            // tap inside it, where Attack and Recall sit.
            if (!tilePopupGuard.clear()) {
                log.warn("World tile popup is still over the map after back; tapping nothing for "
                        + target);
                return false;
            }
            int triggerTaps = 0;
            while (current.isEmpty() && triggerTaps < MAX_TRIGGER_TAPS) {
                triggerTaps++;
                log.debug("Sidebar closed/unknown; trigger tap " + triggerTaps + "/" + MAX_TRIGGER_TAPS
                        + " before selecting " + target);
                taps.tapInside(CommonGameAreas.LEFT_MENU_TRIGGER, 1, SIDEBAR_OPEN_SETTLE_MS);

                TransitionObservation observation = awaitSection(Optional::isPresent);
                current = observation.section();
                if (current.isPresent()) {
                    if (observation.checks() > 1 || triggerTaps > 1) {
                        log.debug("Sidebar open confirmed after triggerTaps=" + triggerTaps
                                + " checks=" + observation.checks()
                                + " observed=" + current.orElseThrow());
                    }
                    break;
                }

                ScreenState retryState = screenStates.read();
                current = retryState.section();
                if (current.isPresent()) {
                    log.debug("Sidebar open confirmed on retry verification without another trigger: observed="
                            + current.orElseThrow());
                    break;
                }
                boolean rootScreen = retryState.rootScreen();
                if (!shouldRetryTrigger(triggerTaps, observation, current, rootScreen)) {
                    log.warn("Sidebar open not confirmed: triggerTaps=" + triggerTaps + "/"
                            + MAX_TRIGGER_TAPS + " checks=" + observation.checks()
                            + " interrupted=" + observation.interrupted()
                            + " observed=closed/unknown rootScreen=" + rootScreen);
                    return false;
                }
                log.debug("Sidebar still closed/unknown on a verified root screen after checks="
                        + observation.checks() + "; retrying the trigger once");
            }
        }

        if (current.orElse(null) != target) {
            // The reading above can be seconds old: the trigger loop waits out a 2 s settle and
            // up to nine polls before this point. That matters because on World the tab strip is
            // not over empty map -- the Marching panel occupies the upper left, and every saved
            // World frame here shows it across the tab rows. A tab tap sent after the panel has
            // closed therefore does not miss harmlessly: it reaches the march list underneath,
            // and a selected march offers Recall.
            //
            // This is not hypothetical. On 2026-11-11 three saved frames (20261111T045932,
            // T070403, T121940) caught the bot sitting on an open "Recall Troops?" confirmation
            // over live gather marches, and all three follow this very log line for WILDERNESS --
            // three for three, while the CITY failures and the trigger-tap failures show no such
            // dialog. Each was dismissed only because recovery happened to press back.
            //
            // So re-read, and tap tab coordinates only with a panel in the frame we have now.
            if (!recallGuard.clear()) {
                log.warn("Recall confirmation appeared before the " + target
                        + " tab could be tapped; tapping nothing");
                return false;
            }
            // Poll rather than demand a panel in one frame: the classifier reads unknown for a
            // frame or two mid-animation, and aborting on that would make honest switches flaky.
            // What must not happen is tapping when the panel is really gone.
            TransitionObservation beforeTap = awaitSection(Optional::isPresent);
            if (beforeTap.section().isEmpty()) {
                log.warn("Sidebar closed before the " + target
                        + " tab could be tapped; refusing to tap tab coordinates over the map");
                return false;
            }
            current = beforeTap.section();
        }

        if (current.orElse(null) != target) {
            taps.tapInside(CommonGameAreas.sidebarTab(target), 1, SECTION_SETTLE_MS);
            TransitionObservation observation = awaitSection(section -> section.orElse(null) == target);
            current = observation.section();
            if (current.orElse(null) != target) {
                log.warn("Sidebar section selection failed: requested=" + target
                        + " observed=" + current.map(Enum::name).orElse("closed/unknown")
                        + " checks=" + observation.checks());
                return false;
            }
            if (observation.checks() > 1) {
                log.debug("Sidebar section transition recovered after checks="
                        + observation.checks() + " requested=" + target);
            }
        }

        log.debug("Sidebar section ready without assuming a scroll origin: " + target);
        return true;
    }

    static AreaData rowActionAreaFor(ImageSearchResultData rowIcon) {
        PointData center = rowIcon.getPoint();
        if (center == null) {
            throw new IllegalArgumentException("A located row icon is required");
        }
        int top = Math.max(CommonGameAreas.SIDEBAR_CONTENT.topLeft().getY(), center.getY() - GO_HALF_HEIGHT);
        int bottom = Math.min(CommonGameAreas.SIDEBAR_CONTENT.bottomRight().getY(), center.getY() + GO_HALF_HEIGHT);
        return new AreaData(
                new PointData(center.getX() + GO_LEFT_OFFSET, top),
                new PointData(center.getX() + GO_RIGHT_OFFSET, bottom));
    }

    static AreaData goButtonFor(ImageSearchResultData rowIcon) {
        return rowActionAreaFor(rowIcon);
    }

    private ScrollScanResult scan(SidebarDestination destination, ScrollDirection direction,
                                  RawImageData initialFrame) {
        RawImageData before = initialFrame;
        for (int step = 1; step <= MAX_BOUNDARY_STEPS; step++) {
            RawImageData after = scrollAndCapture(direction);
            if (after == null || selectedSection(after).orElse(null) != destination.section()) {
                log.warn("Sidebar state changed while scanning for " + destination
                        + " direction=" + direction + " step=" + step);
                return new ScrollScanResult(ImageSearchResultData.miss(), before, false, false);
            }

            ImageSearchResultData rowIcon = locateRowIcon(destination, after);
            boolean changed = SidebarViewportChangeDetector.materiallyChanged(
                    ImageConverter.toBufferedImage(before), ImageConverter.toBufferedImage(after));
            log.debug("Sidebar icon scan: destination=" + destination + " direction=" + direction
                    + " step=" + step + "/" + MAX_BOUNDARY_STEPS
                    + " changed=" + changed + " found=" + rowIcon.isFound());
            if (rowIcon.isFound()) {
                return new ScrollScanResult(rowIcon, after, false, true);
            }
            if (!changed) {
                return new ScrollScanResult(ImageSearchResultData.miss(), after, true, true);
            }
            before = after;
        }
        return new ScrollScanResult(ImageSearchResultData.miss(), before, false, true);
    }

    private RawImageData scrollAndCapture(ScrollDirection direction) {
        emu.swipeScreen(device, direction.from(), direction.to(), SCROLL_DURATION_MS);
        if (!interruptibleWait(SCROLL_SETTLE_MS)) {
            return null;
        }
        return emu.captureScreen(device);
    }

    private ImageSearchResultData locateRowIcon(SidebarDestination destination, RawImageData frame) {
        return emu.locatePattern(device, frame, destination.rowIcon(),
                CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.topLeft(),
                CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.bottomRight(), ROW_ICON_THRESHOLD);
    }

    private ImageSearchResultData locateRowAction(SidebarDestination destination, AreaData area,
                                                  RawImageData frame) {
        for (SidebarRowAction action : destination.actions()) {
            for (TemplatesEnum template : action.templates()) {
                ImageSearchResultData result = emu.locatePattern(device, frame, template,
                        area.topLeft(), area.bottomRight(), ROW_ACTION_THRESHOLD);
                if (result.isFound()) {
                    return result;
                }
            }
        }
        return ImageSearchResultData.miss();
    }

    /** The real screen behind {@link RecallConfirmationGuard}. */
    private final class EmulatorRecallGuard implements RecallConfirmationGuard {

        @Override
        public boolean dialogOnScreen() {
            RawImageData frame = emu.captureScreen(device);
            return frame != null
                    && emu.locatePattern(device, frame, TemplatesEnum.MARCHES_RECALL_CONFIRM_DIALOG,
                            RECALL_DIALOG_THRESHOLD).isFound();
        }

        @Override
        public void pressBack() {
            log.warn("Recall confirmation on screen; backing out of it rather than tapping");
            emu.pressBack(device);
            interruptibleWait(SECTION_SETTLE_MS);
        }
    }

    /** The real screen behind {@link WorldTilePopupGuard}. */
    private final class EmulatorTilePopupGuard implements WorldTilePopupGuard {

        @Override
        public boolean popupOnScreen() {
            RawImageData frame = emu.captureScreen(device);
            return frame != null
                    && emu.locatePattern(device, frame, TemplatesEnum.WORLD_TILE_POPUP_HEADER,
                            TILE_POPUP_THRESHOLD).isFound();
        }

        @Override
        public void pressBack() {
            log.info("World tile popup over the map; pressing back to close it before the sidebar"
                    + " trigger tap");
            emu.pressBack(device);
            interruptibleWait(TILE_POPUP_BACK_SETTLE_MS);
        }
    }

    /** The real screen behind {@link SidebarHomeFallback}. */
    private final class EmulatorScreens implements SidebarHomeFallback.Screens {

        @Override
        public void recordFailedOpen() {
            RawImageData frame = emu.captureScreen(device);
            if (frame == null) {
                log.warn("Sidebar did not open and no frame could be captured for it");
                return;
            }
            log.warn("Sidebar did not open; frame saved: " + new DiagnosticSnapshotStore(WorkspacePaths.current().root())
                    .write(frame, SNAPSHOT_ACTIVITY, "open-failed", Instant.now())
                    .map(Object::toString).orElse("(write failed)"));
        }

        @Override
        public boolean sidebarClosedOnWorld() {
            RawImageData frame = emu.captureScreen(device);
            return frame != null && selectedSection(frame).isEmpty() && locateAnchor(frame, TemplatesEnum.GAME_HOME_WORLD).isFound();
        }

        @Override
        public boolean switchToHome() {
            log.warn("Sidebar will not open on World; opening it from Home instead");
            return switchScreen(TemplatesEnum.GAME_HOME_WORLD, TemplatesEnum.GAME_HOME_FURNACE);
        }

        @Override
        public boolean switchToWorld() {
            boolean back = switchScreen(TemplatesEnum.GAME_HOME_FURNACE, TemplatesEnum.GAME_HOME_WORLD);
            if (!back) {
                log.warn("Could not return to World after using the sidebar from Home");
            }
            return back;
        }

        private boolean switchScreen(TemplatesEnum tapped, TemplatesEnum expected) {
            RawImageData frame = emu.captureScreen(device);
            if (frame == null) {
                return false;
            }
            ImageSearchResultData anchor = locateAnchor(frame, tapped);
            if (!anchor.isFound()) {
                return false;
            }
            taps.tapInside(anchor, 1, SCREEN_SWITCH_SETTLE_MS);
            RawImageData after = emu.captureScreen(device);
            return after != null && locateAnchor(after, expected).isFound();
        }

        private ImageSearchResultData locateAnchor(RawImageData frame, TemplatesEnum anchor) {
            return emu.locatePattern(device, frame, anchor, SearchConfigConstants.DEFAULT_SINGLE.getThreshold());
        }
    }

    private ScreenState captureScreenState() {
        RawImageData frame = emu.captureScreen(device);
        Optional<SidebarSection> section = selectedSection(frame);
        return new ScreenState(section, section.isEmpty() && isRootScreen(frame));
    }

    private boolean isRootScreen(RawImageData frame) {
        double threshold = SearchConfigConstants.DEFAULT_SINGLE.getThreshold();
        return emu.locatePattern(device, frame, TemplatesEnum.GAME_HOME_FURNACE, threshold).isFound()
                || emu.locatePattern(device, frame, TemplatesEnum.GAME_HOME_WORLD, threshold).isFound();
    }

    private Optional<SidebarSection> selectedSection() {
        return sections.read();
    }

    private Optional<SidebarSection> captureSelectedSection() {
        return selectedSection(emu.captureScreen(device));
    }

    private Optional<SidebarSection> selectedSection(RawImageData frame) {
        return SidebarFrameClassifier.selectedSection(ImageConverter.toBufferedImage(frame));
    }

    private TransitionObservation awaitSection(Predicate<Optional<SidebarSection>> accepted) {
        return awaitSection(accepted, sections, transitionWaiter);
    }

    static TransitionObservation awaitSection(Predicate<Optional<SidebarSection>> accepted,
                                               SectionReader reader, Waiter waiter) {
        Optional<SidebarSection> observed = Optional.empty();
        boolean interrupted = false;
        for (int check = 1; check <= TRANSITION_POLL_CHECKS; check++) {
            observed = reader.read();
            if (accepted.test(observed) || check == TRANSITION_POLL_CHECKS) {
                return new TransitionObservation(observed, check, interrupted);
            }
            if (!waiter.waitFor(TRANSITION_POLL_MS)) {
                return new TransitionObservation(observed, check, true);
            }
        }
        throw new IllegalStateException("Unreachable sidebar transition polling state");
    }

    static boolean shouldRetryTrigger(int triggerTaps, TransitionObservation observation,
                                      Optional<SidebarSection> observed, boolean rootScreen) {
        return !observation.interrupted() && triggerTaps < MAX_TRIGGER_TAPS
                && observed.isEmpty() && rootScreen;
    }

    private boolean interruptibleWait(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private record ScrollScanResult(ImageSearchResultData rowIcon, RawImageData frame,
                                    boolean boundaryReached, boolean completed) {}

    record TransitionObservation(Optional<SidebarSection> section, int checks, boolean interrupted) {}

    record ScreenState(Optional<SidebarSection> section, boolean rootScreen) {}

    @FunctionalInterface
    interface SectionReader {
        Optional<SidebarSection> read();
    }

    @FunctionalInterface
    interface ScreenStateReader {
        ScreenState read();
    }

    @FunctionalInterface
    interface Waiter {
        boolean waitFor(long milliseconds);
    }

    private enum ScrollDirection {
        TOWARD_BOTTOM(CommonGameAreas.SIDEBAR_SCROLL_TOWARD_BOTTOM_FROM,
                CommonGameAreas.SIDEBAR_SCROLL_TOWARD_BOTTOM_TO),
        TOWARD_TOP(CommonGameAreas.SIDEBAR_SCROLL_TOWARD_TOP_FROM,
                CommonGameAreas.SIDEBAR_SCROLL_TOWARD_TOP_TO);

        private final PointData from;
        private final PointData to;

        ScrollDirection(PointData from, PointData to) {
            this.from = from;
            this.to = to;
        }

        PointData from() {
            return from;
        }

        PointData to() {
            return to;
        }
    }
}
