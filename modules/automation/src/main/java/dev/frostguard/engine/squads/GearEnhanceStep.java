package dev.frostguard.engine.squads;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import dev.frostguard.engine.squads.ForgeDialogReader.GearDetails;
import dev.frostguard.engine.squads.GearEnhancePass.Stop;
import dev.frostguard.engine.squads.GearEnhancePlanner.Raise;
import dev.frostguard.engine.squads.GearRanker.GearState;
import dev.frostguard.engine.squads.GearScan.Abort;

/**
 * Runs the night's planned enhancement raises: open the panel, run {@link GearEnhancePass}, and save
 * the new level after each confirmed raise. Running out of time ends the step and lets the forge go
 * ahead; a stop means the forge is off too.
 *
 * <p>Before the first live raise the saved scan date is cleared, so an interrupted raise leaves a scan
 * due rather than a stale level. It comes back once the step ends with every level confirmed and saved.
 */
public final class GearEnhanceStep {

    /** Reaches Gear Details for one piece and back; both throw {@link Abort} on any wrong screen. */
    public interface Navigator {
        GearDetails open(String hero, String slot);

        void closeToGrid();

        /**
         * Done with one piece: the default goes all the way back to the grid; a walking navigator only closes
         * Gear Details and stays on the hero's page, so the next piece of the same hero needs no navigation
         * and the next hero is one arrow away.
         */
        default void finishPiece() {
            closeToGrid();
        }
    }

    /** {@link GearEnhancePass#run}, starting on the piece's open Gear Details. */
    public interface Raiser {
        GearEnhancePass.Outcome run(Raise raise, boolean live);
    }

    /** {@code maxLevels} counts single levels, the presses the night may make. */
    public record Settings(Set<String> heroes, int maxLevels, boolean live, Instant stopBy) {
    }

    /**
     * {@code stop} is null when the forge may go ahead; {@code note} says why the step ended early.
     * {@code pressUnconfirmed} means the screen may be anywhere after a press, so only Back is safe.
     * {@code saveFailed} means a level isn't on disk, so the scan must stay due.
     */
    public record Outcome(GearState state, int raises, String stop, String note, boolean pressUnconfirmed, boolean saveFailed) {

        Outcome(GearState state, int raises, String stop, String note, boolean pressUnconfirmed) {
            this(state, raises, stop, note, pressUnconfirmed, false);
        }
    }

    private GearEnhanceStep() {
    }

    public static Outcome run(GearState saved, Settings settings, Navigator navigator, Raiser raiser,
                              HeroGearStore store, long profileId, Supplier<Instant> clock) {
        if (settings.maxLevels() < 1) {
            return new Outcome(saved, 0, null, "most levels " + settings.maxLevels() + " is not usable", false);
        }
        Outcome outcome = raise(saved, settings, navigator, raiser, store, profileId, clock);
        return outcome.pressUnconfirmed() || outcome.saveFailed() ? outcome : restoreScanDate(outcome, saved.scannedAt(), store, profileId);
    }

    private static Outcome raise(GearState saved, Settings settings, Navigator navigator, Raiser raiser,
                                 HeroGearStore store, long profileId, Supplier<Instant> clock) {
        // In priority order, not list order: the parts on hand rarely cover the whole plan, and the first raises must be the best ones.
        List<Raise> plan = GearEnhancePlanner.plan(saved.pieces(), settings.heroes(), settings.maxLevels(), saved.heroWeights());
        GearState state = saved;
        int raises = 0;
        try {
            for (Raise raise : plan) {
                if (!clock.get().isBefore(settings.stopBy())) {
                    return new Outcome(state, raises, null, "out of time, leaving the rest for the forge", false);
                }
                GearDetails panel = navigator.open(raise.hero(), raise.slot());
                if (panel.enhancement() != raise.from()) {
                    state = save(store, profileId, state, raise, panel.enhancement());
                    navigator.closeToGrid();
                    return new Outcome(state, raises, null, raise.hero() + " " + raise.slot() + " is at +"
                            + panel.enhancement() + ", saved state said +" + raise.from() + "; corrected, nothing raised", false);
                }
                if (settings.live() && state.scannedAt() != null) {
                    state = state.withScannedAt(null);
                    store.saveGear(profileId, state);
                }
                GearEnhancePass.Outcome result = raiser.run(raise, settings.live());
                if (result.stop() == Stop.PRESS_UNCONFIRMED) {
                    return new Outcome(state, raises, result.stop() + ": " + result.detail(), null, true);
                }
                if (result.raised() > 0) {
                    state = save(store, profileId, state, raise, result.level());
                    raises++;
                }
                switch (result.stop()) {
                    case TARGET_REACHED -> navigator.finishPiece();
                    case DRY_RUN, DEADLINE, QUICK_SELECT_SHORT, PARTS_SHORT -> {
                        navigator.closeToGrid();
                        return new Outcome(state, raises, null, result.detail(), false);
                    }
                    default -> {
                        return new Outcome(state, raises, result.stop() + ": " + result.detail(), null, false);
                    }
                }
            }
            if (raises > 0) navigator.closeToGrid();
        } catch (Abort abort) {
            return new Outcome(state, raises, abort.getMessage(), null, false);
        } catch (IOException e) {
            return new Outcome(state, raises, "could not save the gear state: " + e.getMessage(), null, false, true);
        }
        return new Outcome(state, raises, null, null, false);
    }

    private static Outcome restoreScanDate(Outcome outcome, String scannedAt, HeroGearStore store, long profileId) {
        if (Objects.equals(outcome.state().scannedAt(), scannedAt)) return outcome;
        GearState restored = outcome.state().withScannedAt(scannedAt);
        try {
            store.saveGear(profileId, restored);
        } catch (IOException e) {
            return new Outcome(outcome.state(), outcome.raises(), "could not restore the scan date: " + e.getMessage(), null, false);
        }
        return new Outcome(restored, outcome.raises(), outcome.stop(), outcome.note(), false);
    }

    private static GearState save(HeroGearStore store, long profileId, GearState state, Raise raise, int level) throws IOException {
        GearState updated = state.withEnhancement(raise.hero(), raise.slot(), level);
        store.saveGear(profileId, updated);
        return updated;
    }
}
