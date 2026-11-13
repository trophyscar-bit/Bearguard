package dev.frostguard.engine.squads;

import java.util.List;

import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.engine.squads.ForgeDialogReader.GearDetails;
import dev.frostguard.engine.squads.GearScan.Abort;
import dev.frostguard.engine.squads.GearScanScreens.Device;

/**
 * Takes the Heroes grid to one hero's Gear Details for one slot, through {@link GearScanScreens}, so
 * every tap follows a read of its screen. The panel never names its hero and two heroes share each
 * Saboteur and Guardian set, so the name is confirmed on the Gear tab and the panel's "+N" must then
 * match that tab's tile.
 */
public final class GearDetailsNavigator implements GearEnhanceStep.Navigator {

    private static final int MAX_BACKS = 4;

    private final GearScan.Screens screens;
    private final Device device;
    // Where the walk is: the hero page that is open (null when on the grid) and each hero's place in the list.
    private String heroNow;
    private int indexNow = -1;
    private int cards;
    private final java.util.Map<String, Integer> seenAt = new java.util.HashMap<>();

    public GearDetailsNavigator(Device device) {
        this(new GearScanScreens(device).withoutStats(), device);
    }

    GearDetailsNavigator(GearScan.Screens screens, Device device) {
        this.screens = screens;
        this.device = device;
    }

    @Override
    public GearDetails open(String hero, String slot) {
        String set = GearForgeGuard.GEAR_SET.get(hero);
        if (set == null || !GearScan.SLOTS.contains(slot)) throw new Abort("no such gear piece: " + hero + " " + slot);
        if (heroNow != null) {
            if (hero.equals(heroNow)) return openPiece(hero, slot, set);
            Integer known = seenAt.get(hero);
            if (known == null || known > indexNow) {
                while (indexNow + 1 < cards) {
                    indexNow++;
                    heroNow = screens.nextHero(indexNow);
                    seenAt.put(String.valueOf(heroNow), indexNow);
                    if (hero.equals(heroNow)) return openPiece(hero, slot, set);
                }
            }
            closeToGrid();
        }
        cards = Math.min(screens.openGrid(), GearScan.CARD_LIMIT);
        for (int index = 0; index < cards; index++) {
            heroNow = index == 0 ? screens.openHero(0) : screens.nextHero(index);
            indexNow = index;
            seenAt.put(String.valueOf(heroNow), index);
            if (hero.equals(heroNow)) return openPiece(hero, slot, set);
        }
        heroNow = null;
        throw new Abort(hero + " is not in the first " + cards + " grid cards");
    }

    /** Back from Gear Details to the hero's Gear tab, where the next piece or the next hero's arrow is. */
    @Override
    public void finishPiece() {
        screens.closeDetails();
    }

    private GearDetails openPiece(String hero, String slot, String set) {
        List<HeroGear> tiles = screens.openGearTab(hero);
        HeroGear tile = tiles.get(GearScan.SLOTS.indexOf(slot));
        String where = hero + " " + slot;
        if (tile.enhancement() == null) throw new Abort(where + " tile did not read");
        GearDetails details = screens.openDetails(slot);
        if (!(set + " " + slot).equals(details.piece())) throw new Abort(where + " opened " + details.piece());
        if (tile.enhancement() != details.enhancement()) {
            throw new Abort(where + " reads +" + details.enhancement() + " in Gear Details, +" + tile.enhancement() + " on its tile");
        }
        return details;
    }

    /** Backs out from wherever the walk stopped; each Back follows a read that this isn't the grid yet. */
    @Override
    public void closeToGrid() {
        heroNow = null;
        for (int back = 0; back <= MAX_BACKS; back++) {
            if (HeroCards.isHeroesGrid(device.capture())) return;
            if (back == MAX_BACKS) break;
            device.back();
            device.pause(1500);
        }
        throw new Abort("Back did not return to the Heroes grid");
    }
}
