package dev.frostguard.engine.squads;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BinaryOperator;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.engine.squads.GearScan.Abort;
import dev.frostguard.engine.squads.GearScanScreens.Device;
import dev.frostguard.engine.squads.QuickEquipPass.GearTab;
import dev.frostguard.vision.convert.ImageConverter;

/**
 * Walks the Gear tabs for {@link QuickEquipPass}. The arrow and Quick Equip are only tapped on a
 * freshly confirmed Gear tab, and every tap goes through a check that it can't reach Unequip All.
 */
public final class QuickEquipScreens implements QuickEquipPass.Screens {

    // Coordinates measured on 720x1280 frames.
    static final PointData QUICK_EQUIP = new PointData(517, 1112);
    static final PointData NEXT_HERO = new PointData(667, 706);
    private static final int[] UNEQUIP_ALL_BOX = { 60, 1070, 345, 1155 };
    private static final int[] BUTTON_SAMPLE = { 400, 1085, 640, 1095 };
    private static final int[] BUTTON_LABEL = { 385, 1085, 650, 1140 };

    // Greyed Quick Equip measured exactly this on all 21 heroes. The lit button is GREEN, measured live on
    // 2026-11-12 on Jessie and Greg: it averages 37,183,86 over the sample. The code first assumed it would be
    // the blue of Unequip All, so a lit button read as an odd look and was never pressed.
    private static final int[] GREY = { 96, 104, 114 };
    private static final int[] GREEN = { 37, 183, 86 };
    private static final int GREY_TOLERANCE = 12;
    private static final int GREEN_TOLERANCE = 30;
    private static final double MIN_SHARE = 0.90;

    private static final int READ_ATTEMPTS = 4;
    private static final int READ_PAUSE_MS = 700;
    private static final int GEAR_READS = 3;
    private static final int MAX_REREADS = 6;
    private static final int SETTLE_ATTEMPTS = 8;
    private static final int MAX_BACKS = 3;

    private final Device device;
    private final BooleanSupplier pastStop;

    public QuickEquipScreens(Device device) {
        this(device, () -> false);
    }

    public QuickEquipScreens(Device device, BooleanSupplier pastStop) {
        this.device = device;
        this.pastStop = pastStop;
    }

    @Override
    public boolean pastStop() {
        return pastStop.getAsBoolean();
    }

    /** True only on a clear blue reading with the label "Quick Equip" in the same box. */
    static boolean isLit(RawImageData frame) {
        return share(frame, GREEN, GREEN_TOLERANCE) >= MIN_SHARE && label(frame).contains("Quick Equip");
    }

    static boolean reachesUnequipAll(PointData point, int radius) {
        return point.getX() + radius >= UNEQUIP_ALL_BOX[0] && point.getX() - radius <= UNEQUIP_ALL_BOX[2]
                && point.getY() + radius >= UNEQUIP_ALL_BOX[1] && point.getY() - radius <= UNEQUIP_ALL_BOX[3];
    }

    @Override
    public GearTab openFirstHero() {
        RawImageData grid = await(HeroCards::isHeroesGrid, "the Heroes grid is not on screen");
        List<HeroCards.Card> cards = HeroCards.gridCards(grid);
        if (cards.isEmpty()) throw new Abort("the Heroes grid shows no full cards");
        HeroCards.Card first = cards.getFirst();
        if (Math.abs(first.top() - GearScanScreens.GRID_FIRST_ROW_TOP) > GearScanScreens.GRID_TOP_TOLERANCE) {
            throw new Abort("the Heroes grid is not scrolled to the top, first row at y=" + first.top());
        }
        if (!HeroCards.isOwned(grid, first)) throw new Abort("the first grid card is not an owned hero");
        tap(new PointData(first.centreX(), first.centreY()), 20);
        device.pause(2000);

        RawImageData page = await(f -> HeroScreenReader.isHeroPage(f) || HeroScreenReader.isGearTab(f),
                "the first card did not open a hero page");
        if (!HeroScreenReader.isGearTab(page)) {
            tap(GearScanScreens.GEAR_TAB, 15);
            device.pause(1200);
        }
        GearTab tab = settle(null);
        if (tab == null) throw new Abort("the Gear tab did not open on the first hero");
        return tab;
    }

    @Override
    public GearTab nextHero(String current) {
        await(HeroScreenReader::isGearTab, "the Gear tab is not on screen before the next-hero arrow");
        tap(NEXT_HERO, 8);
        device.pause(1500);
        return settle(current);
    }

    @Override
    public List<HeroGear> readGear(String hero) {
        List<HeroGear> result = null;
        for (int read = 0; read < GEAR_READS; read++) {
            List<HeroGear> gear = settledGear(hero, HeroScreenReader::readGear);
            result = result == null ? gear : merge(result, gear, QuickEquipScreens::higher);
            device.pause(READ_PAUSE_MS);
        }
        return result;
    }

    @Override
    public List<HeroGear> rereadGear(String hero) {
        List<HeroGear> result = null;
        for (int read = 0; read < MAX_REREADS && (read < GEAR_READS || anyUnread(result)); read++) {
            List<HeroGear> gear = settledGear(hero, HeroScreenReader::readTiles);
            result = result == null ? gear : merge(result, gear, QuickEquipScreens::lower);
            device.pause(READ_PAUSE_MS);
        }
        return result;
    }

    private static boolean anyUnread(List<HeroGear> gear) {
        return gear.stream().anyMatch(piece -> piece.enhancement() == null || piece.mastery() == null);
    }

    // Two frames in a row must show this hero and the same tiles, so the arrow's animation and a one-frame misread don't count.
    private List<HeroGear> settledGear(String hero, Function<RawImageData, List<HeroGear>> reader) {
        List<HeroGear> previous = null;
        RawImageData frame = null;
        for (int attempt = 0; attempt < SETTLE_ATTEMPTS; attempt++) {
            frame = device.capture();
            if (HeroScreenReader.isGearTab(frame) && hero.equals(HeroScreenReader.readName(frame))) {
                List<HeroGear> gear = reader.apply(frame);
                if (gear.equals(previous)) return gear;
                previous = gear;
            } else {
                previous = null;
            }
            device.pause(READ_PAUSE_MS);
        }
        device.readFailed(frame, hero + "'s Gear tab did not settle");
        throw new Abort(hero + "'s Gear tab did not settle");
    }

    @Override
    public boolean press(String hero) {
        RawImageData frame = await(HeroScreenReader::isGearTab, "the Gear tab is not on screen before Quick Equip");
        if (!hero.equals(HeroScreenReader.readName(frame)) || !isLit(frame)) return false;
        tap(QUICK_EQUIP, 8);
        device.pause(1500);
        await(HeroScreenReader::isGearTab, "the Gear tab did not come back after Quick Equip on " + hero);
        return true;
    }

    @Override
    public void closeToGrid() {
        for (int back = 0; back < MAX_BACKS && !HeroCards.isHeroesGrid(device.capture()); back++) {
            device.back();
            device.pause(1500);
        }
        await(HeroCards::isHeroesGrid, "Back did not return to the Heroes grid");
    }

    private void tap(PointData point, int radius) {
        if (reachesUnequipAll(point, radius)) throw new Abort("refused a tap at " + point + " that can reach Unequip All");
        device.tap(point, radius);
    }

    // A frame still showing the previous hero is the arrow's animation, so it doesn't count as arrived.
    private GearTab settle(String previous) {
        GearTab unnamed = null;
        for (int attempt = 0; attempt < READ_ATTEMPTS; attempt++) {
            RawImageData frame = device.capture();
            if (HeroScreenReader.isGearTab(frame)) {
                String name = HeroScreenReader.readName(frame);
                if (name == null) unnamed = tab(frame, null);
                else if (!name.equals(previous)) return tab(frame, name);
            }
            device.pause(READ_PAUSE_MS);
        }
        return unnamed;
    }

    private static GearTab tab(RawImageData frame, String name) {
        boolean lit = isLit(frame);
        boolean known = lit || share(frame, GREY, GREY_TOLERANCE) >= MIN_SHARE;
        return new GearTab(name, lit, known ? null : "button averages " + mean(frame) + ", label '" + label(frame) + "'");
    }

    private static List<HeroGear> merge(List<HeroGear> a, List<HeroGear> b, BinaryOperator<Integer> pick) {
        List<HeroGear> merged = new ArrayList<>();
        for (int slot = 0; slot < a.size(); slot++) {
            merged.add(new HeroGear(a.get(slot).slot(), pick.apply(a.get(slot).enhancement(), b.get(slot).enhancement()),
                    pick.apply(a.get(slot).mastery(), b.get(slot).mastery())));
        }
        return merged;
    }

    private static Integer higher(Integer a, Integer b) {
        return a == null ? b : b == null ? a : Integer.valueOf(Math.max(a, b));
    }

    // Null is a read that failed, so it says nothing about the piece.
    private static Integer lower(Integer a, Integer b) {
        return a == null ? b : b == null ? a : Integer.valueOf(Math.min(a, b));
    }

    private static String label(RawImageData frame) {
        return SquadOcr.text(frame, BUTTON_LABEL[0], BUTTON_LABEL[1], BUTTON_LABEL[2], BUTTON_LABEL[3], SquadOcr.LINE);
    }

    private static double share(RawImageData frame, int[] colour, int tolerance) {
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        int match = 0;
        int total = 0;
        for (int x = BUTTON_SAMPLE[0]; x < BUTTON_SAMPLE[2]; x++) {
            for (int y = BUTTON_SAMPLE[1]; y < BUTTON_SAMPLE[3]; y++) {
                int rgb = image.getRGB(x, y);
                total++;
                if (Math.abs(((rgb >> 16) & 0xFF) - colour[0]) <= tolerance
                        && Math.abs(((rgb >> 8) & 0xFF) - colour[1]) <= tolerance
                        && Math.abs((rgb & 0xFF) - colour[2]) <= tolerance) {
                    match++;
                }
            }
        }
        return match / (double) total;
    }

    private static String mean(RawImageData frame) {
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        long r = 0;
        long g = 0;
        long b = 0;
        int total = 0;
        for (int x = BUTTON_SAMPLE[0]; x < BUTTON_SAMPLE[2]; x++) {
            for (int y = BUTTON_SAMPLE[1]; y < BUTTON_SAMPLE[3]; y++) {
                int rgb = image.getRGB(x, y);
                r += (rgb >> 16) & 0xFF;
                g += (rgb >> 8) & 0xFF;
                b += rgb & 0xFF;
                total++;
            }
        }
        return String.format(Locale.ROOT, "%d,%d,%d", r / total, g / total, b / total);
    }

    private RawImageData await(Predicate<RawImageData> expected, String failure) {
        RawImageData frame = null;
        for (int attempt = 0; attempt < READ_ATTEMPTS; attempt++) {
            frame = device.capture();
            if (expected.test(frame)) return frame;
            device.pause(READ_PAUSE_MS);
        }
        device.readFailed(frame, failure);
        throw new Abort(failure);
    }
}
