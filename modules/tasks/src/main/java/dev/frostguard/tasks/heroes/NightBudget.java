package dev.frostguard.tasks.heroes;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;

import dev.frostguard.engine.squads.GearEnhancePass;
import dev.frostguard.engine.squads.GearEnhanceStep;
import dev.frostguard.engine.squads.GearScan;
import dev.frostguard.engine.squads.HeroGearStore;
import dev.frostguard.engine.squads.HeroGearStore.NightLedger;

/** One night's time cap, buy count and enhance level count, saved so a re-run gets what is left instead of starting over. */
final class NightBudget {

    static final Duration TIME_CAP = Duration.ofMinutes(20);
    static final Duration FORGE_RESERVE = Duration.ofMinutes(5);

    private final HeroGearStore store;
    private final long profileId;
    private NightLedger ledger;

    private NightBudget(HeroGearStore store, long profileId, NightLedger ledger) {
        this.store = store;
        this.profileId = profileId;
        this.ledger = ledger;
    }

    /**
     * The saved budget when it belongs to the same slot as {@code now}, or a fresh one saved now. A run
     * moved off the slot is a night of its own, so it never uses up the real one.
     */
    static NightBudget open(HeroGearStore store, long profileId, Instant now, LocalTime slotUtc) throws IOException {
        String slot = slotFor(now, slotUtc).toString();
        NightLedger saved = store.loadNightLedger(profileId).orElse(null);
        if (saved != null && sameSlot(saved, slot)) return new NightBudget(store, profileId, saved);
        NightLedger fresh = new NightLedger(now.toString(), slot, 0, 0, false);
        store.saveNightLedger(profileId, fresh);
        return new NightBudget(store, profileId, fresh);
    }

    /** The latest time a UTC clock read {@code slotUtc}, at or before {@code now}. */
    static Instant slotFor(Instant now, LocalTime slotUtc) {
        Instant today = now.atZone(ZoneOffset.UTC).toLocalDate().atTime(slotUtc).toInstant(ZoneOffset.UTC);
        return today.isAfter(now) ? today.minus(Duration.ofDays(1)) : today;
    }

    private static boolean sameSlot(NightLedger ledger, String slot) {
        if (!slot.equals(ledger.slot()) || ledger.startedAt() == null) return false;
        try {
            Instant.parse(ledger.startedAt());
            return true;
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

    int levelsLeft(int maxLevels) {
        return Math.max(0, maxLevels - ledger.levels());
    }

    /** Counts a buy before its tap, or returns false when the cap or the count says not to make it. */
    boolean spendBuy(int maxBuys, Instant now) throws IOException {
        if (now.isAfter(deadline()) || buysLeft(maxBuys) < 1) return false;
        save(new NightLedger(ledger.startedAt(), ledger.slot(), ledger.buys() + 1, ledger.levels(), ledger.finished()));
        return true;
    }

    void spendLevels(int levels) throws IOException {
        save(new NightLedger(ledger.startedAt(), ledger.slot(), ledger.buys(), ledger.levels() + levels, ledger.finished()));
    }

    /**
     * Counts each live raise's planned levels before it starts, and gives back the ones it did not
     * press once it returns. A raise that throws or ends unconfirmed keeps its whole count.
     */
    GearEnhanceStep.Raiser counted(GearEnhanceStep.Raiser raiser) {
        return (raise, live) -> {
            if (!live) return raiser.run(raise, false);
            try {
                spendLevels(raise.levels());
            } catch (IOException e) {
                throw new GearScan.Abort("could not save tonight's level count: " + e.getMessage());
            }
            GearEnhancePass.Outcome result = raiser.run(raise, true);
            int unused = raise.levels() - Math.max(0, result.raised());
            if (unused > 0 && result.stop() != GearEnhancePass.Stop.PRESS_UNCONFIRMED) {
                try {
                    spendLevels(-unused);
                } catch (IOException e) {
                    // Keeping the higher count only means fewer levels tonight.
                }
            }
            return result;
        };
    }

    void finish() throws IOException {
        save(new NightLedger(ledger.startedAt(), ledger.slot(), ledger.buys(), ledger.levels(), true));
    }

    private void save(NightLedger updated) throws IOException {
        store.saveNightLedger(profileId, updated);
        ledger = updated;
    }
}
