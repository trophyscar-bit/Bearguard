package dev.frostguard.engine.squads;

import java.util.Map;

import dev.frostguard.engine.squads.ForgeDialogReader.ForgeDialog;
import dev.frostguard.engine.squads.GearRanker.Upgrade;

/**
 * Decides whether a Forge press may go ahead, and whether one that went ahead did what it should.
 * The game is the source of truth: anything on screen that disagrees with the plan blocks the press.
 */
public final class GearForgeGuard {

    public enum Verdict {
        /** Everything matches and the balance covers it. */
        BUY,
        /** Everything matches but the balance is short, so keep saving. */
        SAVE,
        /** Right piece, but its mastery isn't what the saved state says: correct the state, buy nothing. */
        RESYNC,
        /** Wrong piece, unreadable screen, or an unexpected cost: press nothing. */
        STOP
    }

    public record Check(Verdict verdict, String reason) {
    }

    static final Map<String, String> GEAR_SET = Map.of(
            "Mia", "Saboteur", "Molly", "Saboteur", "Flint", "Guardian", "Sergey", "Guardian", "Bahiti", "Sniper");

    private GearForgeGuard() {
    }

    public static Check before(Upgrade expected, ForgeDialog dialog) {
        return before(expected, dialog, 0);
    }

    /** {@code reserve} is the stone balance a purchase must leave behind. */
    public static Check before(Upgrade expected, ForgeDialog dialog, int reserve) {
        if (dialog == null) return new Check(Verdict.STOP, "forge dialog unreadable");
        String piece = GEAR_SET.get(expected.hero()) + " " + expected.slot();
        if (!piece.equals(dialog.piece())) {
            return new Check(Verdict.STOP, "expected " + piece + ", screen shows " + dialog.piece());
        }
        if (dialog.masteryPercent() != expected.fromMastery()) {
            return new Check(Verdict.RESYNC, piece + " is at " + dialog.masteryPercent() + "%, saved state says "
                    + expected.fromMastery() + "%");
        }
        if (dialog.nextMasteryPercent() != expected.toMastery() || dialog.stonesNeeded() != expected.stones()) {
            return new Check(Verdict.STOP, piece + " offers " + dialog.nextMasteryPercent() + "% for "
                    + dialog.stonesNeeded() + " stones, expected " + expected.toMastery() + "% for " + expected.stones());
        }
        if (dialog.stonesHave() - dialog.stonesNeeded() < reserve) {
            return new Check(Verdict.SAVE, piece + " needs " + dialog.stonesNeeded() + " stones plus a reserve of "
                    + reserve + ", have " + dialog.stonesHave());
        }
        return new Check(Verdict.BUY, piece + " " + dialog.masteryPercent() + "% to " + dialog.nextMasteryPercent()
                + "% for " + dialog.stonesNeeded() + " stones");
    }

    /** True while the same piece still shows its pre-press mastery or balance, so the screen may not have caught up. */
    public static boolean stillShowsOld(ForgeDialog before, ForgeDialog after) {
        return after != null && before.piece().equals(after.piece())
                && (after.masteryPercent() == before.masteryPercent() || after.stonesHave() == before.stonesHave());
    }

    /** Null when the press did exactly what the dialog promised, otherwise what went wrong. */
    public static String afterProblem(ForgeDialog before, ForgeDialog after) {
        if (after == null) return "forge dialog unreadable after the press";
        if (!before.piece().equals(after.piece())) return "dialog changed piece to " + after.piece();
        if (after.masteryPercent() != before.nextMasteryPercent()) {
            return "mastery is " + after.masteryPercent() + "%, expected " + before.nextMasteryPercent() + "%";
        }
        int expectedStones = before.stonesHave() - before.stonesNeeded();
        return after.stonesHave() == expectedStones ? null
                : "stones are " + after.stonesHave() + ", expected " + expectedStones;
    }
}
