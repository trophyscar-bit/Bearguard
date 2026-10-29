package dev.frostguard.app.panel.city;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.ExpertSkillItemEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.app.shared.PriorityListView;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;

import java.util.List;

public class DawnAcademyLayoutController extends AbstractProfileController {
    @FXML
    private CheckBox checkBoxDawnAcademy;
    @FXML
    private VBox dawnAcademyOptions;
    @FXML
    private CheckBox checkBoxLearning;
    @FXML
    private CheckBox checkBoxUpgradeSkills;
    @FXML
    private PriorityListView skillPriorities;
    @FXML
    private CheckBox checkBoxGiftCompasses;
    @FXML
    private CheckBox checkBoxGiftTopOff;
    @FXML
    private CheckBox checkBoxAdvanceRelationships;
    @FXML
    private ComboBox<String> comboGiftExpert;
    @FXML
    private CheckBox checkBoxClaimSupplies;
    @FXML
    private CheckBox checkBoxBuySupplies;
    @FXML
    private CheckBox checkBoxTundraTrek;
    @FXML
    private CheckBox checkBoxFrontierTrek;
    @FXML
    private CheckBox checkBoxWayfarer;

    @FXML
    private void initialize() {
        academySwitches().forEach(binding -> checkBoxMappings.put(binding.control(), binding.configKey()));
        for (ExpertSkillItemEnum.Expert expert : ExpertSkillItemEnum.Expert.values()) {
            comboGiftExpert.getItems().add(expert.name());
        }
        comboBoxMappings.put(comboGiftExpert, ConfigurationKeyEnum.DAWN_ACADEMY_GIFT_EXPERT_STRING);
        registerPriorityList(skillPriorities, ConfigurationKeyEnum.EXPERT_SKILL_TRAINING_PRIORITIES_STRING,
                ExpertSkillItemEnum.class);
        setOptionsVisible(checkBoxDawnAcademy.isSelected());
        checkBoxDawnAcademy.selectedProperty().addListener(
                (observable, oldValue, enabled) -> setOptionsVisible(enabled));
        initializeChangeEvents();
    }

    private List<AcademySwitch> academySwitches() {
        return List.of(
            new AcademySwitch(checkBoxDawnAcademy, ConfigurationKeyEnum.DAWN_ACADEMY_ENABLED_BOOL),
            new AcademySwitch(checkBoxLearning, ConfigurationKeyEnum.DAWN_ACADEMY_LEARNING_BOOL),
            new AcademySwitch(checkBoxUpgradeSkills, ConfigurationKeyEnum.DAWN_ACADEMY_UPGRADE_SKILLS_BOOL),
            new AcademySwitch(checkBoxAdvanceRelationships,
                    ConfigurationKeyEnum.DAWN_ACADEMY_ADVANCE_RELATIONSHIPS_BOOL),
            new AcademySwitch(checkBoxGiftCompasses, ConfigurationKeyEnum.DAWN_ACADEMY_GIFT_COMPASSES_BOOL),
            new AcademySwitch(checkBoxGiftTopOff, ConfigurationKeyEnum.DAWN_ACADEMY_GIFT_TOP_OFF_BOOL),
            new AcademySwitch(checkBoxClaimSupplies, ConfigurationKeyEnum.DAWN_ACADEMY_CLAIM_SUPPLIES_BOOL),
            new AcademySwitch(checkBoxBuySupplies, ConfigurationKeyEnum.DAWN_ACADEMY_BUY_SUPPLIES_BOOL),
            new AcademySwitch(checkBoxTundraTrek, ConfigurationKeyEnum.DAWN_ACADEMY_TUNDRA_TREK_BOOL),
            new AcademySwitch(checkBoxFrontierTrek, ConfigurationKeyEnum.DAWN_ACADEMY_FRONTIER_TREK_BOOL),
            new AcademySwitch(checkBoxWayfarer, ConfigurationKeyEnum.DAWN_ACADEMY_WAYFARER_BOOL)
        );
    }

    private void setOptionsVisible(boolean visible) {
        dawnAcademyOptions.setVisible(visible);
        dawnAcademyOptions.setManaged(visible);
    }

    private record AcademySwitch(CheckBox control, ConfigurationKeyEnum configKey) {
    }
}
