package dev.frostguard.app.panel.heroes;

import java.util.Optional;
import dev.frostguard.engine.squads.JoinPlanStore;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import atlantafx.base.controls.ToggleSwitch;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.squads.SavedSquad;
import dev.frostguard.api.squads.SquadSnapshot;
import dev.frostguard.api.squads.TroopStock;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.squads.SquadPlanner;
import dev.frostguard.engine.squads.SquadScanRegistry;
import dev.frostguard.engine.squads.SquadSnapshotStore;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

/**
 * Troops > Squads. Page order: header, status, Bear Trap strip, results, settings. The status
 * banner exists only when a scan is running, failed or came back incomplete.
 */
public final class SquadsLayoutController extends AbstractProfileController {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter WEEKDAY_CLOCK = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    /** Where the bot sends the bear squads: lead on 8, hero joins on 1 to 3, troop-only joins on 4 and 5. */
    private static final int DEFAULT_LEAD_SLOT = 8;
    private static final List<Integer> DEFAULT_JOIN_SLOTS = List.of(1, 2, 3);
    private static final List<Integer> DEFAULT_TROOP_SLOTS = List.of(4, 5);

    private static final String JOIN_NOTE = "Joiners only use the leader's first expedition skill.";

    private enum Tone { INFO, WARN, ERROR }

    private ProfileAux currentProfile;
    private boolean scanRunning;
    private boolean loadingTime;
    private SquadSnapshot shown;
    private String scanPart = "Select a profile";
    private Set<String> flaggedNow = Set.of();
    private boolean cleanNow = true;
    private boolean heroesLostNow;
    private String bearPart;

    @FXML private Button buttonScan;
    @FXML private Label labelStatus;
    @FXML private Label labelScan;
    @FXML private Label labelScanHint;
    @FXML private HBox bannerBox;
    @FXML private Label bannerIcon;
    @FXML private Label bannerTitle;
    @FXML private Label bannerText;
    @FXML private ProgressBar bannerProgress;
    @FXML private Button bannerAction;
    @FXML private VBox bearStrip;
    @FXML private ToggleSwitch switchNightly;
    @FXML private CheckBox checkNightly;
    @FXML private ComboBox<String> comboRunAt;
    @FXML private Label captionRunAt;
    @FXML private javafx.scene.control.TextField fieldPerJoin;
    @FXML private Label messagePerJoin;
    @FXML private TabPane tabsResults;
    @FXML private VBox listSuggested;
    @FXML private VBox panelSaved;
    @FXML private VBox panelTroops;
    @FXML private HBox tilesTroops;
    @FXML private TableView<List<String>> tableSaved;
    @FXML private TableView<List<String>> tableTroops;

    @FXML
    private void initialize() {
        for (int minutes = 0; minutes < 24 * 60; minutes += 15) {
            comboRunAt.getItems().add(String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60));
        }
        registerCheckBox(checkNightly, ConfigurationKeyEnum.SQUAD_SCAN_NIGHTLY_ENABLED_BOOL);
        // The alliance's join size changes, so it is a setting (default 100,000), not a constant.
        registerRangedIntegerField(fieldPerJoin, messagePerJoin, ConfigurationKeyEnum.BEAR_TRAP_JOIN_TROOPS_INT,
                "Troops per join", 10_000, 1_000_000);
        initializeChangeEvents();

        // The switch is the control; the hidden check box is what the shared config binding drives.
        switchNightly.selectedProperty().bindBidirectional(checkNightly.selectedProperty());
        switchNightly.textProperty().bind(Bindings.when(switchNightly.selectedProperty()).then("On").otherwise("Off"));
        comboRunAt.disableProperty().bind(checkNightly.selectedProperty().not());
        checkNightly.selectedProperty().addListener((obs, was, is) -> {
            updateRunAtCaption();
            refreshStatusLine();
        });
        // The list is in the player's time; the setting is stored in UTC, which is how the scan is scheduled.
        comboRunAt.valueProperty().addListener((obs, was, is) -> {
            updateRunAtCaption();
            if (!loadingTime && is != null) publishWhenReady(ConfigurationKeyEnum.SQUAD_SCAN_TIME_UTC_STRING, toUtc(is));
        });
        updateRunAtCaption();

        tabsResults.getSelectionModel().selectedIndexProperty().addListener((obs, was, is) -> showResultsTab(is.intValue()));
        showResultsTab(tabsResults.getSelectionModel().getSelectedIndex());
        savedColumns();
        troopColumns();
        buttonScan.disableProperty().addListener((obs, was, is) -> setHint(is));
        showEmpty();
        setScanPart("Select a profile");
        SquadScanRegistry.addListener(this::onScanEvent);
    }

    @Override
    public void onProfileLoad(ProfileAux profile) {
        boolean changed = currentProfile == null || profile == null || !currentProfile.getId().equals(profile.getId());
        currentProfile = profile;
        if (profile != null) {
            super.onProfileLoad(profile);
            loadRunAt(profile);
        }
        if (changed) loadSaved();
    }

    private void loadRunAt(ProfileAux profile) {
        loadingTime = true;
        try {
            Object utc = profile.getConfiguration(ConfigurationKeyEnum.SQUAD_SCAN_TIME_UTC_STRING);
            comboRunAt.setValue(toLocal(utc == null ? "07:15" : utc.toString()));
        } finally {
            loadingTime = false;
        }
        updateRunAtCaption();
    }

    private void showResultsTab(int index) {
        VBox[] panels = {listSuggested, panelSaved, panelTroops};
        for (int i = 0; i < panels.length; i++) {
            panels[i].setVisible(i == index);
            panels[i].setManaged(i == index);
        }
    }

    // ── scan control ────────────────────────────────────────────────

    @FXML
    private void handleScan() {
        ProfileAux selected = currentProfile;
        if (selected == null) {
            banner(Tone.WARN, "No profile selected", "Pick a profile at the top, then scan.", null, false);
            return;
        }
        TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(selected.getId());
        if (queue == null || !queue.isActive()) {
            banner(Tone.WARN, "The bot is not running",
                    "Start the bot, then scan. A scan runs through the profile's task queue.", null, false);
            return;
        }
        SquadScanRegistry.requestManualScan(selected.getId());
        queue.runNow(TpDailyTaskEnum.SQUAD_SCAN, false);
        setRunning(true, "The scan starts as soon as the bot is free.");
    }

    private void onScanEvent(SquadScanRegistry.Event event) {
        ProfileAux selected = currentProfile;
        if (selected == null || !selected.getId().equals(event.profileId())) return;
        Platform.runLater(() -> {
            switch (event.state()) {
                case RUNNING -> setRunning(true, "Scanning your squads. This takes several minutes.");
                case SUCCEEDED -> {
                    setRunning(false, null);
                    show(event.snapshot());
                }
                case FAILED -> {
                    setRunning(false, null);
                    banner(Tone.ERROR, "The scan did not finish",
                            event.message() == null || event.message().isBlank()
                                    ? "Something went wrong while reading your account. Try again."
                                    : event.message() + (shown == null ? "" : " The squads below are from the last good scan."),
                            "Scan again", false);
                }
            }
        });
    }

    private void setRunning(boolean running, String detail) {
        scanRunning = running;
        buttonScan.setDisable(running);
        if (running) {
            setScanPart("Scanning now");
            banner(Tone.INFO, "Scanning your squads", detail, null, true);
            showSkeletonIfEmpty();
        } else if (shown != null) {
            fill(shown);
        } else {
            clearBanner();
            setScanPart("Not scanned yet");
        }
    }

    /** The line under the title: "On \u00b7 Bear Trap Thu 14:00: not ready". The scan's own quality has its own line below it. */
    private void setScanPart(String text) {
        scanPart = text;
        labelScan.setVisible(false);
        labelScan.setManaged(false);
        refreshStatusLine();
    }

    private void setScanQuality(String text, boolean ok) {
        scanPart = null;
        labelScan.setText(text);
        labelScan.getStyleClass().removeAll("sq-verdict-ok", "sq-verdict-warn");
        labelScan.getStyleClass().add(ok ? "sq-verdict-ok" : "sq-verdict-warn");
        labelScan.setVisible(true);
        labelScan.setManaged(true);
        refreshStatusLine();
    }

    private void refreshStatusLine() {
        List<String> parts = new ArrayList<>();
        if (currentProfile != null) parts.add(checkNightly.isSelected() ? "On" : "Off");
        if (scanPart != null) parts.add(scanPart);
        if (bearPart != null) parts.add(bearPart);
        labelStatus.setText(String.join(" \u00b7 ", parts));
    }

    private void setHint(boolean disabled) {
        labelScanHint.setVisible(disabled);
        labelScanHint.setManaged(disabled);
    }

    // The nightly scan stores the Bear Trap join plan; a plan that differs from the night before is said once.
    private void showJoinPlanChange() {
        if (currentProfile == null) return;
        try {
            new JoinPlanStore(WorkspacePaths.current().root()).load(currentProfile.getId())
                    .flatMap(stored -> joinPlanLine(stored, Instant.now()))
                    .ifPresent(line -> banner(Tone.INFO, line, null, null, false));
        } catch (IOException | RuntimeException e) {
            // no stored plan is not a problem worth a banner
        }
    }

    /** "Join plan changed: 3 \u00d7 100k + 1 \u00d7 62k (was 4 \u00d7 100k)", for a change in the last 36 hours. */
    static Optional<String> joinPlanLine(JoinPlanStore.Stored stored, Instant now) {
        if (stored == null || stored.changedAt() == null || stored.previousSummary() == null) return Optional.empty();
        try {
            if (Duration.between(Instant.parse(stored.changedAt()), now).toHours() >= 36) return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return Optional.of("Join plan changed: " + joinsOf(stored.summary()) + " (was " + joinsOf(stored.previousSummary()) + ")");
    }

    /** "Host 135.6k · Joins: 3 × 100k · Marksmen short by 5k" -> "3 × 100k". */
    private static String joinsOf(String summary) {
        int at = summary.indexOf("Joins: ");
        String joins = at < 0 ? summary : summary.substring(at + "Joins: ".length());
        int end = joins.indexOf(" · ");
        return end < 0 ? joins : joins.substring(0, end);
    }

    // ── loading ─────────────────────────────────────────────────────

    private void loadSaved() {
        shown = null;
        clearBanner();
        showEmpty();
        if (currentProfile == null) {
            setScanPart("Select a profile");
            return;
        }
        setScanPart("Not scanned yet");
        try {
            new SquadSnapshotStore(WorkspacePaths.current().root()).load(currentProfile.getId())
                    .ifPresent(this::show);
        } catch (IOException | RuntimeException e) {
            banner(Tone.ERROR, "The last scan could not be opened",
                    "Press Scan now to read your squads again.", "Scan again", false);
        }
    }

    // A snapshot the tables cannot take (an old file, a value the planner chokes on) must say so on
    // screen; inside Platform.runLater an exception would otherwise just leave every table empty.
    private void show(SquadSnapshot snapshot) {
        try {
            shown = snapshot;
            fill(snapshot);
        } catch (RuntimeException e) {
            banner(Tone.ERROR, "The last scan could not be shown",
                    "Press Scan now to read your squads again.", "Scan again", false);
        }
    }

    private void fill(SquadSnapshot snapshot) {
        // A hero whose stats were not read is not recommended: plan from the heroes that were read.
        Set<String> unreadHeroes = BearTrapCheck.flaggedHeroes(snapshot);
        SquadSnapshot readHeroes = new SquadSnapshot(snapshot.capturedAt(),
                snapshot.heroes().stream().filter(h -> h == null || h.name() == null || !unreadHeroes.contains(h.name())).toList(),
                snapshot.troops(), snapshot.savedSquads(), snapshot.problems());
        SquadPlanner.Plan plan = SquadPlanner.plan(readHeroes);
        boolean heroesLost = snapshot.problems().stream().anyMatch(p -> p.startsWith("Hero card"));
        heroesLostNow = heroesLost;
        BearTrapCheck.Report bear = BearTrapCheck.check(snapshot, plan, leadSlot(), joinSlots(), troopSlots(), !heroesLost);
        List<BearTrapCheck.Issue> issues = issues(snapshot, bear);
        flaggedNow = BearTrapCheck.flaggedHeroes(snapshot);
        cleanNow = scanIsClean(snapshot);
        showBearStrip(bear, snapshot, issues);
        showStatus(snapshot, bear, issues);

        fillSuggested(plan);
        fillSaved(snapshot);
        fillTroops(snapshot);
    }

    // ── status ──────────────────────────────────────────────────────

    private void showStatus(SquadSnapshot snapshot, BearTrapCheck.Report bear, List<BearTrapCheck.Issue> issues) {
        String when = relativeTime(parse(snapshot.capturedAt()), Instant.now(), ZoneId.systemDefault());
        List<String> problems = snapshot.problems();
        if (scanRunning) return;
        if (scanIsClean(snapshot)) {
            setScanQuality("Scan complete \u00b7 " + when, true);
            clearBanner();
            showJoinPlanChange();
            return;
        }
        // The state is said once, in the banner. The header carries only when the scan ran.
        scanPart = "Last scan " + when;
        labelScan.setVisible(false);
        labelScan.setManaged(false);
        refreshStatusLine();
        List<String> phrases = new ArrayList<>();
        List<String> found = new ArrayList<>();
        for (BearTrapCheck.Issue i : issues) {
            if (i.kind() == BearTrapCheck.Kind.SQUADS_EMPTY) {
                found.add(i.shortName());
                continue;
            }
            if (!i.fromScan()) continue;
            String phrase = switch (i.kind()) {
                case MARKSMEN_UNREAD -> "your marksmen at home";
                case STATS_UNREAD -> "all of " + i.shortName();
                default -> i.shortName();
            };
            if (!phrases.contains(phrase)) phrases.add(phrase);
        }
        // The same words as the Saved table and the squad's pill: "couldn't read" only for what it really could not read.
        List<String> says = new ArrayList<>();
        if (!phrases.isEmpty()) says.add("couldn't read " + joinAnd(phrases));
        if (!found.isEmpty()) says.add("found " + String.join(" and ", found) + " empty or locked");
        String sentence = "The scan " + (says.isEmpty() ? "couldn't read part of your account" : String.join(" and ", says)) + ".";
        // Only when the scan stopped partway is there a cause and a how-to to add.
        boolean stopped = snapshot.troops() == null || problems.stream().anyMatch(q -> q.startsWith("Hero card") || q.startsWith("Chief Profile"));
        List<String> causes = new ArrayList<>();
        for (BearTrapCheck.Issue i : issues) {
            if (i.fromScan() && !i.why().isEmpty() && !causes.contains(i.why())) causes.add(i.why());
        }
        List<String> heroesToOpen = new ArrayList<>();
        for (BearTrapCheck.Issue i : issues) {
            if (i.kind() == BearTrapCheck.Kind.STATS_UNREAD) heroesToOpen.add(i.shortName().replace("'s stats", ""));
        }
        // Fix first is the first step; this line only says how to get the unread stats.
        String how = heroesToOpen.isEmpty() ? "" : "To read " + String.join(" and ", heroesToOpen.stream().map(h -> h + "'s").toList())
                + " stats: open " + String.join(" and ", heroesToOpen) + " in the game, then press Scan now.";
        boolean chief = issues.stream().anyMatch(i -> i.kind() == BearTrapCheck.Kind.MARKSMEN_UNREAD || i.kind() == BearTrapCheck.Kind.SQUADS_UNREAD);
        boolean heroScreen = issues.stream().anyMatch(i -> i.kind() == BearTrapCheck.Kind.HEROES_LOST || i.kind() == BearTrapCheck.Kind.STATS_UNREAD);
        String screens = chief && heroScreen ? "Chief Profile and Heroes" : chief ? "Chief Profile" : "Heroes";
        banner(Tone.WARN, sentence, stopped ? String.join(" ", causes) + " Open " + screens + " in the game once, then press Scan now." : how,
                null, false);
        if (!problems.isEmpty()) bannerText.setTooltip(new Tooltip(String.join("\n", problems)));
    }

    /** "a", "a and b", "a, b and c". */
    static String joinAnd(List<String> parts) {
        if (parts.isEmpty()) return "part of your account";
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /** "a", "a or b", "a, b or c". */
    static String joinOr(List<String> parts) {
        if (parts.isEmpty()) return "part of your account";
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " or " + parts.get(parts.size() - 1);
    }

    /** "squad 3 and marksmen", "squad 3, Jessie's stats and 2 more". */
    static String scanNames(List<BearTrapCheck.Issue> issues) {
        List<String> names = new ArrayList<>();
        for (BearTrapCheck.Issue i : issues) {
            if (i.fromScan() && !names.contains(i.shortName())) names.add(i.shortName());
        }
        if (names.isEmpty()) return "part of your account";
        if (names.size() == 1) return names.get(0);
        if (names.size() > 3) {
            return String.join(", ", names.subList(0, 2)) + " and " + (names.size() - 2) + " more";
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    static boolean scanIsClean(SquadSnapshot snapshot) {
        return snapshot.problems().isEmpty() && snapshot.troops() != null;
    }

    /** The first thing the scan itself missed; the banner leads with it and counts the rest. */
    static BearTrapCheck.Issue firstScanIssue(List<BearTrapCheck.Issue> issues) {
        return issues.stream().filter(BearTrapCheck.Issue::fromScan).findFirst().orElse(issues.get(0));
    }

    /** The card's headline: the verdict and the top cause, from the same list as the banner, the pills and the numbered lines. */
    static String headline(List<BearTrapCheck.Issue> issues) {
        if (issues.isEmpty()) return "All set";
        // A scan that missed something is the banner's to explain; the card does not repeat it.
        return issues.get(0).fromScan() ? "Not ready" : "Not ready: " + issues.get(0).headline();
    }

    /** Everything to check, in one list. The Bear Trap squads come first, then what else the scan missed. */
    static List<BearTrapCheck.Issue> issues(SquadSnapshot snapshot, BearTrapCheck.Report bear) {
        List<BearTrapCheck.Issue> out = new ArrayList<>(bear.issues());
        String why = BearTrapCheck.scanWhy(snapshot);
        for (String hero : BearTrapCheck.flaggedHeroes(snapshot)) {
            String title = "Couldn't read " + hero + "'s stats";
            if (out.stream().noneMatch(i -> i.title().equals(title))) {
                out.add(new BearTrapCheck.Issue(BearTrapCheck.Kind.STATS_UNREAD, title,
                        "The scan could not read all of " + hero + "'s stats.",
                        "Open Heroes > " + hero + " once, then Scan again.", hero + "'s stats", 1, hero + "'s stats not read"));
            }
        }
        long lost = snapshot.problems().stream().filter(p -> p.startsWith("Hero card")).count();
        if (lost > 0) {
            out.add(new BearTrapCheck.Issue(BearTrapCheck.Kind.HEROES_LOST,
                    "Couldn't find " + lost + (lost == 1 ? " hero" : " heroes") + " on the Heroes screen",
                    "The Heroes screen changed during the scan.", "Open Heroes once, then Scan again.",
                    lost + (lost == 1 ? " hero" : " heroes"), (int) lost, lost + (lost == 1 ? " hero" : " heroes") + " not found"));
        }
        for (String p : snapshot.problems()) {
            boolean known = p.startsWith("Hero card") || p.startsWith("Chief Profile")
                    || !BearTrapCheck.flaggedHeroes(new SquadSnapshot(snapshot.capturedAt(), snapshot.heroes(), null,
                            List.of(), List.of(p))).isEmpty();
            if (!known) {
                out.add(new BearTrapCheck.Issue(BearTrapCheck.Kind.SCAN_OTHER, "The scan missed: " + friendly(p), "",
                        "Scan again.", friendly(p), 1, friendly(p)));
            }
        }
        if (out.isEmpty() && !scanIsClean(snapshot)) {
            out.add(new BearTrapCheck.Issue(BearTrapCheck.Kind.SCAN_OTHER, "Part of the scan could not be read", why,
                    "Scan again.", "part of your account", 1, "part of the scan not read"));
        }
        return out;
    }

    /** One missed item, in words a player recognises. */
    static String friendly(String problem) {
        // Keep the miss, drop the explanation that follows it ("... (read [Attack]), so it is left out ...").
        String trimmed = problem.split(" \\(|, so ")[0];
        if (trimmed.endsWith(".")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return Character.toLowerCase(trimmed.charAt(0)) + trimmed.substring(1);
    }

    private void banner(Tone tone, String title, String text, String actionLabel, boolean progress) {
        bannerBox.getStyleClass().removeIf(c -> c.startsWith("sq-banner-"));
        bannerBox.getStyleClass().add(switch (tone) {
            case INFO -> "sq-banner-info";
            case WARN -> "sq-banner-warn";
            case ERROR -> "sq-banner-error";
        });
        bannerIcon.setText(switch (tone) {
            case INFO -> "i";
            case WARN, ERROR -> "!";
        });
        bannerTitle.setText(title);
        bannerText.setText(text == null ? "" : text);
        boolean hasText = text != null && !text.isBlank();
        bannerText.setVisible(hasText);
        bannerText.setManaged(hasText);
        bannerText.setTooltip(null);
        bannerProgress.setVisible(progress);
        bannerProgress.setManaged(progress);
        bannerProgress.setProgress(progress ? ProgressBar.INDETERMINATE_PROGRESS : 0);
        // The one Scan now is the header's; the banner explains, it does not carry a second button.
        bannerAction.setVisible(false);
        bannerAction.setManaged(false);
        bannerBox.setVisible(true);
        bannerBox.setManaged(true);
    }

    private void clearBanner() {
        bannerBox.setVisible(false);
        bannerBox.setManaged(false);
    }

    // ── Bear Trap strip ─────────────────────────────────────────────

    private void showBearStrip(BearTrapCheck.Report report, SquadSnapshot snapshot, List<BearTrapCheck.Issue> issues) {
        bearStrip.getChildren().clear();
        boolean allSet = issues.isEmpty();
        String when = bearTimeLabel(bearAnchor(), Instant.now(), ZoneId.systemDefault());
        String bear = when == null ? "Bear Trap" : "Bear Trap " + when;
        Label title = new Label(bear + " \u00b7 " + headline(issues));
        title.getStyleClass().addAll("sq-card-title", allSet ? "sq-verdict-ok" : "sq-verdict-warn");
        title.setWrapText(true);
        // The card title names the bear; the header does not repeat it.
        bearPart = null;
        refreshStatusLine();
        bearStrip.getChildren().add(title);
        if (report.fixFirst() != null) {
            Label fix = new Label(report.fixFirst());
            fix.getStyleClass().add("sq-label");
            fix.setWrapText(true);
            bearStrip.getChildren().add(fix);
        }

        // Marksmen first: it is the one that can cost the bear. Nothing read is one line, not a row of dashes.
        boolean nothingRead = !report.marksmen().read() && report.everySquadUnread();
        if (nothingRead) {
            Label notRead = pill("Not read", "sq-pill-neutral");
            Tooltip.install(notRead, new Tooltip("The scan could not read your marksmen at home or any Bear Trap squad."));
            bearStrip.getChildren().add(oneLine("Marksmen and squads", notRead));
        }
        BearTrapCheck.Marksmen m = report.marksmen();
        if (nothingRead) {
            // said once, above
        } else if (!m.read()) {
            Label notRead = pill("Not read", "sq-pill-neutral");
            Tooltip.install(notRead, new Tooltip("The scan could not read the marksmen you have at home."));
            bearStrip.getChildren().add(oneLine("Marksmen", notRead));
        } else {
            GridPane marks = marksGrid();
            marks.add(head("Marksmen"), 0, 0);
            marks.add(rightHead("Needed"), 1, 0);
            marks.add(rightHead("Home"), 2, 0);
            marks.add(head("Result"), 3, 0);
            Label needed = rightCell(BearTrapCheck.number(m.needed()));
            Tooltip.install(needed, new Tooltip(m.breakdown()));
            Label home = rightCell(BearTrapCheck.number(m.home()));
            Label verdict;
            if (m.short_()) {
                String shortWord = !m.complete() ? "Short by at least " : m.uncertain() ? "Short ~" : "Short by ";
                verdict = pill(shortWord + BearTrapCheck.number(m.shortBy()), "sq-pill-warn");
            } else if (m.ok()) {
                verdict = pill("Enough", "sq-pill-ok");
            } else {
                verdict = pill("Can't tell yet", "sq-pill-neutral");
            }
            Tooltip.install(verdict, new Tooltip(m.ok() ? "Marksmen at home cover the squads below."
                    : !m.complete() ? m.unread().stream().map(n -> "squad " + n).collect(Collectors.joining(", "))
                            + " could not be read, so the shortfall may be larger."
                    : "The scan counts the marksmen in your Troops list. Marksmen out on a march are not at home."));
            Label what = cell("Bear Trap squads");
            for (Label l : List.of(what, needed, home)) l.setPadding(new Insets(12, 0, 12, 0));
            HBox verdictBox = new HBox(verdict);
            verdictBox.setAlignment(Pos.CENTER_LEFT);
            marks.add(what, 0, 1);
            marks.add(needed, 1, 1);
            marks.add(home, 2, 1);
            marks.add(verdictBox, 3, 1);
            bearStrip.getChildren().add(marks);
        }

        // The comparison: one value per cell, the first hero only. Every squad unread, with nothing to suggest, is one line.
        if (nothingRead) {
            // said once, above
        } else if (report.everySquadUnread()) {
            Label notRead = pill("Not read", "sq-pill-neutral");
            Tooltip.install(notRead, new Tooltip("The scan could not read any Bear Trap squad."));
            bearStrip.getChildren().addAll(divider(), oneLine("Bear Trap squads", notRead));
        } else {
            GridPane grid = grid();
            int row = 0;
            grid.add(head("Squad"), 0, row);
            grid.add(head("Your leader"), 1, row);
            grid.add(head("Suggested leader"), 2, row);
            grid.add(head("Result"), 3, row);
            // Only when some squad shares a hero; a column of blanks is not shown.
            if (report.lines().stream().anyMatch(l -> l.badge() != null)) grid.add(head("Shared hero"), 4, row);
            row++;
            for (BearTrapCheck.Line line : report.lines()) {
                String tone = switch (line.result()) {
                    case MATCH -> "sq-pill-ok";
                    case CHOICE -> "sq-pill-info";
                    case NOT_READ -> "sq-pill-neutral";
                    case DUPLICATE -> "sq-pill-warn";
                };
                // Result is how the squad compares and nothing else; a hero in more than one squad is its own column.
                Label compare = pill(line.pill(), tone);
                if (line.tip() != null) Tooltip.install(compare, new Tooltip(line.tip()));
                HBox result = new HBox(8, compare);
                result.setAlignment(Pos.CENTER_LEFT);
                row = addBearRow(grid, row, line.role() + " (squad " + line.slot() + ")", line.saved(), line.suggested(), result);
                if (line.badge() != null) {
                    Label shared = pill(line.badge(), "sq-pill-warn");
                    if (line.badgeTip() != null) Tooltip.install(shared, new Tooltip(line.badgeTip()));
                    HBox cellBox = new HBox(shared);
                    cellBox.setAlignment(Pos.CENTER_LEFT);
                    grid.add(cellBox, 4, row - 1);
                }
            }
            bearStrip.getChildren().addAll(divider(), grid);
        }
        bearStrip.setVisible(true);
        bearStrip.setManaged(true);
    }

    // Needed and Home sit side by side, both right-aligned, so the two numbers read as a pair.
    private static GridPane marksGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.getColumnConstraints().addAll(new ColumnConstraints(150, 150, Double.MAX_VALUE, Priority.ALWAYS, HPos.LEFT, true),
                new ColumnConstraints(110),
                new ColumnConstraints(110),
                new ColumnConstraints(250));
        return grid;
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(0);
        grid.getColumnConstraints().addAll(new ColumnConstraints(150),
                new ColumnConstraints(110, 200, Double.MAX_VALUE, Priority.ALWAYS, HPos.LEFT, true),
                new ColumnConstraints(110, 200, Double.MAX_VALUE, Priority.ALWAYS, HPos.LEFT, true),
                new ColumnConstraints(150),
                new ColumnConstraints(150));
        return grid;
    }

    private int addBearRow(GridPane grid, int row, String role, String saved, String suggested, javafx.scene.Node verdict) {
        Label r = cell(role);
        Label s = cell(saved);
        Label g = cell(suggested);
        for (Label l : List.of(r, s, g)) l.setPadding(new Insets(12, 0, 12, 0));
        HBox verdictBox = verdict instanceof HBox box ? box : new HBox(verdict);
        verdictBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(r, 0, row);
        grid.add(s, 1, row);
        grid.add(g, 2, row);
        grid.add(verdictBox, 3, row);
        return row + 1;
    }

    private static Label head(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sq-col-head");
        l.setPadding(new Insets(0, 0, 8, 0));
        return l;
    }

    // A section nothing of which was read: its name and one "Not read".
    private static HBox oneLine(String name, Label state) {
        Label label = new Label(name);
        label.getStyleClass().add("sq-label");
        HBox line = new HBox(16, label, state);
        line.setAlignment(Pos.CENTER_LEFT);
        line.setPadding(new Insets(8, 0, 8, 0));
        return line;
    }

    private static Label rightHead(String text) {
        Label l = head(text);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setAlignment(Pos.CENTER_RIGHT);
        return l;
    }

    private static Label rightCell(String text) {
        Label l = cell(text);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setAlignment(Pos.CENTER_RIGHT);
        return l;
    }

    private static Label cell(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sq-label");
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    private String bearAnchor() {
        if (currentProfile == null) return null;
        try {
            Object raw = currentProfile.getConfiguration(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING);
            // The setting is a date-time; depending on the caller it arrives parsed or as the text "12-11-2026 19:00".
            if (raw instanceof java.time.LocalDateTime when) {
                return DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm", Locale.ENGLISH).format(when);
            }
            return raw == null ? null : raw.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The next Bear Trap start in the player's clock ("Today 14:00", "Tomorrow 14:00", "Thu 14:00"); the setting repeats every 48 hours. */
    static String bearTimeLabel(String anchor, Instant now, ZoneId zone) {
        if (anchor == null || anchor.isBlank()) return null;
        try {
            Instant start = java.time.LocalDateTime.parse(anchor.trim(), DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm", Locale.ENGLISH))
                    .toInstant(ZoneOffset.UTC);
            Instant window = now.minus(Duration.ofMinutes(30));
            while (start.isBefore(window)) start = start.plus(Duration.ofHours(48));
            LocalDate day = start.atZone(zone).toLocalDate();
            LocalDate today = now.atZone(zone).toLocalDate();
            String clock = CLOCK.format(start.atZone(zone));
            if (day.equals(today)) return "Today " + clock;
            if (day.equals(today.plusDays(1))) return "Tomorrow " + clock;
            return WEEKDAY_CLOCK.format(start.atZone(zone));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private int leadSlot() {
        List<Integer> v = slots(ConfigurationKeyEnum.BEAR_TRAP_RALLY_FLAG_INT);
        return v.isEmpty() ? DEFAULT_LEAD_SLOT : v.get(0);
    }

    private List<Integer> joinSlots() {
        List<Integer> v = slots(ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT);
        return v.isEmpty() ? DEFAULT_JOIN_SLOTS : v;
    }

    private List<Integer> troopSlots() {
        if (currentProfile == null) return DEFAULT_TROOP_SLOTS;
        List<Integer> v = slots(ConfigurationKeyEnum.BEAR_TRAP_JOIN_TROOP_FLAG_STRING);
        return v.isEmpty() ? DEFAULT_TROOP_SLOTS : v;
    }

    private List<Integer> slots(ConfigurationKeyEnum key) {
        if (currentProfile == null) return List.of();
        try {
            Object raw = currentProfile.getConfiguration(key);
            return raw == null ? List.of() : parseSlots(raw.toString());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    static List<Integer> parseSlots(String text) {
        List<Integer> out = new ArrayList<>();
        for (String part : text.split("[,;\\s]+")) {
            try {
                if (!part.isBlank()) out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // a stray token in the setting is skipped, not fatal
            }
        }
        return out;
    }

    // ── settings ────────────────────────────────────────────────────

    private void updateRunAtCaption() {
        if (!checkNightly.isSelected()) {
            captionRunAt.setText("Turn on nightly scans to choose a time.");
            comboRunAt.setTooltip(null);
            return;
        }
        String local = comboRunAt.getValue();
        captionRunAt.setText(local == null ? "Your time" : local + " your time " + "\u00b7" + " " + toUtc(local) + " UTC");
        comboRunAt.setTooltip(local == null ? null : new Tooltip(toUtc(local) + " UTC"));
    }

    /** The setting is kept in UTC; the list shows the player's own clock. */
    static String toLocal(String utc) {
        try {
            LocalTime t = LocalTime.parse(utc.trim());
            return CLOCK.format(t.atDate(LocalDate.now(ZoneOffset.UTC)).atZone(ZoneOffset.UTC)
                    .withZoneSameInstant(ZoneId.systemDefault()));
        } catch (DateTimeParseException e) {
            return "02:15";
        }
    }

    static String toUtc(String local) {
        try {
            LocalTime t = LocalTime.parse(local.trim());
            return CLOCK.format(t.atDate(LocalDate.now(ZoneId.systemDefault())).atZone(ZoneId.systemDefault())
                    .withZoneSameInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            return "07:15";
        }
    }

    // ── results: suggested squads ───────────────────────────────────

    private void fillSuggested(SquadPlanner.Plan plan) {
        cleanNowStatic = cleanNow;
        listSuggested.getChildren().clear();
        listSuggested.setPadding(new Insets(4, 0, 0, 0));
        if (heroesLostNow) {
            // Built from a partial roster, so nothing is suggested, rather than something misleading.
            listSuggested.setPadding(Insets.EMPTY);
            listSuggested.getChildren().add(blankslate("Nothing to suggest yet",
                    "Suggestions use only the heroes the scan read. No heroes read yet.", false));
            return;
        }

        // Joining squads have a skill, level and bonus; the others have a reason. Each gets its own headings.
        List<SquadPlanner.Suggestion> joins = new ArrayList<>();
        List<SquadPlanner.Suggestion> others = new ArrayList<>();
        for (SquadPlanner.Suggestion s : plan.squads()) (s.purpose().contains("join") ? joins : others).add(s);

        Label joinTitle = new Label("Joining squads");
        joinTitle.getStyleClass().add("sq-card-title");
        Label note = new Label(cleanNow ? JOIN_NOTE : JOIN_NOTE + " Suggestions use only the heroes the scan read.");
        note.setWrapText(true);
        note.getStyleClass().add("sq-squad-why");
        note.setPadding(new Insets(4, 0, 12, 0));
        listSuggested.getChildren().addAll(joinTitle, note);
        listSuggested.getChildren().add(joinRow(null, true));
        for (SquadPlanner.Suggestion s : joins) {
            listSuggested.getChildren().add(divider());
            listSuggested.getChildren().add(joinRow(s, false));
        }

        Label leadTitle = new Label("Leading and other squads");
        leadTitle.getStyleClass().add("sq-card-title");
        leadTitle.setPadding(new Insets(24, 0, 8, 0));
        listSuggested.getChildren().addAll(leadTitle, whyRow(null, null, null, true));
        // Squads that get the same heroes for the same reason are one row, named for all their uses.
        java.util.Map<String, List<SquadPlanner.Suggestion>> groups = new java.util.LinkedHashMap<>();
        for (SquadPlanner.Suggestion s : others) {
            groups.computeIfAbsent(String.join("|", s.heroes()) + "#" + s.why(), k -> new ArrayList<>()).add(s);
        }
        for (List<SquadPlanner.Suggestion> group : groups.values()) {
            listSuggested.getChildren().add(divider());
            listSuggested.getChildren().add(groupRow(group));
        }
        if (!plan.exploration().isEmpty()) {
            listSuggested.getChildren().add(divider());
            listSuggested.getChildren().add(whyRow("Exploration", plan.exploration(),
                    "Highest power. Exploration fights use hero stats, not troops.", false));
        }
    }

    private static Region divider() {
        Region divider = new Region();
        divider.getStyleClass().add("sq-divider");
        return divider;
    }

    private static final double COL_SQUAD = 200, COL_HEROES = 260, COL_LEVEL = 64, COL_BONUS = 120;

    private HBox joinRow(SquadPlanner.Suggestion s, boolean header) {
        HBox rowBox = new HBox(16);
        rowBox.setAlignment(Pos.TOP_LEFT);
        if (header) {
            rowBox.setPadding(new Insets(0, 0, 8, 0));
            rowBox.getChildren().addAll(fixed(head("Squad"), COL_SQUAD), fixed(head("Hero"), COL_HEROES),
                    grow(head("Skill")), fixed(right(head("Level")), COL_LEVEL), fixed(right(head("Bonus")), COL_BONUS));
            return rowBox;
        }
        String[] named = splitPurpose(s.purpose());
        Label title = nameLabel(named);
        FlowPane pills = heroPills(s.heroes(), false, "No hero left with a fitting skill");
        SquadPlanner.SkillBonus skill = s.skill();
        // A hero the scan could not read is never shown with skill numbers as if it had.
        boolean unread = skill != null && !s.heroes().isEmpty() && flaggedNow.contains(s.heroes().get(0));
        boolean none = skill == null || unread;
        String noneText = unread ? "Not read" : "No fitting skill";
        Label skillName = new Label(none ? noneText : skill.name());
        skillName.getStyleClass().add(none ? "sq-squad-why" : "sq-label");
        skillName.setWrapText(true);
        skillName.setMinHeight(Region.USE_PREF_SIZE);
        Label level = new Label(none ? (unread ? "Not read" : "") : skill.level() == null ? "Not read" : String.valueOf(skill.level()));
        level.getStyleClass().add(none ? "sq-squad-why" : "sq-label");
        Label bonus = new Label(none ? (unread ? "Not read" : "") : skill.bonus());
        bonus.getStyleClass().add(none ? "sq-squad-why" : "sq-label");
        rowBox.setMinHeight(48);
        rowBox.setPadding(new Insets(12, 0, 12, 0));
        rowBox.getChildren().addAll(fixed(title, COL_SQUAD), fixed(pills, COL_HEROES), grow(skillName),
                fixed(right(level), COL_LEVEL), fixed(right(bonus), COL_BONUS));
        return rowBox;
    }

    private HBox groupRow(List<SquadPlanner.Suggestion> group) {
        SquadPlanner.Suggestion first = group.get(0);
        List<String> names = new ArrayList<>();
        List<String> tips = new ArrayList<>();
        for (SquadPlanner.Suggestion s : group) {
            String[] named = splitPurpose(s.purpose());
            names.add(named[0]);
            if (named[1] != null) tips.add(named[0] + ": " + named[1]);
        }
        HBox row = whyRow(first.purpose(), first.heroes(), first.why(), false);
        if (names.size() > 1) {
            Label title = (Label) row.getChildren().get(0);
            title.setText(joinAnd(names));
            if (!tips.isEmpty()) Tooltip.install(title, new Tooltip(String.join(NEWLINE, tips)));
        }
        return row;
    }

    private static final String NEWLINE = System.lineSeparator();

    private HBox whyRow(String purpose, List<String> heroes, String why, boolean header) {
        HBox rowBox = new HBox(16);
        rowBox.setAlignment(Pos.TOP_LEFT);
        if (header) {
            rowBox.setPadding(new Insets(0, 0, 8, 0));
            rowBox.getChildren().addAll(fixed(head("Squad"), COL_SQUAD), fixed(head("Heroes"), COL_HEROES), grow(head("Why")));
            return rowBox;
        }
        Label reason = new Label(why == null ? "" : why);
        reason.getStyleClass().add("sq-squad-why");
        reason.setWrapText(true);
        reason.setMinHeight(Region.USE_PREF_SIZE);
        rowBox.setMinHeight(48);
        rowBox.setPadding(new Insets(12, 0, 12, 0));
        rowBox.getChildren().addAll(fixed(nameLabel(splitPurpose(purpose)), COL_SQUAD),
                fixed(heroPills(heroes, true, noHeroReason(purpose)), COL_HEROES), grow(reason));
        return rowBox;
    }

    private static Label nameLabel(String[] named) {
        Label title = new Label(named[0]);
        title.getStyleClass().add("sq-squad-title");
        title.setWrapText(true);
        title.setMinHeight(Region.USE_PREF_SIZE);
        if (named[1] != null) Tooltip.install(title, new Tooltip(named[1]));
        return title;
    }

    private boolean cleanNowStatic = true;

    private String noHeroReason(String purpose) {
        if (purpose.startsWith("Gathering")) return "No hero has a gathering skill";
        if (purpose.startsWith("Spare")) return "Kept empty";
        return "No hero left";
    }

    private static FlowPane heroPills(List<String> heroes, boolean team, String emptyText) {
        FlowPane pills = new FlowPane(8, 8);
        if (heroes == null || heroes.isEmpty()) {
            Label none = new Label(emptyText);
            none.getStyleClass().add("sq-squad-why");
            pills.getChildren().add(none);
        } else {
            for (int i = 0; i < heroes.size(); i++) {
                Label name = pill(heroes.get(i), i == 0 ? "sq-pill-lead" : "sq-pill-neutral");
                if (i == 0 && team) {
                    Label badge = new Label("Leads");
                    badge.getStyleClass().add("sq-badge");
                    HBox lead = new HBox(4, name, badge);
                    lead.setAlignment(Pos.CENTER_LEFT);
                    pills.getChildren().add(lead);
                } else {
                    pills.getChildren().add(name);
                }
            }
        }
        return pills;
    }

    private static Label right(Label l) {
        l.setAlignment(Pos.CENTER_RIGHT);
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    private static javafx.scene.Node fixed(javafx.scene.Node node, double width) {
        if (node instanceof Region r) {
            r.setMinWidth(width);
            r.setPrefWidth(width);
            r.setMaxWidth(width);
        }
        return node;
    }

    private static javafx.scene.Node grow(javafx.scene.Node node) {
        if (node instanceof Region r) {
            r.setMinWidth(120);
            r.setPrefWidth(120);
            r.setMaxWidth(Double.MAX_VALUE);
        }
        HBox.setHgrow(node, Priority.ALWAYS);
        return node;
    }

    /** A slot name, and a one-line tooltip when the name needs game knowledge to read. */
    static String[] splitPurpose(String purpose) {
        if (purpose.startsWith("Attack rally lead")) {
            return new String[] {"Attack rally lead",
                    "Used when you start a rally on Crazy Joe, Polar Terror, or in State vs State (SvS)."};
        }
        if (purpose.startsWith("Crazy Joe reinforce")) {
            return new String[] {purpose, "Joins a reinforcement against Crazy Joe."};
        }
        if (purpose.startsWith("Reinforce a garrison")) {
            return new String[] {purpose, "Joins a garrison that an ally has asked for help with."};
        }
        return new String[] {purpose, null};
    }

    // ── results: saved squads ───────────────────────────────────────

    private void fillSaved(SquadSnapshot snapshot) {
        List<List<String>> savedRows = new ArrayList<>();
        for (SavedSquad s : snapshot.savedSquads()) {
            List<Long> t = s.troops() == null ? List.of() : s.troops();
            boolean read = s.state() == SavedSquad.State.SAVED;
            String missing = read ? "Not read" : "";
            // A squad with no troops has no power to read.
            boolean noTroops = "No troops".equals(stateLabel(s));
            savedRows.add(row(String.valueOf(s.slot()), stateLabel(s),
                    s.heroes() == null || s.heroes().isEmpty() ? (read ? "No heroes" : "")
                            : s.heroes().stream().map(n -> n == null ? "Unknown hero" : n).collect(Collectors.joining(", ")),
                    number(t.size() > 0 ? t.get(0) : null, missing), number(t.size() > 1 ? t.get(1) : null, missing),
                    number(t.size() > 2 ? t.get(2) : null, missing), noTroops ? BearTrapCheck.NONE : number(s.power(), missing)));
        }
        tableSaved.setItems(FXCollections.observableArrayList(savedRows));
        fitHeroesColumn(tableSaved, savedRows);
        sizeToRows(tableSaved, savedRows.size());
        tableSaved.setPlaceholder(blankslate("Not read yet", null, false));
    }

    /** The state, or the first thing wrong with a squad that was read: a squad the player can not use as shown. */
    static String stateLabel(SavedSquad s) {
        if (s.state() != SavedSquad.State.SAVED) {
            return switch (s.state()) {
                case EMPTY_OR_LOCKED -> "Empty or locked";
                case SAME_AS_PREVIOUS -> "Same as previous";
                case UNREADABLE -> "Unreadable";
                case SAVED -> "Saved";
            };
        }
        List<Long> t = s.troops() == null ? List.of() : s.troops();
        long total = 0;
        List<String> tiny = new ArrayList<>();
        String[] types = {"infantry", "lancer", "marksman"};
        for (int i = 0; i < t.size(); i++) {
            Long n = t.get(i);
            if (n == null) continue;
            total += n;
            if (n > 0 && n < 10 && i < types.length) tiny.add(n + " " + types[i]);
        }
        if (total == 0) return "No troops";
        // One value: what is wrong and with which count, e.g. "Check counts: 1 infantry, 1 lancer".
        if (!tiny.isEmpty()) return "Check counts: " + String.join(", ", tiny);
        if (s.power() == null) return "Power not read";
        return "Saved";
    }

    private void savedColumns() {
        tableSaved.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        tableSaved.setFixedCellSize(36);
        addColumn(tableSaved, "Squad", 0, 72, false, false);
        addColumn(tableSaved, "State", 1, 216, false, true);
        TableColumn<List<String>, String> heroes = addColumn(tableSaved, "Heroes", 2, 150, false, false);
        addColumn(tableSaved, "Infantry", 3, 88, true, false);
        addColumn(tableSaved, "Lancer", 4, 88, true, false);
        addColumn(tableSaved, "Marksman", 5, 96, true, false);
        addColumn(tableSaved, "Power", 6, 104, true, false);
        growColumn(tableSaved, heroes, 72 + 216 + 88 + 88 + 96 + 104);
    }

    // ── results: troops ─────────────────────────────────────────────

    private void fillTroops(SquadSnapshot snapshot) {
        tilesTroops.getChildren().clear();
        List<List<String>> troopRows = new ArrayList<>();
        if (snapshot.troops() != null) {
            String[] total = pair(snapshot.troops().totalTroops());
            String[] hurt = pair(snapshot.troops().injured());
            long sum = snapshot.troops().stocks().stream().mapToLong(TroopStock::count).sum();
            String counted = BearTrapCheck.number(sum);
            boolean off = total[0].replace(",", "").matches("[0-9]+")
                    && Math.abs(Long.parseLong(total[0].replace(",", "")) - sum) > Math.max(1000, sum / 100);
            tilesTroops.getChildren().add(tile("Total troops", snapshot.troops().stocks().isEmpty() ? total[0] : counted,
                    off ? "The game shows " + total[0] + ", but the types below add up to " + counted + ". Scan again."
                            : "The game rounds this to " + snapshot.troops().totalTroops().toString().split("/")[0] + ".", off));
            tilesTroops.getChildren().add(tile("Injured", hurt[0],
                    "Hospital capacity " + (hurt[1] == null ? "not read" : hurt[1]), false));
            for (TroopStock stock : snapshot.troops().stocks()) {
                troopRows.add(row(stock.name(), stock.troopType(), number(stock.count())));
            }
        }
        tilesTroops.setVisible(!tilesTroops.getChildren().isEmpty());
        tilesTroops.setManaged(!tilesTroops.getChildren().isEmpty());
        tableTroops.setItems(FXCollections.observableArrayList(troopRows));
        sizeToRows(tableTroops, troopRows.size());
        tableTroops.setPlaceholder(blankslate("Not read yet", null, false));
    }

    private void troopColumns() {
        tableTroops.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        tableTroops.setFixedCellSize(36);
        TableColumn<List<String>, String> name = addColumn(tableTroops, "Troop", 0, 240, false, false);
        addColumn(tableTroops, "Type", 1, 160, false, false);
        addColumn(tableTroops, "Count", 2, 144, true, false);
        growColumn(tableTroops, name, 160 + 144);
    }

    private static VBox tile(String label, String value, String caption, boolean warn) {
        Label l = new Label(label);
        l.getStyleClass().add("sq-caption");
        Label v = new Label(value);
        v.getStyleClass().add("sq-tile-value");
        Label c = new Label(caption);
        c.getStyleClass().add("sq-caption");
        if (warn) c.getStyleClass().add("sq-verdict-warn");
        VBox tile = new VBox(4, l, v, c);
        tile.getStyleClass().add("sq-tile");
        HBox.setHgrow(tile, Priority.ALWAYS);
        tile.setMaxWidth(Double.MAX_VALUE);
        return tile;
    }

    /** The first number of the game's "553.8K/553.8K", spelled out, then the capacity it is shown against. */
    static String[] pair(Object raw) {
        if (raw == null) return new String[] {"Not read", null};
        String[] parts = expandPair(raw).split(" / ");
        return new String[] {parts[0], parts.length > 1 ? parts[1] : null};
    }

    // ── tables ──────────────────────────────────────────────────────

    private static TableColumn<List<String>, String> addColumn(TableView<List<String>> table, String header,
            int index, double width, boolean numeric, boolean pill) {
        TableColumn<List<String>, String> column = new TableColumn<>(header);
        column.setSortable(false);
        column.setReorderable(false);
        column.setMinWidth(width);
        column.setPrefWidth(width);
        if (numeric) column.getStyleClass().add("sq-num");
        column.setCellValueFactory(r -> new ReadOnlyObjectWrapper<>(
                index < r.getValue().size() ? r.getValue().get(index) : ""));
        column.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else if (pill) {
                    setText(null);
                    boolean ok = item.equals("Saved");
                    boolean warn = item.equals("Unreadable") || item.equals("No troops")
                            || item.startsWith("Check counts") || item.equals("Power not read");
                    Label p = pill(item, ok ? "sq-pill-ok" : warn ? "sq-pill-warn" : "sq-pill-neutral");
                    String why = switch (item) {
                        case "No troops" -> "This squad is saved with no troops in it.";
                        case String q when q.startsWith("Check counts") -> "A troop count under 10 is probably a misread. Check this squad in the game.";
                        case "Power not read" -> "The scan could not read this squad's power.";
                        default -> null;
                    };
                    if (why != null) Tooltip.install(p, new Tooltip(why));
                    setGraphic(p);
                } else {
                    setGraphic(null);
                    setText(item);
                }
            }
        });
        table.getColumns().add(column);
        return column;
    }

    // The one flexible column takes whatever width the fixed ones leave, never less than its own
    // minimum, so a name is never cut off with an ellipsis.
    private static void growColumn(TableView<List<String>> table, TableColumn<List<String>, String> grow, double fixed) {
        grow.prefWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(grow.getMinWidth(), table.getWidth() - fixed - 6),
                table.widthProperty(), grow.minWidthProperty()));
    }

    private static void fitHeroesColumn(TableView<List<String>> table, List<List<String>> rows) {
        double widest = 0;
        for (List<String> r : rows) {
            if (r.size() > 2) widest = Math.max(widest, new Text(r.get(2)).getLayoutBounds().getWidth());
        }
        table.getColumns().get(2).setMinWidth(Math.max(150, widest + 28));
    }

    private static void sizeToRows(TableView<List<String>> table, int rows) {
        // A table with no rows shows the empty state alone, not a bare header row.
        table.getStyleClass().remove("sq-empty");
        if (rows == 0) table.getStyleClass().add("sq-empty");
        double height = rows == 0 ? 240 : 32 + 36.0 * rows + 6;
        table.setMinHeight(height);
        table.setPrefHeight(height);
        table.setMaxHeight(height);
    }

    // ── empty and loading states ────────────────────────────────────

    private void showEmpty() {
        bearPart = null;
        showSuggestedEmpty();
        bearStrip.setVisible(false);
        bearStrip.setManaged(false);
        tilesTroops.getChildren().clear();
        tilesTroops.setVisible(false);
        tilesTroops.setManaged(false);
        tableSaved.setItems(FXCollections.observableArrayList());
        tableTroops.setItems(FXCollections.observableArrayList());
        // Nothing scanned yet and no banner, so the empty tab says what to do.
        tableSaved.setPlaceholder(blankslate("Not scanned yet", "Press Scan now to read your squads.", false));
        tableTroops.setPlaceholder(blankslate("Not scanned yet", "Press Scan now to read your squads.", false));
        sizeToRows(tableSaved, 0);
        sizeToRows(tableTroops, 0);
    }

    private void showSuggestedEmpty() {
        listSuggested.setPadding(Insets.EMPTY);
        listSuggested.getChildren().setAll(blankslate("Not scanned yet", "Press Scan now to read your squads.", false));
    }

    private void showSkeletonIfEmpty() {
        if (shown != null) return;
        VBox skeleton = new VBox(8);
        skeleton.setPadding(new Insets(12, 0, 0, 0));
        for (int i = 0; i < 3; i++) {
            Region bar = new Region();
            bar.getStyleClass().add("sq-skeleton");
            skeleton.getChildren().add(bar);
        }
        listSuggested.getChildren().setAll(skeleton);
    }

    // Title, one line saying why and what to do, and at most one primary button.
    private VBox blankslate(String title, String line, boolean withScan) {
        Label t = new Label(title);
        t.getStyleClass().add("sq-empty-title");
        VBox box = new VBox(8, t);
        if (line != null) {
            Label l = new Label(line);
            l.getStyleClass().add("sq-empty-text");
            l.setWrapText(true);
            box.getChildren().add(l);
        }
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(360);
        box.setPadding(new Insets(32, 0, 32, 0));
        VBox wrap = new VBox(box);
        wrap.setAlignment(Pos.CENTER);
        return wrap;
    }

    private static Label pill(String text, String tone) {
        Label p = new Label(text);
        p.getStyleClass().addAll("sq-pill", tone);
        p.setMinWidth(Region.USE_PREF_SIZE);
        return p;
    }

    // ── formatting ──────────────────────────────────────────────────

    // A null cell (a name the scan could not read) shows as "-" instead of failing the whole table.
    private static List<String> row(String... cells) {
        List<String> row = new ArrayList<>(cells.length);
        for (String cell : cells) row.add(cell == null ? "-" : cell);
        return row;
    }

    private static String number(Long value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%,d", value);
    }

    private static String number(Long value, String whenMissing) {
        return value == null ? whenMissing : String.format(Locale.ROOT, "%,d", value);
    }

    // The game shows "553.8K/553.8K"; the page spells the numbers out ("553,800 / 553,800").
    static String expandPair(Object raw) {
        if (raw == null) return "-";
        String[] parts = raw.toString().split("/");
        List<String> out = new ArrayList<>();
        for (String part : parts) out.add(expandCount(part.trim()));
        return String.join(" / ", out);
    }

    static String expandCount(String text) {
        Matcher m = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*([KkMmBb]?)$").matcher(text);
        if (!m.matches()) return text;
        double value = Double.parseDouble(m.group(1));
        switch (m.group(2).toUpperCase(Locale.ROOT)) {
            case "K" -> value *= 1_000;
            case "M" -> value *= 1_000_000;
            case "B" -> value *= 1_000_000_000;
            default -> { }
        }
        return String.format(Locale.ROOT, "%,d", Math.round(value));
    }

    private static Instant parse(String iso) {
        try {
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The design brief's relative-time rules: just now, minutes, Today, Yesterday, weekday, then the date. */
    static String relativeTime(Instant then, Instant now, ZoneId zone) {
        if (then == null) return "a while ago";
        Duration age = Duration.between(then, now);
        if (age.isNegative() || age.compareTo(Duration.ofMinutes(1)) < 0) return "just now";
        if (age.compareTo(Duration.ofHours(1)) < 0) {
            long minutes = age.toMinutes();
            return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        }
        LocalDate day = then.atZone(zone).toLocalDate();
        LocalDate today = now.atZone(zone).toLocalDate();
        if (day.equals(today)) return "Today " + CLOCK.format(then.atZone(zone));
        if (day.equals(today.minusDays(1))) return "Yesterday " + CLOCK.format(then.atZone(zone));
        if (!day.isBefore(today.minusDays(6))) return WEEKDAY_CLOCK.format(then.atZone(zone));
        if (day.getYear() == today.getYear()) return MONTH_DAY.format(day);
        return MONTH_DAY_YEAR.format(day);
    }
}
