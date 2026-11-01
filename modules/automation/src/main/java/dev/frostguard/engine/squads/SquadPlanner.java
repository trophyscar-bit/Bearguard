package dev.frostguard.engine.squads;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.squads.HeroRecord;
import dev.frostguard.api.squads.HeroSkill;
import dev.frostguard.api.squads.SquadSnapshot;

/**
 * Turns a Squads scan into rankings and twelve draft squads. Every score is built only from
 * values the scan read; a hero missing the value a score needs is left out of that ranking.
 *
 * <ul>
 *   <li>Offense = expedition Attack% + Lethality%; defense = Defense% + Health%. Those stats only
 *       buff the hero's own troop type, so teams take the best hero of each type.</li>
 *   <li>Joining a rally applies only the first expedition skill of the joining march's first
 *       hero, so joiners are ranked on that one skill. Its value is the sum of the percentages at
 *       the current level ("Defense by 4% and Health by 4%" is 8), scaled by any "N% chance".</li>
 *   <li>A skill that names one troop type ("his Infantry's Damage Dealt") only helps that type.
 *       Bear Trap rallies are filled with marksmen, so Bear joins skip skills limited to infantry
 *       or lancers; elsewhere the limit is shown next to the skill.</li>
 * </ul>
 */
public final class SquadPlanner {

    private static final List<String> CLASS_ORDER = List.of("Infantry", "Lancer", "Marksman");
    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)%");
    private static final Pattern CHANCE = Pattern.compile("(\\d+(?:\\.\\d+)?)%\\s+chance");
    // A percentage of a normal hit: "dealing 120% damage", "120% Attack damage".
    private static final Pattern DAMAGE_HIT = Pattern.compile("\\s+(?:of\\s+)?(?:attack\\s+)?damage", Pattern.CASE_INSENSITIVE);

    private SquadPlanner() {
    }

    public enum Effect {
        OFFENSE,
        DEFENSE,
        GATHERING,
        OTHER
    }

    public record HeroScore(String hero, String troopClass, double offense, double defense) {
    }

    /** {@code onlyFor} is the one troop type the skill names, or null when it helps every type. */
    public record JoinerScore(String hero, String skill, Integer level, Effect effect, double value, String onlyFor) {
    }

    public record Suggestion(int slot, String purpose, List<String> heroes, String why) {
    }

    public record Plan(List<HeroScore> heroScores, List<JoinerScore> joiners, List<String> exploration,
                       List<Suggestion> squads) {
    }

    public static Plan plan(SquadSnapshot snapshot) {
        List<HeroRecord> eligible = snapshot.heroes().stream().filter(HeroRecord::isKey).toList();
        List<HeroScore> scores = eligible.stream()
                .map(SquadPlanner::score).filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(HeroScore::offense).reversed()).toList();
        List<JoinerScore> joiners = eligible.stream()
                .map(SquadPlanner::joiner).filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(JoinerScore::value).reversed()).toList();
        List<String> exploration = eligible.stream().filter(h -> h.power() != null)
                .sorted(Comparator.comparingLong(HeroRecord::power).reversed())
                .limit(5).map(HeroRecord::name).toList();

        List<String> offenseTeam = team(scores, Comparator.comparingDouble(HeroScore::offense), Set.of());
        List<String> defenseTeam = team(scores, Comparator.comparingDouble(HeroScore::defense), Set.of());
        List<String> secondTeam = team(scores, Comparator.comparingDouble(HeroScore::offense), new HashSet<>(offenseTeam));
        // A hero marches in one squad at a time, so a join march never takes a hero the lead or
        // garrison team it runs alongside already uses.
        List<JoinerScore> bearJoiners = joiners.stream()
                .filter(j -> j.effect() == Effect.OFFENSE && !offenseTeam.contains(j.hero()))
                .filter(j -> j.onlyFor() == null || "Marksman".equals(j.onlyFor())).toList();
        List<JoinerScore> defenseJoiners = joiners.stream()
                .filter(j -> j.effect() == Effect.DEFENSE && !defenseTeam.contains(j.hero())).toList();
        List<String> gatherers = joiners.stream().filter(j -> j.effect() == Effect.GATHERING)
                .map(JoinerScore::hero).limit(1).toList();
        if (gatherers.isEmpty()) gatherers = gatheringHeroes(eligible);

        String leadWhy = "Best expedition Attack% + Lethality% for each troop type.";
        String joinWhy = "Joining only uses this hero's first expedition skill; the other hero slots add nothing.";
        List<Suggestion> squads = new ArrayList<>();
        squads.add(new Suggestion(1, "Bear Trap - lead", offenseTeam, leadWhy));
        squads.add(joinSuggestion(2, "Bear Trap - join 1", bearJoiners, 0, joinWhy));
        squads.add(joinSuggestion(3, "Bear Trap - join 2", bearJoiners, 1, joinWhy));
        squads.add(joinSuggestion(4, "Bear Trap - join 3", bearJoiners, 2, joinWhy));
        squads.add(new Suggestion(5, "Attack rally lead (Crazy Joe, Polar Terror, SvS)", offenseTeam, leadWhy));
        squads.add(new Suggestion(6, "Field attack (solo march)", offenseTeam, leadWhy));
        squads.add(new Suggestion(7, "City garrison - defense", defenseTeam,
                "Best expedition Defense% + Health% for each troop type."));
        squads.add(joinSuggestion(8, "Reinforce a garrison - join", defenseJoiners, 0, joinWhy));
        squads.add(joinSuggestion(9, "Crazy Joe reinforce - join", defenseJoiners, 1, joinWhy));
        squads.add(new Suggestion(10, "Gathering", gatherers, gatherers.isEmpty()
                ? "No scanned skill mentions gathering." : "Expedition skill mentions gathering."));
        squads.add(new Suggestion(11, "Beast hunting (second team)", secondTeam,
                "Next best per troop type, so the main team stays free."));
        squads.add(new Suggestion(12, "Spare", List.of(), "Left open for you."));
        return new Plan(scores, joiners, exploration, squads);
    }

    static HeroScore score(HeroRecord hero) {
        Double attack = hero.expeditionStat("Attack"), lethality = hero.expeditionStat("Lethality");
        Double defense = hero.expeditionStat("Defense"), health = hero.expeditionStat("Health");
        if (hero.troopClass() == null || attack == null || lethality == null || defense == null || health == null) {
            return null;
        }
        return new HeroScore(hero.name(), hero.troopClass(), attack + lethality, defense + health);
    }

    static JoinerScore joiner(HeroRecord hero) {
        HeroSkill skill = hero.firstExpeditionSkill();
        if (skill == null || skill.description() == null) return null;
        Valued valued = evaluate(skill.description());
        if (valued == null) return null;
        return new JoinerScore(hero.name(), skill.name(), skill.level(), valued.effect(), valued.value(),
                onlyFor(skill.description()));
    }

    /** The single troop type a skill names ("his Infantry's Damage Dealt"), or null. */
    static String onlyFor(String description) {
        String text = description.toLowerCase(Locale.ROOT);
        String found = null;
        for (String troopClass : CLASS_ORDER) {
            String word = troopClass.toLowerCase(Locale.ROOT);
            boolean named = text.contains(word) || ("Marksman".equals(troopClass) && text.contains("marksmen"));
            if (!named) continue;
            if (found != null) return null;
            found = troopClass;
        }
        return found;
    }

    static Effect classify(String description) {
        String text = description.toLowerCase(Locale.ROOT);
        boolean lowers = text.contains("reduc") || text.contains("decreas") || text.contains("lower");
        boolean aimsAtEnemy = text.contains("enemy") || text.contains("enemies") || text.contains("target");
        if (text.contains("gathering")) return Effect.GATHERING;
        // Less damage taken protects whoever takes it; more damage taken is a curse on the enemy.
        // ("Clouds enemy sights ... reducing all troops' Damage Taken" is still defense.)
        if (text.contains("damage taken")) return lowers ? Effect.DEFENSE : Effect.OFFENSE;
        if (lowers && aimsAtEnemy && (text.contains("attack") || text.contains("damage"))) return Effect.DEFENSE;
        if (text.contains("defense") || text.contains("health")) return Effect.DEFENSE;
        if (text.contains("attack") || text.contains("lethality") || text.contains("damage")) return Effect.OFFENSE;
        return Effect.OTHER;
    }

    record Valued(Effect effect, double value) {
    }

    static Double expectedValue(String description) {
        Valued valued = evaluate(description);
        return valued == null ? null : valued.value();
    }

    /**
     * Each percentage at the current level is classified by the words just before it, so "Attack
     * by 3% and Defense by 2%" is 3 offense and 2 defense, not 5 of one kind. Percentages of the
     * same kind add up ("Defense by 4% and Health by 4%" is 8); the kind with the larger total is
     * the skill's effect. "Dealing 120% damage" is a 20% gain over a normal hit. The total is
     * scaled by any "N% chance" in the text.
     */
    static Valued evaluate(String description) {
        Matcher chance = CHANCE.matcher(description);
        Double chancePercent = null;
        int chanceStart = -1, from = 0;
        if (chance.find()) {
            chancePercent = Double.parseDouble(chance.group(1));
            chanceStart = chance.start();
        }
        Map<Effect, Double> totals = new EnumMap<>(Effect.class);
        Effect previous = null;
        Matcher percent = PERCENT.matcher(description);
        while (percent.find()) {
            if (percent.start() == chanceStart) {
                from = percent.end();
                continue;
            }
            double value = Double.parseDouble(percent.group(1));
            if (value >= 100 && DAMAGE_HIT.matcher(description.substring(percent.end())).lookingAt()) value -= 100;
            Effect effect = classify(description.substring(from, percent.start()));
            if (effect == Effect.OTHER) effect = previous != null ? previous : classify(description);
            totals.merge(effect, value, Double::sum);
            previous = effect;
            from = percent.end();
        }
        if (totals.isEmpty()) return null;
        Map.Entry<Effect, Double> best = totals.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        double value = chancePercent == null ? best.getValue() : best.getValue() * chancePercent / 100.0;
        return new Valued(best.getKey(), value);
    }

    private static List<String> team(List<HeroScore> scores, Comparator<HeroScore> by, Set<String> exclude) {
        List<String> team = new ArrayList<>();
        for (String troopClass : CLASS_ORDER) {
            scores.stream()
                    .filter(s -> troopClass.equals(s.troopClass()) && !exclude.contains(s.hero()))
                    .max(by).ifPresent(s -> team.add(s.hero()));
        }
        return team;
    }

    private static Suggestion joinSuggestion(int slot, String purpose, List<JoinerScore> ranked, int index, String why) {
        if (index >= ranked.size()) {
            return new Suggestion(slot, purpose, List.of(), "No more heroes with a fitting first expedition skill.");
        }
        JoinerScore pick = ranked.get(index);
        return new Suggestion(slot, purpose, List.of(pick.hero()),
                why + " " + pick.skill() + " Lv " + pick.level() + ": " + format(pick.value()) + "%"
                        + (pick.onlyFor() == null ? "." : ", " + pick.onlyFor() + " only."));
    }

    private static List<String> gatheringHeroes(List<HeroRecord> heroes) {
        for (HeroRecord hero : heroes) {
            if (hero.expeditionSkills() == null) continue;
            for (HeroSkill skill : hero.expeditionSkills()) {
                if (skill.description() != null && skill.description().toLowerCase(Locale.ROOT).contains("gathering")) {
                    return List.of(hero.name());
                }
            }
        }
        return List.of();
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }
}
