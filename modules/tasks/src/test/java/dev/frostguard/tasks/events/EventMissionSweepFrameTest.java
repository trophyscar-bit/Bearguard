package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.tasks.events.EventMissionClassifier.Kind;
import dev.frostguard.tasks.events.EventMissionSweep.ButtonState;
import dev.frostguard.tasks.events.EventMissionSweep.Geometry;
import dev.frostguard.tasks.events.EventMissionSweep.Result;
import dev.frostguard.tasks.events.EventMissionSweep.Row;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/** The sweep against the Wanderer Missions frames of 2026-11-09 (720x1280). */
class EventMissionSweepFrameTest {

    private static final String DIR = "/wander/";
    static final Geometry WANDER = new Geometry(640, 1245, 582, 528, 636, 40, 520, 77, 24, 1150, 750, 330, 8, 20);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void rowsAreFoundByTheirButtonColour() throws Exception {
        List<Row> rows = EventMissionSweep.findRows(image("missions-claimable.png"), WANDER);
        assertEquals(3, rows.size(), rows.toString());
        assertEquals(ButtonState.CLAIM, rows.get(0).state());
        assertEquals(747, rows.get(0).centerY(), 6);
        assertEquals(ButtonState.GO, rows.get(1).state());
        assertEquals(917, rows.get(1).centerY(), 6);
        assertEquals(ButtonState.GO, rows.get(2).state());
        assertEquals(1088, rows.get(2).centerY(), 6);
    }

    @Test
    void titlesReadFromTheBandAboveEachButton() throws Exception {
        RawImageData raw = raw("missions-claimable.png");
        String troops = EventMissionSweep.ocrTitle(raw, WANDER, 747);
        String polar = EventMissionSweep.ocrTitle(raw, WANDER, 917);
        String donations = EventMissionSweep.ocrTitle(raw, WANDER, 1088);
        assertEquals(Kind.PASSIVE, EventMissionClassifier.classify(troops).kind(), troops);
        assertEquals(TpDailyTaskEnum.EVENT_POLAR_TERROR, EventMissionClassifier.classify(polar).task(), polar);
        assertEquals(TpDailyTaskEnum.ALLIANCE_TECH, EventMissionClassifier.classify(donations).task(), donations);
    }

    @Test
    void identicalFramesMeanTheListHasEnded() throws Exception {
        assertTrue(EventMissionSweep.sameList(image("missions-claimable.png"), image("missions-claimable.png"), WANDER));
        assertFalse(EventMissionSweep.sameList(image("missions-claimable.png"), image("missions-claimed-one.png"), WANDER));
    }

    /** Frames by state: before the claim, after it; titles by the row's button position. */
    private class FakeScreen implements EventMissionSweep.Screen {
        final List<String> taps = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> saved = new ArrayList<>();
        boolean claimed;
        boolean listVisible = true;
        final boolean afterClaimUnexpected;
        final BiFunction<Boolean, Integer, String> titles;

        FakeScreen(boolean afterClaimUnexpected, BiFunction<Boolean, Integer, String> titles) {
            this.afterClaimUnexpected = afterClaimUnexpected;
            this.titles = titles;
        }

        @Override
        public BufferedImage frame() {
            try {
                return image(claimed ? "missions-claimed-one.png" : "missions-claimable.png");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String readTitle(BufferedImage frame, int buttonCenterY) {
            return titles.apply(claimed, buttonCenterY);
        }

        @Override
        public void tap(int x, int y) {
            taps.add(x + "," + y);
            claimed = true;
            if (afterClaimUnexpected) {
                listVisible = false;
            }
        }

        @Override
        public void swipe(int fromX, int fromY, int toX, int toY) {
        }

        @Override
        public void sleep(long millis) {
        }

        @Override
        public boolean onMissionList() {
            return listVisible;
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public void saveFrame(String tag, BufferedImage frame) {
            saved.add(tag);
        }
    }

    private static String wanderTitle(boolean afterClaim, int y) {
        int row = Math.round((y - 747) / 171f);
        String[] before = {"Train/Promote up 30,000 Troop Power", "Call rally and hunt down 1 Polar Terror(s)",
            "Make 5 Alliance Contribution(s)", "Make any payment 1 time(s)"};
        String[] after = {"Call rally and hunt down 1 Polar Terror(s)", "Make 5 Alliance Contribution(s)",
            "Make any payment 1 time(s)", "Train/Promote up 30,000 Troop Power"};
        return (afterClaim ? after : before)[row];
    }

    @Test
    void itClaimsTheFinishedMissionAndNeverTapsAGoButton() {
        FakeScreen screen = new FakeScreen(false, EventMissionSweepFrameTest::wanderTitle);
        Result result = EventMissionSweep.run(screen, WANDER);
        assertEquals(1, result.claimed());
        assertEquals(1, result.currencyClaimed());
        assertEquals(1, screen.taps.size(), screen.taps.toString());
        assertTrue(screen.taps.get(0).startsWith("582,74"), screen.taps.get(0));
        assertEquals(List.of(TpDailyTaskEnum.EVENT_POLAR_TERROR, TpDailyTaskEnum.ALLIANCE_TECH),
                EventMissionSweep.tasksOf(result));
        assertEquals(1, result.purchase().size());
        assertFalse(result.stoppedEarly());
    }

    @Test
    void aChiefGearRowIsNotClaimed() {
        FakeScreen screen = new FakeScreen(false, (after, y) -> after ? wanderTitle(true, y)
                : y < 800 ? "Upgrade Chief Gear 2 times" : wanderTitle(false, y));
        Result result = EventMissionSweep.run(screen, WANDER);
        assertEquals(0, result.claimed());
        assertTrue(screen.taps.isEmpty(), screen.taps.toString());
        assertEquals(1, result.skipped().size());
        assertTrue(screen.saved.contains("claim-refused-excluded"), screen.saved.toString());
    }

    @Test
    void anUnreadableTitleIsNotClaimed() {
        FakeScreen screen = new FakeScreen(false, (after, y) -> y < 800 ? null : wanderTitle(after, y));
        Result result = EventMissionSweep.run(screen, WANDER);
        assertEquals(0, result.claimed());
        assertTrue(screen.taps.isEmpty());
        assertTrue(screen.saved.contains("claim-refused-unreadable"));
    }

    @Test
    void anUnexpectedScreenAfterAClaimStopsTheSweepAndKeepsTheFrame() {
        FakeScreen screen = new FakeScreen(true, EventMissionSweepFrameTest::wanderTitle);
        Result result = EventMissionSweep.run(screen, WANDER);
        assertTrue(result.stoppedEarly());
        assertEquals(1, screen.taps.size());
        assertTrue(screen.saved.contains("after-claim-unexpected"));
    }

    private BufferedImage image(String name) throws IOException {
        return ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(DIR + name), name));
    }

    private RawImageData raw(String name) throws IOException {
        BufferedImage image = image(name);
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
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
    }
}
