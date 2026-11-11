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

    public GearDetailsNavigator(Device device) {
        this(new GearScanScreens(device), device);
    }

    GearDetailsNavigator(GearScan.Screens screens, Device device) {
        this.screens = screens;
        this.device = device;
    }

    @Override
    public GearDetails open(String hero, String slot) {
        String set = GearForgeGuard.GEAR_SET.get(hero);
        if (set == null || !GearScan.SLOTS.contains(slot)) throw new Abort("no such gear piece: " + hero + " " + slot);
        int cards = Math.min(screens.openGrid(), GearScan.CARD_LIMIT);
        for (int index = 0; index < cards; index++) {
            if (hero.equals(screens.openHero(index))) return openPiece(hero, slot, set);
            screens.closeHero();
        }
        throw new Abort(hero + " is not in the first " + cards + " grid cards");
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
        for (int back = 0; back <= MAX_BACKS; back++) {
            if (HeroCards.isHeroesGrid(device.capture())) return;
            if (back == MAX_BACKS) break;
            device.back();
            device.pause(1500);
        }
        throw new Abort("Back did not return to the Heroes grid");
    }
}
