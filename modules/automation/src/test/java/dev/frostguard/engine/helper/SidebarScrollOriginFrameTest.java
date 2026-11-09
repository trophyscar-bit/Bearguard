package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.emulator.EmulatorInstance;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SidebarRowLookup;
import dev.frostguard.engine.nav.SidebarSection;

/**
 * The Daily panel reopens at whatever scroll offset the previous routine left behind. These tests
 * drive the real captured Daily frames as a three-position scroll model, because a row parked above
 * the opening viewport is exactly the case a downward-only scan reported as an unavailable
 * destination while it sat one swipe up.
 */
class SidebarScrollOriginFrameTest {

    /** Real Daily captures, top of the list first. Life Essence is only in the top one. */
    private static final String[] POSITIONS = {
            "/navigation/sidebar-update-20260817/daily-top.png",
            "/navigation/sidebar-update-20260817/daily-middle.png",
            "/navigation/sidebar-update-20260817/daily-bottom.png" };

    @Test
    void findsARowParkedAboveTheOpeningViewport() throws Exception {
        Rig rig = new Rig(2);

        SidebarRowLookup lookup = rig.nav.findRowWithStatus(SidebarDestination.LIFE_ESSENCE);

        assertEquals(SidebarRowLookup.Status.FOUND, lookup.status(),
                "A row above the opening viewport must be reachable");
        ImageSearchResultData row = lookup.rowIcon();
        assertTrue(row.isFound());
        assertEquals(712, row.getPoint().getY(), "Life Essence sits at y=712 in the top frame");
        assertEquals(0, rig.backend.position, "The scan must walk the list back up to the top");
    }

    @Test
    void stillFindsARowAlreadyVisibleWithoutScrolling() throws Exception {
        Rig rig = new Rig(0);

        SidebarRowLookup lookup = rig.nav.findRowWithStatus(SidebarDestination.LIFE_ESSENCE);

        assertEquals(SidebarRowLookup.Status.FOUND, lookup.status());
        assertEquals(0, rig.backend.swipes, "A visible row must never be swiped for");
    }

    @Test
    void scansBothWaysBeforeCallingARealDestinationAbsent() throws Exception {
        Rig rig = new Rig(1);

        SidebarRowLookup lookup = rig.nav.findRowWithStatus(SidebarDestination.CRYSTAL_LABORATORY);

        assertEquals(SidebarRowLookup.Status.DESTINATION_ABSENT, lookup.status(),
                "Crystal Laboratory is in none of these frames");
        assertEquals(0, rig.backend.position, "An absent row is only absent once both ends are seen");
        assertTrue(rig.backend.reachedBottom, "The downward leg must reach the bottom of the list");
    }

    // ===================== scroll model =====================

    private static RawImageData raw(String resource) throws IOException {
        BufferedImage image;
        try (InputStream stream = SidebarScrollOriginFrameTest.class.getResourceAsStream(resource)) {
            image = ImageIO.read(new ByteArrayInputStream(
                    Objects.requireNonNull(stream, "Missing test resource: " + resource).readAllBytes()));
        }
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] pixels = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int offset = (y * width + x) * 4;
                pixels[offset] = (byte) ((rgb >> 16) & 0xFF);
                pixels[offset + 1] = (byte) ((rgb >> 8) & 0xFF);
                pixels[offset + 2] = (byte) (rgb & 0xFF);
                pixels[offset + 3] = (byte) 0xFF;
            }
        }
        // 32, not 4: the OpenCV frame decoder switches on bpp == 32 for RGBA and treats anything
        // else as RGB565, which turns an RGBA buffer tagged 4 into noise that matches nothing.
        return RawImageData.capture(pixels, width, height, 32);
    }

    /** A Daily list that scrolls between the three real captures and clamps at both ends. */
    private static class Backend extends EmulatorInstance {
        private List<RawImageData> frames;
        private int position;
        private int swipes;
        private boolean reachedBottom;

        private Backend() {
            super("unused-scroll-model");
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
            return frames.get(position);
        }

        @Override
        public void swipe(String index, PointData from, PointData to, int duration) {
            swipes++;
            if (to.getY() < from.getY()) {
                position = Math.min(position + 1, frames.size() - 1);
                if (position == frames.size() - 1) {
                    reachedBottom = true;
                }
            } else {
                position = Math.max(position - 1, 0);
            }
        }
    }

    private static class Rig {
        private final Backend backend;
        private final SidebarNavigator nav;
        private final AtomicInteger taps = new AtomicInteger();

        private Rig(int startPosition) throws Exception {
            dev.frostguard.vision.match.OpenCvPatternLocator.loadNativeLibrary();
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            backend = (Backend) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(Backend.class);
            List<RawImageData> frames = new ArrayList<>();
            for (String position : POSITIONS) {
                frames.add(raw(position));
            }
            backend.frames = frames;
            backend.position = startPosition;
            Constructor<EmulatorController> constructor = EmulatorController.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            EmulatorController emu = constructor.newInstance();
            set(emu, "backend", backend);
            AccountDescriptor account =
                    new AccountDescriptor(998L, "Synthetic Scroll", "scroll", true, 0L, 0L);
            nav = new SidebarNavigator(emu, "scroll", account);
            set(nav, "taps", new TapInteractionService(point -> taps.incrementAndGet(), null, millis -> {}));
            set(nav, "transitionWaiter", (SidebarNavigator.Waiter) millis -> true);
            set(nav, "screenStates", (SidebarNavigator.ScreenStateReader) () ->
                    new SidebarNavigator.ScreenState(Optional.of(SidebarSection.DAILY), false));
        }

        private static void set(Object target, String name, Object value) throws Exception {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        }
    }
}
