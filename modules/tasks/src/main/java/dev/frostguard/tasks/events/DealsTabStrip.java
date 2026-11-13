package dev.frostguard.tasks.events;

import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.function.Supplier;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.schedule.DelayedTask;

/**
 * The walk along the tab strip of the Deals (or Events) panel that finds one tab by its picture. The strip
 * opens wherever it was last left, so the walk looks where it opened, then swipes toward the right end and
 * back toward the start. A direction ends when two swipes in a row leave the strip still, and neither
 * direction takes more than {@link #MAX_SWIPES} swipes.
 */
final class DealsTabStrip {

    static final int MAX_SWIPES = 20;
    static final int STILL_SWIPES_FOR_END = 2;
    static final int SWIPE_MS = 600;
    static final int SETTLE_MS = 1200;
    private static final double STILL_MEAN_DIFF = 3.0;

    /** What the walk needs from the routine driving it. */
    interface Walker {
        /** One look for the tab in the strip as it is now. */
        ImageSearchResultData locate();

        /** Drags the strip one step toward its right end (or back toward its start) and waits for it to settle. */
        void swipe(boolean towardEnd);

        /** The current screen, or empty when no capture came back. */
        Optional<BufferedImage> frame();
    }

    private DealsTabStrip() {
    }

    /** The tab, or a not-found result when a full walk both ways did not show it or a capture failed. */
    static ImageSearchResultData find(Walker walker) {
        ImageSearchResultData tab = walker.locate();
        if (tab.isFound()) {
            return tab;
        }
        for (boolean towardEnd : new boolean[] {true, false}) {
            int still = 0;
            for (int swipe = 0; swipe < MAX_SWIPES && still < STILL_SWIPES_FOR_END; swipe++) {
                Optional<BufferedImage> before = walker.frame();
                walker.swipe(towardEnd);
                tab = walker.locate();
                if (tab.isFound()) {
                    return tab;
                }
                Optional<BufferedImage> after = walker.frame();
                if (before.isEmpty() || after.isEmpty()) {
                    return tab;
                }
                still = stripMoved(before.get(), after.get()) ? 0 : still + 1;
            }
        }
        return tab;
    }

    /** A walker built from a routine's own template search, screen capture and settle wait. */
    static Walker walker(DelayedTask task, Supplier<ImageSearchResultData> locate,
            Supplier<Optional<BufferedImage>> frame, Runnable settle) {
        return new Walker() {
            @Override
            public ImageSearchResultData locate() {
                return locate.get();
            }

            @Override
            public void swipe(boolean towardEnd) {
                if (towardEnd) {
                    task.swipe(CommonGameAreas.DEALS_TAB_STRIP_SWIPE_FROM, CommonGameAreas.DEALS_TAB_STRIP_SWIPE_TO,
                            SWIPE_MS);
                } else {
                    task.swipe(CommonGameAreas.DEALS_TAB_STRIP_SWIPE_TO, CommonGameAreas.DEALS_TAB_STRIP_SWIPE_FROM,
                            SWIPE_MS);
                }
                settle.run();
            }

            @Override
            public Optional<BufferedImage> frame() {
                return frame.get();
            }
        };
    }

    static boolean stripMoved(BufferedImage before, BufferedImage after) {
        AreaData strip = CommonGameAreas.DEALS_TAB_STRIP;
        long total = 0;
        int samples = 0;
        for (int y = strip.topLeft().getY(); y <= strip.bottomRight().getY(); y += 2) {
            for (int x = strip.topLeft().getX(); x <= strip.bottomRight().getX(); x += 2) {
                int a = before.getRGB(x, y);
                int b = after.getRGB(x, y);
                total += Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                        + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                        + Math.abs((a & 0xFF) - (b & 0xFF));
                samples += 3;
            }
        }
        return samples > 0 && (double) total / samples >= STILL_MEAN_DIFF;
    }
}
