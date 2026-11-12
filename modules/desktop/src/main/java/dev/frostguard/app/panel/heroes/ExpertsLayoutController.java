package dev.frostguard.app.panel.heroes;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;

import java.util.List;

public class ExpertsLayoutController extends AbstractProfileController {

    @FXML
    private CheckBox claimIntelCheckBox;

    @FXML
    private CheckBox claimLoyaltyTagCheckBox;

    @FXML
    private CheckBox claimTroopsCheckBox;

    @FXML
    private VBox troopOptionsVBox;

    @FXML
    private ComboBox<String> troopTypeComboBox;


    @FXML
    public void initialize() {
        configureOptions();
        registerSettings();
        initializeChangeEvents();
    }

    private void configureOptions() {
        troopTypeComboBox.getItems().setAll(TroopChoice.labels());
    }

    private void registerSettings() {
        expertSwitches().forEach(binding -> checkBoxMappings.put(binding.control(), binding.configKey()));
        comboBoxMappings.put(troopTypeComboBox, ConfigurationKeyEnum.EXPERT_ROMULUS_TROOPS_TYPE_STRING);
    }

    private List<ExpertSwitch> expertSwitches() {
        return List.of(
            new ExpertSwitch(claimIntelCheckBox, ConfigurationKeyEnum.EXPERT_AGNES_INTEL_BOOL),
            new ExpertSwitch(claimLoyaltyTagCheckBox, ConfigurationKeyEnum.EXPERT_ROMULUS_TAG_BOOL),
            new ExpertSwitch(claimTroopsCheckBox, ConfigurationKeyEnum.EXPERT_ROMULUS_TROOPS_BOOL)
        );
    }

    private record ExpertSwitch(CheckBox control, ConfigurationKeyEnum configKey) {
    }

    private enum TroopChoice {
        INFANTRY("Infantry"),
        LANCER("Lancer"),
        MARKSMAN("Marksman");

        private final String label;

        TroopChoice(String label) {
            this.label = label;
        }

        static List<String> labels() {
            return List.of(INFANTRY.label, LANCER.label, MARKSMAN.label);
        }
    }
}
