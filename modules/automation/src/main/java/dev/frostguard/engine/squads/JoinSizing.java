package dev.frostguard.engine.squads;

import dev.frostguard.api.squads.SavedSquad;
import dev.frostguard.api.squads.SquadSnapshot;
import dev.frostguard.api.squads.TroopStock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * How big each Bear Trap join march should be. The rule: every join march targets 100k troops;
 * marksmen first (as many as are free, up to the preset's own marksmen count), then infantry and
 * lancers split 1:1 to fill the gap, one class standing in for the other when it runs short, and
 * whatever is free when there is not enough. When the player hosts, the rally squad takes its troops
 * first and the joins are planned from what is left, in flag order.
 */
public final class JoinSizing {

    public static final long TARGET = 100_000;
    /**
     * What a march carries when a preset's capacity was not read: a squad with no heroes loaded, from
     * the 14:07 frame on Nov 12 ("7,772/101,400"). It varies with the heroes: a preset with heroes
     * showed 141,610 that afternoon, so this is only the fallback.
     */
    public static final long FALLBACK_CAPACITY = 101_400;
    /** A march smaller than this is not worth a march slot. */
    public static final long MIN_USEFUL = 10_000;

    private JoinSizing() {
    }

    public record Troops(long infantry, long lancer, long marksmen) {
        public long total() {
            return infantry + lancer + marksmen;
        }

        Troops minus(Troops other) {
            return new Troops(Math.max(0, infantry - other.infantry), Math.max(0, lancer - other.lancer),
                    Math.max(0, marksmen - other.marksmen));
        }
    }

    /** One join flag, the marksmen its preset carries and what that preset can carry (0 when unread). */
    public record Join(int flag, long presetMarksmen, long capacity) {
        public Join(int flag, long presetMarksmen) {
            this(flag, presetMarksmen, 0);
        }
    }

    public record March(int flag, Troops troops) {
    }

    public record Plan(Troops host, List<March> joins, long marksmenShort, long marksmenWanted) {
        public String summary() {
            StringBuilder out = new StringBuilder();
            if (host != null) out.append("Host ").append(k(host.total())).append(" · ");
            if (joins.isEmpty()) {
                out.append("Joins: none");
            } else {
                out.append("Joins: ");
                int i = 0;
                while (i < joins.size()) {
                    long size = joins.get(i).troops().total();
                    int n = 0;
                    while (i + n < joins.size() && joins.get(i + n).troops().total() == size) n++;
                    if (i > 0) out.append(" + ");
                    out.append(n).append(" × ").append(k(size));
                    i += n;
                }
            }
            if (marksmenShort > 0) out.append(" · Marksmen short by ").append(k(marksmenShort));
            return out.toString();
        }
    }

    /** One march: marksmen first, then the 1:1 infantry/lancer fill, then whatever is free. */
    public static Troops size(Troops free, long presetMarksmen, long target) {
        long mrk = Math.min(Math.min(free.marksmen(), Math.max(0, presetMarksmen)), target);
        long gap = target - mrk;
        long wantInf = gap / 2;
        long wantLan = gap - wantInf;
        long inf = Math.min(free.infantry(), wantInf);
        long lan = Math.min(free.lancer(), wantLan);
        long left = gap - inf - lan;
        if (left > 0) { // one class is short: the other stands in
            long moreInf = Math.min(free.infantry() - inf, left);
            inf += moreInf;
            left -= moreInf;
            long moreLan = Math.min(free.lancer() - lan, left);
            lan += moreLan;
        }
        return new Troops(inf, lan, mrk);
    }

    /**
     * The whole plan. {@code host} is what the rally squad takes (null when not hosting);
     * {@code joins} are in flag order.
     */
    public static Plan plan(Troops free, Troops host, List<Join> joins, long target) {
        Troops stock = host == null ? free : free.minus(host);
        List<March> marches = new ArrayList<>();
        long wanted = 0;
        long used = 0;
        for (Join join : joins) wanted += Math.min(Math.max(0, join.presetMarksmen()), target);
        for (Join join : joins) {
            long cap = join.capacity() > 0 ? join.capacity() : FALLBACK_CAPACITY;
            Troops t = size(stock, join.presetMarksmen(), Math.min(target, cap));
            if (t.total() < Math.min(MIN_USEFUL, target)) break;
            marches.add(new March(join.flag(), t));
            used += t.marksmen();
            stock = stock.minus(t);
        }
        return new Plan(host, List.copyOf(marches), Math.max(0, wanted - used), wanted);
    }

    /** The size of each join march: the setting, or the default when it is unset. Each preset's own capacity caps it. */
    public static long perJoinTarget(long setting) {
        return setting <= 0 ? TARGET : setting;
    }

    /** "69,698/126,850" -> 126,850; 0 when the capacity was not read. */
    static long capacityOf(String text) {
        if (text == null) return 0;
        int slash = text.indexOf('/');
        String digits = (slash < 0 ? text : text.substring(slash + 1)).replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 9) return 0;
        return Long.parseLong(digits);
    }

    /**
     * The plan from the night's scan: the troops at home from Troops Preview, the host squad's saved
     * troops when hosting, and each join flag's saved marksmen. Flags are taken in numeric order.
     */
    public static Plan fromSnapshot(SquadSnapshot snapshot, boolean hosting, int hostFlag,
                                    List<Integer> heroFlags, List<Integer> troopFlags, long perJoin) {
        long inf = 0;
        long lan = 0;
        long mrk = 0;
        if (snapshot.troops() != null) {
            for (TroopStock stock : snapshot.troops().stocks()) {
                String type = stock.troopType() == null ? "" : stock.troopType().toLowerCase(Locale.ROOT);
                if (type.contains("infantry")) inf += stock.count();
                else if (type.contains("lancer")) lan += stock.count();
                else if (type.contains("marksm")) mrk += stock.count();
            }
        }
        Troops free = new Troops(inf, lan, mrk);
        Troops host = hosting ? savedTroops(snapshot, hostFlag) : null;
        java.util.TreeSet<Integer> flags = new java.util.TreeSet<>(heroFlags);
        flags.addAll(troopFlags);
        List<Join> joins = new ArrayList<>();
        for (int flag : flags) {
            Troops saved = savedTroops(snapshot, flag);
            joins.add(new Join(flag, saved == null ? 0 : saved.marksmen(), savedCapacity(snapshot, flag)));
        }
        return plan(free, host, joins, perJoinTarget(perJoin));
    }

    private static long savedCapacity(SquadSnapshot snapshot, int slot) {
        for (SavedSquad squad : snapshot.savedSquads()) {
            if (squad.slot() == slot) return capacityOf(squad.capacity());
        }
        return 0;
    }

    private static Troops savedTroops(SquadSnapshot snapshot, int slot) {
        for (SavedSquad squad : snapshot.savedSquads()) {
            if (squad.slot() == slot && squad.state() == SavedSquad.State.SAVED
                    && squad.troops() != null && squad.troops().size() >= 3) {
                return new Troops(squad.troops().get(0), squad.troops().get(1), squad.troops().get(2));
            }
        }
        return null;
    }

    /**
     * The hero to put in an empty slot when the preset's own hero is already marching: the free hero
     * of the same class with the best offense. Empty when none is free.
     */
    public static Optional<String> bestFreeHero(String troopClass, List<SquadPlanner.HeroScore> scores, Set<String> busy) {
        SquadPlanner.HeroScore best = null;
        for (SquadPlanner.HeroScore s : scores) {
            if (busy.contains(s.hero()) || !s.troopClass().equalsIgnoreCase(troopClass)) continue;
            if (best == null || s.offense() > best.offense()) best = s;
        }
        return Optional.ofNullable(best).map(SquadPlanner.HeroScore::hero);
    }

    static String k(long n) {
        if (n < 1000) return Long.toString(n);
        String s = String.format(Locale.ROOT, "%.1f", n / 1000.0);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + "k";
    }
}
