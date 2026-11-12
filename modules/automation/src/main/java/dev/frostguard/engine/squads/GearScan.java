package dev.frostguard.engine.squads;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.engine.squads.ForgeDialogReader.ForgeDialog;
import dev.frostguard.engine.squads.ForgeDialogReader.GearDetails;
import dev.frostguard.engine.squads.GearRanker.GearPiece;
import dev.frostguard.engine.squads.GearRanker.GearState;
import dev.frostguard.engine.squads.HeroScreenReader.HeroXp;
import dev.frostguard.engine.squads.HeroUpgradePlanner.Instructor;
import dev.frostguard.engine.squads.HeroUpgradePlanner.Inventory;

/**
 * The read-only full gear scan: when one is due, the walk over the {@value #HERO_COUNT} main heroes,
 * and what gets saved. {@link Screens} has no way to press Forge. A scan reads every piece or saves
 * nothing, because the ranker and the guard would otherwise trust a state with holes in it.
 */
public final class GearScan {

    public static final int HERO_COUNT = 5;
    /** Three full rows of four are all the Heroes grid shows without scrolling. */
    static final int CARD_LIMIT = 12;
    static final List<String> SLOTS = List.of("Goggles", "Gauntlets", "Belt", "Boots");
    private static final Set<String> HEALTH_SLOTS = Set.of("Gauntlets", "Belt");
    private static final Set<String> LETHALITY_SLOTS = Set.of("Goggles", "Boots");
    private static final Map<String, String> SET_CLASS =
            Map.of("Sniper", "Marksman", "Saboteur", "Lancer", "Guardian", "Infantry");
    private static final double NEW_HERO_WEIGHT = 0.5;

    /** A screen check or read failed, so the scan stops where it is. */
    public static final class Abort extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Abort(String reason) {
            super(reason);
        }
    }

    /** {@code level} and {@code xp} are null when unread; neither blocks the gear scan. */
    public record HeroPage(String name, String troopClass, Integer level, HeroXp xp) {
    }

    /** Each step confirms its screen and throws {@link Abort} when it can't. */
    public interface Screens {
        /** How many owned cards the walk may open, in reading order. */
        int openGrid();

        /** Opens the card and returns the hero's name, or null when it isn't a known hero. */
        String openHero(int index);

        /** Reads the hero page {@link #openHero} left open. */
        HeroPage readHero(String name);

        List<HeroGear> openGearTab(String hero);

        GearDetails openDetails(String slot);

        ForgeDialog openForge();

        void closeForge();

        void closeDetails();

        void closeHero();
    }

    /** {@code problem} is why the scan stopped, and null with {@code state} saved when it finished. */
    public record Outcome(GearState state, String problem, String inventoryNote) {
    }

    private GearScan() {
    }

    /** A scan a few hours short of its age still counts as due, so a nightly scan can't skip a night by minutes. */
    private static final Duration DUE_GRACE = Duration.ofHours(6);

    /** Due when nothing is saved, no valid scan time is, or the last scan is {@code maxAgeDays} old, less a few hours' grace. */
    public static boolean isDue(GearState saved, Instant now, int maxAgeDays) {
        if (saved == null || saved.scannedAt() == null) return true;
        try {
            Instant scanned = Instant.parse(saved.scannedAt());
            return scanned.isAfter(now)
                    || Duration.between(scanned, now).compareTo(Duration.ofDays(maxAgeDays).minus(DUE_GRACE)) >= 0;
        } catch (DateTimeException malformed) {
            return true;
        }
    }

    /** Infantry fight on Health, from Gauntlets and Belt; Lancers and Marksmen on Lethality, from Goggles and Boots. */
    public static boolean onRole(String troopClass, String slot) {
        return ("Infantry".equals(troopClass) ? HEALTH_SLOTS : LETHALITY_SLOTS).contains(slot);
    }

    /** Runs the scan and saves it; on any failure nothing is written and {@code previous} stands. */
    public static Outcome run(Screens screens, HeroGearStore store, long profileId, GearState previous, Instant now) {
        List<HeroPage> pages = new ArrayList<>();
        List<GearPiece> pieces = new ArrayList<>();
        Set<Integer> stones = new LinkedHashSet<>();
        try {
            int cards = Math.min(screens.openGrid(), CARD_LIMIT);
            for (int index = 0; index < cards && pages.size() < HERO_COUNT; index++) {
                String name = screens.openHero(index);
                if (name == null || !GearForgeGuard.GEAR_SET.containsKey(name)) {
                    screens.closeHero();
                    continue;
                }
                HeroPage page = page(screens.readHero(name), pages);
                pages.add(page);
                List<HeroGear> tiles = screens.openGearTab(page.name());
                for (int slot = 0; slot < SLOTS.size(); slot++) {
                    pieces.add(piece(screens, page, SLOTS.get(slot), tiles.get(slot), stones));
                }
                screens.closeHero();
            }
            if (pages.size() < HERO_COUNT) {
                throw new Abort("found only " + pages.stream().map(HeroPage::name).toList()
                        + " of the main heroes in the first " + cards + " grid cards");
            }
        } catch (Abort abort) {
            return new Outcome(null, abort.getMessage(), null);
        }
        GearState scanned = new GearState(List.copyOf(pieces), mergedWeights(previous, pages), now.toString());
        try {
            store.saveGear(profileId, scanned);
        } catch (IOException e) {
            return new Outcome(null, "could not save the scan: " + e.getMessage(), null);
        }
        return new Outcome(scanned, null, saveInventory(store, profileId, pages, stones, now));
    }

    private static HeroPage page(HeroPage page, List<HeroPage> earlier) {
        if (!HeroScreenReader.TROOP_CLASSES.contains(page.troopClass())) {
            throw new Abort(page.name() + " has no readable troop class, read " + page.troopClass());
        }
        if (earlier.stream().anyMatch(p -> p.name().equals(page.name()))) {
            throw new Abort(page.name() + " came up on two different grid cards");
        }
        return page;
    }

    private static GearPiece piece(Screens screens, HeroPage page, String slot, HeroGear tile, Set<Integer> stones) {
        String where = page.name() + " " + slot;
        GearDetails details = screens.openDetails(slot);
        if (!details.piece().endsWith(" " + slot)) {
            throw new Abort(where + " opened " + details.piece());
        }
        String setClass = SET_CLASS.get(details.piece().split(" ")[0]);
        if (!page.troopClass().equals(setClass)) {
            throw new Abort(where + " is " + details.piece() + " on a " + page.troopClass());
        }
        if (tile.enhancement() != null && tile.enhancement() != details.enhancement()) {
            throw new Abort(where + " reads +" + details.enhancement() + " in Gear Details, +" + tile.enhancement() + " on its tile");
        }
        ForgeDialog dialog = screens.openForge();
        if (!details.piece().equals(dialog.piece())) {
            throw new Abort(where + " forge dialog shows " + dialog.piece() + ", expected " + details.piece());
        }
        if (tile.mastery() != null && tile.mastery() != dialog.masteryPercent() / 10) {
            throw new Abort(where + " reads " + dialog.masteryPercent() + "% in the forge dialog, Lv." + tile.mastery() + " on its tile");
        }
        stones.add(dialog.stonesHave());
        screens.closeForge();
        screens.closeDetails();
        return new GearPiece(page.name(), slot, details.enhancement(), dialog.masteryPercent(),
                onRole(page.troopClass(), slot));
    }

    /** Existing weights stand, including for heroes this scan didn't see; a hero new to the file gets 0.5. */
    static Map<String, Double> mergedWeights(GearState previous, List<HeroPage> pages) {
        Map<String, Double> weights = new LinkedHashMap<>();
        if (previous != null && previous.heroWeights() != null) weights.putAll(previous.heroWeights());
        pages.forEach(page -> weights.putIfAbsent(page.name(), NEW_HERO_WEIGHT));
        return weights;
    }

    /** Null on success, otherwise why the inventory was left alone. It never fails the gear scan. */
    private static String saveInventory(HeroGearStore store, long profileId, List<HeroPage> pages, Set<Integer> stones, Instant now) {
        if (stones.size() != 1) return "stone balance read differently across pieces " + stones + ", inventory left alone";
        Set<Long> balances = xpBalances(pages);
        if (balances.size() > 1) return "hero XP read differently across heroes " + balances + ", inventory left alone";
        try {
            Inventory old = store.loadInventory(profileId).orElse(null);
            if (old == null && balances.isEmpty()) return "hero XP did not read and no earlier inventory holds it, inventory left alone";
            store.saveInventory(profileId, mergedInventory(old, pages, stones.iterator().next(), now));
            return null;
        } catch (IOException e) {
            return "inventory left alone: " + e.getMessage();
        }
    }

    private static Set<Long> xpBalances(List<HeroPage> pages) {
        Set<Long> balances = new LinkedHashSet<>();
        for (HeroPage page : pages) {
            if (page.xp() != null) balances.add(page.xp().have());
        }
        return balances;
    }

    /**
     * Enhancement XP, the priority list and instructors this scan didn't see aren't read, so they
     * carry over, in their saved order. An unread XP cost carries only at the level it was read for.
     */
    static Inventory mergedInventory(Inventory old, List<HeroPage> pages, int stones, Instant now) {
        Map<String, Instructor> instructors = new LinkedHashMap<>();
        if (old != null && old.instructors() != null) old.instructors().forEach(i -> instructors.put(i.name(), i));
        for (HeroPage page : pages) {
            Instructor known = instructors.get(page.name());
            Integer level = page.level() != null ? page.level() : known != null ? Integer.valueOf(known.level()) : null;
            if (level == null) continue;
            Long carried = known != null && known.level() == level ? known.xpToNext() : null;
            Long toNext = page.xp() != null ? Long.valueOf(page.xp().toNext()) : carried;
            instructors.put(page.name(), new Instructor(page.name(), level, toNext));
        }
        Set<Long> balances = xpBalances(pages);
        long oldXp = old == null ? 0 : old.heroXp();
        return new Inventory(now.toString(), balances.size() == 1 ? balances.iterator().next() : oldXp, stones,
                old == null ? 0 : old.enhancementXp(), List.copyOf(instructors.values()),
                old == null || old.priority() == null ? List.of() : old.priority());
    }
}
