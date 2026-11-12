package dev.frostguard.app.panel.heroes;

import dev.frostguard.api.squads.SavedSquad;
import dev.frostguard.api.squads.SquadSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What each squad is for, and what the last scan found in it, in words. The Squads page groups by
 * what the owner is doing, not by what the scanner read: a squad is shown by its job (rally host,
 * Bear join, troops-only join), with the heroes it holds, the troops it sends, and a plain sentence
 * when something about it looks wrong.
 */
final class SquadJobs {

    enum Level { OK, INFO, WARN }

    /** One squad with a job. {@code check} is blank when nothing is wrong. */
    record Row(int slot, String job, String heroes, String sends, String check, Level level) {
    }

    private static final String NONE = "—";

    private SquadJobs() {
    }

    /**
     * @param hosting      whether the bot starts the rally itself (squad {@code hostSlot} leads)
     * @param heroJoins    squads that join with heroes
     * @param troopJoins   squads that join with troops only
     * @param perJoin      the Troops per join setting
     */
    static List<Row> rows(SquadSnapshot snapshot, boolean hosting, int hostSlot, List<Integer> heroJoins,
                          List<Integer> troopJoins, long perJoin) {
        Map<Integer, String> jobs = new TreeMap<>();
        if (hosting) jobs.put(hostSlot, "Rally host");
        for (int s : heroJoins) jobs.putIfAbsent(s, "Bear join with heroes");
        for (int s : troopJoins) jobs.putIfAbsent(s, "Bear join, troops only");

        List<Row> out = new ArrayList<>();
        for (Map.Entry<Integer, String> job : jobs.entrySet()) {
            int slot = job.getKey();
            boolean host = hosting && slot == hostSlot;
            boolean heroJoin = !host && heroJoins.contains(slot);
            boolean troopJoin = !host && !heroJoin;
            SavedSquad squad = saved(snapshot, slot);
            if (squad == null) {
                out.add(new Row(slot, job.getValue(), NONE, NONE,
                        "Not read in the last scan. Press Scan now, or save this squad in the game.", Level.WARN));
                continue;
            }
            if (squad.state() != SavedSquad.State.SAVED) {
                out.add(new Row(slot, job.getValue(), NONE, NONE, stateWords(squad.state()), Level.WARN));
                continue;
            }
            List<String> heroes = squad.heroes() == null ? List.of() : squad.heroes();
            long total = total(squad);
            String check = "";
            Level level = Level.OK;
            if (heroJoin && heroes.isEmpty()) {
                check = "Squad " + slot + " has no heroes, so it joins with troops only. Add heroes in the game, or move it to troops-only joins.";
                level = Level.WARN;
            } else if (total == 0) {
                check = "Has no troops, so it cannot " + (host ? "host a rally." : "join.");
                level = Level.WARN;
            } else if (troopJoin && !heroes.isEmpty()) {
                check = "Its heroes are ignored: this squad joins with troops only.";
                level = Level.INFO;
            } else if (host) {
                check = "Sends as many troops as it can carry.";
            } else if (perJoin > 0 && total != perJoin) {
                check = "Sends " + count(total) + ", but Troops per join is " + count(perJoin)
                        + ". Edit the squad in the game to match.";
                level = Level.WARN;
            }
            out.add(new Row(slot, job.getValue(), heroes.isEmpty() ? NONE : String.join(", ", heroes), sends(squad), check, level));
        }
        return out;
    }

    /** The scope of the Troops per join setting, so it is clear which squads it affects. */
    static String perJoinScope(boolean hosting, int hostSlot, List<Integer> heroJoins, List<Integer> troopJoins) {
        TreeSet<Integer> joins = new TreeSet<>(heroJoins);
        joins.addAll(troopJoins);
        String who = joins.isEmpty() ? "No join squads are set." : "Applies to join squads " + list(joins) + " only.";
        String host = hosting
                ? " Squad " + hostSlot + ", your rally, always sends as many as it can carry."
                : " You are not hosting, so the rally squad is not used.";
        return who + host;
    }

    /** Saved squads the bot has no job for. */
    static String unusedNote(SquadSnapshot snapshot, boolean hosting, int hostSlot, List<Integer> heroJoins, List<Integer> troopJoins) {
        TreeSet<Integer> used = new TreeSet<>(heroJoins);
        used.addAll(troopJoins);
        if (hosting) used.add(hostSlot);
        TreeSet<Integer> idle = new TreeSet<>();
        if (snapshot != null && snapshot.savedSquads() != null) {
            for (SavedSquad s : snapshot.savedSquads()) {
                if (s.state() == SavedSquad.State.SAVED && !used.contains(s.slot())) idle.add(s.slot());
            }
        }
        return idle.isEmpty() ? "" : "Saved but not used by the bot: " + list(idle) + ".";
    }

    /** "1", "1 and 2", "1, 2 and 3". */
    static String list(Iterable<Integer> numbers) {
        List<String> parts = new ArrayList<>();
        for (Integer n : numbers) parts.add(Integer.toString(n));
        if (parts.size() <= 1) return String.join("", parts);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    private static SavedSquad saved(SquadSnapshot snapshot, int slot) {
        if (snapshot == null || snapshot.savedSquads() == null) return null;
        return snapshot.savedSquads().stream().filter(s -> s.slot() == slot).findFirst().orElse(null);
    }

    private static long total(SavedSquad squad) {
        long sum = 0;
        if (squad.troops() != null) for (Long t : squad.troops()) sum += t == null ? 0 : t;
        return sum;
    }

    private static String sends(SavedSquad squad) {
        List<Long> t = squad.troops();
        if (t == null || t.size() < 3) return NONE;
        return count(total(squad)) + " (" + count(t.get(0)) + " / " + count(t.get(1)) + " / " + count(t.get(2)) + ")";
    }

    private static String stateWords(SavedSquad.State state) {
        return switch (state) {
            case EMPTY_OR_LOCKED -> "Empty or locked in the game. Save a squad here first.";
            case SAME_AS_PREVIOUS -> "Reads the same as the squad before it, so it may be empty. Check it in the game.";
            case UNREADABLE -> "The scan could not read this squad. Press Scan now.";
            default -> "Not read in the last scan.";
        };
    }

    static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** Rows ordered by slot number (numbers carry meaning here, unlike names). */
    static Comparator<Row> bySlot() {
        return Comparator.comparingInt(Row::slot);
    }
}
