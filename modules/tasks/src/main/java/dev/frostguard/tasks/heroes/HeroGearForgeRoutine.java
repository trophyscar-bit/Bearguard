package dev.frostguard.tasks.heroes;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.squads.ForgeDialogReader;
import dev.frostguard.engine.squads.ForgeDialogReader.ForgeDialog;
import dev.frostguard.engine.squads.GearDetailsNavigator;
import dev.frostguard.engine.squads.GearEnhancePass;
import dev.frostguard.engine.squads.GearAmount;
import dev.frostguard.engine.squads.GearHeroes;
import dev.frostguard.engine.squads.GearOrder;
import dev.frostguard.engine.squads.GearManualRun;
import dev.frostguard.engine.squads.GearEnhanceStep;
import dev.frostguard.engine.squads.GearForgeGuard;
import dev.frostguard.engine.squads.GearRanker;
import dev.frostguard.engine.squads.GearRanker.GearState;
import dev.frostguard.engine.squads.GearRanker.Upgrade;
import dev.frostguard.engine.squads.GearScan;
import dev.frostguard.engine.squads.GearScanScreens;
import dev.frostguard.engine.squads.HeroCards;
import dev.frostguard.engine.squads.HeroGearStore;
import dev.frostguard.engine.squads.HeroScreenReader;
import dev.frostguard.engine.squads.QuickEquipPass;
import dev.frostguard.engine.squads.QuickEquipScreens;

/**
 * Once a night, buys the top Mastery Forging upgrades from {@link GearRanker} while the stones
 * cover them, saving for the top item otherwise. With
 * {@link ConfigurationKeyEnum#HERO_GEAR_FORGE_LIVE_BOOL} off it opens the dialog, logs what it
 * would do and presses nothing. When the saved state is missing or stale it first runs a read-only
 * {@link GearScan}, live or not; a scan that stops means nothing is bought that night. With
 * {@link ConfigurationKeyEnum#HERO_QUICK_EQUIP_ENABLED_BOOL} on, a {@link QuickEquipPass} runs before
 * all of that, whether or not buying is live, since Quick Equip spends nothing. With
 * {@link ConfigurationKeyEnum#HERO_GEAR_ENHANCE_ENABLED_BOOL} on, a {@link GearEnhanceStep} runs after
 * the scan and before the forge; a stop from it means nothing is forged that night. Damage to a lead
 * or an unconfirmed Enhance press saves a halt, and while one is saved nothing is pressed on any run.
 * A {@link NightBudget} carries the time cap and the counts across re-runs of the same night.
 *
 * <p>Every tap follows a read confirming the screen it expects. The forge's only spending tap is
 * Forge, after {@link GearForgeGuard} passes. Reforge, Ascend, Unequip and Swap are never tapped.
 */
public final class HeroGearForgeRoutine extends DelayedTask {

    // Coordinates measured on 720x1280 frames.
    private static final PointData HEROES_BUTTON = new PointData(188, 1225);
    private static final int READ_RETRIES = 3;
    private static final int SETTLE_RETRIES = 3;
    private static final String DEFAULT_TIME = "02:30";
    // Room for the walk to and from a piece, so a raise started just in time can't eat into the reserve.
    private static final Duration WALK_MARGIN = Duration.ofMinutes(1);
    private static final String SCAN = "scan";
    private static final String QUICK_EQUIP = "quickequip";
    private static final String ENHANCE = "enhance";
    private static final String FORGE = "forge";

    private final GearFailureFrames frames = GearFailureFrames.alwaysOn();
    private Instant deadline;
    private NightBudget budget;

    public HeroGearForgeRoutine(AccountDescriptor profile, TpDailyTaskEnum task) {
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
        boolean enabled = Boolean.TRUE.equals(
                profile.getConfig(ConfigurationKeyEnum.HERO_GEAR_FORGE_ENABLED_BOOL, Boolean.class));
        LocalTime slot = slotUtc();
        // Run now: not bound to the slot window, and a finished night is picked up again with what it already spent.
        boolean manual = GearManualRun.consume(profile.getId()) && enabled;
        setRecurring(enabled);
        if (enabled) reschedule(SquadScanRoutine.nextOccurrenceLocal(Instant.now(), slot, ZoneId.systemDefault()));
        if (!enabled || !manual && !SquadScanRoutine.withinSlotWindow(Instant.now(), slot)) {
            logInfo("Gear forge: not the nightly slot");
            return;
        }
        HeroGearStore store = new HeroGearStore(WorkspacePaths.current().root());
        try {
            GearNight.run(new GearNight.Steps() {
                @Override
                public void halted(HeroGearStore.GearHalt halt) {
                    logWarning("Gear forge: halted since " + halt.at() + ", nothing pressed until it is cleared on the Hero Gear tab: "
                            + halt.reason());
                }

                @Override
                public void haltUnsaved(HeroGearStore.GearHalt halt, IOException cause) {
                    logError("Gear forge: could not save the halt, nothing more pressed this run: " + halt.reason(), cause);
                }

                @Override
                public void alreadyFinished() {
                    logInfo("Gear forge: already finished tonight");
                }

                @Override
                public void begin(NightBudget night) {
                    budget = night;
                    deadline = night.deadline();
                }

                @Override
                public void baselineUnchecked() {
                    logWarning("Gear forge: a Quick Equip baseline is still unchecked, Enhance and Forge skipped this run");
                }

                @Override
                public String quickEquip() throws IOException {
                    return HeroGearForgeRoutine.this.quickEquip(store);
                }

                @Override
                public GearState scanIfDue() throws IOException {
                    return HeroGearForgeRoutine.this.scanIfDue(store);
                }

                @Override
                public GearNight.Enhanced enhance(GearState state) {
                    return HeroGearForgeRoutine.this.enhance(store, state);
                }

                @Override
                public void forge(GearState state) throws IOException {
                    HeroGearForgeRoutine.this.forgeNight(store, state);
                }

                @Override
                public void home() {
                    navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
                }

                @Override
                public void backOutHome() {
                    HeroGearForgeRoutine.this.backOutHome();
                }
            }, store, profile.getId(), slot, Instant::now, manual);
        } catch (IOException e) {
            logWarning("Gear forge: gear state unreadable or unsaved: " + e.getMessage());
        }
    }

    private boolean quickEquipOn() {
        return Boolean.TRUE.equals(profile.getConfig(ConfigurationKeyEnum.HERO_QUICK_EQUIP_ENABLED_BOOL, Boolean.class));
    }

    private LocalTime slotUtc() {
        String value = profile.getConfig(ConfigurationKeyEnum.HERO_GEAR_FORGE_TIME_UTC_STRING, String.class);
        try {
            return LocalTime.parse(value == null || value.isBlank() ? DEFAULT_TIME : value.trim());
        } catch (DateTimeParseException malformed) {
            logWarning("Gear forge: time '" + value + "' is not HH:mm; using " + DEFAULT_TIME);
            return LocalTime.parse(DEFAULT_TIME);
        }
    }

    /** The state to work from, or null when a due scan stopped. */
    private GearState scanIfDue(HeroGearStore store) throws IOException {
        GearState state = store.loadGear(profile.getId()).orElse(null);
        return GearScan.isDue(state, Instant.now(), intSetting(ConfigurationKeyEnum.HERO_GEAR_FORGE_SCAN_DAYS_INT))
                ? scan(store, state) : state;
    }

    private GearNight.Enhanced enhance(HeroGearStore store, GearState state) {
        if (!Boolean.TRUE.equals(profile.getConfig(ConfigurationKeyEnum.HERO_GEAR_ENHANCE_ENABLED_BOOL, Boolean.class))) {
            return new GearNight.Enhanced(state, null);
        }
        frames.startStep();
        if (!openHeroesGrid()) {
            logWarning("Gear enhance: Heroes grid did not open, nothing forged tonight; "
                    + frames.stopped(ENHANCE, "Heroes grid did not open"));
            return new GearNight.Enhanced(null, null);
        }
        int levelsLeft = budget.levelsLeft(enhanceAmount().levels());
        if (levelsLeft < 1) {
            logInfo("Gear enhance: tonight's levels are used up");
            return new GearNight.Enhanced(state, null);
        }
        Instant reserveAt = budget.reserveAt();
        Instant stopBy = reserveAt.minus(WALK_MARGIN);
        GearEnhanceStep.Settings settings = new GearEnhanceStep.Settings(tickedHeroes(state),
                levelsLeft,
                Boolean.TRUE.equals(profile.getConfig(ConfigurationKeyEnum.HERO_GEAR_ENHANCE_LIVE_BOOL, Boolean.class)),
                stopBy);
        GearEnhanceStep.Outcome outcome = GearEnhanceStep.run(state, settings,
                new GearDetailsNavigator(gearDevice(ENHANCE, reserveAt)), budget.counted(new GearEnhancePass(enhanceDevice(store, stopBy))::run),
                store, profile.getId(), Instant::now);
        logInfo("Gear enhance: " + outcome.raises() + " raises confirmed");
        if (outcome.note() != null) logInfo("Gear enhance: " + outcome.note());
        keepPlan(settings.live(), outcome.note() != null && outcome.note().startsWith(WOULD_RAISE)
                ? "raise " + outcome.note().substring(WOULD_RAISE.length()) : null);
        if (outcome.stop() != null) {
            GearNight.Enhanced next = GearNight.afterEnhanceStop(outcome);
            logWarning("Gear enhance: stopped, " + (next.state() != null ? "forge still runs its own checks" : "nothing forged tonight")
                    + ": " + outcome.stop() + "; " + frames.stopped(ENHANCE, outcome.stop()));
            return next;
        }
        return new GearNight.Enhanced(outcome.state(), null);
    }

    private static final String WOULD_RAISE = "would raise ";

    /** Saves how this step ran and, for a dry run, what it would have done. A failed save only loses the report. */
    private void keepPlan(boolean live, String planned) {
        try {
            budget.markMode(live);
            if (planned != null) budget.recordPlan(planned);
        } catch (IOException e) {
            logWarning("Gear night: could not save the practice report: " + e.getMessage());
        }
    }

    // No tap on this path: the screen may be a dialog or prompt left by an unconfirmed press.
    private void backOutHome() {
        boolean settled = GearNight.backOut(this::onHomeOrWorld, () -> {
            pressBack();
            sleepTask(1500);
        });
        if (!settled) logWarning("Gear enhance: Back did not reach Home after an unconfirmed press; no tap made");
    }

    private boolean onHomeOrWorld() {
        return templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_FURNACE, SearchConfigConstants.DEFAULT_SINGLE).isFound()
                || templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_WORLD, SearchConfigConstants.DEFAULT_SINGLE).isFound();
    }

    private void forgeNight(HeroGearStore store, GearState saved) throws IOException {
        GearState state = saved;
        boolean live = Boolean.TRUE.equals(
                profile.getConfig(ConfigurationKeyEnum.HERO_GEAR_FORGE_LIVE_BOOL, Boolean.class));
        int maxBuys = forgeAmount().forges();
        int reserve = intSetting(ConfigurationKeyEnum.HERO_GEAR_FORGE_RESERVE_INT);
        GearOrder.plan(ranked(state), forgeOrder(), 10).forEach(upgrade -> logInfo("Gear forge, rolling: " + upgrade));
        frames.startStep();
        if (!openHeroesGrid()) {
            forgeStopped("Heroes grid did not open");
            return;
        }
        while (budget.buysLeft(maxBuys) > 0) {
            checkPreemption();
            if (pastCap()) {
                logWarning("Gear forge: time cap reached, stopping");
                return;
            }
            List<Upgrade> rolling = GearOrder.plan(ranked(state), forgeOrder(), 1);
            if (rolling.isEmpty()) return;
            Upgrade next = rolling.getFirst();
            if (!openForgeDialog(next)) return;
            ForgeDialog before = readDialog();
            GearForgeGuard.Check check = GearForgeGuard.before(next, before, reserve);
            logInfo("Gear forge: " + check.verdict() + ": " + check.reason());
            switch (check.verdict()) {
                case STOP -> {
                    forgeStopped(check.reason());
                    return;
                }
                case SAVE -> {
                    // Practice reports the same stone check a real night makes, so it never claims a forge it could not afford.
                    if (!live) keepPlan(false, "skip forge (" + before.stonesHave() + " of " + (before.stonesNeeded() + reserve) + " stones)");
                    return;
                }
                case RESYNC -> {
                    store.saveGear(profile.getId(), state.withMastery(next.hero(), next.slot(), before.masteryPercent()));
                    return;
                }
                case BUY -> {
                    if (!live) {
                        keepPlan(false, "forge " + next.hero() + " " + next.slot() + " " + next.fromMastery() + "% to "
                                + next.toMastery() + "% (" + next.stones() + " stones)");
                        return;
                    }
                    keepPlan(true, null);
                    if (!budget.spendBuy(maxBuys, Instant.now())) {
                        logWarning("Gear forge: time cap reached, stopping");
                        return;
                    }
                    if (!forge(before)) return;
                    state = state.withMastery(next.hero(), next.slot(), next.toMastery());
                    store.saveGear(profile.getId(), state);
                    if (!closeToGrid()) {
                        forgeStopped("could not get back to the Heroes grid");
                        return;
                    }
                }
            }
        }
    }

    /** Why the night must halt, when a press left a march lead worse off or unverified; otherwise null. */
    private String quickEquip(HeroGearStore store) throws IOException {
        frames.startStep();
        return GearNight.quickEquip(quickEquipOn(), new GearNight.QuickEquipRun() {
            @Override
            public boolean openGrid() {
                boolean open = openHeroesGrid();
                if (!open) {
                    logWarning("Quick Equip: Heroes grid did not open, nothing pressed; "
                            + frames.stopped(QUICK_EQUIP, "Heroes grid did not open"));
                }
                return open;
            }

            // The walk stops at the reserve; the clock never cuts a re-read, which would halt the night for nothing.
            @Override
            public QuickEquipPass.Screens screens() {
                return new QuickEquipScreens(gearDevice(QUICK_EQUIP, Instant.MAX), () -> Instant.now().isAfter(budget.reserveAt()));
            }

            @Override
            public void report(QuickEquipPass.Outcome outcome) {
                outcome.oddLooks().forEach(look -> logWarning("Quick Equip: unknown button look, not pressed: " + look));
                logInfo("Quick Equip: walked " + outcome.heroes() + " heroes, pressed " + outcome.presses());
                if (outcome.truncated() != null) logWarning("Quick Equip: " + outcome.truncated());
                if (outcome.stopNight()) {
                    logWarning("QUICK EQUIP STOPPED THE NIGHT, NOTHING BOUGHT: " + outcome.problem() + "; "
                            + frames.stopped(QUICK_EQUIP, outcome.problem()));
                } else if (outcome.problem() != null) {
                    logWarning("Quick Equip: " + outcome.problem() + "; " + frames.stopped(QUICK_EQUIP, outcome.problem()));
                }
            }
        }, store, profile.getId());
    }

    /** The freshly saved state, or null when the scan stopped and the night's buying is off. */
    private GearState scan(HeroGearStore store, GearState saved) {
        logInfo("Gear forge: full gear scan due, last scan " + (saved == null ? "none" : saved.scannedAt()));
        frames.startStep();
        if (!openHeroesGrid()) {
            logWarning("Gear forge: scan stopped, Heroes grid did not open; saved state kept, nothing bought tonight; "
                    + frames.stopped(SCAN, "Heroes grid did not open"));
            return null;
        }
        GearScan.Outcome outcome = GearScan.run(new GearScanScreens(gearDevice(SCAN, budget.reserveAt())), store, profile.getId(), saved, Instant.now());
        if (outcome.problem() != null) {
            logWarning("Gear forge: scan stopped: " + outcome.problem() + "; saved state kept, nothing bought tonight; "
                    + frames.stopped(SCAN, outcome.problem()));
            return null;
        }
        if (outcome.inventoryNote() != null) logWarning("Gear forge: " + outcome.inventoryNote());
        logInfo("Gear forge: scan saved " + outcome.state().pieces().size() + " pieces");
        return outcome.state();
    }

    // The pass checks pastDeadline itself and must never lose a read to the cap after a press.
    private GearEnhancePass.Device enhanceDevice(HeroGearStore store, Instant stopAt) {
        return new GearEnhancePass.Device() {
            @Override
            public RawImageData capture() {
                return HeroGearForgeRoutine.this.capture();
            }

            @Override
            public void tap(PointData point, int radius) {
                tapNear(point, radius);
            }

            @Override
            public void swipe(PointData from, PointData to, int durationMs) {
                HeroGearForgeRoutine.this.swipe(from, to, durationMs);
            }

            @Override
            public void back() {
                pressBack();
            }

            @Override
            public void pause(int millis) {
                sleepTask(millis);
            }

            @Override
            public boolean pastDeadline() {
                return Instant.now().isAfter(stopAt);
            }

            @Override
            public void pressing(String hero, String slot, int level) throws IOException {
                store.saveRaiseInFlight(profile.getId(), new HeroGearStore.RaiseInFlight(hero, slot, level));
            }

            @Override
            public void pressConfirmed() throws IOException {
                store.clearRaiseInFlight(profile.getId());
            }

            @Override
            public void readFailed(RawImageData frame, String failure) {
                HeroGearForgeRoutine.this.readFailed(ENHANCE, frame, failure);
            }
        };
    }

    private void readFailed(String step, RawImageData frame, String failure) {
        logWarning("Gear " + step + ": " + failure + "; " + frames.readFailed(step, frame, failure));
    }

    private void forgeStopped(String reason) {
        logWarning("Gear forge: stopped, " + reason + "; " + frames.stopped(FORGE, reason));
    }

    private GearScanScreens.Device gearDevice(String step, Instant stopAt) {
        return new GearScanScreens.Device() {
            @Override
            public RawImageData capture() {
                checkPreemption();
                if (Instant.now().isAfter(stopAt)) throw new GearScan.Abort("time cap reached");
                return HeroGearForgeRoutine.this.capture();
            }

            @Override
            public void tap(PointData point, int radius) {
                tapNear(point, radius);
            }

            @Override
            public void back() {
                pressBack();
            }

            @Override
            public void pause(int millis) {
                sleepTask(millis);
            }

            @Override
            public void readFailed(RawImageData frame, String failure) {
                HeroGearForgeRoutine.this.readFailed(step, frame, failure);
            }
        };
    }

    private boolean openHeroesGrid() {
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
        tapNear(HEROES_BUTTON);
        sleepTask(2500);
        return HeroCards.isHeroesGrid(capture());
    }

    /** The heroes the scan read, less any left out on the Heroes page. */
    private Set<String> tickedHeroes(GearState state) {
        Set<String> scanned = new LinkedHashSet<>();
        state.pieces().forEach(piece -> scanned.add(piece.hero()));
        return GearHeroes.included(scanned, GearHeroes.skipped(
                profile.getConfig(ConfigurationKeyEnum.HERO_GEAR_SKIP_HEROES_STRING, String.class)));
    }

    private GearState ranked(GearState state) {
        String focus = profile.getConfig(ConfigurationKeyEnum.HERO_GEAR_FORGE_FOCUS_STRING, String.class);
        return new GearState(state.pieces(), GearRanker.weightsFor(state, focus, tickedHeroes(state)));
    }

    private GearAmount enhanceAmount() {
        return GearAmount.choose(profile.getConfig(ConfigurationKeyEnum.HERO_GEAR_ENHANCE_AMOUNT_STRING, String.class),
                profile.getConfig(ConfigurationKeyEnum.HERO_GEAR_ENHANCE_MAX_LEVELS_INT, Integer.class));
    }

    private GearAmount forgeAmount() {
        return GearAmount.choose(profile.getConfig(ConfigurationKeyEnum.HERO_GEAR_FORGE_AMOUNT_STRING, String.class),
                profile.getConfig(ConfigurationKeyEnum.HERO_GEAR_FORGE_MAX_BUYS_INT, Integer.class));
    }

    private String forgeOrder() {
        return GearOrder.normalize(profile.getConfig(ConfigurationKeyEnum.HERO_GEAR_FORGE_FOCUS_STRING, String.class));
    }

    private int intSetting(ConfigurationKeyEnum key) {
        Integer value = profile.getConfig(key, Integer.class);
        return value != null ? value : Integer.parseInt(key.getDefaultValue());
    }

    /** From the Heroes grid to the open dialog; false, with the reason logged, on any wrong screen. */
    private boolean openForgeDialog(Upgrade upgrade) {
        if (!openHero(upgrade.hero())) {
            forgeStopped("could not open " + upgrade.hero());
            return false;
        }
        tapNear(GearScanScreens.GEAR_TAB, 15);
        sleepTask(1200);
        if (!HeroScreenReader.isGearTab(capture())) {
            forgeStopped("Gear tab did not open for " + upgrade.hero());
            return false;
        }
        tapNear(GearScanScreens.GEAR_TILES.get(upgrade.slot()), 12);
        sleepTask(1600);
        if (!ForgeDialogReader.isGearDetails(capture())) {
            forgeStopped("Gear Details did not open for " + upgrade.hero() + " " + upgrade.slot());
            return false;
        }
        tapNear(GearScanScreens.MASTERY_FORGING_BUTTON, 10);
        sleepTask(1600);
        return true;
    }

    private boolean openHero(String hero) {
        // Gear Details and other pages can pass for cards, so the grid is confirmed first.
        RawImageData grid = capture();
        if (!HeroCards.isHeroesGrid(grid)) {
            logWarning("Gear forge: the Heroes grid is not on screen");
            return false;
        }
        List<HeroCards.Card> cards = new ArrayList<>(HeroCards.gridCards(grid));
        for (HeroCards.Card card : cards) {
            checkPreemption();
            tapNear(new PointData(card.centreX(), card.centreY()), 20);
            sleepTask(2000);
            RawImageData page = capture();
            if (!HeroScreenReader.isHeroPage(page) && !HeroScreenReader.isGearTab(page)) {
                logWarning("Gear forge: a hero card did not open a hero page");
                return false;
            }
            if (hero.equals(HeroScreenReader.readName(page))) return true;
            pressBack();
            sleepTask(1500);
            if (!HeroCards.isHeroesGrid(capture())) return false;
        }
        return false;
    }

    private boolean forge(ForgeDialog before) {
        PointData button = ForgeDialogReader.forgeButton(capture());
        if (button == null) {
            forgeStopped("Forge button not found, nothing pressed");
            return false;
        }
        tapNear(button, 8);
        sleepTask(2500);
        ForgeDialog after = readDialog();
        for (int retry = 0; retry < SETTLE_RETRIES && GearForgeGuard.stillShowsOld(before, after); retry++) {
            sleepTask(800);
            after = readDialog();
        }
        if (after == null) {
            // A one-time paid offer can open over the dialog after a forge; Back dismisses it unbought.
            pressBack();
            sleepTask(1500);
            after = readDialog();
        }
        String problem = GearForgeGuard.afterProblem(before, after);
        if (problem != null) {
            forgeStopped("after a press, " + problem);
            return false;
        }
        logInfo("Gear forge: bought " + before);
        return true;
    }

    private ForgeDialog readDialog() {
        ForgeDialog dialog = ForgeDialogReader.read(capture());
        for (int retry = 0; retry < READ_RETRIES && dialog == null; retry++) {
            sleepTask(800);
            dialog = ForgeDialogReader.read(capture());
        }
        return dialog;
    }

    private boolean closeToGrid() {
        for (int i = 0; i < 3 && !HeroCards.isHeroesGrid(capture()); i++) {
            pressBack();
            sleepTask(1500);
        }
        return HeroCards.isHeroesGrid(capture());
    }

    private boolean pastCap() {
        return Instant.now().isAfter(deadline);
    }

    private RawImageData capture() {
        return frames.seen(emuManager.captureScreen(EMULATOR_NUMBER));
    }
}
