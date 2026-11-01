package dev.frostguard.api.squads;

import java.util.List;

/** The "All" tab of Troops Preview; header values are kept as printed ("422.3K/553.8K"). */
public record TroopOverview(String totalTroops, String injured, List<TroopStock> stocks) {
}
