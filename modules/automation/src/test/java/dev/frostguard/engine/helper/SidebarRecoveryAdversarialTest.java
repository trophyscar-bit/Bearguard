package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.emulator.EmulatorInstance;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SidebarRouteResult;
import dev.frostguard.engine.nav.SidebarRowLookup;
import dev.frostguard.engine.nav.SidebarSection;

class SidebarRecoveryAdversarialTest {

    private static void put(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static RawImageData frame(boolean daily) {
        byte[] pixels = new byte[720 * 1280 * 4];
        if (daily) {
            paint(pixels, CommonGameAreas.LEFT_MENU_CLOSE, 255);
            paint(pixels, CommonGameAreas.sidebarTabSample(SidebarSection.DAILY), 170);
        }
        return RawImageData.capture(pixels, 720, 1280, 4);
    }

    private static RawImageData sectionFrame(SidebarSection section) {
        byte[] pixels = new byte[720 * 1280 * 4];
        paint(pixels, CommonGameAreas.LEFT_MENU_CLOSE, 255);
        paint(pixels, CommonGameAreas.sidebarTabSample(section), 170);
        return RawImageData.capture(pixels, 720, 1280, 4);
    }

    private static void paint(byte[] pixels, AreaData area, int value) {
        for (int y = area.topLeft().getY(); y <= area.bottomRight().getY(); y++) {
            for (int x = area.topLeft().getX(); x <= area.bottomRight().getX(); x++) {
                for (int channel = 0; channel < 3; channel++) {
                    pixels[(y * 720 + x) * 4 + channel] = (byte) value;
                }
            }
        }
    }

    private static class Backend extends EmulatorInstance {
        private RawImageData current = frame(false);
        private boolean interruptOnSwipe;
        private boolean preserveSectionOnSwipe;
        private boolean loseSectionAfterFirstCapture;
        private int captures;
        private int swipeCount;
        private Queue<RawImageData> captureFrames;

        private Backend() {
            super("unused-review-backend");
        }

        @Override
        protected String getDeviceSerial(String index) {
            throw new AssertionError("No live device");
        }

        @Override
        public void launchEmulator(String index) {
            throw new AssertionError("No live device");
        }

        @Override
        public void closeEmulator(String index) {
            throw new AssertionError("No live device");
        }

        @Override
        public boolean isRunning(String index) {
            return false;
        }

        @Override
        public RawImageData captureScreenshot(String index) {
            RawImageData captured = captureFrames.isEmpty() ? current : captureFrames.remove();
            if (loseSectionAfterFirstCapture && captures++ == 0) {
                current = frame(false);
            }
            return captured;
        }

        @Override
        public void swipe(String index, PointData from, PointData to, int duration) {
            swipeCount++;
            if (!preserveSectionOnSwipe) {
                current = frame(false);
            }
            if (interruptOnSwipe) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class Rig {
        private final Backend backend;
        private final EmulatorController emu;
        private final AccountDescriptor account =
                new AccountDescriptor(999L, "Synthetic Review", "review", true, 0L, 0L);
        private final SidebarNavigator nav;
        private final AtomicInteger taps = new AtomicInteger();
        private final java.util.List<PointData> tapPoints = new java.util.ArrayList<>();
        private Runnable onTap = () -> {};

        private Rig() throws Exception {
            this("review");
        }

        private Rig(String device) throws Exception {
            dev.frostguard.vision.match.OpenCvPatternLocator.loadNativeLibrary();
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            backend = (Backend) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(Backend.class);
            backend.current = frame(false);
            backend.captureFrames = new ArrayDeque<>();
            Constructor<EmulatorController> constructor = EmulatorController.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            emu = constructor.newInstance();
            put(emu, "backend", backend);
            nav = new SidebarNavigator(emu, device, account);
            put(nav, "taps", new TapInteractionService(point -> {
                taps.incrementAndGet();
                tapPoints.add(point);
                onTap.run();
            }, null, millis -> {}));
            put(nav, "transitionWaiter", (SidebarNavigator.Waiter) millis -> true);
        }
    }

    @Test
    void sectionThatAppearsDuringFreshRootCheckMustNotReceiveSecondTrigger() throws Exception {
        Rig rig = new Rig();
        AtomicInteger reads = new AtomicInteger();
        put(rig.nav, "screenStates", (SidebarNavigator.ScreenStateReader) () -> {
            if (reads.incrementAndGet() == 2) {
                rig.backend.current = frame(true);
                return new SidebarNavigator.ScreenState(
                        java.util.Optional.of(SidebarSection.DAILY), false);
            }
            return new SidebarNavigator.ScreenState(java.util.Optional.empty(), true);
        });

        assertTrue(rig.nav.openSection(SidebarSection.DAILY));
        assertEquals(1, rig.taps.get(), "Already visible Daily panel must suppress retry");
    }

    @Test
    void lostSectionDuringScanMustNotBecomeDestinationAbsent() throws Exception {
        Rig rig = new Rig();
        rig.backend.current = frame(true);

        assertEquals(SidebarRowLookup.Status.SIDEBAR_UNAVAILABLE,
                rig.nav.findRowWithStatus(SidebarDestination.LIGHTHOUSE_INTEL).status());
    }

    @Test
    void lostSectionBeforeFirstScanMustNotSwipe() throws Exception {
        Rig rig = new Rig();
        rig.backend.current = frame(true);
        rig.backend.loseSectionAfterFirstCapture = true;

        assertEquals(SidebarRowLookup.Status.SIDEBAR_UNAVAILABLE,
                rig.nav.findRowWithStatus(SidebarDestination.LIGHTHOUSE_INTEL).status());
        assertEquals(0, rig.backend.swipeCount, "No swipe is safe after section evidence is lost");
    }

    @Test
    void interruptedScanMustNotBecomeDestinationAbsent() throws Exception {
        Rig rig = new Rig();
        rig.backend.current = frame(true);
        rig.backend.interruptOnSwipe = true;
        try {
            assertEquals(SidebarRowLookup.Status.SIDEBAR_UNAVAILABLE,
                    rig.nav.findRowWithStatus(SidebarDestination.LIGHTHOUSE_INTEL).status());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void completedValidScanCanStillReportDestinationAbsent() throws Exception {
        Rig rig = new Rig();
        rig.backend.current = frame(true);
        rig.backend.preserveSectionOnSwipe = true;

        assertEquals(SidebarRowLookup.Status.DESTINATION_ABSENT,
                rig.nav.findRowWithStatus(SidebarDestination.LIGHTHOUSE_INTEL).status());
        assertEquals(0, rig.taps.get(), "An unreadable checkbox must not be tapped");
    }

    private static RawImageData realFrame(String name) throws Exception {
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(java.util.Objects.requireNonNull(
                SidebarRecoveryAdversarialTest.class.getResourceAsStream(name), name));
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                rgba[offset++] = (byte) ((rgb >> 16) & 0xFF);
                rgba[offset++] = (byte) ((rgb >> 8) & 0xFF);
                rgba[offset++] = (byte) (rgb & 0xFF);
                rgba[offset++] = (byte) 0xFF;
            }
        }
        // The fixtures are blacked out to the right of the panel, which removes its close control.
        paint(rgba, CommonGameAreas.LEFT_MENU_CLOSE, 255);
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
    }

    private static final String HIDE_ON = "/navigation/sidebar-dynamic-20260821/daily-hide-on.png";
    private static final String HIDE_OFF = "/navigation/sidebar-dynamic-20260821/daily-hide-off.png";

    @Test
    void aTickedBoxIsUntickedOnceAndTheListIsScannedAfterwards() throws Exception {
        Rig rig = new Rig("hide-completed-once");
        RawImageData ticked = realFrame(HIDE_ON);
        RawImageData unticked = realFrame(HIDE_OFF);
        rig.backend.current = ticked;
        rig.backend.preserveSectionOnSwipe = true;
        rig.onTap = () -> rig.backend.current = unticked;

        assertEquals(SidebarRowLookup.Status.FOUND,
                rig.nav.findRowWithStatus(SidebarDestination.LIGHTHOUSE_INTEL).status(),
                "The Lighthouse row is only on the frame shown after the box is unticked");
        assertEquals(1, rig.taps.get());
        PointData tap = rig.tapPoints.get(0);
        AreaData area = CommonGameAreas.DAILY_HIDE_COMPLETED_TAP;
        assertTrue(tap.getX() >= area.topLeft().getX() && tap.getX() <= area.bottomRight().getX()
                && tap.getY() >= area.topLeft().getY() && tap.getY() <= area.bottomRight().getY(),
                () -> "Tap landed outside the checkbox: " + tap);

        rig.backend.current = ticked;
        assertEquals(SidebarRowLookup.Status.DESTINATION_ABSENT,
                rig.nav.findRowWithStatus(SidebarDestination.LIGHTHOUSE_INTEL).status());
        assertEquals(1, rig.taps.get(), "Even a box seen ticked again is left alone this run");
    }

    @Test
    void aTapThatDoesNotTakeIsNeverRepeated() throws Exception {
        Rig rig = new Rig("hide-completed-stuck");
        rig.backend.current = realFrame(HIDE_ON);
        rig.backend.preserveSectionOnSwipe = true;

        rig.nav.findRowWithStatus(SidebarDestination.LIGHTHOUSE_INTEL);
        rig.nav.findRowWithStatus(SidebarDestination.LIGHTHOUSE_INTEL);

        assertEquals(1, rig.taps.get());
    }

    @Test
    void anUntickedBoxIsNeverTapped() throws Exception {
        Rig rig = new Rig("hide-completed-off");
        rig.backend.current = realFrame(HIDE_OFF);

        assertEquals(SidebarRowLookup.Status.FOUND,
                rig.nav.findRowWithStatus(SidebarDestination.LIGHTHOUSE_INTEL).status());
        assertEquals(0, rig.taps.get());
    }

    @Test
    void losingThePanelAfterTheTapIsNotARowVerdict() throws Exception {
        Rig rig = new Rig("hide-completed-lost");
        rig.backend.current = realFrame(HIDE_ON);
        rig.onTap = () -> rig.backend.current = frame(false);

        assertEquals(SidebarRowLookup.Status.SIDEBAR_UNAVAILABLE,
                rig.nav.findRowWithStatus(SidebarDestination.LIGHTHOUSE_INTEL).status());
        assertEquals(1, rig.taps.get());
        assertEquals(0, rig.backend.swipeCount);
    }

    @Test
    void aMissingDailyRowIsNotOfferedButAMissingCityRowIsAFailure() throws Exception {
        Rig daily = new Rig();
        daily.backend.current = frame(true);
        daily.backend.preserveSectionOnSwipe = true;
        assertEquals(SidebarRouteResult.NOT_OFFERED, daily.nav.navigateTo(SidebarDestination.PET_ADVENTURE));

        Rig city = new Rig();
        city.backend.current = sectionFrame(SidebarSection.CITY);
        city.backend.preserveSectionOnSwipe = true;
        assertEquals(SidebarRouteResult.FAILED, city.nav.navigateTo(SidebarDestination.RESEARCH_CENTER));
    }

    @Test
    void aRowShowingAControlTheRouteDoesNotUseIsNotTapped() throws Exception {
        Rig rig = new Rig();
        RawImageData real = realFrame("/navigation/sidebar-update-20260818/daily-current-account.png");
        byte[] pixels = real.getData().clone();
        // The Pet Adventure row sits at y=487; blanking its control leaves the icon and no action.
        paint(pixels, AreaData.of(361, 457, 440, 517), 0);
        rig.backend.current = RawImageData.capture(pixels, 720, 1280, 32);

        assertEquals(SidebarRouteResult.NOT_OFFERED, rig.nav.navigateTo(SidebarDestination.PET_ADVENTURE));
        assertEquals(0, rig.taps.get());
        assertEquals(0, rig.backend.swipeCount, "The row was on screen, so this was the action check");
    }

    @Test
    void initialUnknownNonRootStateNeverTaps() throws Exception {
        Rig rig = new Rig();
        put(rig.nav, "screenStates", (SidebarNavigator.ScreenStateReader) () ->
                new SidebarNavigator.ScreenState(java.util.Optional.empty(), false));

        assertFalse(rig.nav.openSection(SidebarSection.DAILY));
        assertEquals(0, rig.taps.get());
    }

    @Test
    void retryExhaustionStopsAfterTwoVerifiedTriggers() throws Exception {
        Rig rig = new Rig();
        put(rig.nav, "screenStates", (SidebarNavigator.ScreenStateReader) () ->
                new SidebarNavigator.ScreenState(java.util.Optional.empty(), true));

        assertFalse(rig.nav.openSection(SidebarSection.DAILY));
        assertEquals(SidebarNavigator.MAX_TRIGGER_TAPS, rig.taps.get());
    }

    @Test
    void retryIsRefusedWhenTheFreshFrameIsNoLongerARootScreen() throws Exception {
        Rig rig = new Rig();
        AtomicInteger reads = new AtomicInteger();
        put(rig.nav, "screenStates", (SidebarNavigator.ScreenStateReader) () ->
                new SidebarNavigator.ScreenState(
                        java.util.Optional.empty(), reads.getAndIncrement() == 0));

        assertFalse(rig.nav.openSection(SidebarSection.DAILY));
        assertEquals(1, rig.taps.get());
    }

    @Test
    void sectionSwitchWaitsPastThePreviousAndUnknownFrames() throws Exception {
        Rig rig = new Rig();
        put(rig.nav, "screenStates", (SidebarNavigator.ScreenStateReader) () ->
                new SidebarNavigator.ScreenState(Optional.of(SidebarSection.CITY), false));
        rig.backend.captureFrames.add(sectionFrame(SidebarSection.CITY));
        rig.backend.captureFrames.add(frame(false));
        rig.backend.captureFrames.add(frame(true));

        assertTrue(rig.nav.openSection(SidebarSection.DAILY));
        assertEquals(1, rig.taps.get(), "A section switch must tap the requested tab once");
    }

    @Test
    void interruptionDuringTriggerPollMustNotRetry() throws Exception {
        Rig rig = new Rig();
        put(rig.nav, "transitionWaiter", (SidebarNavigator.Waiter) millis -> false);
        put(rig.nav, "screenStates", (SidebarNavigator.ScreenStateReader) () ->
                new SidebarNavigator.ScreenState(Optional.empty(), true));

        assertFalse(rig.nav.openSection(SidebarSection.DAILY));
        assertEquals(1, rig.taps.get(),
                "interrupted waiting must never issue a second trigger tap");
    }
}
