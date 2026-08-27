package dev.frostguard.engine.chat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The translation models on this machine, opened as they are first needed.
 *
 * <p>Four of them: a general one that reads every language the game ships in bar three, and one
 * each for Korean, Japanese and Chinese, which the general one reads badly enough to be worse than
 * useless. Roughly a hundred megabytes each, so they are opened on first use rather than at
 * startup -- an alliance that never speaks Japanese should never pay for the Japanese model.
 *
 * <p>A model that will not open is remembered as absent rather than retried. The failure is a
 * missing or broken file, which does not repair itself between one message and the next, and
 * retrying it per message would turn one problem into a stall.
 */
final class TranslationModels implements AutoCloseable {

    private final Path root;
    private final Map<String, Optional<OfflineTranslator>> opened = new ConcurrentHashMap<>();

    TranslationModels(Path root) {
        this.root = root;
    }

    /** The directory a script's model lives in: "" is the general one, "ko" is model-ko. */
    private Path dirFor(String script) {
        if (script.isEmpty()) {
            return root;
        }
        Path sibling = root.resolveSibling(root.getFileName() + "-" + script);
        return Files.isDirectory(sibling) ? sibling : root;
    }

    Optional<String> translate(String body) {
        String script = OfflineTranslator.scriptOf(body);
        Optional<String> out = using(script).flatMap(t -> t.toEnglish(body));
        if (out.isPresent() || script.isEmpty()) {
            return out;
        }
        // A dedicated model that is not installed should not cost the message its translation --
        // the general one reads CJK poorly, and poorly still beats not at all.
        return using("").flatMap(t -> t.toEnglish(body));
    }

    private Optional<OfflineTranslator> using(String script) {
        return opened.computeIfAbsent(script, key -> {
            Path dir = dirFor(key);
            if (!OfflineTranslator.isAvailable(dir)) {
                return Optional.empty();
            }
            try {
                return Optional.of(new OfflineTranslator(dir));
            } catch (Exception cannotOpen) {
                return Optional.empty();
            }
        });
    }

    /** Where the models sit, in a checkout or an installation. */
    static Path defaultRoot() {
        Path here = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path up = here; up != null; up = up.getParent()) {
            for (Path candidate : new Path[] {
                    up.resolve("lib").resolve("translate"),
                    up.resolve("tools").resolve("translate").resolve("model")}) {
                if (OfflineTranslator.isAvailable(candidate)) {
                    return candidate;
                }
            }
        }
        return here.resolve("lib").resolve("translate");
    }

    @Override
    public void close() {
        opened.values().forEach(o -> o.ifPresent(OfflineTranslator::close));
        opened.clear();
    }
}
