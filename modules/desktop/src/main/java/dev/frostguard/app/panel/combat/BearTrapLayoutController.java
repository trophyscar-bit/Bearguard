package dev.frostguard.app.panel.combat;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.FormationSlots;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.app.shared.UtcDateTimeEditor;
import dev.frostguard.app.shared.UtcDateTimeValue;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.BooleanExpression;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import org.controlsfx.control.CheckComboBox;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class BearTrapLayoutController extends AbstractProfileController {

    private static final int MIN_LEAD_MINUTES = 1;
    private static final int MAX_LEAD_MINUTES = 60;
    private static final ConfigurationKeyEnum START_KEY = ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING;
    private static final ConfigurationKeyEnum GUARD_ENABLED_KEY = ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL;
    private static final ConfigurationKeyEnum GUARD_BLOCK_KEY = ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_BLOCK_RALLIES_BOOL;
    private static final ConfigurationKeyEnum GUARD_PAUSE_KEY = ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_PAUSE_ALL_TASKS_BOOL;

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
    private Label labelJoinError;

    @FXML
    private CheckBox checkBoxCallRally;

    @FXML
    private HBox rowOwnSquad;

    @FXML
    private ComboBox<Integer> comboBoxRallyFlag;

    @FXML
    private CheckBox checkBoxRecallTroops;

    @FXML
    private CheckBox checkBoxActivePets;

    private final BooleanProperty startSet = new SimpleBooleanProperty(false);
    private boolean loadingGuard;
    private boolean squadRulesPending;
    private Integer lastHeroSquad;

    @FXML
    private void initialize() {
        registerConfigurationFields();
        showEverySquadChoice();
        configureGuard();
        configureBearStart();
        bindEnabledState();
        initializeChangeEvents();
        watchSquads();
    }

    private void registerConfigurationFields() {
        checkBoxMappings.put(checkBoxEnableBearTrap, ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL);
        checkBoxMappings.put(checkBoxIconStart, ConfigurationKeyEnum.BEAR_TRAP_ICON_PARTICIPATION_FALLBACK_BOOL);
        checkBoxMappings.put(checkBoxActivePets, ConfigurationKeyEnum.BEAR_TRAP_ACTIVE_PETS_BOOL);
        checkBoxMappings.put(checkBoxRecallTroops, ConfigurationKeyEnum.BEAR_TRAP_RECALL_TROOPS_BOOL);
        checkBoxMappings.put(checkBoxCallRally, ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL);
        checkBoxMappings.put(checkBoxEnableJoin, ConfigurationKeyEnum.BEAR_TRAP_JOIN_RALLY_BOOL);

        registerRangedIntegerField(textFieldPreparationTime, labelPreparationTimeError,
                ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT, "Lead", MIN_LEAD_MINUTES, MAX_LEAD_MINUTES);

        comboBoxTrapNumber.getItems().setAll(1, 2);
        comboBoxMappings.put(comboBoxTrapNumber, ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT);
        comboBoxMappings.put(comboBoxRallyFlag, ConfigurationKeyEnum.BEAR_TRAP_RALLY_FLAG_INT);
        checkComboBoxMappings.put(checkComboBoxJoinFlag, ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT);
        checkComboBoxMappings.put(checkComboBoxTroopFlag, ConfigurationKeyEnum.BEAR_TRAP_JOIN_TROOP_FLAG_STRING);
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
            startSet.set(true);
            if (firstStart && comboBoxGuard.getValue() == Guard.OFF) {
                comboBoxGuard.setValue(Guard.BLOCK_RALLIES);
            }
        });
        timerDateTimeEditor.setOnClear(() -> {
            publishWhenReady(START_KEY, "");
            startSet.set(false);
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
        BooleanExpression ownRally = checkBoxCallRally.selectedProperty();
        BooleanExpression iconStart = checkBoxIconStart.selectedProperty();
        BooleanBinding noHeroSquad = Bindings.isEmpty(checkComboBoxJoinFlag.getCheckModel().getCheckedItems());

        // Each rule leaves a control that is already on free to be switched off, so a saved
        // combination the page would no longer allow can always be undone.
        checkBoxEnableBearTrap.disableProperty().bind(startSet.not().and(autoOff));
        comboBoxGuard.disableProperty().bind(startSet.not());
        timerDateTimeEditor.timerEnabledProperty().bind(autoOff.not().or(
                Bindings.createBooleanBinding(() -> comboBoxGuard.getValue() != Guard.OFF, comboBoxGuard.valueProperty())));

        checkBoxIconStart.disableProperty().bind(autoOff.or(ownRally.and(iconStart.not())));
        checkBoxCallRally.disableProperty().bind(autoOff.or(iconStart.and(ownRally.not())));
        checkBoxEnableJoin.disableProperty().bind(autoOff.or(noHeroSquad.and(joining.not())));
        checkComboBoxJoinFlag.disableProperty().bind(autoOff);
        checkComboBoxTroopFlag.disableProperty().bind(autoOff);
        comboBoxRallyFlag.disableProperty().bind(autoOff);
        checkBoxActivePets.disableProperty().bind(autoOff);
        checkBoxRecallTroops.disableProperty().bind(autoOff.or(joining.not().and(ownRally.not())));

        bindManagedVisibility(rowOwnSquad, ownRally);
        bindManagedVisibility(labelJoinError, joining.and(noHeroSquad));
    }

    private void watchSquads() {
        ListChangeListener<Integer> squadsChanged = change -> scheduleSquadRules();
        checkComboBoxJoinFlag.getCheckModel().getCheckedItems().addListener(squadsChanged);
        checkComboBoxTroopFlag.getCheckModel().getCheckedItems().addListener(squadsChanged);
        comboBoxRallyFlag.valueProperty().addListener((obs, oldValue, newValue) -> scheduleSquadRules());
        checkBoxCallRally.selectedProperty().addListener((obs, oldValue, newValue) -> scheduleSquadRules());
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
            keepAHeroSquadWhileJoining();
            moveOwnSquadOffAPickedOne();
            refreshSquadChoices();
        });
    }

    private void keepAHeroSquadWhileJoining() {
        List<Integer> checked = checkComboBoxJoinFlag.getCheckModel().getCheckedItems();
        if (!checked.isEmpty()) {
            lastHeroSquad = checked.getLast();
        } else if (checkBoxEnableJoin.isSelected() && lastHeroSquad != null) {
            checkComboBoxJoinFlag.getCheckModel().check(lastHeroSquad);
        }
    }

    private void moveOwnSquadOffAPickedOne() {
        Set<Integer> joining = joinSquads();
        if (checkBoxCallRally.isSelected() && joining.contains(comboBoxRallyFlag.getValue())) {
            FormationSlots.numbers().stream()
                    .filter(squad -> !joining.contains(squad))
                    .findFirst()
                    .ifPresent(comboBoxRallyFlag::setValue);
        }
    }

    private Set<Integer> joinSquads() {
        Set<Integer> squads = new HashSet<>(checkComboBoxJoinFlag.getCheckModel().getCheckedItems());
        squads.addAll(checkComboBoxTroopFlag.getCheckModel().getCheckedItems());
        return squads;
    }

    /** Updating which choices are offered is not an edit, so it never saves anything. */
    private void refreshSquadChoices() {
        boolean wasLoading = isLoadingProfile;
        isLoadingProfile = true;
        try {
            Set<Integer> hero = Set.copyOf(checkComboBoxJoinFlag.getCheckModel().getCheckedItems());
            Set<Integer> troop = Set.copyOf(checkComboBoxTroopFlag.getCheckModel().getCheckedItems());
            Set<Integer> own = checkBoxCallRally.isSelected() && comboBoxRallyFlag.getValue() != null
                    ? Set.of(comboBoxRallyFlag.getValue())
                    : Set.of();
            offer(checkComboBoxJoinFlag, available(hero, troop, own));
            offer(checkComboBoxTroopFlag, available(troop, hero, own));
            Integer current = comboBoxRallyFlag.getValue();
            comboBoxRallyFlag.getItems().setAll(available(own, hero, troop));
            comboBoxRallyFlag.setValue(current);
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
        offer(checkComboBoxJoinFlag, FormationSlots.numbers());
        offer(checkComboBoxTroopFlag, FormationSlots.numbers());
        comboBoxRallyFlag.getItems().setAll(FormationSlots.numbers());
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
            timerDateTimeEditor.setDateTime(loadSavedStart(profile));
            startSet.set(timerDateTimeEditor.hasCommittedDateTime());
            comboBoxGuard.setValue(startSet.get() ? loadGuard(profile) : Guard.OFF);
            List<Integer> heroSquads = checkComboBoxJoinFlag.getCheckModel().getCheckedItems();
            lastHeroSquad = heroSquads.isEmpty() ? null : heroSquads.getLast();
            refreshSquadChoices();
        } finally {
            loadingGuard = false;
            isLoadingProfile = false;
        }
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

    private static void bindManagedVisibility(Node node, javafx.beans.value.ObservableBooleanValue visible) {
        node.visibleProperty().bind(visible);
        node.managedProperty().bind(node.visibleProperty());
    }

    private enum Guard {
        OFF("Off"),
        BLOCK_RALLIES("Block rallies"),
        PAUSE_ALL("Pause all");

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
