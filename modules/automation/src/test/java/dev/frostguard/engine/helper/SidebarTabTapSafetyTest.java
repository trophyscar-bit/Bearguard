package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.emulator.EmulatorInstance;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.RecallConfirmationGuard;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.engine.nav.WorldTilePopupGuard;

/**
 * A sidebar tab tap must never be sent on a stale reading.
 *
 * <p>On the World screen the Marching panel sits under the tab strip, so a tab tap sent after the
 * sidebar has closed selects one of the owner's running marches and opens that march's menu, which
 * carries Recall. Three saved frames from 2026-11-11 (T045932, T070403, T121940) caught the bot on
 * an open "Recall Troops?" confirmation, each one straight after
 * "Sidebar section selection failed: requested=WILDERNESS observed=closed/unknown".
 */
class SidebarTabTapSafetyTest {

    @Test
    void refusesToTapATabAfterTheSidebarHasClosed() throws Exception {
        Rig rig = new Rig();
        // The trigger loop saw DAILY; by the time the tab would be tapped the panel is gone.
        rig.sectionNow = Optional.empty();
        rig.put("screenStates", (SidebarNavigator.ScreenStateReader) () ->
                new SidebarNavigator.ScreenState(Optional.of(SidebarSection.DAILY), false));

        assertFalse(rig.nav.openSection(SidebarSection.WILDERNESS));
        assertTrue(rig.tapsInside(CommonGameAreas.sidebarTab(SidebarSection.WILDERNESS)).isEmpty(),
                "A tab tap over a closed sidebar lands on the Marching panel and exposes Recall");
        assertEquals(0, rig.taps.size(), "Nothing at all may be tapped on a stale reading");
    }

    @Test
    void stillTapsTheTabWhenThePanelIsVerifiedInTheCurrentFrame() throws Exception {
        Rig rig = new Rig();
        rig.sectionNow = Optional.of(SidebarSection.DAILY);
        rig.put("screenStates", (SidebarNavigator.ScreenStateReader) () ->
                new SidebarNavigator.ScreenState(Optional.of(SidebarSection.DAILY), false));
        // The tab tap switches the panel, as it does on a real open sidebar.
        rig.sectionAfterTap = Optional.of(SidebarSection.WILDERNESS);

        assertTrue(rig.nav.openSection(SidebarSection.WILDERNESS));
        assertEquals(1, rig.tapsInside(CommonGameAreas.sidebarTab(SidebarSection.WILDERNESS)).size(),
                "A verified open panel must still be switched with one tab tap");
    }

    @Test
    void doesNotTapATabAtAllWhenTheSidebarAlreadyShowsTheWantedSection() throws Exception {
        Rig rig = new Rig();
        rig.sectionNow = Optional.of(SidebarSection.WILDERNESS);
        rig.put("screenStates", (SidebarNavigator.ScreenStateReader) () ->
                new SidebarNavigator.ScreenState(Optional.of(SidebarSection.WILDERNESS), false));

        assertTrue(rig.nav.openSection(SidebarSection.WILDERNESS));
        assertEquals(0, rig.taps.size());
    }

    @Test
    void tapsNothingAtAllWhileARecallConfirmationIsOnScreen() throws Exception {
        Rig rig = new Rig();
        rig.sectionNow = Optional.of(SidebarSection.DAILY);
        rig.put("screenStates", (SidebarNavigator.ScreenStateReader) () ->
                new SidebarNavigator.ScreenState(Optional.of(SidebarSection.DAILY), false));
        StuckRecallGuard guard = new StuckRecallGuard();
        rig.put("recallGuard", guard);

        assertFalse(rig.nav.openSection(SidebarSection.WILDERNESS));
        assertEquals(0, rig.taps.size(),
                "Confirm sits one stray tap away; nothing may be tapped over that dialog");
        assertTrue(guard.backPresses > 0, "The dialog must be backed out of, never confirmed");
    }

    @Test
    void backsOutOfATilePopupBeforeTheTriggerTap() throws Exception {
        // 2026-11-13: an own-gather tile card (Details / Recall) over World while the World anchor
        // still matched, so both trigger taps went into the card.
        Rig rig = new Rig();
        rig.worldRootWhileClosed();
        TilePopup popup = new TilePopup(rig, true);
        rig.put("tilePopupGuard", popup);
        rig.onTap = point -> {
            if (rig.sectionNow.isEmpty()) {
                rig.sectionNow = Optional.of(SidebarSection.DAILY);
            } else {
                rig.sectionNow = Optional.of(SidebarSection.WILDERNESS);
            }
        };

        assertTrue(rig.nav.openSection(SidebarSection.WILDERNESS));
        assertEquals(1, popup.backPresses, "One back closes the card");
        assertEquals(0, popup.tapsWhileUp, "Nothing may be tapped while the card is over the map");
        assertEquals(1, rig.tapsInside(CommonGameAreas.LEFT_MENU_TRIGGER).size());
    }

    @Test
    void tapsNothingWhenTheTilePopupWillNotClose() throws Exception {
        Rig rig = new Rig();
        rig.worldRootWhileClosed();
        TilePopup popup = new TilePopup(rig, true);
        popup.closesOnBack = false;
        rig.put("tilePopupGuard", popup);

        assertFalse(rig.nav.openSection(SidebarSection.WILDERNESS));
        assertEquals(0, rig.taps.size(), "Attack and Recall sit inside the card; tap nothing");
        assertEquals(2, popup.backPresses,
                "One back per attempt, and only the one retry: back is never pressed blind");
    }

    @Test
    void retriesOnceWhenTheAttemptLeavesATilePopupBehind() throws Exception {
        // 2026-11-13 17:38 and 18:24: the panel opened on DAILY, the WILDERNESS tab tap landed on
        // the map as the panel went, and a player-city / beast card was left over World.
        Rig rig = new Rig();
        rig.worldRootWhileClosed();
        TilePopup popup = new TilePopup(rig, false);
        rig.put("tilePopupGuard", popup);
        int[] tabTaps = {0};
        rig.onTap = point -> {
            if (rig.sectionNow.isEmpty()) {
                rig.sectionNow = Optional.of(SidebarSection.DAILY);
            } else if (tabTaps[0]++ == 0) {
                rig.sectionNow = Optional.empty();
                popup.up = true;
            } else {
                rig.sectionNow = Optional.of(SidebarSection.WILDERNESS);
            }
        };

        assertTrue(rig.nav.openSection(SidebarSection.WILDERNESS));
        assertEquals(1, popup.backPresses);
        assertEquals(0, popup.tapsWhileUp, "The retry must start by closing the card");
        assertEquals(2, rig.tapsInside(CommonGameAreas.LEFT_MENU_TRIGGER).size());
    }

    @Test
    void doesNotRetryAFailureThatLeftNoTilePopup() throws Exception {
        Rig rig = new Rig();
        rig.worldRootWhileClosed();
        TilePopup popup = new TilePopup(rig, false);
        rig.put("tilePopupGuard", popup);

        assertFalse(rig.nav.openSection(SidebarSection.WILDERNESS));
        assertEquals(0, popup.backPresses, "Back on a bare World map asks to quit the game");
        assertEquals(SidebarNavigator.MAX_TRIGGER_TAPS,
                rig.tapsInside(CommonGameAreas.LEFT_MENU_TRIGGER).size(),
                "Without a popup the attempt is not repeated");
    }

    @Test
    void neverTapsTheTriggerWhileAMarchRowLiesOverIt() throws Exception {
        // 2026-11-14 01:45: five gather marches listed; every trigger tap selected the fifth march
        // and opened its tile card with Recall, before and after back.
        Rig rig = new Rig();
        rig.worldRootWhileClosed();
        rig.marchRowOverTrigger = true;
        HomeRoute home = new HomeRoute(rig);
        rig.put("homeFallback", new SidebarHomeFallback(home));
        rig.onTap = point -> {
            if (rig.sectionNow.isEmpty()) {
                rig.sectionNow = Optional.of(SidebarSection.DAILY);
            }
        };

        assertTrue(rig.nav.openSection(SidebarSection.DAILY));
        assertEquals(0, home.triggerTapsOnWorld, "A trigger tap here selects the march");
        assertTrue(home.onHome, "The panel is opened from Home, where there is no Marching list");
        assertEquals(1, rig.tapsInside(CommonGameAreas.LEFT_MENU_TRIGGER).size(),
                "Exactly one trigger tap, sent on Home");
    }

    @Test
    void looksAgainForTheMarchRowOnceATileCardIsClosed() throws Exception {
        // 2026-11-13 22:38: a tile card hid rows three to five, so the first reading saw no fifth
        // row. Closing the card uncovers it.
        Rig rig = new Rig();
        TilePopup popup = new TilePopup(rig, true);
        rig.put("tilePopupGuard", popup);
        rig.put("screenStates", (SidebarNavigator.ScreenStateReader) () ->
                new SidebarNavigator.ScreenState(rig.sectionNow, rig.sectionNow.isEmpty(),
                        rig.sectionNow.isEmpty() && !popup.up));

        assertFalse(rig.nav.openSection(SidebarSection.WILDERNESS));
        assertEquals(1, popup.backPresses);
        assertEquals(0, rig.taps.size(), "The uncovered fifth row lies over the trigger");
    }

    @Test
    void stopsBeforeTheSecondTriggerTapWhenAMarchRowHasAppeared() throws Exception {
        Rig rig = new Rig();
        rig.worldRootWhileClosed();
        // A march sets out while the first trigger tap goes unanswered.
        rig.onTap = point -> rig.marchRowOverTrigger = true;

        assertFalse(rig.nav.openSection(SidebarSection.DAILY));
        assertEquals(1, rig.tapsInside(CommonGameAreas.LEFT_MENU_TRIGGER).size());
    }

    /** Home route of the fallback: no Marching list there, and no trigger tap counted on World. */
    private static final class HomeRoute implements SidebarHomeFallback.Screens {
        private final Rig rig;
        private boolean onHome;
        private int triggerTapsOnWorld;

        private HomeRoute(Rig rig) {
            this.rig = rig;
            rig.tapWatch = point -> {
                AreaData trigger = CommonGameAreas.LEFT_MENU_TRIGGER;
                boolean triggerTap = point.getX() >= trigger.topLeft().getX()
                        && point.getX() <= trigger.bottomRight().getX()
                        && point.getY() >= trigger.topLeft().getY()
                        && point.getY() <= trigger.bottomRight().getY();
                if (triggerTap && !onHome) {
                    triggerTapsOnWorld++;
                }
            };
        }

        @Override
        public void recordFailedOpen() {
        }

        @Override
        public boolean sidebarClosedOnWorld() {
            return !onHome && rig.sectionNow.isEmpty();
        }

        @Override
        public boolean switchToHome() {
            onHome = true;
            rig.marchRowOverTrigger = false;
            return true;
        }

        @Override
        public boolean switchToWorld() {
            onHome = false;
            return true;
        }
    }

    /** A tile popup over World that back closes, recording any tap sent while it is up. */
    private static final class TilePopup implements WorldTilePopupGuard {
        private boolean up;
        private boolean closesOnBack = true;
        private int backPresses;
        private int tapsWhileUp;

        private TilePopup(Rig rig, boolean up) {
            this.up = up;
            rig.tapWatch = point -> {
                if (this.up) {
                    tapsWhileUp++;
                }
            };
        }

        @Override
        public boolean popupOnScreen() {
            return up;
        }

        @Override
        public void pressBack() {
            backPresses++;
            if (closesOnBack) {
                up = false;
            }
        }
    }

    /** A confirmation that will not go away, so the navigator has to give up rather than tap. */
    private static final class StuckRecallGuard implements RecallConfirmationGuard {
        private int backPresses;

        @Override
        public boolean dialogOnScreen() {
            return true;
        }

        @Override
        public void pressBack() {
            backPresses++;
        }
    }

    // ===================== rig =====================

    private static final class Rig {
        private final SidebarNavigator nav;
        private final List<PointData> taps = new ArrayList<>();
        private Optional<SidebarSection> sectionNow = Optional.empty();
        /** A fifth World Marching row is listed, lying over the trigger. */
        private boolean marchRowOverTrigger;
        private Optional<SidebarSection> sectionAfterTap;
        private java.util.function.Consumer<PointData> onTap = point -> {};
        private java.util.function.Consumer<PointData> tapWatch = point -> {};

        private Rig() throws Exception {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Backend backend =
                    (Backend) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(Backend.class);
            Constructor<EmulatorController> constructor = EmulatorController.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            EmulatorController emu = constructor.newInstance();
            set(emu, "backend", backend);
            AccountDescriptor account =
                    new AccountDescriptor(997L, "Synthetic Tabs", "tabs", true, 0L, 0L);
            nav = new SidebarNavigator(emu, "tabs", account);
            put("taps", new TapInteractionService(point -> {
                tapWatch.accept(point);
                taps.add(point);
                if (sectionAfterTap != null) {
                    sectionNow = sectionAfterTap;
                }
                onTap.accept(point);
            }, null, millis -> {}));
            put("transitionWaiter", (SidebarNavigator.Waiter) millis -> true);
            // No Recall confirmation unless a test says so; the guard has its own tests.
            put("recallGuard", new RecallConfirmationGuard() {
                @Override
                public boolean dialogOnScreen() {
                    return false;
                }

                @Override
                public void pressBack() {
                    throw new AssertionError("No dialog, so no back press");
                }
            });
            put("tilePopupGuard", new WorldTilePopupGuard() {
                @Override
                public boolean popupOnScreen() {
                    return false;
                }

                @Override
                public void pressBack() {
                    throw new AssertionError("No tile popup, so no back press");
                }
            });
            // Keep the Home fallback out of the way: these tests are about what openSection does
            // on World, not about the recovery route, which has its own tests.
            put("homeFallback", new SidebarHomeFallback(new SidebarHomeFallback.Screens() {
                @Override
                public void recordFailedOpen() {
                }

                @Override
                public boolean sidebarClosedOnWorld() {
                    return false;
                }

                @Override
                public boolean switchToHome() {
                    return false;
                }

                @Override
                public boolean switchToWorld() {
                    return false;
                }
            }));
            // Read the section from the rig rather than from a frame, so each test states exactly
            // what the screen shows at the moment the navigator looks.
            put("sections", (SidebarNavigator.SectionReader) () -> sectionNow);
        }

        /** World with the sidebar closed reads as a root screen; an open panel is not. */
        private void worldRootWhileClosed() throws Exception {
            put("screenStates", (SidebarNavigator.ScreenStateReader) () ->
                    new SidebarNavigator.ScreenState(sectionNow, sectionNow.isEmpty(),
                            sectionNow.isEmpty() && marchRowOverTrigger));
        }

        private void put(String name, Object value) throws Exception {
            set(nav, name, value);
        }

        private List<PointData> tapsInside(AreaData area) {
            List<PointData> inside = new ArrayList<>();
            for (PointData point : taps) {
                if (point.getX() >= area.topLeft().getX() && point.getX() <= area.bottomRight().getX()
                        && point.getY() >= area.topLeft().getY()
                        && point.getY() <= area.bottomRight().getY()) {
                    inside.add(point);
                }
            }
            return inside;
        }

        private static void set(Object target, String name, Object value) throws Exception {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        }
    }

    private static class Backend extends EmulatorInstance {
        private Backend() {
            super("unused-tab-safety");
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
            return RawImageData.capture(new byte[720 * 1280 * 4], 720, 1280, 32);
        }
    }
}
