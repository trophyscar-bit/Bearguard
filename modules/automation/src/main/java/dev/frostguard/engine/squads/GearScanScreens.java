package dev.frostguard.engine.squads;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.engine.squads.ForgeDialogReader.ForgeDialog;
import dev.frostguard.engine.squads.ForgeDialogReader.GearDetails;
import dev.frostguard.engine.squads.GearScan.Abort;
import dev.frostguard.engine.squads.GearScan.HeroPage;

/**
 * Walks the Heroes grid for {@link GearScan}. Every tap follows a read of the screen it belongs to,
 * since the same coordinates mean different controls on other screens: the Overall Stats button on
 * the Stats tab is Quick Equip on the Gear tab. Only the coordinates below are ever tapped.
 */
public final class GearScanScreens implements GearScan.Screens {

    // Coordinates measured on 720x1280 frames.
    public static final PointData GEAR_TAB = new PointData(586, 1240);
    public static final PointData MASTERY_FORGING_BUTTON = new PointData(208, 815);
    public static final Map<String, PointData> GEAR_TILES = Map.of(
            "Goggles", new PointData(100, 322), "Gauntlets", new PointData(619, 322),
            "Belt", new PointData(100, 540), "Boots", new PointData(619, 540));
    private static final PointData STATS_TAB = new PointData(133, 1237);
    private static final PointData OVERALL_STATS_BUTTON = new PointData(617, 1110);
    private static final PointData POPUP_DISMISS = new PointData(120, 300);

    // A grid scrolled off its top would hide cards from the walk.
    static final int GRID_FIRST_ROW_TOP = 110;
    static final int GRID_TOP_TOLERANCE = 4;
    // Two cards are the same hero above this; matches between different heroes stayed under 0.45.
    private static final double SAME_HERO = 0.90;
    private static final int READ_ATTEMPTS = 4;
    private static final int READ_PAUSE_MS = 700;

    /** The emulator, as far as the scan is allowed to touch it. */
    public interface Device {
        RawImageData capture();

        void tap(PointData point, int radius);

        void back();

        void pause(int millis);

        /** The frame a read gave up on, just before the abort it causes. Must not touch the screen. */
        default void readFailed(RawImageData frame, String failure) {
        }
    }

    private final Device device;
    private final List<HeroCards.Portrait> portraits = new ArrayList<>();

    public GearScanScreens(Device device) {
        this.device = device;
    }

    @Override
    public int openGrid() {
        RawImageData grid = await(HeroCards::isHeroesGrid, "the Heroes grid is not on screen");
        List<HeroCards.Card> cards = HeroCards.gridCards(grid);
        if (cards.size() < GearScan.HERO_COUNT) {
            throw new Abort("the Heroes grid shows only " + cards.size() + " full cards");
        }
        if (Math.abs(cards.getFirst().top() - GRID_FIRST_ROW_TOP) > GRID_TOP_TOLERANCE) {
            throw new Abort("the Heroes grid is not scrolled to the top, first row at y=" + cards.getFirst().top());
        }
        portraits.clear();
        // An unowned card opens the recruit screen, so it is never part of the walk.
        for (HeroCards.Card card : cards.subList(0, Math.min(cards.size(), GearScan.CARD_LIMIT))) {
            if (HeroCards.isOwned(grid, card)) portraits.add(HeroCards.portrait(grid, card));
        }
        if (portraits.size() < GearScan.HERO_COUNT) {
            throw new Abort("the Heroes grid shows only " + portraits.size() + " owned heroes");
        }
        return portraits.size();
    }

    @Override
    public String openHero(int index) {
        String label = "hero card " + (index + 1);
        RawImageData grid = await(HeroCards::isHeroesGrid, "the Heroes grid is not on screen before " + label);
        HeroCards.Card card = HeroCards.gridCards(grid).stream()
                .filter(c -> HeroCards.similarity(HeroCards.portrait(grid, c), portraits.get(index)) >= SAME_HERO)
                .findFirst().orElseThrow(() -> new Abort(label + " is no longer on the Heroes grid"));
        device.tap(new PointData(card.centreX(), card.centreY()), 20);
        device.pause(2000);

        RawImageData page = await(f -> HeroScreenReader.isHeroPage(f) || HeroScreenReader.isGearTab(f),
                label + " did not open a hero page");
        if (!HeroScreenReader.isStatsTab(page)) {
            device.tap(STATS_TAB, 15);
            device.pause(900);
            await(HeroScreenReader::isStatsTab, "the Stats tab did not open for " + label);
        }
        return tryRead(HeroScreenReader::readName);
    }

    @Override
    public HeroPage readHero(String name) {
        RawImageData page = await(HeroScreenReader::isStatsTab, "the Stats tab was lost for " + name);
        Integer level = HeroScreenReader.readDetail(page).level();
        HeroScreenReader.HeroXp xp = HeroScreenReader.readHeroXp(page);

        await(HeroScreenReader::isStatsTab, "the Stats tab was lost for " + name);
        device.tap(OVERALL_STATS_BUTTON, 10);
        device.pause(900);
        String troopClass = awaitRead(GearScanScreens::troopClass, "the Overall Stats pop-up did not read for " + name);
        device.tap(POPUP_DISMISS, 10);
        device.pause(700);
        await(HeroScreenReader::isStatsTab, "the Overall Stats pop-up did not close for " + name);
        return new HeroPage(name, troopClass, level, xp);
    }

    @Override
    public List<HeroGear> openGearTab(String hero) {
        await(HeroScreenReader::isStatsTab, "the Stats tab was lost for " + hero);
        device.tap(GEAR_TAB, 15);
        device.pause(1200);
        RawImageData tab = await(HeroScreenReader::isGearTab, "the Gear tab did not open for " + hero);
        String shown = awaitRead(HeroScreenReader::readName, "the Gear tab name did not read for " + hero);
        if (!hero.equals(shown)) throw new Abort("the Gear tab shows " + shown + ", expected " + hero);
        return HeroScreenReader.readGear(tab);
    }

    @Override
    public GearDetails openDetails(String slot) {
        await(HeroScreenReader::isGearTab, "the Gear tab is not on screen before " + slot);
        device.tap(GEAR_TILES.get(slot), 12);
        device.pause(1600);
        return awaitRead(ForgeDialogReader::readGearDetails, "Gear Details did not read for " + slot);
    }

    @Override
    public ForgeDialog openForge() {
        await(ForgeDialogReader::isGearDetails, "Gear Details is not on screen before Mastery Forging");
        device.tap(MASTERY_FORGING_BUTTON, 10);
        device.pause(1600);
        return awaitRead(ForgeDialogReader::read, "the Mastery Forging dialog did not read");
    }

    @Override
    public void closeForge() {
        device.back();
        device.pause(1500);
        await(ForgeDialogReader::isGearDetails, "Back from Mastery Forging did not return to Gear Details");
    }

    @Override
    public void closeDetails() {
        device.back();
        device.pause(1500);
        await(HeroScreenReader::isGearTab, "Back from Gear Details did not return to the Gear tab");
    }

    @Override
    public void closeHero() {
        device.back();
        device.pause(1500);
        await(HeroCards::isHeroesGrid, "Back from the hero page did not return to the Heroes grid");
    }

    private static String troopClass(RawImageData frame) {
        HeroScreenReader.Stats stats = HeroScreenReader.readStats(frame);
        return stats == null ? null : stats.troopClass();
    }

    private RawImageData await(Predicate<RawImageData> expected, String failure) {
        return awaitFrame(frame -> expected.test(frame) ? frame : null, failure);
    }

    private <T> T awaitRead(Function<RawImageData, T> read, String failure) {
        return awaitFrame(read, failure);
    }

    private <T> T awaitFrame(Function<RawImageData, T> read, String failure) {
        RawImageData frame = null;
        for (int attempt = 0; attempt < READ_ATTEMPTS; attempt++) {
            frame = device.capture();
            T value = read.apply(frame);
            if (value != null) return value;
            device.pause(READ_PAUSE_MS);
        }
        device.readFailed(frame, failure);
        throw new Abort(failure);
    }

    private <T> T tryRead(Function<RawImageData, T> read) {
        for (int attempt = 0; attempt < READ_ATTEMPTS; attempt++) {
            T value = read.apply(device.capture());
            if (value != null) return value;
            device.pause(READ_PAUSE_MS);
        }
        return null;
    }
}
