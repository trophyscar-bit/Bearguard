package dev.frostguard.engine.squads;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import dev.frostguard.api.squads.SquadSnapshot;

/** Keeps the latest Squads scan per profile as JSON under the workspace's {@code squads/} folder. */
public final class SquadSnapshotStore {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path root;

    public SquadSnapshotStore(Path workspaceRoot) {
        this.root = workspaceRoot.resolve("squads");
    }

    public void save(long profileId, SquadSnapshot snapshot) throws IOException {
        Files.createDirectories(root);
        Path target = file(profileId);
        Path temp = root.resolve(target.getFileName() + ".tmp");
        mapper.writeValue(temp.toFile(), snapshot);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public Optional<SquadSnapshot> load(long profileId) throws IOException {
        Path file = file(profileId);
        if (!Files.exists(file)) return Optional.empty();
        return Optional.of(mapper.readValue(file.toFile(), SquadSnapshot.class));
    }

    private Path file(long profileId) {
        return root.resolve("profile-" + profileId + ".json");
    }
}
