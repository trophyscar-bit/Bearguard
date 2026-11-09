package dev.frostguard.engine.nav;

import java.awt.image.BufferedImage;

import dev.frostguard.api.domain.AreaData;

/**
 * Reads the checkbox at the foot of the Daily tab that hides completed rows. A tick is only reported
 * from a frame that also shows the Daily tab selected and the empty box around the tick, because the
 * caller taps on that answer.
 */
public final class DailyHideCompletedCheckbox {

    public enum State { TICKED, UNTICKED, UNREADABLE }

    // Saved ticked frames measure 293-298 green pixels. A row's green Claim button is solid colour
    // and measures over 1,000 in the same box, so the upper bound is what rejects it.
    private static final int MIN_TICK_GREEN = 240;
    private static final int MAX_TICK_GREEN = 360;
    private static final int MAX_UNTICKED_GREEN = 5;
    private static final int GREEN_MIN_CHANNEL = 130;
    private static final int GREEN_MIN_MARGIN = 40;

    // The tick runs from the left edge to the top right, so these two corners stay empty.
    private static final AreaData EMPTY_CORNER_TOP_LEFT = AreaData.of(391, 889, 396, 894);
    private static final AreaData EMPTY_CORNER_BOTTOM_RIGHT = AreaData.of(416, 916, 421, 921);
    private static final AreaData BOX_INTERIOR_SAMPLE = AreaData.of(398, 890, 411, 892);
    private static final AreaData BOX_LEFT_EDGE = AreaData.of(386, 896, 386, 910);
    private static final AreaData BOX_RIGHT_EDGE = AreaData.of(424, 896, 425, 910);
    private static final int MAX_CORNER_CHANNEL = 100;
    private static final double MIN_EDGE_CONTRAST = 15.0;

    private DailyHideCompletedCheckbox() {}

    public static State read(BufferedImage frame) {
        if (frame == null || frame.getWidth() < 720 || frame.getHeight() < 1280
                || SidebarFrameClassifier.selectedSection(frame).orElse(null) != SidebarSection.DAILY
                || !boxVisible(frame)) {
            return State.UNREADABLE;
        }
        int green = greenPixels(frame, CommonGameAreas.DAILY_HIDE_COMPLETED_BOX);
        if (green <= MAX_UNTICKED_GREEN) {
            return State.UNTICKED;
        }
        return green >= MIN_TICK_GREEN && green <= MAX_TICK_GREEN ? State.TICKED : State.UNREADABLE;
    }

    private static boolean boxVisible(BufferedImage frame) {
        double interior = meanGreen(frame, BOX_INTERIOR_SAMPLE);
        return cornerIsDark(frame, EMPTY_CORNER_TOP_LEFT) && cornerIsDark(frame, EMPTY_CORNER_BOTTOM_RIGHT)
                && meanGreen(frame, BOX_LEFT_EDGE) - interior >= MIN_EDGE_CONTRAST
                && meanGreen(frame, BOX_RIGHT_EDGE) - interior >= MIN_EDGE_CONTRAST;
    }

    private static boolean cornerIsDark(BufferedImage frame, AreaData area) {
        for (int y = area.topLeft().getY(); y <= area.bottomRight().getY(); y++) {
            for (int x = area.topLeft().getX(); x <= area.bottomRight().getX(); x++) {
                int rgb = frame.getRGB(x, y);
                if (((rgb >> 16) & 0xff) > MAX_CORNER_CHANNEL || ((rgb >> 8) & 0xff) > MAX_CORNER_CHANNEL) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double meanGreen(BufferedImage frame, AreaData area) {
        long total = 0;
        int samples = 0;
        for (int y = area.topLeft().getY(); y <= area.bottomRight().getY(); y++) {
            for (int x = area.topLeft().getX(); x <= area.bottomRight().getX(); x++) {
                total += (frame.getRGB(x, y) >> 8) & 0xff;
                samples++;
            }
        }
        return (double) total / samples;
    }

    private static int greenPixels(BufferedImage frame, AreaData area) {
        int count = 0;
        for (int y = area.topLeft().getY(); y <= area.bottomRight().getY(); y++) {
            for (int x = area.topLeft().getX(); x <= area.bottomRight().getX(); x++) {
                int rgb = frame.getRGB(x, y);
                int red = (rgb >> 16) & 0xff;
                int green = (rgb >> 8) & 0xff;
                int blue = rgb & 0xff;
                if (green >= GREEN_MIN_CHANNEL && green - red >= GREEN_MIN_MARGIN
                        && green - blue >= GREEN_MIN_MARGIN) {
                    count++;
                }
            }
        }
        return count;
    }
}
