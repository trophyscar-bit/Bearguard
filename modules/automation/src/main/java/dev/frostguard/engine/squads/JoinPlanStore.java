package dev.frostguard.engine.squads;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;

/**
 * The Bear Trap join plan the nightly scan worked out, kept per profile next to the squads snapshot.
 * A plan that differs from the night before is remembered as changed so the Squads page can say so.
 */
public final class JoinPlanStore {

    /** @param changedAt when the summary last differed from the one before it; null until it has. */
    public record Stored(String computedAt, String summary, String previousSummary, String changedAt, long perJoin) {
    }

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path root;

    public JoinPlanStore(Path workspaceRoot) {
        this.root = workspaceRoot.resolve("squads");
    }

    /** Stores tonight's plan and returns it with what changed since the last one. */
    public Stored save(long profileId, JoinSizing.Plan plan, long perJoin, Instant now) throws IOException {
        String summary = plan.summary();
        Optional<Stored> before = load(profileId);
        Stored stored;
        if (before.isPresent() && before.get().summary().equals(summary) && before.get().perJoin() == perJoin) {
            Stored b = before.get();
            stored = new Stored(now.toString(), summary, b.previousSummary(), b.changedAt(), perJoin);
        } else if (before.isPresent()) {
            stored = new Stored(now.toString(), summary, before.get().summary(), now.toString(), perJoin);
        } else {
            stored = new Stored(now.toString(), summary, null, null, perJoin);
        }
        Files.createDirectories(root);
        Path target = file(profileId);
        Path temp = root.resolve(target.getFileName() + ".tmp");
        mapper.writeValue(temp.toFile(), stored);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return stored;
    }

    public Optional<Stored> load(long profileId) throws IOException {
        Path file = file(profileId);
        if (!Files.exists(file)) return Optional.empty();
        return Optional.of(mapper.readValue(file.toFile(), Stored.class));
    }

    private Path file(long profileId) {
        return root.resolve("join-plan-" + profileId + ".json");
    }
}
