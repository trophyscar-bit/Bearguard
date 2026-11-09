package dev.frostguard.engine.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.engine.nav.DailyHideCompletedCheckbox.State;

/** Evidence level: saved real-frame verification, plus edits of those frames for the refusals. */
class DailyHideCompletedCheckboxFrameTest {

    private static final String HIDE_ON = "/navigation/sidebar-dynamic-20260821/daily-hide-on.png";
    private static final String HIDE_OFF = "/navigation/sidebar-dynamic-20260821/daily-hide-off.png";

    @Test
    void readsATickInEverySavedFrameThatHasOne() throws IOException {
        assertEquals(State.TICKED, DailyHideCompletedCheckbox.read(frame(HIDE_ON)));
        assertEquals(State.TICKED, DailyHideCompletedCheckbox.read(
                frame("/navigation/sidebar-update-20260821/daily-dynamic-intel.png")));
        assertEquals(State.TICKED, DailyHideCompletedCheckbox.read(
                frame("/navigation/sidebar-update-20260912/daily-crystal-laboratory.png")));
    }

    @Test
    void readsAnEmptyBoxAsUnticked() throws IOException {
        assertEquals(State.UNTICKED, DailyHideCompletedCheckbox.read(frame(HIDE_OFF)));
    }

    @Test
    void framesWithoutTheFooterAreUnreadableRatherThanUnticked() throws IOException {
        assertEquals(State.UNREADABLE, DailyHideCompletedCheckbox.read(
                frame("/navigation/sidebar-update-20260817/daily-bottom.png")));
        assertEquals(State.UNREADABLE, DailyHideCompletedCheckbox.read(
                frame("/navigation/sidebar-dynamic-20260821/daily-top.png")));
    }

    @Test
    void anotherTabIsUnreadableEvenWithATickWhereTheBoxWouldBe() throws IOException {
        BufferedImage frame = frame(HIDE_ON);
        fill(frame, CommonGameAreas.sidebarTabSample(SidebarSection.DAILY), 0x202020);

        assertEquals(State.UNREADABLE, DailyHideCompletedCheckbox.read(frame));
        assertEquals(State.UNREADABLE, DailyHideCompletedCheckbox.read(
                frame("/navigation/sidebar-update-20260817/city.png")));
    }

    @Test
    void aGreenClaimButtonWhereTheBoxWouldBeIsNotATick() throws IOException {
        BufferedImage frame = frame(HIDE_ON);
        AreaData box = CommonGameAreas.DAILY_HIDE_COMPLETED_BOX;
        int claimButtonTop = 818;
        for (int y = 0; y <= box.bottomRight().getY() - box.topLeft().getY(); y++) {
            for (int x = box.topLeft().getX(); x <= box.bottomRight().getX(); x++) {
                frame.setRGB(x, box.topLeft().getY() + y, frame.getRGB(x, claimButtonTop + y));
            }
        }

        assertEquals(State.UNREADABLE, DailyHideCompletedCheckbox.read(frame));
    }

    @Test
    void theTapStaysInsideTheBox() {
        AreaData box = CommonGameAreas.DAILY_HIDE_COMPLETED_BOX;
        AreaData tap = CommonGameAreas.DAILY_HIDE_COMPLETED_TAP;
        assertEquals(true, tap.topLeft().getX() > box.topLeft().getX()
                && tap.topLeft().getY() > box.topLeft().getY()
                && tap.bottomRight().getX() < box.bottomRight().getX()
                && tap.bottomRight().getY() < box.bottomRight().getY());
    }

    private static void fill(BufferedImage frame, AreaData area, int rgb) {
        for (int y = area.topLeft().getY(); y <= area.bottomRight().getY(); y++) {
            for (int x = area.topLeft().getX(); x <= area.bottomRight().getX(); x++) {
                frame.setRGB(x, y, rgb);
            }
        }
    }

    private static BufferedImage frame(String name) throws IOException {
        BufferedImage source = ImageIO.read(Objects.requireNonNull(
                DailyHideCompletedCheckboxFrameTest.class.getResourceAsStream(name), name));
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        copy.getGraphics().drawImage(source, 0, 0, null);
        // The fixtures are blacked out to the right of the panel, which removes its close control.
        fill(copy, CommonGameAreas.LEFT_MENU_CLOSE, 0xffffff);
        return copy;
    }
}
