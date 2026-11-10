package dev.frostguard.app.panel.combat;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.FormationSlots;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.app.shared.SingleHomeSettingsPage;
import dev.frostguard.app.shared.UtcDateTimeEditor;
import dev.frostguard.app.shared.UtcDateTimeValue;
import dev.frostguard.tasks.combat.BearJoinSquadKeys;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.BooleanExpression;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.beans.value.ObservableStringValue;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.event.EventTarget;
import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.controlsfx.control.CheckComboBox;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class BearTrapLayoutController extends AbstractProfileController implements SingleHomeSettingsPage {

    private static final int MIN_LEAD_MINUTES = 1;
    private static final int MAX_LEAD_MINUTES = 60;
    private static final ConfigurationKeyEnum START_KEY = ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING;
    private static final ConfigurationKeyEnum GUARD_ENABLED_KEY = ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL;
    private static final ConfigurationKeyEnum GUARD_BLOCK_KEY = ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_BLOCK_RALLIES_BOOL;
    private static final ConfigurationKeyEnum GUARD_PAUSE_KEY = ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_PAUSE_ALL_TASKS_BOOL;
    private static final PseudoClass ROLE_ACTIVE = PseudoClass.getPseudoClass("role-active");
    private static final String ACTIVE_BADGE = "ACTIVE";

    @FXML
    private CheckBox checkBoxEnableBearTrap;

    @FXML
    private TextField textFieldPreparationTime;

    @FXML
    private Label labelPreparationTimeError;

    @FXML
    private UtcDateTimeEditor timerDateTimeEditor;

    @FXML
    private ComboBox<Integer> comboBoxTrapNumber;

    @FXML
    private ComboBox<Guard> comboBoxGuard;

    @FXML
    private CheckBox checkBoxIconStart;

    @FXML
    private CheckBox checkBoxEnableJoin;

    @FXML
    private CheckComboBox<Integer> checkComboBoxJoinFlag;

    @FXML
    private CheckComboBox<Integer> checkComboBoxTroopFlag;

    @FXML
    private CheckComboBox<Integer> checkComboBoxLeaderJoinFlag;

    @FXML
    private CheckComboBox<Integer> checkComboBoxLeaderTroopFlag;

    @FXML
    private Label labelJoinError;

    @FXML
    private RadioButton radioLeader;

    @FXML
    private RadioButton radioJoiner;

    @FXML
    private VBox sectionLeader;

    @FXML
    private VBox sectionJoiner;

    @FXML
    private HBox headerLeader;

    @FXML
    private HBox headerJoiner;

    @FXML
    private Label labelLeaderActive;

    @FXML
    private Label labelJoinerActive;

    @FXML
    private Label labelLeaderSummary;

    @FXML
    private Label labelJoinerSummary;

    @FXML
    private Label labelLeaderBlocked;

    @FXML
    private ComboBox<Integer> comboBoxRallyFlag;

    @FXML
    private CheckBox checkBoxRecallTroops;

    @FXML
    private CheckBox checkBoxActivePets;

    private final ObjectProperty<LocalDateTime> savedStart = new SimpleObjectProperty<>();
    private final BooleanBinding startSet = savedStart.isNotNull();
    private final Map<CheckComboBox<Integer>, Integer> lastHeroSquad = new HashMap<>();
    private StringBinding settingsSummary;
    private boolean loadingGuard;
    private boolean squadRulesPending;

    @FXML
    private void initialize() {
        registerConfigurationFields();
        configureRole();
        showEverySquadChoice();
        configureGuard();
        configureBearStart();
        bindEnabledState();
        initializeChangeEvents();
        watchSquads();
        settingsSummary = Bindings.createStringBinding(this::summarize, radioLeader.selectedProperty(),
                comboBoxTrapNumber.valueProperty(), savedStart, checkBoxEnableBearTrap.selectedProperty());
    }

    @Override
    public ObservableStringValue settingsSummary() {
        return settingsSummary;
    }

    @Override
    public String settingsHome() {
        return "Events > Bear Trap";
    }

    private String summarize() {
        List<String> parts = new ArrayList<>();
        parts.add(radioLeader.isSelected() ? "Leader" : "Joiner");
        parts.add("Trap " + Optional.ofNullable(comboBoxTrapNumber.getValue()).orElse(1));
        LocalDateTime start = savedStart.get();
        parts.add(start == null ? "No start time" : UtcDateTimeValue.formatLocalTime(
                UtcDateTimeValue.nextActivation(start, Clock.systemUTC()), ZoneId.systemDefault()));
        parts.add(checkBoxEnableBearTrap.isSelected() ? "Auto bear on" : "Auto bear off");
        return String.join(" · ", parts);
    }

    private void registerConfigurationFields() {
        checkBoxMappings.put(checkBoxEnableBearTrap, ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL);
        checkBoxMappings.put(checkBoxIconStart, ConfigurationKeyEnum.BEAR_TRAP_ICON_PARTICIPATION_FALLBACK_BOOL);
        checkBoxMappings.put(checkBoxActivePets, ConfigurationKeyEnum.BEAR_TRAP_ACTIVE_PETS_BOOL);
        checkBoxMappings.put(checkBoxRecallTroops, ConfigurationKeyEnum.BEAR_TRAP_RECALL_TROOPS_BOOL);
        // Joiner is the other half of one toggle group, so Leader alone carries the stored value.
        radioButtonMappings.put(radioLeader, ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL);
        checkBoxMappings.put(checkBoxEnableJoin, ConfigurationKeyEnum.BEAR_TRAP_JOIN_RALLY_BOOL);

        registerRangedIntegerField(textFieldPreparationTime, labelPreparationTimeError,
                ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT, "Get ready", MIN_LEAD_MINUTES, MAX_LEAD_MINUTES);

        comboBoxTrapNumber.getItems().setAll(1, 2);
        comboBoxMappings.put(comboBoxTrapNumber, ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT);
        comboBoxMappings.put(comboBoxRallyFlag, ConfigurationKeyEnum.BEAR_TRAP_RALLY_FLAG_INT);
        checkComboBoxMappings.put(checkComboBoxJoinFlag, BearJoinSquadKeys.JOINER.hero());
        checkComboBoxMappings.put(checkComboBoxTroopFlag, BearJoinSquadKeys.JOINER.troop());
        // The leader lists load with a fallback to the joiner lists, so they save through their own listener.
        ListChangeListener<Integer> leaderSquadsChanged = change -> publishLeaderSquads();
        checkComboBoxLeaderJoinFlag.getCheckModel().getCheckedItems().addListener(leaderSquadsChanged);
        checkComboBoxLeaderTroopFlag.getCheckModel().getCheckedItems().addListener(leaderSquadsChanged);
    }

    /** Both lists are saved together, so a leader edit never leaves one list falling back to the joiner's. */
    private void publishLeaderSquads() {
        publishWhenReady(BearJoinSquadKeys.LEADER.hero(), joined(checkComboBoxLeaderJoinFlag));
        publishWhenReady(BearJoinSquadKeys.LEADER.troop(), joined(checkComboBoxLeaderTroopFlag));
    }

    private void configureRole() {
        createToggleGroup(radioLeader, radioJoiner);
        radioJoiner.setSelected(true);
        // Only the picked card says what the role does, so the sentence appears once on the page.
        labelLeaderSummary.textProperty().bind(Bindings.createStringBinding(
                () -> radioLeader.isSelected() ? roleSummary() : "",
                radioLeader.selectedProperty(), comboBoxRallyFlag.valueProperty(), checkBoxEnableJoin.selectedProperty()));
        labelJoinerSummary.textProperty().bind(Bindings.createStringBinding(
                () -> radioLeader.isSelected() ? "" : roleSummary(),
                radioLeader.selectedProperty(), checkBoxEnableJoin.selectedProperty()));
        bindManagedVisibility(labelLeaderSummary, radioLeader.selectedProperty());
        bindManagedVisibility(labelJoinerSummary, radioLeader.selectedProperty().not());
        radioLeader.selectedProperty().addListener((obs, oldValue, leading) -> showActiveSection(leading));
        showActiveSection(radioLeader.isSelected());
        selectByClicking(sectionLeader, headerLeader, radioLeader);
        selectByClicking(sectionJoiner, headerJoiner, radioJoiner);
    }

    /** The whole card picks its role, except a click that lands on one of its own inputs. */
    private static void selectByClicking(VBox section, HBox header, RadioButton pick) {
        section.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (!pick.isDisabled() && !landsOnAnInput(event.getTarget(), section)) {
                pick.fire();
            }
        });
        header.cursorProperty().bind(Bindings.when(pick.disabledProperty())
                .then(Cursor.DEFAULT).otherwise(Cursor.HAND));
    }

    private static boolean landsOnAnInput(EventTarget target, Node section) {
        for (Node node = target instanceof Node hit ? hit : null; node != null && node != section; node = node.getParent()) {
            if (node instanceof Control && !(node instanceof Label) && !(node instanceof RadioButton)) {
                return true;
            }
        }
        return false;
    }

    /** The other role's card stays editable, so a switch of role finds its settings ready. */
    private void showActiveSection(boolean leading) {
        sectionLeader.pseudoClassStateChanged(ROLE_ACTIVE, leading);
        sectionJoiner.pseudoClassStateChanged(ROLE_ACTIVE, !leading);
        labelLeaderActive.setText(leading ? ACTIVE_BADGE : "");
        labelJoinerActive.setText(leading ? "" : ACTIVE_BADGE);
        labelLeaderActive.setVisible(leading);
        labelJoinerActive.setVisible(!leading);
    }

    private String roleSummary() {
        boolean joining = checkBoxEnableJoin.isSelected();
        if (radioLeader.isSelected()) {
            Integer squad = comboBoxRallyFlag.getValue();
            String start = squad == null ? "You start the rally" : "You start the rally on squad " + squad;
            return joining ? start + " and join others." : start + ".";
        }
        return joining ? "You only join other rallies." : "Join rallies is off, so you will not rally.";
    }

    private void configureGuard() {
        comboBoxGuard.getItems().setAll(Guard.values());
        comboBoxGuard.setValue(Guard.OFF);
        comboBoxGuard.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null && !loadingGuard) {
                publishGuard(newValue);
            }
        });
    }

    private void publishGuard(Guard guard) {
        publishWhenReady(GUARD_ENABLED_KEY, guard != Guard.OFF);
        publishWhenReady(GUARD_BLOCK_KEY, guard == Guard.BLOCK_RALLIES);
        publishWhenReady(GUARD_PAUSE_KEY, guard == Guard.PAUSE_ALL);
    }

    private void configureBearStart() {
        timerDateTimeEditor.setOnCommit(value -> {
            boolean firstStart = !startSet.get();
            publishWhenReady(START_KEY, UtcDateTimeValue.formatPersisted(value));
            savedStart.set(value);
            if (firstStart && comboBoxGuard.getValue() == Guard.OFF) {
                comboBoxGuard.setValue(Guard.BLOCK_RALLIES);
            }
        });
        timerDateTimeEditor.setOnClear(() -> {
            publishWhenReady(START_KEY, "");
            savedStart.set(null);
            loadingGuard = true;
            try {
                comboBoxGuard.setValue(Guard.OFF);
            } finally {
                loadingGuard = false;
            }
            publishGuard(Guard.OFF);
            checkBoxEnableBearTrap.setSelected(false);
        });
    }

    private void bindEnabledState() {
        BooleanExpression autoOff = checkBoxEnableBearTrap.selectedProperty().not();
        BooleanExpression joining = checkBoxEnableJoin.selectedProperty();
        BooleanExpression ownRally = radioLeader.selectedProperty();
        BooleanExpression iconStart = checkBoxIconStart.selectedProperty();
        BooleanBinding noHeroSquad = Bindings.createBooleanBinding(
                () -> activeHeroSquads().getCheckModel().getCheckedItems().isEmpty(), ownRally,
                checkComboBoxJoinFlag.getCheckModel().getCheckedItems(),
                checkComboBoxLeaderJoinFlag.getCheckModel().getCheckedItems());

        // Each rule leaves a control that is already on free to be switched off, so a saved
        // combination the page would no longer allow can always be undone.
        checkBoxEnableBearTrap.disableProperty().bind(startSet.not().and(autoOff));
        comboBoxGuard.disableProperty().bind(startSet.not());
        timerDateTimeEditor.timerEnabledProperty().bind(autoOff.not().or(
                Bindings.createBooleanBinding(() -> comboBoxGuard.getValue() != Guard.OFF, comboBoxGuard.valueProperty())));

        checkBoxIconStart.disableProperty().bind(autoOff.or(ownRally.and(iconStart.not())));
        radioLeader.disableProperty().bind(autoOff.or(iconStart.and(ownRally.not())));
        radioJoiner.disableProperty().bind(autoOff);
        checkBoxEnableJoin.disableProperty().bind(autoOff.or(noHeroSquad.and(joining.not())));
        for (CheckComboBox<Integer> squads : allSquadLists()) {
            squads.disableProperty().bind(autoOff);
        }
        comboBoxRallyFlag.disableProperty().bind(autoOff);
        checkBoxActivePets.disableProperty().bind(autoOff);
        checkBoxRecallTroops.disableProperty().bind(autoOff.or(joining.not().and(ownRally.not())));

        bindManagedVisibility(labelJoinError, joining.and(noHeroSquad));
        bindManagedVisibility(labelLeaderBlocked, iconStart.and(ownRally.not()));
    }

    private CheckComboBox<Integer> activeHeroSquads() {
        return radioLeader.isSelected() ? checkComboBoxLeaderJoinFlag : checkComboBoxJoinFlag;
    }

    private List<CheckComboBox<Integer>> allSquadLists() {
        return List.of(checkComboBoxLeaderJoinFlag, checkComboBoxLeaderTroopFlag, checkComboBoxJoinFlag,
                checkComboBoxTroopFlag);
    }

    private void watchSquads() {
        ListChangeListener<Integer> squadsChanged = change -> scheduleSquadRules();
        allSquadLists().forEach(squads -> squads.getCheckModel().getCheckedItems().addListener(squadsChanged));
        comboBoxRallyFlag.valueProperty().addListener((obs, oldValue, newValue) -> scheduleSquadRules());
        radioLeader.selectedProperty().addListener((obs, oldValue, newValue) -> scheduleSquadRules());
        checkBoxEnableJoin.selectedProperty().addListener((obs, oldValue, newValue) -> scheduleSquadRules());
    }

    /** Waits a turn because the lists cannot be rebuilt from inside their own change event. */
    private void scheduleSquadRules() {
        if (isLoadingProfile || squadRulesPending) {
            return;
        }
        squadRulesPending = true;
        Platform.runLater(() -> {
            squadRulesPending = false;
            keepAHeroSquadWhileJoining(checkComboBoxJoinFlag);
            keepAHeroSquadWhileJoining(checkComboBoxLeaderJoinFlag);
            moveOwnSquadOffAPickedOne();
            refreshSquadChoices();
        });
    }

    private void keepAHeroSquadWhileJoining(CheckComboBox<Integer> heroSquads) {
        List<Integer> checked = heroSquads.getCheckModel().getCheckedItems();
        Integer last = lastHeroSquad.get(heroSquads);
        if (!checked.isEmpty()) {
            lastHeroSquad.put(heroSquads, checked.getLast());
        } else if (checkBoxEnableJoin.isSelected() && last != null) {
            heroSquads.getCheckModel().check(last);
        }
    }

    private void moveOwnSquadOffAPickedOne() {
        Set<Integer> joining = picked(checkComboBoxLeaderJoinFlag, checkComboBoxLeaderTroopFlag);
        if (radioLeader.isSelected() && joining.contains(comboBoxRallyFlag.getValue())) {
            FormationSlots.numbers().stream()
                    .filter(squad -> !joining.contains(squad))
                    .findFirst()
                    .ifPresent(comboBoxRallyFlag::setValue);
        }
    }

    @SafeVarargs
    private static Set<Integer> picked(CheckComboBox<Integer>... lists) {
        Set<Integer> squads = new HashSet<>();
        for (CheckComboBox<Integer> list : lists) {
            squads.addAll(list.getCheckModel().getCheckedItems());
        }
        return squads;
    }

    /**
     * Updating which choices are offered is not an edit, so it never saves anything. Each role keeps
     * its squads apart: a squad can join as a hero squad or a troop squad, or lead, but not two of these.
     * Only one role runs at a time, so the two roles may pick the same squad.
     */
    private void refreshSquadChoices() {
        boolean wasLoading = isLoadingProfile;
        isLoadingProfile = true;
        try {
            Set<Integer> hero = picked(checkComboBoxJoinFlag);
            Set<Integer> troop = picked(checkComboBoxTroopFlag);
            offer(checkComboBoxJoinFlag, available(hero, troop, Set.of()));
            offer(checkComboBoxTroopFlag, available(troop, hero, Set.of()));

            Integer rally = comboBoxRallyFlag.getValue();
            Set<Integer> rallyPick = rally == null ? Set.of() : Set.of(rally);
            Set<Integer> leaderHero = picked(checkComboBoxLeaderJoinFlag);
            Set<Integer> leaderTroop = picked(checkComboBoxLeaderTroopFlag);
            offer(checkComboBoxLeaderJoinFlag, available(leaderHero, leaderTroop, rallyPick));
            offer(checkComboBoxLeaderTroopFlag, available(leaderTroop, leaderHero, rallyPick));
            comboBoxRallyFlag.getItems().setAll(available(rallyPick, leaderHero, leaderTroop));
            comboBoxRallyFlag.setValue(rally);
        } finally {
            isLoadingProfile = wasLoading;
        }
    }

    /** A squad another group took is left out, unless a saved overlap already has it picked here too. */
    private static List<Integer> available(Set<Integer> mine, Set<Integer> first, Set<Integer> second) {
        return FormationSlots.numbers().stream()
                .filter(squad -> mine.contains(squad) || (!first.contains(squad) && !second.contains(squad)))
                .toList();
    }

    private static void offer(CheckComboBox<Integer> combo, List<Integer> choices) {
        if (combo.getItems().equals(choices)) {
            return;
        }
        List<Integer> checked = List.copyOf(combo.getCheckModel().getCheckedItems());
        combo.getCheckModel().clearChecks();
        combo.getItems().setAll(choices);
        checked.forEach(combo.getCheckModel()::check);
    }

    private void showEverySquadChoice() {
        allSquadLists().forEach(squads -> offer(squads, FormationSlots.numbers()));
        comboBoxRallyFlag.getItems().setAll(FormationSlots.numbers());
    }

    private static String joined(CheckComboBox<Integer> squads) {
        return squads.getCheckModel().getCheckedItems().stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static void checkSaved(CheckComboBox<Integer> squads, String saved) {
        squads.getCheckModel().clearChecks();
        if (saved == null || saved.isBlank()) {
            return;
        }
        for (String token : saved.split(",")) {
            try {
                Integer squad = Integer.valueOf(token.trim());
                if (squads.getItems().contains(squad)) {
                    squads.getCheckModel().check(squad);
                }
            } catch (NumberFormatException ignored) {
                // The routine skips an unreadable entry too, so the page shows what it will use.
            }
        }
    }

    @Override
    public void onProfileLoad(ProfileAux profile) {
        isLoadingProfile = true;
        try {
            // The last profile may have narrowed the lists, which would drop this profile's picks.
            showEverySquadChoice();
        } finally {
            isLoadingProfile = false;
        }
        super.onProfileLoad(profile);
        isLoadingProfile = true;
        loadingGuard = true;
        try {
            if (!radioLeader.isSelected()) {
                radioJoiner.setSelected(true);
            }
            loadLeaderSquads(profile);
            LocalDateTime start = loadSavedStart(profile);
            timerDateTimeEditor.setDateTime(start);
            savedStart.set(start);
            comboBoxGuard.setValue(startSet.get() ? loadGuard(profile) : Guard.OFF);
            lastHeroSquad.clear();
            for (CheckComboBox<Integer> heroSquads : List.of(checkComboBoxJoinFlag, checkComboBoxLeaderJoinFlag)) {
                List<Integer> checked = heroSquads.getCheckModel().getCheckedItems();
                if (!checked.isEmpty()) {
                    lastHeroSquad.put(heroSquads, checked.getLast());
                }
            }
            refreshSquadChoices();
        } finally {
            loadingGuard = false;
            isLoadingProfile = false;
        }
    }

    /** Shows the squads a leader run will really use, including the joiner fallback before any are saved. */
    private void loadLeaderSquads(ProfileAux profile) {
        String leaderHero = profile.getConfiguration(BearJoinSquadKeys.LEADER.hero());
        BearJoinSquadKeys used = BearJoinSquadKeys.forRole(true, leaderHero);
        checkSaved(checkComboBoxLeaderJoinFlag, profile.getConfiguration(used.hero()));
        checkSaved(checkComboBoxLeaderTroopFlag, profile.getConfiguration(used.troop()));
    }

    private Guard loadGuard(ProfileAux profile) {
        boolean explicitlyEnabled = Boolean.TRUE.equals(profile.<Boolean>getConfiguration(GUARD_ENABLED_KEY));
        boolean legacyEnabled = !profileHasConfiguration(profile, GUARD_ENABLED_KEY)
                && Boolean.TRUE.equals(profile.<Boolean>getConfiguration(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL));
        if (!explicitlyEnabled && !legacyEnabled) {
            return Guard.OFF;
        }
        if (Boolean.TRUE.equals(profile.<Boolean>getConfiguration(GUARD_PAUSE_KEY))) {
            return Guard.PAUSE_ALL;
        }
        return legacyEnabled || Boolean.TRUE.equals(profile.<Boolean>getConfiguration(GUARD_BLOCK_KEY))
                ? Guard.BLOCK_RALLIES
                : Guard.OFF;
    }

    private boolean profileHasConfiguration(ProfileAux profile, ConfigurationKeyEnum key) {
        return profile.getConfigs().stream().anyMatch(config -> key.name().equalsIgnoreCase(config.getName()));
    }

    private LocalDateTime loadSavedStart(ProfileAux profile) {
        return profile.getConfigs().stream()
                .filter(config -> START_KEY.name().equalsIgnoreCase(config.getName()))
                .map(config -> config.getValue())
                .map(UtcDateTimeValue::parsePersisted)
                .flatMap(Optional::stream)
                .findFirst()
                .orElse(null);
    }

    private static void bindManagedVisibility(Node node, ObservableBooleanValue visible) {
        node.visibleProperty().bind(visible);
        node.managedProperty().bind(node.visibleProperty());
    }

    private enum Guard {
        OFF("Keep everything running"),
        BLOCK_RALLIES("Keep other tasks running, but don't start new rallies"),
        PAUSE_ALL("Pause all other tasks");

        private final String label;

        Guard(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
