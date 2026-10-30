package dev.frostguard.engine.alliance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import dev.frostguard.engine.alliance.AllianceSpendReport.PriceSource;
import dev.frostguard.engine.alliance.AllianceSpendReport.Purchase;

/**
 * Writes {@code data/alliance-spend/nightly/<day>.json} for the nightly review: every buyer and pack
 * name with the spellings it was read as, and every pack no deal scan or price file has priced, each
 * with the saved screens it appears on. Run from the packaged jars, so the review sees exactly the
 * names and prices the Statistics tab shows:
 *
 * <pre>java -cp "packaging/desktop/target/input/lib/*" dev.frostguard.engine.alliance.AllianceSpendNightly C:\Bearguard</pre>
 */
public final class AllianceSpendNightly {

    private AllianceSpendNightly() {
    }

    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args.length > 0 ? args[0] : System.getProperty("user.dir"));
        AllianceSpendReport report = AllianceSpendReport.load(workspace);
        Path spend = workspace.resolve("data").resolve("alliance-spend");
        List<String> passes = passNames(spend.resolve("passes"));
        LocalDateTime now = LocalDateTime.now();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", now.toString());
        out.put("purchasesLast24h", report.since(now.minusDays(1)).size());
        out.put("purchasesTotal", report.purchases().size());
        out.put("buyers", names(report.purchases(), true, passes));
        out.put("packs", names(report.purchases(), false, passes));

        Map<String, Map<String, Object>> unseen = new TreeMap<>();
        for (Purchase p : report.purchases()) {
            if (p.source() != PriceSource.TIER_ESTIMATE && p.source() != PriceSource.NONE) {
                continue;
            }
            Map<String, Object> entry = unseen.computeIfAbsent(p.pack(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pack", k);
                m.put("purchases", 0);
                m.put("tiers", new TreeSet<String>());
                m.put("readAs", new TreeSet<String>());
                m.put("lastSeen", "");
                m.put("screens", new ArrayList<String>());
                return m;
            });
            entry.put("purchases", (Integer) entry.get("purchases") + 1);
            if (p.tier() != null) {
                cast(entry.get("tiers")).add(p.tier());
            }
            cast(entry.get("readAs")).add(p.readPack());
            if (p.at().toString().compareTo((String) entry.get("lastSeen")) > 0) {
                entry.put("lastSeen", p.at().toString());
            }
            List<String> screens = castList(entry.get("screens"));
            String screen = screenOf(p, passes, spend);
            if (screen != null && screens.size() < 3 && !screens.contains(screen)) {
                screens.add(screen);
            }
        }
        List<Map<String, Object>> unseenList = new ArrayList<>(unseen.values());
        unseenList.sort((a, b) -> (Integer) b.get("purchases") - (Integer) a.get("purchases"));
        out.put("unpricedPacks", unseenList);

        Path dir = spend.resolve("nightly");
        Files.createDirectories(dir);
        Path file = dir.resolve(LocalDate.now() + ".json");
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(file.toFile(), out);
        System.out.println(file);
    }

    /** Each shown name with how often it was bought under it and every spelling folded into it. */
    private static List<Map<String, Object>> names(List<Purchase> purchases, boolean buyers, List<String> passes) {
        Map<String, Map<String, Object>> byName = new TreeMap<>();
        for (Purchase p : purchases) {
            String shown = buyers ? p.buyer() : p.pack();
            String read = buyers ? p.readBuyer() : p.readPack();
            if (shown == null || shown.isBlank()) {
                continue;
            }
            Map<String, Object> e = byName.computeIfAbsent(shown, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", k);
                m.put("purchases", 0);
                m.put("readAs", new TreeMap<String, Integer>());
                return m;
            });
            e.put("purchases", (Integer) e.get("purchases") + 1);
            @SuppressWarnings("unchecked")
            Map<String, Integer> spellings = (Map<String, Integer>) e.get("readAs");
            spellings.merge(read == null ? "" : read, 1, Integer::sum);
        }
        return new ArrayList<>(byName.values());
    }

    /** The saved screen a purchase was read from: the latest pass that started before it. */
    static String screenOf(Purchase p, List<String> passes, Path spend) {
        if (p.frame() == null) {
            return null;
        }
        String at = p.at().toString().replace(':', '-');
        String pass = null;
        for (String name : passes) {
            if (name.compareTo(at) <= 0) {
                pass = name;
            }
        }
        if (pass == null) {
            return null;
        }
        Path screen = spend.resolve("passes").resolve(pass).resolve(p.frame());
        return Files.exists(screen) ? screen.toString() : null;
    }

    private static List<String> passNames(Path passes) throws java.io.IOException {
        if (!Files.isDirectory(passes)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(passes)) {
            return dirs.map(d -> d.getFileName().toString()).sorted().toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static TreeSet<String> cast(Object o) {
        return (TreeSet<String>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object o) {
        return (List<String>) o;
    }
}
