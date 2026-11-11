package dev.frostguard.engine.squads;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides what to spend on heroes next from balances and costs read off the game screen.
 *
 * <p>A cost that was not read stays null and is never spent against: the guides' cost formulas
 * have already been wrong on this account (Mastery Forging is charged per pip, not per level).
 * Each hero or piece gets at most one step per plan, because the next cost is unknown until the
 * screen is read again. Shards and every Ascend are never steps: star promotion and red gear are
 * left to the player.
 *
 * <p>Gear spending follows the priority list strictly: the first piece that can't be paid stops
 * that currency, so it saves for the piece instead of leaking into something cheaper further down.
 *
 * <p>The Drill Camp raises every other hero to the lowest level among the five instructors, so
 * hero XP always goes to the lowest instructor that can still level.
 */
public final class HeroUpgradePlanner {

    public enum Spend { HERO_XP, ESSENCE_STONES, ENHANCEMENT_XP }

    /** {@code xpToNext} is null at max level or when unread. */
    public record Instructor(String name, int level, Long xpToNext) {
    }

    /** {@code forgeCost} is stones for the next pip and {@code enhanceCost} XP for the next level. */
    public record Piece(String hero, String piece, Integer forgeCost, Integer enhanceCost) {
    }

    /** {@code instructors} and {@code priority} are both in spending order. */
    public record Inventory(String capturedAt, long heroXp, int essenceStones, int enhancementXp,
                            List<Instructor> instructors, List<Piece> priority) {
    }

    public record Step(Spend spend, String hero, String target, long cost) {
    }

    public record Plan(List<Step> steps, List<String> waiting) {
    }

    private HeroUpgradePlanner() {
    }

    public static Plan plan(Inventory inventory) {
        List<Step> steps = new ArrayList<>();
        List<String> waiting = new ArrayList<>();
        heroXp(inventory, steps, waiting);
        stones(inventory, steps, waiting);
        enhancement(inventory, steps, waiting);
        return new Plan(List.copyOf(steps), List.copyOf(waiting));
    }

    private static void heroXp(Inventory inventory, List<Step> steps, List<String> waiting) {
        long xp = inventory.heroXp();
        List<Instructor> levelling = inventory.instructors().stream()
                .filter(i -> i.xpToNext() != null)
                .sorted(Comparator.comparingInt(Instructor::level))
                .toList();
        for (Instructor hero : levelling) {
            if (hero.xpToNext() <= xp) {
                xp -= hero.xpToNext();
                steps.add(new Step(Spend.HERO_XP, hero.name(), "Lv " + (hero.level() + 1), hero.xpToNext()));
            } else {
                waiting.add(hero.name() + " Lv " + (hero.level() + 1) + ": needs " + hero.xpToNext() + " XP, " + xp + " left");
            }
        }
    }

    private static void stones(Inventory inventory, List<Step> steps, List<String> waiting) {
        int stones = inventory.essenceStones();
        for (Piece piece : inventory.priority()) {
            String label = piece.hero() + " " + piece.piece();
            if (piece.forgeCost() == null) {
                waiting.add(label + " forge: cost not read");
                return;
            }
            if (piece.forgeCost() > stones) {
                waiting.add(label + " forge: saving, needs " + piece.forgeCost() + " stones, " + stones + " left");
                return;
            }
            stones -= piece.forgeCost();
            steps.add(new Step(Spend.ESSENCE_STONES, piece.hero(), piece.piece(), piece.forgeCost()));
        }
    }

    private static void enhancement(Inventory inventory, List<Step> steps, List<String> waiting) {
        int xp = inventory.enhancementXp();
        for (Piece piece : inventory.priority()) {
            String label = piece.hero() + " " + piece.piece();
            if (piece.enhanceCost() == null) {
                waiting.add(label + " enhance: cost not read");
                return;
            }
            if (piece.enhanceCost() > xp) {
                waiting.add(label + " enhance: saving, needs " + piece.enhanceCost() + " XP, " + xp + " left");
                return;
            }
            xp -= piece.enhanceCost();
            steps.add(new Step(Spend.ENHANCEMENT_XP, piece.hero(), piece.piece(), piece.enhanceCost()));
        }
    }
}
