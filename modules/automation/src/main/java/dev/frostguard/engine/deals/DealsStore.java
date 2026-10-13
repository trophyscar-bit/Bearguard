package dev.frostguard.engine.deals;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import dev.frostguard.api.deals.DealScan;

/**
 * Files under {@code <workspace>/data/deals}: one scan per day with the frames behind it, the
 * curated item icon library, and the operator's own item dollar values.
 *
 * <p>Plain files rather than database rows so a misread can be checked against its saved frame,
 * and so icons and values can be corrected by editing a folder without a rebuild.</p>
 */
public final class DealsStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path root;

    public DealsStore(Path root) {
        this.root = root;
    }

    public static DealsStore forWorkspace(Path workspaceRoot) {
        return new DealsStore(workspaceRoot.resolve("data").resolve("deals"));
    }

    public Path root() {
        return root;
    }

    public Path scansDir() {
        return root.resolve("scans");
    }

    public Path frameDir(LocalDate day) {
        return scansDir().resolve(day.toString());
    }

    /** Item tiles, one PNG per item, named after the item. */
    public Path itemIconsDir() {
        return root.resolve("item-icons");
    }

    /** {@code {"1h Speedup": 0.35}} -- dollars per unit, written by the operator. */
    public Path itemValuesFile() {
        return root.resolve("item-values.json");
    }

    public void write(LocalDate day, DealScan scan) throws IOException {
        Files.createDirectories(scansDir());
        Path target = scansDir().resolve(day + ".json");
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        MAPPER.writeValue(temporary.toFile(), scan);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Scans on or after {@code from}, oldest first. Unreadable files are reported, never dropped silently. */
    public Loaded readSince(LocalDate from) {
        Map<LocalDate, DealScan> scans = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        if (!Files.isDirectory(scansDir())) {
            return new Loaded(scans, problems);
        }
        try (Stream<Path> files = Files.list(scansDir())) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                String name = file.getFileName().toString();
                LocalDate day;
                try {
                    day = LocalDate.parse(name.substring(0, name.length() - ".json".length()));
                } catch (DateTimeParseException notAScan) {
                    continue;
                }
                if (day.isBefore(from)) {
                    continue;
                }
                try {
                    scans.put(day, MAPPER.readValue(file.toFile(), DealScan.class));
                } catch (IOException unreadable) {
                    problems.add("Could not read " + name + ": " + unreadable.getMessage());
                }
            }
        } catch (IOException listFailed) {
            problems.add("Could not list " + scansDir() + ": " + listFailed.getMessage());
        }
        return new Loaded(scans, problems);
    }

    public Map<String, Double> readItemValues() throws IOException {
        if (!Files.isRegularFile(itemValuesFile())) {
            return Map.of();
        }
        Map<String, Double> values = MAPPER.readValue(itemValuesFile().toFile(),
                new TypeReference<LinkedHashMap<String, Double>>() { });
        return values == null ? Map.of() : values;
    }

    public record Loaded(Map<LocalDate, DealScan> scans, List<String> problems) {
    }
}
