package dev.frostguard.app.panel.alliance;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;

public class AllianceSpendLayoutController extends AbstractProfileController {

    @FXML
    private CheckBox checkBoxSpendEnabled, checkBoxSpendAnonymize;

    @FXML
    private TextField textfieldSpendMaxScrolls;

    @FXML
    private void initialize() {
        checkBoxMappings.put(checkBoxSpendEnabled, ConfigurationKeyEnum.ALLIANCE_SPEND_ENABLED_BOOL);
        checkBoxMappings.put(checkBoxSpendAnonymize, ConfigurationKeyEnum.ALLIANCE_SPEND_ANONYMIZE_BOOL);
        textFieldMappings.put(textfieldSpendMaxScrolls, ConfigurationKeyEnum.ALLIANCE_SPEND_MAX_SCROLLS_INT);
        checkBoxSpendAnonymize.disableProperty().bind(checkBoxSpendEnabled.selectedProperty().not());
        textfieldSpendMaxScrolls.disableProperty().bind(checkBoxSpendEnabled.selectedProperty().not());
        initializeChangeEvents();
    }
}
