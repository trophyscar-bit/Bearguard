package dev.frostguard.tasks.heroes;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;

import dev.frostguard.engine.squads.GearEnhanceStep;
import dev.frostguard.engine.squads.GearScan;
import dev.frostguard.engine.squads.HeroGearStore;
import dev.frostguard.engine.squads.HeroGearStore.NightLedger;

/** One night's time cap and buy and raise counts, saved so a re-run gets what is left instead of starting over. */
final class NightBudget {

    static final Duration TIME_CAP = Duration.ofMinutes(20);
    static final Duration FORGE_RESERVE = Duration.ofMinutes(5);
    // Slots are 24 hours apart, so anything newer than this is the same night.
    private static final Duration SAME_NIGHT = Duration.ofHours(20);

    private final HeroGearStore store;
    private final long profileId;
    private NightLedger ledger;

    private NightBudget(HeroGearStore store, long profileId, NightLedger ledger) {
        this.store = store;
        this.profileId = profileId;
        this.ledger = ledger;
    }

    /** Tonight's saved budget, or a fresh one saved now. */
    static NightBudget open(HeroGearStore store, long profileId, Instant now) throws IOException {
        NightLedger saved = store.loadNightLedger(profileId).orElse(null);
        if (saved != null && sameNight(saved, now)) return new NightBudget(store, profileId, saved);
        NightLedger fresh = new NightLedger(now.toString(), 0, 0, false);
        store.saveNightLedger(profileId, fresh);
        return new NightBudget(store, profileId, fresh);
    }

    private static boolean sameNight(NightLedger ledger, Instant now) {
        if (ledger.startedAt() == null) return false;
        try {
            Instant started = Instant.parse(ledger.startedAt());
            return !started.isAfter(now) && Duration.between(started, now).compareTo(SAME_NIGHT) < 0;
        } catch (DateTimeException malformed) {
            return false;
        }
    }

    boolean finished() {
        return ledger.finished();
    }

    Instant deadline() {
        return Instant.parse(ledger.startedAt()).plus(TIME_CAP);
    }

    /** Where everything before the forge has to stop. */
    Instant reserveAt() {
        return deadline().minus(FORGE_RESERVE);
    }

    int buysLeft(int maxBuys) {
        return Math.max(0, maxBuys - ledger.buys());
    }

    int raisesLeft(int maxRaises) {
        return Math.max(0, maxRaises - ledger.raises());
    }

    /** Counts a buy before its tap, or returns false when the cap or the count says not to make it. */
    boolean spendBuy(int maxBuys, Instant now) throws IOException {
        if (now.isAfter(deadline()) || buysLeft(maxBuys) < 1) return false;
        save(new NightLedger(ledger.startedAt(), ledger.buys() + 1, ledger.raises(), ledger.finished()));
        return true;
    }

    void spendRaise() throws IOException {
        save(new NightLedger(ledger.startedAt(), ledger.buys(), ledger.raises() + 1, ledger.finished()));
    }

    /** Counts each live raise before it starts. */
    GearEnhanceStep.Raiser counted(GearEnhanceStep.Raiser raiser) {
        return (raise, live) -> {
            if (live) {
                try {
                    spendRaise();
                } catch (IOException e) {
                    throw new GearScan.Abort("could not save tonight's raise count: " + e.getMessage());
                }
            }
            return raiser.run(raise, live);
        };
    }

    void finish() throws IOException {
        save(new NightLedger(ledger.startedAt(), ledger.buys(), ledger.raises(), true));
    }

    private void save(NightLedger updated) throws IOException {
        store.saveNightLedger(profileId, updated);
        ledger = updated;
    }
}
