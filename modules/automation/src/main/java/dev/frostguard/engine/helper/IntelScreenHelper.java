package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.error.HomeNotFoundException;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.logging.ProfileContextLogger;
import dev.frostguard.vision.ocr.OcrException;

import java.io.IOException;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;

// Verifies the Intel panel is on-screen; navigates there when it is not.
public class IntelScreenHelper {

    private static final int MAX_NAV_PASSES = 3;
    private static final int LIGHTHOUSE_ROW_X = 46;
    private static final int LIGHTHOUSE_ROW_Y = 649;

    private final EmulatorController emu;
    private final String dev;
    private final TapInteractionService taps;
    private final TemplateSearchHelper tpl;
    private final NavigationHelper nav;
    private final ProfileContextLogger log;

    public IntelScreenHelper(EmulatorController emuManager, String emulatorNumber,
                             TemplateSearchHelper templateSearchHelper,
                             NavigationHelper navigationHelper, AccountDescriptor profile) {
        this.emu = emuManager;
        this.dev = emulatorNumber;
        this.taps = TapInteractionService.forController(emuManager, emulatorNumber);
        this.tpl = templateSearchHelper;
        this.nav = navigationHelper;
        this.log = new ProfileContextLogger(IntelScreenHelper.class, profile);
    }

    public void ensureOnIntelScreen() {
        ensureOnIntelScreenAndReadGain();
    }

    /**
     * Opens Intel through the Daily sidebar and returns its advertised mission count when readable.
     * The sidebar value is captured before entering Intel, because the marker map alone is not a
     * reliable availability signal across Furnace eras.
     */
    public OptionalInt ensureOnIntelScreenAndReadGain() {
        pause(500);
        log.info("Checking Intel screen");
        if (isIntelScreenActive()) {
            log.info("Already on Intel");
            return OptionalInt.empty();
        }

        nav.ensureCorrectScreenLocation(LaunchPoint.HOME);


        log.info("Not on Intel - checking for the Lighthouse Intel bubble in City");
        if (reachIntelScreen()) {
            log.info("Intel reached directly from the City Lighthouse bubble");
            return OptionalInt.empty();
        }

        log.info("Lighthouse Intel bubble not available in City - routing through Daily sidebar");
        return enterIntelFromOpenSidebarAndReadGain();
    }

    /** Continues Intel navigation while the sidebar is already open after a March Queue scan. */
    public OptionalInt enterIntelFromOpenSidebarAndReadGain() {
        if (!nav.openSidebarSection(SidebarSection.DAILY)) {
            throw new HomeNotFoundException("Failed to open Daily sidebar for Intel navigation");
        }

        log.info("Scrolling Daily sidebar to its bottom position");
        emu.swipeScreen(dev, CommonGameAreas.SIDEBAR_SCROLL_FROM,
                CommonGameAreas.SIDEBAR_SCROLL_TO, 400);
        pause(600);

        ImageSearchResultData lighthouseRow = lighthouseRowAtBottom();

        OptionalInt advertisedGain = readAdvertisedGain(lighthouseRow);
        advertisedGain.ifPresent(value -> log.info("Daily sidebar reports Intel Gain: " + value));
        if (advertisedGain.isEmpty()) {
            log.warn("Daily sidebar Intel Gain was not readable; marker detection will remain the fallback");
        }

        AreaData go = SidebarNavigator.goButtonFor(lighthouseRow);
        log.info("Opening Lighthouse from Daily sidebar");
        taps.tapInside(go);
        pause(1_500);

        if (reachIntelScreen()) {
            log.info("Intel reached");
            return advertisedGain;
        }
        log.error("Intel unreachable after " + MAX_NAV_PASSES + " passes");
        throw new HomeNotFoundException("Failed to navigate to Intel screen");
    }

    /**
     * Gets onto the Intel map, whether or not the Lighthouse is advertising anything.
     *
     * <p>The bubble answers "is there NEW intel", which is a question about content. Whether the
     * map can be opened is a question about navigation. Requiring the bubble before entering
     * conflates the two, and the consequence is not a missed reward -- it is that the refresh
     * timer becomes unreadable in exactly the state that most needs it.
     *
     * <p>Observed live: a Fire Beast nothing can beat sits on the board for hours. It is already
     * known intel, so the Lighthouse shows no bubble; navigation therefore reports "unreachable"
     * and throws, the caller backs off a fixed 15 minutes, and the real "Refreshes In: 02:26:45"
     * banner -- sitting in plain view on the screen we refused to open -- is never read. That
     * repeated 163 times over two days without one success.
     *
     * <p>So the screen check comes first: tapping Go frequently lands on the map already, and when
     * it does there is nothing left to look for. The bubble is only hunted when we are demonstrably
     * not there yet, which is the one case it was ever needed for.
     *
     * <p>The two unconditional Back presses that used to run here are gone. They existed to clear a
     * tutorial overlay, but fired whether or not one was present, so on the ordinary path they
     * navigated away from whatever Go had just opened -- pressing Back on the assumption that
     * something is there is the same class of guess as reading a value without an anchor.
     */
    private boolean reachIntelScreen() {
        for (int i = 1; i <= MAX_NAV_PASSES; i++) {
            if (isIntelScreenActive()) {
                return true;
            }

            ImageSearchResultData hit = tpl.locatePattern(TemplatesEnum.LIGHTHOUSE_INTEL_BUBBLE,
                    SearchConfigConstants.DEFAULT_SINGLE);
            if (!hit.isFound()) {
                log.debug("Not on Intel and no bubble to tap, pass " + i);
                pause(300);
                continue;
            }

            log.info("Tapping Intel button");
            taps.tapInside(hit);
            pause(1000);
            if (isIntelScreenActive()) {
                return true;
            }

            log.warn("Intel bubble tap did not open the Intel map, pass " + i);
            pause(500);
        }
        return false;
    }

    static AreaData gainAreaFor(ImageSearchResultData rowIcon) {
        PointData center = rowIcon.getPoint();
        if (center == null) {
            throw new IllegalArgumentException("A located Lighthouse row icon is required");
        }
        return AreaData.of(center.getX() + 70, center.getY() - 5,
                center.getX() + 310, center.getY() + 38);
    }

    static ImageSearchResultData lighthouseRowAtBottom() {
        return ImageSearchResultData.hit(LIGHTHOUSE_ROW_X, LIGHTHOUSE_ROW_Y, 100.0, 44, 44);
    }

    static OptionalInt parseAdvertisedGain(String text) {
        if (text == null) {
            return OptionalInt.empty();
        }
        Matcher matcher = CommonOCRSettings.NUMBER_PATTERN.matcher(text.replaceAll("\\s+", ""));
        if (!matcher.matches()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }

    private OptionalInt readAdvertisedGain(ImageSearchResultData rowIcon) {
        AreaData area = gainAreaFor(rowIcon);
        try {
            return parseAdvertisedGain(emu.readText(dev, area.topLeft(), area.bottomRight(),
                    CommonOCRSettings.INTEL_GAIN_SETTINGS));
        } catch (IOException | OcrException e) {
            log.warn("Intel Gain OCR failed: " + e.getMessage());
            return OptionalInt.empty();
        }
    }

    public boolean isIntelScreenActive() {
        // Two quick probes with a short gap
        for (int i = 0; i < 2; i++) {
            if (screenMatchesIntel()) { log.debug("Intel confirmed, probe " + (i + 1)); return true; }
            if (i == 0) pause(300);
        }
        return false;
    }

    // Merged: checks both template variants and OCR fallback in one pass
    private boolean screenMatchesIntel() {
        BooleanSupplier[] checks = {
                () -> tpl.locatePattern(TemplatesEnum.INTEL_SCREEN_1, SearchConfigConstants.DEFAULT_SINGLE).isFound(),
                () -> tpl.locatePattern(TemplatesEnum.INTEL_SCREEN_2, SearchConfigConstants.DEFAULT_SINGLE).isFound(),
                this::ocrShowsIntel
        };
        for (BooleanSupplier check : checks) {
            if (check.getAsBoolean()) return true;
        }
        return false;
    }

    private boolean ocrShowsIntel() {
        try {
            String txt = emu.readText(dev, new PointData(85, 15), new PointData(171, 62));
            return txt != null && txt.toLowerCase().contains("intel");
        } catch (IOException | OcrException e) {
            log.warn("OCR check failed: " + e.getMessage());
            return false;
        }
    }

    private static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
