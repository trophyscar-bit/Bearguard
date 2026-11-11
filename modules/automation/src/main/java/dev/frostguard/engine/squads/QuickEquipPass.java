package dev.frostguard.engine.squads;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.engine.squads.GearRanker.GearState;
import dev.frostguard.engine.squads.GearScan.Abort;

/**
 * Flicks through every recruited hero, strongest first, pressing Quick Equip where it is lit.
 *
 * <p>Quick Equip may move gear between heroes, so the tiles of every hero with a gear set are recorded
 * before the walk and re-read after any press. One that lost anything stops the night. The record is
 * saved and the saved gear distrusted before the first press, so a run cut off part way leaves a scan
 * due and the next run compares against that record instead of taking the damage as its baseline.
 */
public final class QuickEquipPass {

    public static final int MAX_PRESSES = 25;
    static final int MAX_HEROES = 64;
    static final List<String> LEADS = List.of("Mia", "Flint", "Molly", "Bahiti", "Sergey");

    /** One confirmed Gear tab. {@code name} is null when unread; {@code oddLook} describes a button that is neither known look. */
    public record GearTab(String name, boolean lit, String oddLook) {
    }

    /** Each step confirms its screen and throws {@link Abort} when it can't. */
    public interface Screens {
        GearTab openFirstHero();

        /** The hero after {@code current}, or null when no further Gear tab comes up. */
        GearTab nextHero(String current);

        /** A settled reading of {@code hero}'s tiles, keeping the highest seen. */
        List<HeroGear> readGear(String hero);

        /** A settled reading of {@code hero}'s tiles, keeping the lowest seen, so any confirmed drop shows. */
        List<HeroGear> rereadGear(String hero);

        /** False when a fresh read no longer shows {@code hero} with a lit button, so nothing was pressed. */
        boolean press(String hero);

        void closeToGrid();

        /** True once the walk is out of time; re-reads aren't held to it, since a re-read cut short would halt the night. */
        boolean pastStop();
    }

    /** {@code stopNight} means nothing more may happen until cleared. {@code truncated} describes a walk that didn't finish. */
    public record Outcome(int heroes, int presses, String problem, boolean stopNight, List<String> oddLooks, String truncated) {
    }

    private record Recheck(String problem, Map<String, List<HeroGear>> leads) {
    }

    private QuickEquipPass() {
    }

    public static Outcome run(Screens screens, HeroGearStore store, long profileId) {
        Map<String, List<HeroGear>> before;
        try {
            before = store.loadLeadBaseline(profileId).map(HeroGearStore.LeadBaseline::leads).orElse(null);
        } catch (IOException e) {
            return new Outcome(0, 0, "the saved lead baseline could not be read: " + e.getMessage(), true, List.of(), null);
        }
        if (before != null) {
            Recheck checked = check(screens, before, store, profileId);
            if (checked.problem() != null) return new Outcome(0, 0, checked.problem(), true, List.of(), null);
            before = checked.leads();
        } else {
            if (screens.pastStop()) return new Outcome(0, 0, "out of time, nothing pressed", false, List.of(), null);
            try {
                before = withSavedMastery(readLeads(screens, screens::readGear), store.loadGear(profileId).orElse(null));
                requireComplete(before);
                screens.closeToGrid();
            } catch (Abort abort) {
                return new Outcome(0, 0, "nothing pressed: " + abort.getMessage(), false, List.of(), null);
            } catch (IOException e) {
                return new Outcome(0, 0, "nothing pressed: the saved gear could not be read: " + e.getMessage(), false, List.of(), null);
            }
        }

        int heroes = 0;
        int presses = 0;
        boolean touched = false;
        String problem = null;
        String cutShort = null;
        List<String> oddLooks = new ArrayList<>();
        List<String> litSkipped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            GearTab tab = screens.openFirstHero();
            while (tab != null && heroes < MAX_HEROES) {
                if (screens.pastStop()) {
                    cutShort = "out of time";
                    break;
                }
                if (tab.name() != null && !seen.add(tab.name())) {
                    cutShort = tab.name() + " came up again";
                    break;
                }
                heroes++;
                if (tab.oddLook() != null) oddLooks.add(tab.name() + ": " + tab.oddLook());
                if (tab.lit() && tab.name() != null) {
                    if (presses >= MAX_PRESSES) {
                        litSkipped.add(tab.name());
                    } else {
                        if (!touched) prepareForPress(store, profileId, before);
                        touched = true;
                        if (screens.press(tab.name())) presses++;
                    }
                }
                tab = screens.nextHero(tab.name());
            }
            if (tab != null && cutShort == null) cutShort = "the limit of " + MAX_HEROES + " heroes was reached";
        } catch (Abort abort) {
            problem = abort.getMessage();
            cutShort = abort.getMessage();
        }
        if (cutShort == null && !litSkipped.isEmpty()) cutShort = "the cap of " + MAX_PRESSES + " presses was reached";
        if (cutShort == null && !seen.containsAll(before.keySet())) cutShort = "no further hero came up";
        String truncated = cutShort == null ? null : truncation(cutShort, heroes, seen, before.keySet(), litSkipped);
        if (!touched) return new Outcome(heroes, 0, problem, false, List.copyOf(oddLooks), truncated);

        String damage;
        try {
            screens.closeToGrid();
            damage = check(screens, before, store, profileId).problem();
        } catch (Abort abort) {
            damage = "the march leads could not be re-read: " + abort.getMessage();
        }
        return new Outcome(heroes, presses, damage != null ? damage : problem, damage != null, List.copyOf(oddLooks), truncated);
    }

    /** Only compares the leads against a baseline left by an earlier run, pressing nothing; a no-op when none is saved. */
    public static Outcome recheck(Screens screens, HeroGearStore store, long profileId) {
        Map<String, List<HeroGear>> before;
        try {
            before = store.loadLeadBaseline(profileId).map(HeroGearStore.LeadBaseline::leads).orElse(null);
        } catch (IOException e) {
            return new Outcome(0, 0, "the saved lead baseline could not be read: " + e.getMessage(), true, List.of(), null);
        }
        String problem = before == null ? null : check(screens, before, store, profileId).problem();
        return new Outcome(0, 0, problem, problem != null, List.of(), null);
    }

    /** A clean re-read clears the saved baseline and returns what it read; otherwise {@code problem} says why it wasn't clean. */
    private static Recheck check(Screens screens, Map<String, List<HeroGear>> before, HeroGearStore store, long profileId) {
        try {
            Map<String, List<HeroGear>> after = readLeads(screens, screens::rereadGear);
            String damage = damage(before, after);
            if (damage != null) return new Recheck(damage, null);
            String unread = unread(before, after);
            if (unread != null) return new Recheck("the march leads could not be re-read: " + unread, null);
            screens.closeToGrid();
            store.clearLeadBaseline(profileId);
            return new Recheck(null, after);
        } catch (Abort abort) {
            return new Recheck("the march leads could not be re-read: " + abort.getMessage(), null);
        } catch (IOException e) {
            return new Recheck("the saved lead baseline could not be cleared: " + e.getMessage(), null);
        }
    }

    private static String truncation(String why, int heroes, Set<String> seen, Collection<String> leads, List<String> litSkipped) {
        List<String> notReached = new ArrayList<>(leads);
        notReached.removeAll(seen);
        notReached.addAll(litSkipped);
        return "walk cut short after " + heroes + " heroes, " + why + "; not reached: "
                + (notReached.isEmpty() ? "none known by name" : notReached);
    }

    private static Map<String, List<HeroGear>> readLeads(Screens screens, Function<String, List<HeroGear>> reader) {
        Map<String, List<HeroGear>> leads = new LinkedHashMap<>();
        GearTab tab = screens.openFirstHero();
        for (int step = 0; step < MAX_HEROES && tab != null; step++) {
            if (tab.name() != null && LEADS.contains(tab.name())) leads.put(tab.name(), reader.apply(tab.name()));
            if (leads.size() == LEADS.size()) return leads;
            tab = screens.nextHero(tab.name());
        }
        throw new Abort("found only " + leads.keySet() + " of the march leads");
    }

    private static void requireComplete(Map<String, List<HeroGear>> leads) {
        leads.forEach((hero, gear) -> {
            if (gear.stream().anyMatch(piece -> piece.enhancement() == null)) {
                throw new Abort(hero + " has a tile that did not read, " + gear);
            }
        });
    }

    // A level no baseline look could read must not count as none when the saved scan says there is one.
    private static Map<String, List<HeroGear>> withSavedMastery(Map<String, List<HeroGear>> leads, GearState saved) {
        if (saved == null) return leads;
        Map<String, List<HeroGear>> filled = new LinkedHashMap<>();
        leads.forEach((hero, gear) -> filled.put(hero, gear.stream().map(piece -> withSavedMastery(hero, piece, saved)).toList()));
        return filled;
    }

    private static HeroGear withSavedMastery(String hero, HeroGear piece, GearState saved) {
        if (piece.mastery() != null) return piece;
        String slot = GearScan.SLOTS.get(piece.slot() - 1);
        return saved.pieces().stream()
                .filter(p -> p.hero().equals(hero) && p.slot().equals(slot) && p.masteryPercent() != null && p.masteryPercent() >= 10)
                .findFirst().map(p -> new HeroGear(piece.slot(), piece.enhancement(), p.masteryPercent() / 10)).orElse(piece);
    }

    private static void prepareForPress(HeroGearStore store, long profileId, Map<String, List<HeroGear>> before) {
        try {
            store.saveLeadBaseline(profileId, before);
            GearState saved = store.loadGear(profileId).orElse(null);
            if (saved != null && saved.scannedAt() != null) {
                store.saveGear(profileId, new GearState(saved.pieces(), saved.heroWeights(), null));
            }
        } catch (IOException e) {
            throw new Abort("could not save the lead baseline or mark the saved gear as stale: " + e.getMessage());
        }
    }

    /** Null when no lead's piece read lower or empty. A null reading is not damage; {@link #unread} deals with it. */
    static String damage(Map<String, List<HeroGear>> before, Map<String, List<HeroGear>> after) {
        for (Map.Entry<String, List<HeroGear>> lead : before.entrySet()) {
            List<HeroGear> now = after.get(lead.getKey());
            for (int slot = 0; slot < lead.getValue().size(); slot++) {
                HeroGear was = lead.getValue().get(slot);
                HeroGear is = now.get(slot);
                String where = lead.getKey() + " slot " + was.slot();
                if (is.enhancement() != null && is.enhancement() == 0) return where + " went missing, was " + was;
                if (is.enhancement() != null && is.enhancement() < was.enhancement()
                        || is.mastery() != null && is.mastery() < level(was)) {
                    return where + " went down from " + was + " to " + is;
                }
            }
        }
        return null;
    }

    /** The first piece still unread after the retries, or null. No level on a piece that never had one needs no reading. */
    private static String unread(Map<String, List<HeroGear>> before, Map<String, List<HeroGear>> after) {
        for (Map.Entry<String, List<HeroGear>> lead : before.entrySet()) {
            List<HeroGear> now = after.get(lead.getKey());
            for (int slot = 0; slot < lead.getValue().size(); slot++) {
                HeroGear was = lead.getValue().get(slot);
                HeroGear is = now.get(slot);
                if (is.enhancement() == null || is.mastery() == null && level(was) > 0) {
                    return lead.getKey() + " slot " + was.slot() + " did not read, was " + was;
                }
            }
        }
        return null;
    }

    private static int level(HeroGear piece) {
        return piece.mastery() == null ? 0 : piece.mastery();
    }
}
