package dev.frostguard.app.panel.dailies;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.FormationSlots;
import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.app.shared.SettingValidators;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;

public class EventsLayoutController extends AbstractProfileController {

    @FXML
    private CheckBox checkBoxTundraEvent, checkBoxTundraUseGems, checkBoxTundraSSR, checkBoxHeroMission,
        checkBoxMercenaryEvent, checkBoxJourneyofLight, checkBoxJourneyofLightAssembleCommon,
        checkBoxJourneyofLightAssemblePremium, checkBoxMyriadBazaar, checkBoxTundraEventActivationHour, checkBoxMergeventure, checkBoxMergeventureBoard,
        checkBoxMergeventureUseMax, checkBoxMergeventureMissions, checkBoxMergeventureAtlasPath,
        checkBoxMergeventureHarvest, checkBoxMergeventureCaravan, checkBoxMergeventureSaveSojourner, checkBoxSignInEarn;

    @FXML
    private TextField textfieldTundraActivationHour, textfieldMergeventureSpendHours;

    @FXML
    private ComboBox<Integer> comboBoxMercenaryFlag, comboBoxHeroMissionFlag;

    @FXML
    private Label labelDateTimeError;

    @FXML
    private void initialize() {
        fillFlagOptions();
        registerConfigurationFields();
        wireEnablementRules();
        prepareActivationTimeField();
        initializeChangeEvents();
    }

    private void fillFlagOptions() {
        comboBoxMercenaryFlag.getItems().setAll(FormationSlots.numbersWithNone(0));
        comboBoxHeroMissionFlag.getItems().setAll(FormationSlots.numbersWithNone(0));
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
        textfieldMergeventureSpendHours.disableProperty().bind(
            checkBoxMergeventure.selectedProperty().not().or(checkBoxMergeventureCaravan.selectedProperty().not()));
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
