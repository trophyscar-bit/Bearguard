package dev.frostguard.engine.squads;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.squads.GearScan.Abort;
import dev.frostguard.engine.squads.HeroScreenReader.Upgrade;

/**
 * Spends hero XP on the five instructors, evenly: only a hero at the lowest level among those who can still
 * level may be upgraded, because the Drill Camp lifts every other hero to the lowest instructor's level. One
 * walk by the right arrow visits each instructor once; on each, the Stats tab's Upgrade button is read and
 * pressed only while all of these hold:
 *
 * <ul>
 * <li>the page is this hero's Stats tab and the level on it was read;</li>
 * <li>this hero's level equals the lowest of the instructors that can still level;</li>
 * <li>the game itself prints the balance green, which it does only when the balance covers the cost.</li>
 * </ul>
 *
 * <p>Each press is one level. The next screen must show the same hero one level higher, else the pass stops
 * and presses nothing more. Gems are never touched: nothing here taps anything but the Upgrade button.
 */
public final class HeroLevelUpPass {

    /** The centre of the Upgrade button on the Stats tab (720x1280 frames). */
    public static final PointData UPGRADE = new PointData(360, 1112);
    private static final int BUTTON_RADIUS = 10;
    private static final int CONFIRM_ATTEMPTS = 6;
    private static final int CONFIRM_PAUSE_MS = 700;
    private static final int MAX_PRESSES = 12;

    /** The emulator, as far as this pass may touch it. */
    public interface Device {
        RawImageData capture();

        void tap(PointData point, int radius);

        void pause(int millis);

        boolean pastDeadline();

        /** The frame a read gave up on, just before the stop it causes. Must not touch the screen. */
        default void readFailed(RawImageData frame, String failure) {
        }
    }

    /**
     * {@code levels} is each instructor's level after the pass, {@code upgraded} one line per press, {@code notes}
     * why a hero was left alone, and {@code halt} why the pass stopped for good (null when it ended normally).
     */
    public record Outcome(Map<String, Integer> levels, List<String> upgraded, List<String> notes, String halt) {
    }

    private final GearScan.Screens screens;
    private final Device device;

    public HeroLevelUpPass(GearScan.Screens screens, Device device) {
        this.screens = screens;
        this.device = device;
    }

    /**
     * @param levels      the instructors' levels as last read, in walk order; a hero at max level is left out of
     *                    {@code canLevel} and is never upgraded or counted as the lowest
     * @param canLevel    the instructors that are not at the level cap
     * @param live        with false nothing is pressed and each hero says what it would do
     */
    public Outcome run(Map<String, Integer> levels, Set<String> canLevel, boolean live) {
        Map<String, Integer> now = new LinkedHashMap<>(levels);
        List<String> upgraded = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int presses = 0;
        boolean onAHero = false;
        try {
            int cards = Math.min(screens.openGrid(), GearScan.CARD_LIMIT);
            int visited = 0;
            for (int index = 0; index < cards && visited < levels.size(); index++) {
                String name = onAHero ? screens.nextHero(index) : screens.openHero(index);
                onAHero = true;
                if (name == null || !levels.containsKey(name)) continue;
                visited++;
                presses = visit(name, now, canLevel, live, upgraded, notes, presses);
            }
            screens.closeHero();
        } catch (Abort abort) {
            return new Outcome(Map.copyOf(now), List.copyOf(upgraded), List.copyOf(notes), abort.getMessage());
        } catch (Halt halt) {
            return new Outcome(Map.copyOf(now), List.copyOf(upgraded), List.copyOf(notes), halt.getMessage());
        }
        return new Outcome(Map.copyOf(now), List.copyOf(upgraded), List.copyOf(notes), null);
    }

    private static final class Halt extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Halt(String reason) {
            super(reason);
        }
    }

    private int visit(String name, Map<String, Integer> now, Set<String> canLevel, boolean live,
                      List<String> upgraded, List<String> notes, int pressesSoFar) {
        int presses = pressesSoFar;
        while (true) {
            if (device.pastDeadline()) throw new Halt("time cap reached before " + name);
            RawImageData frame = device.capture();
            Integer level = readLevel(frame, name);
            if (level == null) {
                notes.add(name + ": the Stats tab did not read, left alone");
                return presses;
            }
            now.put(name, level);
            if (!canLevel.contains(name)) {
                notes.add(name + " Lv " + level + ": already at the top");
                return presses;
            }
            Upgrade upgrade = HeroScreenReader.readUpgrade(frame);
            if (upgrade == null) {
                notes.add(name + " Lv " + level + ": no Upgrade button (maxed or unreadable), left alone");
                return presses;
            }
            int lowest = lowestLevel(now, canLevel);
            if (level != lowest) {
                notes.add(name + " Lv " + level + ": not the lowest (Lv " + lowest + "), so it waits its turn");
                return presses;
            }
            String cost = name + " Lv " + level + " to Lv " + (level + 1) + " (" + upgrade.xp().toNext() + " XP, balance " + upgrade.xp().have() + ")";
            if (!upgrade.affordable()) {
                notes.add(name + " Lv " + level + ": saving, needs " + upgrade.xp().toNext() + " XP, has " + upgrade.xp().have());
                return presses;
            }
            if (!live) {
                notes.add("would upgrade " + cost);
                return presses;
            }
            if (presses >= MAX_PRESSES) throw new Halt("more than " + MAX_PRESSES + " level-ups in one night, stopping");
            device.tap(UPGRADE, BUTTON_RADIUS);
            presses++;
            confirm(name, level, frame);
            now.put(name, level + 1);
            upgraded.add(cost);
        }
    }

    private Integer readLevel(RawImageData frame, String name) {
        if (!HeroScreenReader.isStatsTab(frame) || !name.equals(HeroScreenReader.readName(frame))) return null;
        return HeroScreenReader.readDetail(frame).level();
    }

    /** The same hero one level higher must show; anything else stops the pass with no further tap. */
    private void confirm(String name, int level, RawImageData before) {
        RawImageData frame = before;
        device.pause(1500);
        for (int attempt = 0; attempt < CONFIRM_ATTEMPTS; attempt++) {
            frame = device.capture();
            if (HeroScreenReader.isStatsTab(frame) && name.equals(HeroScreenReader.readName(frame))) {
                Integer after = HeroScreenReader.readDetail(frame).level();
                if (after != null && after == level + 1) return;
                if (after != null && after > level + 1) throw new Halt(name + " jumped from Lv " + level + " to Lv " + after + "; stopping");
            } else if (!HeroScreenReader.isHeroPage(frame)) {
                device.readFailed(frame, "an unexpected screen after Upgrade on " + name);
                throw new Halt("an unexpected screen showed after Upgrade on " + name + "; nothing more pressed");
            }
            device.pause(CONFIRM_PAUSE_MS);
        }
        device.readFailed(frame, "Lv " + (level + 1) + " not shown after Upgrade on " + name);
        throw new Halt("Lv " + (level + 1) + " was not confirmed after Upgrade on " + name + "; nothing more pressed");
    }

    private static int lowestLevel(Map<String, Integer> levels, Set<String> canLevel) {
        return levels.entrySet().stream().filter(e -> canLevel.contains(e.getKey())).mapToInt(Map.Entry::getValue).min().orElse(Integer.MAX_VALUE);
    }
}
