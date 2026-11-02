package dev.frostguard.api.squads;

import java.util.List;

/**
 * One complete Squads scan for a profile.
 *
 * @param capturedAt ISO-8601 UTC instant the scan finished
 * @param problems   what the scan could not read, in plain words for the operator
 */
public record SquadSnapshot(String capturedAt, List<HeroRecord> heroes, TroopOverview troops,
                            List<SavedSquad> savedSquads, List<String> problems) {
}
