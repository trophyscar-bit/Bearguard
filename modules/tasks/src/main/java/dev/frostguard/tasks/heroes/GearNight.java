package dev.frostguard.tasks.heroes;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import dev.frostguard.engine.squads.GearEnhanceStep;
import dev.frostguard.engine.squads.GearRanker.GearState;
import dev.frostguard.engine.squads.HeroGearStore;
import dev.frostguard.engine.squads.HeroGearStore.GearHalt;
import dev.frostguard.engine.squads.HeroGearStore.RaiseInFlight;
import dev.frostguard.engine.squads.QuickEquipPass;

/**
 * The order of the nightly gear steps. A saved halt means no step runs at all, on any run, until it is
 * cleared on the Hero Gear tab, and an Enhance press left unconfirmed by any earlier ending saves one.
 * A night that ran to its end does nothing more until the next night. Whatever stops the night, the
 * bot still ends up Home.
 */
final class GearNight {

    static final int MAX_BACKS = 5;
    private static final int HALT_SAVE_ATTEMPTS = 2;

    /** A null {@code state} ends the night. A {@code halt} means a press went unconfirmed, so only Back may be used. */
    record Enhanced(GearState state, String halt) {
    }

    /**
     * What an Enhance step that stopped leaves the Forge. A press that went unconfirmed halts the night,
     * and a level that didn't save ends it. Any other stop ended unspent, so the Forge still runs: it goes
     * Home first and re-reads every dialog against its own guard before it spends.
     */
    static Enhanced afterEnhanceStop(GearEnhanceStep.Outcome outcome) {
        if (outcome.pressUnconfirmed()) return new Enhanced(null, outcome.stop());
        if (outcome.saveFailed()) return new Enhanced(null, null);
        return new Enhanced(outcome.state(), null);
    }

    interface Steps {
        void halted(GearHalt halt);

        /** A halt failed to save; called for each failed attempt. */
        void haltUnsaved(GearHalt halt, IOException cause);

        void alreadyFinished();

        void begin(NightBudget budget);

        /** Null to carry on, or the reason the night must halt. */
        String quickEquip() throws IOException;

        void baselineUnchecked();

        /** The state to work from, or null to end the night. */
        GearState scanIfDue() throws IOException;

        Enhanced enhance(GearState state);

        void forge(GearState state) throws IOException;

        void home();

        void backOutHome();
    }

    /** What {@link #quickEquip} needs from the routine. */
    interface QuickEquipRun {
        boolean openGrid();

        QuickEquipPass.Screens screens();

        void report(QuickEquipPass.Outcome outcome);
    }

    private GearNight() {
    }

    static void run(Steps steps, HeroGearStore store, long profileId, LocalTime slotUtc, Supplier<Instant> clock)
            throws IOException {
        run(steps, store, profileId, slotUtc, clock, false);
    }

    /** {@code manual} is Run now: a finished night is picked up again, still bound by what it already spent. */
    static void run(Steps steps, HeroGearStore store, long profileId, LocalTime slotUtc, Supplier<Instant> clock, boolean manual)
            throws IOException {
        boolean backsOnly = false;
        boolean started = false;
        boolean noOp = false;
        try {
            Optional<GearHalt> halt = store.loadHalt(profileId);
            if (halt.isPresent()) {
                steps.halted(halt.get());
                return;
            }
            Optional<RaiseInFlight> inFlight = store.loadRaiseInFlight(profileId);
            if (inFlight.isPresent()) {
                backsOnly = true;
                saveHalt(steps, store, profileId, inFlightHalt(inFlight.get(), clock));
                return;
            }
            NightBudget budget = NightBudget.open(store, profileId, clock.get(), slotUtc, manual);
            if (budget.finished()) {
                noOp = true;
                steps.alreadyFinished();
                return;
            }
            steps.begin(budget);
            started = true;
            String stopped = steps.quickEquip();
            if (stopped != null) {
                backsOnly = !saveHalt(steps, store, profileId,
                        new GearHalt("Quick Equip: " + stopped, clock.get().toString(), GearHalt.QUICK_EQUIP));
                return;
            }
            if (store.loadLeadBaseline(profileId).isPresent()) {
                steps.baselineUnchecked();
                return;
            }
            GearState scanned = steps.scanIfDue();
            if (scanned != null) {
                Enhanced enhanced = steps.enhance(scanned);
                if (enhanced.halt() != null) {
                    backsOnly = true;
                    saveHalt(steps, store, profileId,
                            new GearHalt("Enhance: " + enhanced.halt(), clock.get().toString(), GearHalt.ENHANCE));
                    return;
                }
                if (enhanced.state() != null) steps.forge(enhanced.state());
            }
            budget.finish();
        } finally {
            if (started && !backsOnly) backsOnly = haltIfPressInFlight(steps, store, profileId, clock);
            if (backsOnly) steps.backOutHome();
            else if (!noOp) steps.home();
        }
    }

    // A run cut off between an Enhance tap and its confirmation must not tap its way Home.
    private static boolean haltIfPressInFlight(Steps steps, HeroGearStore store, long profileId, Supplier<Instant> clock) {
        try {
            Optional<RaiseInFlight> inFlight = store.loadRaiseInFlight(profileId);
            inFlight.ifPresent(raise -> saveHalt(steps, store, profileId, inFlightHalt(raise, clock)));
            return inFlight.isPresent();
        } catch (IOException unreadable) {
            return true;
        }
    }

    private static GearHalt inFlightHalt(RaiseInFlight raise, Supplier<Instant> clock) {
        return new GearHalt("Enhance: a press on " + raise.hero() + " " + raise.slot() + " at +" + raise.level()
                + " was never confirmed", clock.get().toString(), GearHalt.ENHANCE);
    }

    private static boolean saveHalt(Steps steps, HeroGearStore store, long profileId, GearHalt halt) {
        for (int attempt = 0; attempt < HALT_SAVE_ATTEMPTS; attempt++) {
            try {
                store.saveHalt(profileId, halt);
                return true;
            } catch (IOException e) {
                steps.haltUnsaved(halt, e);
            }
        }
        return false;
    }

    /** Runs the pass, or with Quick Equip unticked only the check of a baseline left behind. Null to carry on. */
    static String quickEquip(boolean ticked, QuickEquipRun run, HeroGearStore store, long profileId) throws IOException {
        if (!ticked && store.loadLeadBaseline(profileId).isEmpty()) return null;
        if (!run.openGrid()) return null;
        QuickEquipPass.Outcome outcome = ticked ? QuickEquipPass.run(run.screens(), store, profileId)
                : QuickEquipPass.recheck(run.screens(), store, profileId);
        run.report(outcome);
        return haltReason(outcome);
    }

    /** The reason a Quick Equip outcome must halt the night, or null when it needn't. */
    static String haltReason(QuickEquipPass.Outcome outcome) {
        return outcome.stopNight() ? outcome.problem() : null;
    }

    /** Backs out one step at a time, reading first so Back is never pressed once {@code settled}; true when it got there. */
    static boolean backOut(BooleanSupplier settled, Runnable back) {
        return backOut(settled, () -> true, back);
    }

    /**
     * As above, and Back is never pressed while {@code gameShown} says the game is not on screen: on the
     * launcher or a bare game screen it opens the exit prompt instead of going anywhere.
     */
    static boolean backOut(BooleanSupplier settled, BooleanSupplier gameShown, Runnable back) {
        for (int step = 0; step < MAX_BACKS; step++) {
            if (!gameShown.getAsBoolean()) return false;
            if (settled.getAsBoolean()) return true;
            back.run();
        }
        return gameShown.getAsBoolean() && settled.getAsBoolean();
    }
}
