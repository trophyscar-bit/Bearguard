package dev.frostguard.api.squads;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the Squads scan reads about one owned hero. Fields the scan could not read stay
 * null (or empty) rather than defaulting, so the planner never ranks a hero on a guessed value.
 *
 * @param rarity          "SSR", "SR" or "R" as printed on the hero page
 * @param troopClass      "Infantry", "Lancer" or "Marksman", taken from the expedition stat labels
 * @param stars           filled star area, measured from pixels, so 3.5 means three and a half stars lit
 * @param exploration     exploration stat label to value, e.g. "Hero Attack" -> 3528
 * @param expedition      expedition stat label to percent, e.g. "Attack" -> 175.02
 * @param exclusiveGear   exclusive gear name, or null when the hero has none or it was unreadable
 */
public record HeroRecord(
        String name,
        String rarity,
        String troopClass,
        Long power,
        Integer level,
        Double stars,
        Integer escorts,
        Long troopCapacity,
        Map<String, Long> exploration,
        Map<String, Double> expedition,
        List<HeroSkill> explorationSkills,
        List<HeroSkill> expeditionSkills,
        List<HeroGear> gear,
        String exclusiveGear,
        Integer exclusiveGearLevel) {

    /** The purple heroes worth a full read: the rally joiners and the marksman lead. */
    public static final Set<String> KEY_PURPLE_HEROES = Set.of("Bahiti", "Jasser", "Jessie", "Sergey");

    /** Gold heroes plus the key purple ones are read in full and ranked; the rest are not. */
    public static boolean isKey(String name, String rarity) {
        return "SSR".equals(rarity) || ("SR".equals(rarity) && KEY_PURPLE_HEROES.contains(name));
    }

    public boolean isKey() {
        return isKey(name, rarity);
    }

    /** The expedition skill that also applies when this hero leads a march that joins a rally. */
    public HeroSkill firstExpeditionSkill() {
        return expeditionSkills == null || expeditionSkills.isEmpty() ? null : expeditionSkills.getFirst();
    }

    public Double expeditionStat(String stat) {
        return expedition == null ? null : expedition.get(stat);
    }
}
