package dev.frostguard.api.squads;

/** One hero skill as read from the Skills tab; a null level or description was unreadable. */
public record HeroSkill(String name, Integer level, String description) {

    /** Name of a skill position whose title could not be read; it holds the position. */
    public static final String UNREADABLE_NAME = "(unreadable)";

    public boolean isUnreadable() {
        return UNREADABLE_NAME.equals(name);
    }
}
