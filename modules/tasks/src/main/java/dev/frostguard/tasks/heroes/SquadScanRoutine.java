package dev.frostguard.tasks.heroes;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.api.squads.HeroRecord;
import dev.frostguard.api.squads.HeroSkill;
import dev.frostguard.api.squads.SavedSquad;
import dev.frostguard.api.squads.SquadSnapshot;
import dev.frostguard.api.squads.TroopOverview;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.squads.HeroCards;
import dev.frostguard.engine.squads.HeroScreenReader;
import dev.frostguard.engine.squads.SquadScanRegistry;
import dev.frostguard.engine.squads.SquadSnapshotStore;
import dev.frostguard.engine.squads.TroopsScreenReader;

/**
 * Reads every owned hero, the troop totals and the twelve saved squads for the Squads panel, once
 * a night at {@link ConfigurationKeyEnum#SQUAD_SCAN_TIME_UTC_STRING} (UTC) and whenever "Scan now" is
 * pressed. It only reads: saved squads are loaded on the Chief Profile Squads screen, which never
 * deploys, and nothing is saved there.
 */
public final class SquadScanRoutine extends DelayedTask {

    // Coordinates measured on 720x1280 frames.
    private static final PointData HEROES_BUTTON = new PointData(188, 1225);
    private static final PointData STATS_TAB = new PointData(133, 1237);
    private static final PointData SKILLS_TAB = new PointData(360, 1240);
    private static final PointData GEAR_TAB = new PointData(586, 1240);
    private static final PointData OVERALL_STATS_BUTTON = new PointData(617, 1110);
    private static final PointData POPUP_DISMISS = new PointData(120, 300);
    private static final PointData PROFILE_TROOPS_BUTTON = new PointData(270, 1235);
    private static final PointData TROOPS_SQUADS_BUTTON = new PointData(360, 1207);
    // Exploration skills down the left, expedition skills down the right, top to bottom.
    private static final PointData[] SKILL_ICONS = {
            new PointData(128, 268), new PointData(78, 428), new PointData(128, 598),
            new PointData(591, 268), new PointData(642, 428), new PointData(591, 598) };
    private static final PointData GRID_SWIPE_LOW = new PointData(360, 1000);
    private static final PointData GRID_SWIPE_HIGH = new PointData(360, 300);
    // Two cards are the same hero above this; matches between different heroes stayed under 0.45.
    private static final double SAME_HERO = 0.90;
    private static final int READ_RETRIES = 2;
    // A nightly run that starts late (the queue was busy) still counts until this long after the slot.
    private static final Duration SLOT_WINDOW = Duration.ofHours(2);
    // Holds the place of a skill position whose title could not be read, so it is never mistaken
    // for an empty position or for the next skill.
    private static final HeroSkill UNREADABLE = new HeroSkill(HeroSkill.UNREADABLE_NAME, null, null);
    private static final String DEFAULT_NIGHTLY_TIME = "07:15";
    // Queued at startup outside its slot, the nightly scan only runs when the last scan is older
    // than this; a scan from last night or a "Scan now" earlier today is left alone.
    private static final Duration FRESH_ENOUGH = Duration.ofHours(20);

    private final List<String> problems = new ArrayList<>();
    private final List<HeroRecord> heroes = new ArrayList<>();
    private final List<HeroCards.Portrait> portraits = new ArrayList<>();

    public SquadScanRoutine(AccountDescriptor profile, TpDailyTaskEnum task) {
        super(profile, task);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected boolean acceptsInjections() {
        return false;
    }

    @Override
    protected void execute() {
        boolean manual = SquadScanRegistry.takeManualRequest(profile.getId());
        boolean nightly = Boolean.TRUE.equals(
                profile.getConfig(ConfigurationKeyEnum.SQUAD_SCAN_NIGHTLY_ENABLED_BOOL, Boolean.class));
        // Book the next night's slot first, so a failed or skipped run keeps the schedule, and a
        // "Scan now" (which runs this same task) does not drop it.
        LocalTime slot = nightlyTimeUtc();
        setRecurring(nightly);
        if (nightly) reschedule(nextOccurrenceLocal(Instant.now(), slot, ZoneId.systemDefault()));

        // The scan takes the emulator for several minutes, so only a "Scan now" press runs it off
        // the night slot. The startup queue, a missed night or a preempted run retried in the
        // morning just book the next slot.
        if (!manual && !withinSlotWindow(Instant.now(), slot)) {
            logInfo("Squads scan: not the nightly slot; next scan "
                    + (nightly ? "at " + slot + " UTC" : "only on Scan now"));
            return;
        }
        if (!manual && lastScanIsFresh()) {
            logInfo("Squads scan: already scanned tonight; next nightly scan at " + slot + " UTC");
            return;
        }
        problems.clear();
        heroes.clear();
        portraits.clear();
        publish(SquadScanRegistry.State.RUNNING, "Reading heroes...", null);
        try {
            scanHeroes();
            if (heroes.isEmpty()) {
                throw new IllegalStateException("No heroes could be read: " + String.join("; ", problems));
            }
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
            publish(SquadScanRegistry.State.RUNNING, "Reading troops and saved squads...", null);
            TroopOverview troops = openTroopsPreview() ? readTroops() : null;
            List<SavedSquad> squads = troops != null ? readSavedSquads() : List.of();
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);

            SquadSnapshot snapshot = new SquadSnapshot(Instant.now().toString(), List.copyOf(heroes),
                    troops, squads, List.copyOf(problems));
            SquadSnapshotStore store = new SquadSnapshotStore(WorkspacePaths.current().root());
            if (isWorseThanSaved(store, snapshot)) {
                logWarning("Squads scan: this scan read " + heroes.size() + " heroes and "
                        + (troops == null ? "no troops" : "troops") + ", less than the saved scan; keeping the saved one. "
                        + problems.size() + " problem(s): " + String.join("; ", problems));
                publish(SquadScanRegistry.State.FAILED,
                        "This scan came back incomplete, so the last good scan was kept. Try again.", null);
                return;
            }
            store.save(profile.getId(), snapshot);
            logInfo("Squads scan saved: " + heroes.size() + " heroes, "
                    + (troops == null ? "no" : troops.stocks().size()) + " troop lines, "
                    + squads.stream().filter(s -> s.state() == SavedSquad.State.SAVED).count()
                    + " saved squads, " + problems.size() + " problem(s)");
            publish(SquadScanRegistry.State.SUCCEEDED, "Scan complete", snapshot);
        } catch (RuntimeException | IOException e) {
            publish(SquadScanRegistry.State.FAILED, e.getMessage(), null);
            throw new IllegalStateException("Squads scan failed: " + e.getMessage(), e);
        }
    }

    private boolean isWorseThanSaved(SquadSnapshotStore store, SquadSnapshot fresh) {
        try {
            return store.load(profile.getId()).map(saved -> isWorse(fresh, saved)).orElse(false);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * A scan that lost the Heroes grid or never reached troops must not replace one that read
     * them: it would blank the Squads tables until the next night.
     */
    static boolean isWorse(SquadSnapshot fresh, SquadSnapshot saved) {
        return (fresh.troops() == null && saved.troops() != null)
                || fresh.heroes().size() < saved.heroes().size();
    }

    // ── schedule ────────────────────────────────────────────────────

    private LocalTime nightlyTimeUtc() {
        String value = profile.getConfig(ConfigurationKeyEnum.SQUAD_SCAN_TIME_UTC_STRING, String.class);
        try {
            return LocalTime.parse(value == null || value.isBlank() ? DEFAULT_NIGHTLY_TIME : value.trim());
        } catch (DateTimeParseException malformed) {
            logWarning("Squads scan: time '" + value + "' is not HH:mm; using " + DEFAULT_NIGHTLY_TIME);
            return LocalTime.parse(DEFAULT_NIGHTLY_TIME);
        }
    }

    /** True from the UTC slot until {@link #SLOT_WINDOW} after it, today's slot or yesterday's. */
    static boolean withinSlotWindow(Instant now, LocalTime slotUtc) {
        ZonedDateTime nowUtc = now.atZone(ZoneOffset.UTC);
        for (int daysBack = 0; daysBack <= 1; daysBack++) {
            ZonedDateTime start = nowUtc.toLocalDate().minusDays(daysBack).atTime(slotUtc).atZone(ZoneOffset.UTC);
            if (!nowUtc.isBefore(start) && nowUtc.isBefore(start.plus(SLOT_WINDOW))) return true;
        }
        return false;
    }

    /**
     * The next time a UTC clock reads {@code slotUtc}, strictly after {@code now}, as local time
     * for the scheduler. Anchored in UTC so a daylight-saving change does not move it into the
     * slots around it, which are UTC-anchored too.
     */
    static LocalDateTime nextOccurrenceLocal(Instant now, LocalTime slotUtc, ZoneId local) {
        ZonedDateTime nowUtc = now.atZone(ZoneOffset.UTC);
        ZonedDateTime next = nowUtc.toLocalDate().atTime(slotUtc).atZone(ZoneOffset.UTC);
        if (!next.isAfter(nowUtc)) next = next.plusDays(1);
        return next.withZoneSameInstant(local).toLocalDateTime();
    }

    // On time at the nightly slot the last scan is a day old, so this only skips a second run in the
    // same night (a preempted scan retried after it already saved).
    private boolean lastScanIsFresh() {
        try {
            Optional<SquadSnapshot> last = new SquadSnapshotStore(WorkspacePaths.current().root()).load(profile.getId());
            return last.map(snapshot -> Duration.between(Instant.parse(snapshot.capturedAt()), Instant.now())
                    .compareTo(FRESH_ENOUGH) < 0).orElse(false);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    // ── heroes ──────────────────────────────────────────────────────

    private record Target(HeroCards.Portrait portrait, boolean scrolledDown) {
    }

    private void scanHeroes() {
        tapNear(HEROES_BUTTON);
        sleepTask(2000);
        if (!HeroCards.isHeroesGrid(capture())) {
            problems.add("Heroes screen did not open");
            return;
        }
        List<Target> targets = new ArrayList<>();
        collectTargets(capture(), false, targets);
        scrollGrid(true);
        collectTargets(capture(), true, targets);
        logInfo("Squads scan: " + targets.size() + " owned hero card(s) on the Heroes grid");

        for (int i = 0; i < targets.size(); i++) {
            checkPreemption();
            publish(SquadScanRegistry.State.RUNNING, "Reading hero " + (i + 1) + " of " + targets.size() + "...", null);
            readHero(targets.get(i), i + 1);
        }
        pressBack();
        sleepTask(1200);
    }

    private void collectTargets(RawImageData grid, boolean scrolledDown, List<Target> targets) {
        for (HeroCards.Card card : HeroCards.gridCards(grid)) {
            if (!HeroCards.isOwned(grid, card)) continue;
            HeroCards.Portrait portrait = HeroCards.portrait(grid, card);
            boolean seen = targets.stream()
                    .anyMatch(t -> HeroCards.similarity(t.portrait(), portrait) >= SAME_HERO);
            if (!seen) targets.add(new Target(portrait, scrolledDown));
        }
    }

    private void readHero(Target target, int number) {
        HeroCards.Card card = findOnGrid(target);
        if (card == null) {
            problems.add("Hero card " + number + " could not be found again on the Heroes grid");
            return;
        }
        tapNear(new PointData(card.centreX(), card.centreY()), 20);
        sleepTask(1800);
        if (!HeroScreenReader.isHeroPage(capture())) {
            problems.add("Hero card " + number + " did not open a hero page");
            pressBack();
            sleepTask(1200);
            return;
        }
        tapNear(STATS_TAB, 15);
        sleepTask(900);
        HeroScreenReader.Detail detail = HeroScreenReader.readDetail(capture());
        // The title's swoosh art animates in; a still frame a moment later reads cleanly.
        for (int retry = 0; retry < READ_RETRIES && (detail.name() == null || detail.rarity() == null); retry++) {
            sleepTask(700);
            detail = HeroScreenReader.readDetail(capture());
        }
        String label = detail.name() != null ? detail.name() : "hero card " + number;
        if (detail.name() == null) problems.add("Name unreadable for hero card " + number);
        if (detail.rarity() == null) problems.add("Rarity unreadable for " + label + ", so it was not read in full");

        HeroScreenReader.Stats stats = null;
        List<HeroSkill> exploration = List.of();
        List<HeroSkill> expedition = List.of();
        List<HeroGear> gear = List.of();
        HeroScreenReader.Exclusive exclusive = null;
        // Only gold heroes and the key purple ones are read in full; the rest keep their name,
        // level and power so saved squads can still name them.
        if (HeroRecord.isKey(detail.name(), detail.rarity())) {
            tapNear(OVERALL_STATS_BUTTON, 10);
            sleepTask(900);
            stats = HeroScreenReader.readStats(capture());
            if (missingExpeditionStats(stats)) {
                sleepTask(700);
                stats = HeroScreenReader.readStats(capture());
            }
            if (stats == null) problems.add("Stats pop-up unreadable for " + label);
            else if (missingExpeditionStats(stats)) {
                problems.add("Expedition stats incomplete for " + label + " (read " + stats.expedition().keySet()
                        + "), so it is left out of team rankings");
            }
            if (detail.level() == null) problems.add("Level unreadable for " + label);
            tapNear(POPUP_DISMISS, 10);
            sleepTask(600);

            tapNear(SKILLS_TAB, 15);
            sleepTask(900);
            List<HeroSkill> skills = readSkills(label, "SSR".equals(detail.rarity()));
            exploration = skills.subList(0, 3).stream().filter(s -> s != null && s != UNREADABLE).toList();
            // Joining a rally uses the first expedition skill by position. When that one could not
            // be read, a placeholder holds its place so the second skill is never taken for it.
            List<HeroSkill> right = skills.subList(3, 6);
            expedition = right.stream().filter(s -> s != null && s != UNREADABLE).toList();
            if (right.getFirst() == UNREADABLE) {
                List<HeroSkill> held = new ArrayList<>();
                held.add(UNREADABLE);
                held.addAll(expedition);
                expedition = List.copyOf(held);
            }

            tapNear(GEAR_TAB, 15);
            sleepTask(900);
            RawImageData gearFrame = capture();
            gear = HeroScreenReader.readGear(gearFrame);
            exclusive = HeroScreenReader.readExclusive(gearFrame);
        }

        heroes.add(new HeroRecord(detail.name() != null ? detail.name() : label, detail.rarity(),
                stats != null ? stats.troopClass() : null, detail.power(), detail.level(), detail.stars(),
                detail.escorts(), detail.troopCapacity(),
                stats != null ? stats.exploration() : Map.of(),
                stats != null ? stats.expedition() : Map.of(),
                exploration, expedition, gear,
                exclusive != null ? exclusive.name() : null, exclusive != null ? exclusive.level() : null));
        portraits.add(target.portrait());
        logInfo("Squads scan: read " + label + " (" + detail.rarity() + ", Lv " + detail.level()
                + ", power " + detail.power() + ")");
        pressBack();
        sleepTask(1500);
    }

    private static boolean missingExpeditionStats(HeroScreenReader.Stats stats) {
        return stats == null || stats.troopClass() == null
                || !stats.expedition().keySet().containsAll(List.of("Attack", "Lethality", "Defense", "Health"));
    }

    /**
     * Reads the six skill positions in order. A tap on an empty position leaves the previous
     * skill selected, so a repeated title means no skill there; but a tap the page has not caught
     * up with looks the same, so a repeat is tapped once more before it counts as empty. Gold
     * heroes fill all six positions, so an empty one on a gold hero is reported.
     */
    private List<HeroSkill> readSkills(String label, boolean gold) {
        List<HeroSkill> skills = new ArrayList<>();
        String previous = null;
        for (int position = 0; position < SKILL_ICONS.length; position++) {
            PointData icon = SKILL_ICONS[position];
            tapNear(icon, 12);
            sleepTask(700);
            HeroSkill skill = readSkillWithRetries();
            if (skill != null && skill.name().equals(previous)) {
                tapNear(icon, 12);
                sleepTask(900);
                HeroSkill again = readSkillWithRetries();
                if (again != null && !again.name().equals(previous)) skill = again;
            }
            if (skill == null) {
                problems.add("Skill " + (position + 1) + " of " + label + " was unreadable");
                skill = UNREADABLE;
            } else if (skill.name().equals(previous)) {
                if (gold) problems.add("Skill " + (position + 1) + " of " + label + " looked empty");
                skill = null;
            } else {
                previous = skill.name();
            }
            skills.add(skill);
        }
        return skills;
    }

    // Fire and snow particles drift across some skill titles; retry on a later frame.
    private HeroSkill readSkillWithRetries() {
        HeroSkill skill = HeroScreenReader.readSelectedSkill(capture());
        for (int retry = 0; retry < READ_RETRIES && skill == null; retry++) {
            sleepTask(600);
            skill = HeroScreenReader.readSelectedSkill(capture());
        }
        return skill;
    }

    // Coming back from a hero page the grid can still be settling, and its scroll position is
    // not always where it was. Look again where it is, then at the side the card was first seen
    // on, then at the other side.
    private HeroCards.Card findOnGrid(Target target) {
        boolean side = target.scrolledDown();
        for (int attempt = 0; attempt < 5; attempt++) {
            switch (attempt) {
                case 0 -> { }
                case 1 -> sleepTask(800);
                case 4 -> scrollGrid(!side);
                default -> scrollGrid(side);
            }
            RawImageData grid = capture();
            if (!HeroCards.isHeroesGrid(grid)) continue;
            for (HeroCards.Card card : HeroCards.gridCards(grid)) {
                if (HeroCards.similarity(HeroCards.portrait(grid, card), target.portrait()) >= SAME_HERO) {
                    return card;
                }
            }
        }
        return null;
    }

    private void scrollGrid(boolean down) {
        for (int i = 0; i < 2; i++) {
            if (down) swipe(GRID_SWIPE_LOW, GRID_SWIPE_HIGH, 1200);
            else swipe(GRID_SWIPE_HIGH, GRID_SWIPE_LOW, 1200);
            sleepTask(900);
        }
    }

    // ── troops and saved squads ─────────────────────────────────────

    private boolean openTroopsPreview() {
        tapInside(CommonGameAreas.PROFILE_AVATAR);
        sleepTask(2500);
        if (!TroopsScreenReader.isChiefProfile(capture())) {
            dismissProfilePopup();
        }
        if (!TroopsScreenReader.isChiefProfile(capture())) {
            problems.add("Chief Profile did not open, so troops and saved squads were not read");
            return false;
        }
        tapNear(PROFILE_TROOPS_BUTTON, 15);
        sleepTask(2000);
        return true;
    }

    // The profile can open behind a "You're getting popular!" card whose only button is "Amazing!".
    private void dismissProfilePopup() {
        PointData button = TroopsScreenReader.popularityCardButton(capture());
        if (button != null) {
            logInfo("Squads scan: dismissing the profile popularity card");
            tapNear(button, 10);
            sleepTask(1500);
        }
    }

    private TroopOverview readTroops() {
        TroopOverview troops = TroopsScreenReader.readPreview(capture());
        if (troops == null) problems.add("Troops Preview was not readable");
        else if (troops.stocks().isEmpty()) problems.add("No troop lines were readable on Troops Preview");
        return troops;
    }

    private List<SavedSquad> readSavedSquads() {
        List<SavedSquad> squads = new ArrayList<>();
        if (!openSquadsScreen()) {
            problems.add("The Squads screen did not open, so saved squads were not read");
            return squads;
        }
        for (int slot = 1; slot <= 12; slot++) {
            checkPreemption();
            // The formation strip only moves to its right end from its starting position, so each
            // high slot after the first starts from a freshly opened Squads screen.
            if (slot > 9) {
                pressBack();
                sleepTask(1500);
                if (!openSquadsScreen()) {
                    problems.add("The Squads screen did not reopen for squad " + slot);
                    squads.add(unreadable(slot));
                    continue;
                }
            }
            if (!marchHelper.selectFlag(slot)) {
                squads.add(new SavedSquad(slot, SavedSquad.State.EMPTY_OR_LOCKED, List.of(), List.of(),
                        List.of(), null, null));
                continue;
            }
            sleepTask(1200);
            SavedSquad read = readSquad(capture(), slot);
            // Tapping an empty slot leaves the previous squad selected, so a read identical to the
            // previous slot is usually that slot showing through. It could also be the same squad
            // saved twice, and nothing on screen tells the two apart, so it is reported as either.
            if (!squads.isEmpty() && sameSquad(squads.getLast(), read)) {
                logInfo("Squads scan: squad " + slot + " shows squad " + (slot - 1) + " again: empty, or saved twice");
                read = new SavedSquad(slot, SavedSquad.State.SAME_AS_PREVIOUS, List.of(), List.of(), List.of(), null, null);
            }
            squads.add(read);
        }
        return squads;
    }

    private static boolean sameSquad(SavedSquad a, SavedSquad b) {
        return a.state() == SavedSquad.State.SAVED && b.state() == SavedSquad.State.SAVED
                && Objects.equals(a.heroes(), b.heroes()) && Objects.equals(a.troops(), b.troops())
                && Objects.equals(a.capacity(), b.capacity()) && Objects.equals(a.power(), b.power());
    }

    private boolean openSquadsScreen() {
        tapNear(TROOPS_SQUADS_BUTTON, 15);
        sleepTask(2000);
        return TroopsScreenReader.isDeploy(capture());
    }

    private SavedSquad readSquad(RawImageData frame, int slot) {
        SavedSquad read = TroopsScreenReader.readDeploy(frame, slot);
        List<String> names = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        for (HeroCards.Card card : HeroCards.deployCards()) {
            int match = HeroCards.bestMatch(HeroCards.portrait(frame, card), portraits);
            Integer level = HeroCards.level(frame, card);
            if (match >= 0) {
                names.add(heroes.get(match).name());
                levels.add(heroes.get(match).level());
            } else if (level != null) {
                names.add(null);
                levels.add(level);
                problems.add("A hero in saved squad " + slot + " did not match any scanned hero");
            }
        }
        logInfo("Squads scan: squad " + slot + " heroes=" + names + " troops=" + read.troops());
        return new SavedSquad(slot, SavedSquad.State.SAVED, names, levels, read.troops(), read.capacity(), read.power());
    }

    private static SavedSquad unreadable(int slot) {
        return new SavedSquad(slot, SavedSquad.State.UNREADABLE, List.of(), List.of(), List.of(), null, null);
    }

    // ── helpers ─────────────────────────────────────────────────────

    private RawImageData capture() {
        return emuManager.captureScreen(EMULATOR_NUMBER);
    }

    private void publish(SquadScanRegistry.State state, String message, SquadSnapshot snapshot) {
        SquadScanRegistry.publish(new SquadScanRegistry.Event(profile.getId(), state, message, snapshot));
    }
}
