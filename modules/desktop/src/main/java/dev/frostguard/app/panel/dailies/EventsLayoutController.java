package dev.frostguard.app.panel.dailies;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.FormationSlots;
import dev.frostguard.app.panel.launcher.NavOrder;
import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.app.shared.SettingValidators;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import org.controlsfx.control.CheckComboBox;

public class EventsLayoutController extends AbstractProfileController {

    @FXML
    private CheckBox checkBoxTundraEvent, checkBoxTundraUseGems, checkBoxTundraSSR, checkBoxHeroMission,
        checkBoxMercenaryEvent, checkBoxJourneyofLight, checkBoxJourneyofLightAssembleCommon,
        checkBoxJourneyofLightAssemblePremium, checkBoxMyriadBazaar, checkBoxTundraEventActivationHour, checkBoxMergeventure, checkBoxMergeventureBoard,
        checkBoxMergeventureUseMax, checkBoxMergeventureMissions, checkBoxMergeventureAtlasPath,
        checkBoxMergeventureHarvest, checkBoxMergeventureCaravan, checkBoxMergeventureSaveSojourner, checkBoxSignInEarn, checkBoxWanderTheater, checkBoxWanderTheaterRally,
        checkBoxWanderTheaterExplore, checkBoxDeadShotSignIn, checkBoxWinterSiege, checkBoxFortuneHut, checkBoxFortuneHutPlay, checkBoxFacilityJoin, checkBoxFacilityRecall, checkBoxRedDotSweep;

    @FXML
    private TextField textfieldTundraActivationHour, textfieldMergeventureSpendHours,
        textfieldWanderTheaterReserve, textfieldFortuneHutBankThreshold, textfieldFortuneHutWish;

    @FXML
    private ComboBox<Integer> comboBoxMercenaryFlag, comboBoxHeroMissionFlag, comboBoxWanderTheaterRallyFlag;

    @FXML
    private CheckComboBox<Integer> checkComboBoxFacilityFlag;

    @FXML
    private Label labelFacilityDetected;

    @FXML
    private TabPane tabPaneEvents;

    @FXML
    private Label labelDateTimeError;

    @FXML
    private void initialize() {
        tabPaneEvents.getTabs().sort(java.util.Comparator.comparing(Tab::getText, NavOrder.BY_NAME));
        fillFlagOptions();
        registerConfigurationFields();
        wireEnablementRules();
        prepareActivationTimeField();
        initializeChangeEvents();
    }

    private void fillFlagOptions() {
        comboBoxMercenaryFlag.getItems().setAll(FormationSlots.numbersWithNone(0));
        comboBoxHeroMissionFlag.getItems().setAll(FormationSlots.numbersWithNone(0));
        comboBoxWanderTheaterRallyFlag.getItems().setAll(FormationSlots.numbers());
        checkComboBoxFacilityFlag.getItems().setAll(FormationSlots.numbers());
    }

    private void registerConfigurationFields() {
        checkBoxMappings.put(checkBoxTundraEvent, ConfigurationKeyEnum.TUNDRA_TRUCK_EVENT_BOOL);
        checkBoxMappings.put(checkBoxTundraUseGems, ConfigurationKeyEnum.TUNDRA_TRUCK_USE_GEMS_BOOL);
        checkBoxMappings.put(checkBoxTundraSSR, ConfigurationKeyEnum.TUNDRA_TRUCK_SSR_BOOL);
        checkBoxMappings.put(checkBoxTundraEventActivationHour, ConfigurationKeyEnum.TUNDRA_TRUCK_ACTIVATION_TIME_BOOL);
        checkBoxMappings.put(checkBoxHeroMission, ConfigurationKeyEnum.HERO_MISSION_EVENT_BOOL);
        checkBoxMappings.put(checkBoxMercenaryEvent, ConfigurationKeyEnum.MERCENARY_EVENT_BOOL);
        checkBoxMappings.put(checkBoxJourneyofLight, ConfigurationKeyEnum.JOURNEY_OF_LIGHT_BOOL);
        checkBoxMappings.put(checkBoxJourneyofLightAssembleCommon, ConfigurationKeyEnum.JOURNEY_OF_LIGHT_ASSEMBLE_COMMON_BOOL);
        checkBoxMappings.put(checkBoxJourneyofLightAssemblePremium, ConfigurationKeyEnum.JOURNEY_OF_LIGHT_ASSEMBLE_PREMIUM_BOOL);
        checkBoxMappings.put(checkBoxMyriadBazaar, ConfigurationKeyEnum.MYRIAD_BAZAAR_EVENT_BOOL);
        checkBoxMappings.put(checkBoxMergeventure, ConfigurationKeyEnum.MERGEVENTURE_ENABLED_BOOL);
        checkBoxMappings.put(checkBoxMergeventureBoard, ConfigurationKeyEnum.MERGEVENTURE_PLAY_BOARD_BOOL);
        checkBoxMappings.put(checkBoxMergeventureUseMax, ConfigurationKeyEnum.MERGEVENTURE_USE_MAX_PIECES_BOOL);
        checkBoxMappings.put(checkBoxMergeventureMissions, ConfigurationKeyEnum.MERGEVENTURE_MISSIONS_BOOL);
        checkBoxMappings.put(checkBoxMergeventureAtlasPath, ConfigurationKeyEnum.MERGEVENTURE_ATLAS_PATH_BOOL);
        checkBoxMappings.put(checkBoxMergeventureHarvest, ConfigurationKeyEnum.MERGEVENTURE_HARVEST_BOOL);
        checkBoxMappings.put(checkBoxMergeventureCaravan, ConfigurationKeyEnum.MERGEVENTURE_CARAVAN_BOOL);
        checkBoxMappings.put(checkBoxMergeventureSaveSojourner, ConfigurationKeyEnum.MERGEVENTURE_SAVE_FOR_SOJOURNER_BOOL);
        textFieldMappings.put(textfieldMergeventureSpendHours, ConfigurationKeyEnum.MERGEVENTURE_SPEND_HOURS_INT);
        checkBoxMappings.put(checkBoxSignInEarn, ConfigurationKeyEnum.SIGN_IN_EARN_ENABLED_BOOL);
        checkBoxMappings.put(checkBoxWanderTheater, ConfigurationKeyEnum.WANDER_THEATER_ENABLED_BOOL);
        checkBoxMappings.put(checkBoxWanderTheaterRally, ConfigurationKeyEnum.WANDER_THEATER_RALLY_BOOL);
        checkBoxMappings.put(checkBoxWanderTheaterExplore, ConfigurationKeyEnum.WANDER_THEATER_EXPLORE_BOOL);
        textFieldMappings.put(textfieldWanderTheaterReserve, ConfigurationKeyEnum.WANDER_THEATER_TALISMAN_RESERVE_INT);
        comboBoxMappings.put(comboBoxWanderTheaterRallyFlag, ConfigurationKeyEnum.WANDER_THEATER_RALLY_FLAG_INT);
        checkBoxMappings.put(checkBoxDeadShotSignIn, ConfigurationKeyEnum.DEAD_SHOT_SIGN_IN_ENABLED_BOOL);
        checkBoxMappings.put(checkBoxWinterSiege, ConfigurationKeyEnum.WINTER_SIEGE_ENABLED_BOOL);
        checkBoxMappings.put(checkBoxRedDotSweep, ConfigurationKeyEnum.RED_DOT_SWEEP_ENABLED_BOOL);
        checkBoxMappings.put(checkBoxFortuneHut, ConfigurationKeyEnum.FORTUNE_HUT_ENABLED_BOOL);
        checkBoxMappings.put(checkBoxFortuneHutPlay, ConfigurationKeyEnum.FORTUNE_HUT_PLAY_BOOL);
        textFieldMappings.put(textfieldFortuneHutBankThreshold, ConfigurationKeyEnum.FORTUNE_HUT_BANK_THRESHOLD_INT);
        textFieldMappings.put(textfieldFortuneHutWish, ConfigurationKeyEnum.FORTUNE_HUT_WISH_INT);
        // Fortress / Stronghold joiner: no schedule of its own, the event calendar says when it runs.
        checkBoxMappings.put(checkBoxFacilityJoin, ConfigurationKeyEnum.FACILITY_RALLY_JOIN_ENABLED_BOOL);
        checkBoxMappings.put(checkBoxFacilityRecall, ConfigurationKeyEnum.FACILITY_RALLY_JOIN_RECALL_BOOL);
        checkComboBoxMappings.put(checkComboBoxFacilityFlag, ConfigurationKeyEnum.FACILITY_RALLY_JOIN_FLAG_STRING);
        showDetectedFacilities();

        comboBoxMappings.put(comboBoxMercenaryFlag, ConfigurationKeyEnum.MERCENARY_FLAG_INT);
        comboBoxMappings.put(comboBoxHeroMissionFlag, ConfigurationKeyEnum.HERO_MISSION_FLAG_INT);
    }

    private void wireEnablementRules() {
        checkBoxTundraUseGems.disableProperty().bind(checkBoxTundraEvent.selectedProperty().not());
        checkBoxTundraSSR.disableProperty().bind(checkBoxTundraEvent.selectedProperty().not());
        checkBoxTundraEventActivationHour.disableProperty().bind(checkBoxTundraEvent.selectedProperty().not());
        checkBoxJourneyofLightAssembleCommon.disableProperty().bind(checkBoxJourneyofLight.selectedProperty().not());
        checkBoxJourneyofLightAssemblePremium.disableProperty().bind(checkBoxJourneyofLight.selectedProperty().not());
        textfieldTundraActivationHour.disableProperty().bind(
            checkBoxTundraEvent.selectedProperty().not().or(checkBoxTundraEventActivationHour.selectedProperty().not())
        );
        comboBoxHeroMissionFlag.disableProperty().bind(checkBoxHeroMission.selectedProperty().not());
        comboBoxMercenaryFlag.disableProperty().bind(checkBoxMercenaryEvent.selectedProperty().not());
        for (CheckBox option : new CheckBox[] {checkBoxMergeventureBoard, checkBoxMergeventureMissions,
                checkBoxMergeventureAtlasPath, checkBoxMergeventureHarvest, checkBoxMergeventureCaravan}) {
            option.disableProperty().bind(checkBoxMergeventure.selectedProperty().not());
        }
        checkBoxMergeventureUseMax.disableProperty().bind(
            checkBoxMergeventure.selectedProperty().not().or(checkBoxMergeventureBoard.selectedProperty().not()));
        checkBoxMergeventureSaveSojourner.disableProperty().bind(
            checkBoxMergeventure.selectedProperty().not().or(checkBoxMergeventureCaravan.selectedProperty().not()));
        checkBoxFacilityRecall.disableProperty().bind(checkBoxFacilityJoin.selectedProperty().not());
        checkComboBoxFacilityFlag.disableProperty().bind(checkBoxFacilityJoin.selectedProperty().not());
        textfieldMergeventureSpendHours.disableProperty().bind(
            checkBoxMergeventure.selectedProperty().not().or(checkBoxMergeventureCaravan.selectedProperty().not()));
        checkBoxFortuneHutPlay.disableProperty().bind(checkBoxFortuneHut.selectedProperty().not());
        textfieldFortuneHutBankThreshold.disableProperty().bind(
            checkBoxFortuneHut.selectedProperty().not().or(checkBoxFortuneHutPlay.selectedProperty().not()));
        textfieldFortuneHutWish.disableProperty().bind(
            checkBoxFortuneHut.selectedProperty().not().or(checkBoxFortuneHutPlay.selectedProperty().not()));
        checkBoxWanderTheaterRally.disableProperty().bind(checkBoxWanderTheater.selectedProperty().not());
        checkBoxWanderTheaterExplore.disableProperty().bind(checkBoxWanderTheater.selectedProperty().not());
        comboBoxWanderTheaterRallyFlag.disableProperty().bind(
            checkBoxWanderTheater.selectedProperty().not().or(checkBoxWanderTheaterRally.selectedProperty().not()));
        textfieldWanderTheaterReserve.disableProperty().bind(
            checkBoxWanderTheater.selectedProperty().not().or(checkBoxWanderTheaterExplore.selectedProperty().not()));
    }

    /** The facilities the game's schedule names, so nothing is typed: empty reads as no battle known. */
    private void showDetectedFacilities() {
        java.util.List<String> facilities = dev.frostguard.engine.service.FacilityBattleCandidates
            .schedule(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
                dev.frostguard.engine.service.FacilityBattleCandidates.BATTLE_LENGTH).stream()
            .map(dev.frostguard.engine.service.FacilityBattleCandidates.Candidate::facility)
            .distinct().sorted(NavOrder.BY_NAME).toList();
        labelFacilityDetected.setText(facilities.isEmpty()
            ? "No Fortress or Stronghold battle is on the schedule yet."
            : String.join(", ", facilities));
    }

    private void prepareActivationTimeField() {
        textfieldTundraActivationHour.setPromptText("HH:mm");
        textfieldTundraActivationHour.setTooltip(new Tooltip("Example: 15:30"));
        registerTimeTextField(
            textfieldTundraActivationHour,
            labelDateTimeError,
            ConfigurationKeyEnum.TUNDRA_TRUCK_ACTIVATION_TIME_STRING,
            SettingValidators.localTime("Tundra activation time"));
    }
}
