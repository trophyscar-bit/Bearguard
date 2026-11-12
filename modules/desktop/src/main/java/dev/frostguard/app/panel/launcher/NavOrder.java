package dev.frostguard.app.panel.launcher;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Alphabetical ordering for user-facing lists: case-insensitive, ignoring leading articles and punctuation. */
public final class NavOrder {

    public static final Comparator<String> BY_NAME = Comparator
            .comparing(NavOrder::sortKey)
            .thenComparing(Comparator.naturalOrder());

    private NavOrder() {
    }

    /** Lower-cased name with leading punctuation and a leading "the/a/an" removed. */
    public static String sortKey(String name) {
        if (name == null) {
            return "";
        }
        String key = Normalizer.normalize(name, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        key = key.replaceFirst("^[^\\p{L}\\p{N}]+", "");
        key = key.replaceFirst("^(the|a|an)\\s+", "");
        return key;
    }

    public static List<String> sorted(List<String> names) {
        List<String> copy = new ArrayList<>(names);
        copy.sort(BY_NAME);
        return copy;
    }

    public static <V> LinkedHashMap<String, V> sortedByKey(Map<String, V> entries) {
        List<Map.Entry<String, V>> list = new ArrayList<>(entries.entrySet());
        list.sort(Map.Entry.comparingByKey(BY_NAME));
        LinkedHashMap<String, V> out = new LinkedHashMap<>();
        list.forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
