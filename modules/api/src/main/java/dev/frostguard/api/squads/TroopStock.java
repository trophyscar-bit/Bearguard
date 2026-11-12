package dev.frostguard.api.squads;

/** One troop line from Troops Preview, e.g. name "Apex Infantry", type "Infantry". */
public record TroopStock(String name, String troopType, long count) {
}
