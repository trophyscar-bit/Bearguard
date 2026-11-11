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
    }

    /** An Enhance press on its way, saved before the tap and cleared once the new level shows. */
    public record RaiseInFlight(String hero, String slot, int level) {
    }

    /** What one night has used of its time cap, its buys and its enhance levels. */
    public record NightLedger(String startedAt, int buys, int levels, boolean finished) {

        // A ledger saved before levels were counted holds raises of up to five levels each.
        @JsonCreator
        static NightLedger read(@JsonProperty("startedAt") String startedAt, @JsonProperty("buys") int buys,
                                @JsonProperty("levels") Integer levels, @JsonProperty("raises") Integer raises,
                                @JsonProperty("finished") boolean finished) {
            int used = levels != null ? levels : raises != null ? raises * GearEnhancePlanner.STEP : 0;
            return new NightLedger(startedAt, buys, used, finished);
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
