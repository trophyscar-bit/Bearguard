package dev.frostguard.engine.squads;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.engine.squads.GearRanker.GearState;
import dev.frostguard.engine.squads.HeroUpgradePlanner.Inventory;

/** Reads and atomically saves the gear state and hero inventory for a profile; empty when none is saved. */
public final class HeroGearStore {

    /** A stop only the player can lift; while one is saved the nightly routine presses nothing. */
    public record GearHalt(String reason, String at, String source) {
        public static final String QUICK_EQUIP = "quick-equip";
        public static final String ENHANCE = "enhance";
        public static final String LEVEL_UP = "level-up";
    }

    /** An Enhance press on its way, saved before the tap and cleared once the new level shows. */
    public record RaiseInFlight(String hero, String slot, int level) {
    }

    /**
     * What one night has used of its time cap, its buys and its enhance levels. {@code slot} is the
     * forge slot instant the night belongs to; a ledger saved before it was kept has none.
     */
    public record NightLedger(String startedAt, String slot, int buys, int levels, boolean finished,
                              List<String> plan, String mode) {

        public static final String PRACTICE = "practice";
        public static final String LIVE = "live";

        /** {@code plan} is what a practice night would have done, one line each; {@code mode} is how the night ran. */
        public NightLedger {
            plan = plan == null ? List.of() : List.copyOf(plan);
        }

        public NightLedger(String startedAt, String slot, int buys, int levels, boolean finished) {
            this(startedAt, slot, buys, levels, finished, List.of(), null);
        }

        // A ledger saved before levels were counted holds raises of up to five levels each.
        @JsonCreator
        static NightLedger read(@JsonProperty("startedAt") String startedAt, @JsonProperty("slot") String slot,
                                @JsonProperty("buys") int buys, @JsonProperty("levels") Integer levels,
                                @JsonProperty("raises") Integer raises, @JsonProperty("finished") boolean finished,
                                @JsonProperty("plan") List<String> plan, @JsonProperty("mode") String mode) {
            int used = levels != null ? levels : raises != null ? raises * GearEnhancePlanner.STEP : 0;
            return new NightLedger(startedAt, slot, buys, used, finished, plan, mode);
        }

        public NightLedger withBuys(int newBuys) {
            return new NightLedger(startedAt, slot, newBuys, levels, finished, plan, mode);
        }

        public NightLedger withLevels(int newLevels) {
            return new NightLedger(startedAt, slot, buys, newLevels, finished, plan, mode);
        }

        /** A finished night picked up again by Run now: a fresh time cap and plan, but the same buys and levels already spent. */
        public NightLedger reopened(java.time.Instant now) {
            return new NightLedger(now.toString(), slot, buys, levels, false, List.of(), null);
        }

        public NightLedger finishedNow() {
            return new NightLedger(startedAt, slot, buys, levels, true, plan, mode);
        }

        /** Adds one line to what a practice night would have done, once. */
        public NightLedger withPlanned(String line) {
            if (plan.contains(line)) return this;
            List<String> more = new java.util.ArrayList<>(plan);
            more.add(line);
            return new NightLedger(startedAt, slot, buys, levels, finished, more, mode);
        }

        /** Live as soon as any step really presses; practice only while every step is a dry run. */
        public NightLedger withMode(boolean live) {
            String next = live || LIVE.equals(mode) ? LIVE : PRACTICE;
            return next.equals(mode) ? this : new NightLedger(startedAt, slot, buys, levels, finished, plan, next);
        }
    }

    /** The march leads' tiles from before a Quick Equip press, kept until a clean re-read. */
    public record LeadBaseline(Map<String, List<HeroGear>> leads) {
    }

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path squads;

    public HeroGearStore(Path workspaceRoot) {
        this.squads = workspaceRoot.resolve("squads");
    }

    public Optional<GearState> loadGear(long profileId) throws IOException {
        return load(squads.resolve("hero-gear-" + profileId + ".json"), GearState.class);
    }

    public Optional<Inventory> loadInventory(long profileId) throws IOException {
        return load(squads.resolve("hero-inventory-" + profileId + ".json"), Inventory.class);
    }

    public void saveGear(long profileId, GearState state) throws IOException {
        save(squads.resolve("hero-gear-" + profileId + ".json"), state);
    }

    public void saveInventory(long profileId, Inventory inventory) throws IOException {
        save(squads.resolve("hero-inventory-" + profileId + ".json"), inventory);
    }

    public Optional<GearHalt> loadHalt(long profileId) throws IOException {
        return load(haltFile(profileId), GearHalt.class);
    }

    public void saveHalt(long profileId, GearHalt halt) throws IOException {
        save(haltFile(profileId), halt);
    }

    /** Lifts a halt and what caused it; a baseline only goes if the Quick Equip check found the problem in it. */
    public void clearHalt(long profileId) throws IOException {
        if (haltFromQuickEquip(profileId)) clearLeadBaseline(profileId);
        clearRaiseInFlight(profileId);
        Files.deleteIfExists(haltFile(profileId));
    }

    private boolean haltFromQuickEquip(long profileId) {
        try {
            return loadHalt(profileId).map(halt -> GearHalt.QUICK_EQUIP.equals(halt.source())).orElse(false);
        } catch (IOException unreadable) {
            return false;
        }
    }

    public Optional<RaiseInFlight> loadRaiseInFlight(long profileId) throws IOException {
        return load(raiseFile(profileId), RaiseInFlight.class);
    }

    public void saveRaiseInFlight(long profileId, RaiseInFlight raise) throws IOException {
        save(raiseFile(profileId), raise);
    }

    public void clearRaiseInFlight(long profileId) throws IOException {
        Files.deleteIfExists(raiseFile(profileId));
    }

    public Optional<NightLedger> loadNightLedger(long profileId) throws IOException {
        return load(squads.resolve("hero-gear-night-" + profileId + ".json"), NightLedger.class);
    }

    public void saveNightLedger(long profileId, NightLedger ledger) throws IOException {
        save(squads.resolve("hero-gear-night-" + profileId + ".json"), ledger);
    }

    public Optional<LeadBaseline> loadLeadBaseline(long profileId) throws IOException {
        return load(baselineFile(profileId), LeadBaseline.class);
    }

    public void saveLeadBaseline(long profileId, Map<String, List<HeroGear>> leads) throws IOException {
        save(baselineFile(profileId), new LeadBaseline(leads));
    }

    public void clearLeadBaseline(long profileId) throws IOException {
        Files.deleteIfExists(baselineFile(profileId));
    }

    private Path haltFile(long profileId) {
        return squads.resolve("hero-gear-halt-" + profileId + ".json");
    }

    private Path raiseFile(long profileId) {
        return squads.resolve("hero-gear-raise-" + profileId + ".json");
    }

    private Path baselineFile(long profileId) {
        return squads.resolve("hero-lead-baseline-" + profileId + ".json");
    }

    private void save(Path file, Object value) throws IOException {
        Files.createDirectories(squads);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), value);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private <T> Optional<T> load(Path file, Class<T> type) throws IOException {
        return Files.exists(file) ? Optional.of(mapper.readValue(file.toFile(), type)) : Optional.empty();
    }
}
