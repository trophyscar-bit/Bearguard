package dev.frostguard.api.squads;

/**
 * One of the four gear slots. {@code enhancement} is the "+N" badge and {@code mastery} the
 * "Lv.N" forge level; mastery is null when the piece shows no level, and both are null for an
 * empty or unreadable slot.
 */
public record HeroGear(int slot, Integer enhancement, Integer mastery) {
}
